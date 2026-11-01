package com.questclaude.bridge

import java.util.Locale

/**
 * Счётчики эфира и ник внизу (2.8.2, Александр 01.11: «как у ShowSpeed» — пункты 1, 2, 3).
 * 1) Сколько смотрят сейчас — одно число по Twitch, Kick и YouTube (карточка справа вверху, под налётом).
 * 2) Сколько фолловеров (подписчиков) — тоже одним числом, во второй строке той же карточки; растёт в эфире.
 * 3) Ник внизу кадра со значками площадок: «TW KICK YT 3xsks_fu_gun».
 * Числа шлёт zriteli в Termux (/schet/upr) раз в минуту, пока идёт эфир. Зрители старше [STARO_MS] — не
 * показываются (площадка перестала отвечать); фолловеры и ник помнятся в файле. Обычная JVM — SchetTest.
 */
class Schet(zagruzit: () -> String?, private val sohranit: (String) -> Unit, private val chasy: () -> Long = System::currentTimeMillis) {
    private class Zr(val n: Long, val ms: Long)

    private val zr = HashMap<String, Zr>()
    private val pod = HashMap<String, Long>()
    @Volatile var nik = ""
        private set
    /** Ссылка доната внизу кадра (2.8.2): «streamelements.com/…/tip»; пусто — нет. */
    @Volatile var ssylka = ""
        private set
    @Volatile var pokazZr = true
        private set
    @Volatile var pokazPod = true
        private set
    @Volatile var pokazNik = true
        private set
    /** Растёт с каждым изменением — плашки перерисовываются. */
    @Volatile var versiya = 0L
        private set

    init {
        zagruzit()?.lines()?.forEach { l ->
            val p = l.split('\t')
            when (p.getOrNull(0)) {
                "pokaz" -> { pokazZr = p.getOrNull(1) != "0"; pokazPod = p.getOrNull(2) != "0"; pokazNik = p.getOrNull(3) != "0" }
                "nik" -> nik = Top.nik(p.getOrNull(1) ?: "")
                "ssylka" -> ssylka = chistaSsylka(p.getOrNull(1) ?: "")
                "pod" -> if (p.getOrNull(1) in PLOSHCHADKI) p.getOrNull(2)?.toLongOrNull()?.takeIf { it >= 0 }?.let { pod[p[1]] = it }
            }
        }
    }

    /**
     * /schet/upr: zr_tw, zr_kick, zr_yt — зрителей сейчас («-» — нет эфира на площадке); pod_tw, pod_kick, pod_yt —
     * фолловеров всего («-» — забыть); nik — ник внизу (пусто — убрать); ssylka — ссылка доната (пусто — убрать);
     * zr, pod, niki — показывать 0/1.
     */
    @Synchronized fun upr(q: Map<String, String>): String {
        val now = chasy()
        for (pl in PLOSHCHADKI) {
            q["zr_$pl"]?.let { v -> chislo(v)?.let { zr[pl] = Zr(it, now) } ?: run { if (v.trim() == "-") zr.remove(pl) } }
            q["pod_$pl"]?.let { v -> chislo(v)?.let { pod[pl] = it } ?: run { if (v.trim() == "-") pod.remove(pl) } }
        }
        q["nik"]?.let { nik = Top.nik(Titry.dekod(it)) }
        q["ssylka"]?.let { ssylka = chistaSsylka(Titry.dekod(it)) }
        q["zr"]?.let { pokazZr = it != "0" }
        q["pod"]?.let { pokazPod = it != "0" }
        q["niki"]?.let { pokazNik = it != "0" }
        versiya++
        sohranit((listOf("pokaz\t${b(pokazZr)}\t${b(pokazPod)}\t${b(pokazNik)}", "nik\t$nik", "ssylka\t$ssylka") +
            pod.map { (pl, n) -> "pod\t$pl\t$n" }).joinToString("\n"))
        return status()
    }

    private fun b(v: Boolean) = if (v) "1" else "0"

    /** Свежие зрители по площадкам (старше [STARO_MS] — нет). */
    @Synchronized fun zriteli(): Map<String, Long> {
        val now = chasy()
        return PLOSHCHADKI.mapNotNull { pl -> zr[pl]?.takeIf { now - it.ms in 0..STARO_MS }?.let { pl to it.n } }.toMap()
    }

    @Synchronized fun podpischiki(): Map<String, Long> = PLOSHCHADKI.mapNotNull { pl -> pod[pl]?.let { pl to it } }.toMap()

    val zrVidny: Boolean get() = pokazZr && zriteli().isNotEmpty()
    val podVidny: Boolean get() = pokazPod && podpischiki().isNotEmpty()
    /** Карточка счётчиков видна. */
    val vidna: Boolean get() = zrVidny || podVidny
    /** Полоска внизу видна: есть ник или ссылка доната. */
    val nikViden: Boolean get() = pokazNik && (nik.isNotEmpty() || ssylka.isNotEmpty())

    /**
     * Что сейчас внизу: "nik" (значки и ник), "donat" (ссылка доната) или "" — ничего. Есть и то и другое — по очереди:
     * ник [Vizor.NK_NIK_MS], ссылка [Vizor.NK_DONAT_MS].
     */
    fun nizSeychas(now: Long): String = when {
        !nikViden -> ""
        ssylka.isEmpty() -> "nik"
        nik.isEmpty() -> "donat"
        now % (Vizor.NK_NIK_MS + Vizor.NK_DONAT_MS) < Vizor.NK_NIK_MS -> "nik"
        else -> "donat"
    }

    /** Ключ карточки: меняется, только когда меняется нарисованное (зрители устарели — тоже). */
    fun klyuch(): String = if (!vidna) "net" else
        "${if (zrVidny) zriteli() else "-"} ${if (podVidny) podpischiki() else "-"}"

    fun status(): String {
        val z = zriteli(); val p = podpischiki()
        fun m(m: Map<String, Long>) = m.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
        return "{\"zriteli\":${m(z)},\"zriteli_vsego\":${z.values.sum()},\"podpischiki\":${m(p)}," +
            "\"podpischiki_vsego\":${p.values.sum()},\"nik\":${FrameServer.json(nik)},\"ssylka\":${FrameServer.json(ssylka)}," +
            "\"zr\":$pokazZr,\"pod\":$pokazPod," +
            "\"niki\":$pokazNik,\"vidna\":$vidna,\"nik_viden\":$nikViden}"
    }

    companion object {
        val PLOSHCHADKI = listOf("tw", "kick", "yt")
        /** Значки внизу кадра: площадки и соцсети (Александр 01.11: «добавь инстаграм и тикток»). */
        val ZNACHKI = listOf("tw", "kick", "yt", "ig", "tt")
        const val MAKS_SSYLKA = 60

        /** Ссылка без «https://» и «www.», только буквы, цифры и . / _ - ; длиннее MAKS_SSYLKA — нет. */
        fun chistaSsylka(s: String): String {
            val t = s.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
            return if (t.length <= MAKS_SSYLKA && Regex("[A-Za-z0-9._/-]+").matches(t) && '.' in t) t else ""
        }
        /** Зрители без обновления дольше 3 минут — устарели (zriteli шлёт раз в минуту). */
        const val STARO_MS = 180_000L
        const val MAKS = 10_000_000_000L

        /** «37545», «37 545», «37,545» — число 0…MAKS; иначе null. */
        fun chislo(s: String): Long? =
            Titry.dekod(s).trim().replace(Regex("[\\s,]"), "").toLongOrNull()?.takeIf { it in 0..MAKS }

        /** «37,545» — как у Speed (запятые по тысячам). */
        fun krasivo(n: Long): String = String.format(Locale.US, "%,d", n)
    }
}
