package com.questclaude.bridge

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import java.io.File

/**
 * Струны, этап 1 (заход 2, 22.10): игра с карты — основой кадра эфира. Мост 3 (com.questclaude.most3) показывает
 * карту в шлеме в 1440p/4K и тем же кадром рисует 1920×1080 с суперсэмплингом в поверхность развилки; эфир
 * получает игру с карты, а не вид шлема (без двойного сжатия и без рамки окна). Переключатель — `efir igra|shlem`
 * (по умолчанию shlem: этап 1 включается вручную, пока не замерили в шлеме).
 * 2.6.9 (Александр 22.10: на эфире было окно Моста 3 не во всю ширину и с полоской режима сверху): по умолчанию
 * "avto" — Мост 3 показывает карту, эфир берёт игру из него (1080 во весь кадр, без окна и надписей); Мост 3 молчит
 * дольше секунды — вид шлема. `efir shlem` — всегда вид шлема, `efir igra` — всегда игра.
 *
 * Мост 3 спрашивает поверхность через [StrunySluzhba] (Binder, разрешение уровня подписи: оба приложения
 * подписаны одним ключом). Поколение растёт, когда поверхность меняется (новый эфир, переключатель) —
 * Мост 3 видит это по коду 2 раз в 2 с и забирает новую кодом 1.
 */
object Struny {
    const val KOD_POVERHNOST = 1         // ответ: int поколение, int есть(0/1), Surface (если есть)
    const val KOD_POKOLENIE = 2          // ответ: int поколение
    private const val FAJL = "efir-osnova"

    @Volatile private var razvilka: Razvilka? = null
    @Volatile var pokolenie = 1
        private set
    @Volatile var dir: File? = null
    /** "avto" — игра из Моста 3, если он показывает, иначе вид шлема; "igra"; "shlem" — вид шлема (как было). */
    @Volatile var osnova = "avto"
        private set
    private val MOZHNO = setOf("avto", "igra", "shlem")

    fun zagruzit(d: File) {
        dir = d
        osnova = try { File(d, FAJL).readText().trim().takeIf { it in MOZHNO } ?: "avto" } catch (e: Exception) { "avto" }
    }

    fun podklyuchit(rz: Razvilka) { razvilka = rz; pokolenie++ }

    fun otklyuchit() {
        razvilka?.let { try { it.igraUbrat() } catch (e: Exception) { } }
        razvilka = null; pokolenie++
    }

    /** `efir igra|shlem`: ответ — JSON для Плюса. */
    fun ustanovit(v: String?): String {
        if (v != null && v in MOZHNO) {
            if (v != osnova) {
                osnova = v
                if (v == "shlem") razvilka?.let { try { it.igraUbrat() } catch (e: Exception) { } }
                pokolenie++
                dir?.let { try { File(it, FAJL).writeText(v) } catch (e: Exception) { } }
            }
        }
        return status()
    }

    fun status(): String {
        val rz = razvilka
        return "{\"osnova\":\"$osnova\",\"efir\":${rz != null},\"igra_idyot\":${rz?.igraIdyot() == true}," +
            "\"igra_kadrov\":${rz?.igraKadrov ?: 0}}"
    }

    /** Поверхность для Моста 3 — только когда эфир идёт и основа «игра». */
    fun poverhnost(): android.view.Surface? {
        if (osnova == "shlem") return null
        val rz = razvilka ?: return null
        return try { rz.igra() } catch (e: Exception) { null }
    }
}

/** Дверь для Моста 3: android:permission уровня подписи в манифесте — чужое приложение не привяжется. */
class StrunySluzhba : Service() {
    private val dver = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val r = reply ?: return super.onTransact(code, data, reply, flags)
            when (code) {
                Struny.KOD_POKOLENIE -> r.writeInt(Struny.pokolenie)
                Struny.KOD_POVERHNOST -> {
                    val p = Struny.pokolenie
                    val s = Struny.poverhnost()
                    r.writeInt(p)
                    r.writeInt(if (s != null) 1 else 0)
                    s?.writeToParcel(r, 0)
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = dver
}
