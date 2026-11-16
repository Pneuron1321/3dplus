package com.questclaude.bridge

import kotlin.math.roundToInt

/**
 * Диспетчер «тесно / свободно» (2.3.9, план эфира III.6–III.7, IV.7). Раз в секунду, пока идёт
 * эфир, смотрит: кадры экрана (то, что видит игрок) против их обычного уровня, потери кадров
 * эфира, тепло шлема, шквал чата. Тесно — говорит об этом в /efir («tesno» и почему):
 * автопросмотр, соведущий и приветствия зрителей ждут, уши говорят Плюсу. Если тесно игре
 * (кадры экрана просели или шлем греется) — бридж первым сбрасывает своё: роза стоит, лицо
 * реже, глаза Плюса — 1 кадр в секунду (Nalozhenie). 720p сам не включает (решение Александра).
 * Свободно — когда [otpuskMs] подряд причин нет. Обычная JVM — DispetcherTest.
 */
class Dispetcher(private val otpuskMs: Long = 20_000) {
    private val ekran = ArrayDeque<Float>()           // ровные секунды — из них обычный уровень игры
    private var dropsBylo = -1L
    private var nizhe = 0                             // секунд подряд игра ниже обычного
    private var svobodnoS = 0L

    @Volatile var tesno = false
        private set
    @Volatile var pochemu = ""
        private set
    /** Тесно самой игре (кадры экрана, тепло): бридж сбрасывает своё. */
    @Volatile var igre = false
        private set

    /** Обычный уровень кадров экрана: медиана ровных секунд (меньше 10 — ещё не знаем, 0). */
    fun norma(): Float = if (ekran.size < 10) 0f else ekran.sorted()[ekran.size / 2]

    /**
     * Секунда эфира. ekranFps — кадров экрана за секунду (0 — экран стоит: не считаем), drops — потери
     * эфира всего, teplo — тепловой статус Android (0 — норма … 6), chatSek — сообщений чата в секунду.
     */
    @Synchronized fun shag(now: Long, ekranFps: Float, drops: Long, teplo: Int, chatSek: Float) {
        val prichiny = mutableListOf<String>()
        var igra = false
        val n = norma()
        if (ekranFps > 0f && n > 0f && ekranFps < n * PROSADKA) nizhe++ else nizhe = 0
        if (nizhe >= 2) {
            prichiny += "игра ${ekranFps.roundToInt()} к/с из ${n.roundToInt()}"
            igra = true
        } else if (ekranFps > 0f && nizhe == 0) {
            ekran.addLast(ekranFps)
            while (ekran.size > OKNO) ekran.removeFirst()
        }
        if (dropsBylo >= 0 && drops > dropsBylo) prichiny += "эфир теряет кадры"
        dropsBylo = drops
        if (teplo >= TEPLO) {
            prichiny += "шлем греется"
            igra = true
        }
        if (chatSek > Chat.BYSTRO_V_SEK) prichiny += "чат кипит"
        if (prichiny.isNotEmpty()) {
            tesno = true
            pochemu = prichiny.joinToString(", ")
            igre = igre || igra
            svobodnoS = now + otpuskMs
        } else if (tesno && now >= svobodnoS) {
            tesno = false
            pochemu = ""
            igre = false
        }
    }

    companion object {
        /** Игра «просела» — ниже стольких долей обычного, две секунды подряд. */
        const val PROSADKA = 0.8f
        /** Сколько ровных секунд помнить для обычного уровня. */
        const val OKNO = 60
        /** Тепло, с которого тесно: PowerManager.THERMAL_STATUS_MODERATE. */
        const val TEPLO = 2
    }
}
