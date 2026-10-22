package com.questclaude.most3

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.view.Surface

/**
 * Струны, этап 1 (заход 2): кадр игры — в эфир моста 2. Мост 2 (com.questclaude.bridge2) держит дверь
 * StrunySluzhba (разрешение уровня подписи — у обоих приложений один ключ). Раз в 2 с спрашиваем поколение;
 * сменилось — забираем поверхность развилки (или узнаём, что её нет: эфир не идёт, основа «shlem»).
 * Показ (Pokaz) рисует в неё тот же кадр, что в шлеме, — 1920×1080 с суперсэмплингом из 1440p/4K.
 */
object Struny {
    private const val PAKET = "com.questclaude.bridge2"
    private const val KOD_POVERHNOST = 1
    private const val KOD_POKOLENIE = 2

    @Volatile private var dver: IBinder? = null
    @Volatile private var pokolenie = 0
    /** Поверхность кадра эфира; null — эфира нет или основа эфира — вид шлема. */
    @Volatile var poverhnost: Surface? = null; private set
    /** Для самоотчёта: что с дверью моста 2. */
    @Volatile var sostoyanie = "мост 2 не найден"; private set
    @Volatile var kadrov = 0L

    private val svyaz = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) { dver = b; pokolenie = 0; sostoyanie = "мост 2 на связи" }
        override fun onServiceDisconnected(n: ComponentName?) { dver = null; sbrosit(); sostoyanie = "мост 2 закрылся" }
    }

    fun start(ctx: Context) {
        val i = Intent().setComponent(ComponentName(PAKET, "com.questclaude.bridge.StrunySluzhba"))
        val ok = try { ctx.bindService(i, svyaz, Context.BIND_AUTO_CREATE) } catch (e: SecurityException) {
            sostoyanie = "мост 2 не пускает (подпись или старая версия моста 2 — нужна 2.6.7+)"; false
        }
        if (!ok && dver == null && !sostoyanie.startsWith("мост 2 не пускает")) sostoyanie = "мост 2 не найден (нужна 2.6.7+)"
        Thread({
            while (true) {
                try { Thread.sleep(2000); proverit() } catch (e: InterruptedException) { break } catch (e: Exception) { }
            }
        }, "most3-struny").apply { isDaemon = true; start() }
    }

    /** Показ не смог рисовать в поверхность (её закрыли — эфир кончился): спросить заново. */
    fun slomana() { sbrosit(); pokolenie = 0 }

    private fun sbrosit() { poverhnost = null }

    private fun proverit() {
        val b = dver ?: return
        val p = zapros(b, KOD_POKOLENIE) { it.readInt() } ?: return
        if (p == pokolenie) return
        val nova = zapros(b, KOD_POVERHNOST) { r ->
            val pk = r.readInt()
            val s = if (r.readInt() == 1) Surface.CREATOR.createFromParcel(r) else null
            pk to s
        } ?: return
        pokolenie = nova.first
        val stara = poverhnost
        poverhnost = nova.second
        if (stara != null && stara !== nova.second) Thread { Thread.sleep(500); stara.release() }.start()   // показ успеет отпустить
        sostoyanie = if (nova.second != null) "эфир берёт игру отсюда" else "мост 2 на связи, эфир на виде шлема (efir igra — взять игру)"
    }

    private fun <T> zapros(b: IBinder, kod: Int, chitat: (Parcel) -> T): T? {
        val d = Parcel.obtain(); val r = Parcel.obtain()
        return try { if (b.transact(kod, d, r, 0)) chitat(r) else null } catch (e: Exception) { null } finally { d.recycle(); r.recycle() }
    }
}
