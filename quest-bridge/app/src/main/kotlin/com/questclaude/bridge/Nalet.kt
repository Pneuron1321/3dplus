package com.questclaude.bridge

import kotlin.math.roundToLong

/**
 * Налёт эфира — как часы у пилота: 1 коин в минуту, капает только пока идёт эфир (платформа —
 * Twitch или любая другая — приняла поток), потратить нельзя. Считаем секунды, а не складываем
 * дроби (в студии сложение по 1/60 давало 59,99 за час). «Всего» — в файле приложения (переживает перезапуск); «за эфир» — с начала
 * этого эфира (обрыв и переподключение — тот же эфир). Каждая сотня — надпись «НАЛЁТ 100 —
 * сотня» на 10 с и звон. Обычная JVM — проверяется на столе (NaletTest).
 */
class Nalet(zagruzit: () -> Long, private val sohranit: (Long) -> Unit) {
    @Volatile var vsegoSek: Long = maxOf(0L, zagruzit())
        private set
    @Volatile var efirSek = 0L
        private set
    /** Последняя взятая сотня (100, 200…) и до какого времени её показывать. */
    @Volatile var sotnya = 0
        private set
    @Volatile var sotnyaDoMs = 0L
        private set
    private var efirId = Long.MIN_VALUE

    /** Раз в секунду, пока эфир идёт; id — начало этого эфира. true — только что взята сотня. */
    @Synchronized fun tik(id: Long, nowMs: Long): Boolean {
        if (id != efirId) { efirId = id; efirSek = 0 }
        val bylo = vsegoSek / SOTNYA_SEK
        vsegoSek++
        efirSek++
        if (vsegoSek / SOTNYA_SEK > bylo) {
            sotnya = (vsegoSek / SOTNYA_SEK * 100).toInt()
            sotnyaDoMs = nowMs + 10_000
            sohranit(vsegoSek)
            return true
        }
        if (vsegoSek % 10 == 0L) sohranit(vsegoSek)
        return false
    }

    fun sotnyaVidna(nowMs: Long) = nowMs < sotnyaDoMs

    @Synchronized fun zapisat() = sohranit(vsegoSek)

    /** Перенести налёт (например, из студии): всего — столько коинов. */
    @Synchronized fun ustanovit(koiny: Double) {
        vsegoSek = (koiny * 60).roundToLong().coerceAtLeast(0)
        sohranit(vsegoSek)
    }

    fun status(): String = "{\"vsego\":\"${koiny(vsegoSek)}\",\"za_efir\":\"${koiny(efirSek)}\"," +
        "\"vsego_sek\":$vsegoSek,\"efir_sek\":$efirSek,\"sotnya\":$sotnya}"

    companion object {
        const val SOTNYA_SEK = 100L * 60

        /** Для эфира — по-английски: «1,234.56» (подписи в эфире — английские, Александр 08.10). */
        fun koinyEn(sek: Long): String {
            val sotye = sek * 100 / 60
            val celye = (sotye / 100).toString().reversed().chunked(3).joinToString(",").reversed()
            return "$celye.${(sotye % 100).toString().padStart(2, '0')}"
        }

        /** Секунды → коины «1 234,56»: сотые вниз, тысячи через узкий пробел. */
        fun koiny(sek: Long): String {
            val sotye = sek * 100 / 60
            val celye = (sotye / 100).toString().reversed().chunked(3).joinToString(" ").reversed()
            return "$celye,${(sotye % 100).toString().padStart(2, '0')}"
        }
    }
}
