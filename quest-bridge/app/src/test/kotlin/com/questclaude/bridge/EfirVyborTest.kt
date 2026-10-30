package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Шторка эфира (2.8.0): галочки площадок → набор для /efir, подписи и советы. */
class EfirVyborTest {
    @Test fun galkiVNabor() {
        assertEquals("twitch,kick,youtube", EfirVybor.pl(setOf("youtube", "twitch", "kick")))   // порядок — всегда свой
        assertEquals("kick", EfirVybor.pl(listOf("kick")))
        assertNull(EfirVybor.pl(emptySet()))
        assertEquals(listOf("twitch", "kick"), Ploshchadki.razobrat(EfirVybor.pl(setOf("kick", "twitch"))))   // мост поймёт
        assertEquals("Twitch, YouTube", EfirVybor.spisok(setOf("youtube", "twitch")))
    }

    @Test fun podpisi() {
        assertEquals("Kick — нет ключа (скажи Плюсу: kluch kick)", EfirVybor.podpis("kick", false, null))
        assertEquals("Kick — нет адреса (Stream URL → kluch kick)", EfirVybor.podpis("kick", true, null, adresKick = false))
        assertEquals("Twitch — идёт ●", EfirVybor.podpis("twitch", true, "efir"))
        assertEquals("YouTube", EfirVybor.podpis("youtube", true, null))
        assertEquals("YouTube — обрыв, переподключаюсь…", EfirVybor.podpis("youtube", true, "obryv"))
    }

    @Test fun sovety() {
        assertTrue(EfirVybor.sovet(setOf("twitch", "kick", "youtube"), setOf("twitch", "kick", "youtube")).contains("18 Мбит"))
        assertEquals("Без ключа не пойдёт: YouTube. Нужно примерно 12 Мбит/с отдачи (по 6 на площадку); не тянет — 720p или меньше площадок.",
            EfirVybor.sovet(setOf("twitch", "kick", "youtube"), setOf("twitch", "kick")))
        assertEquals("", EfirVybor.sovet(setOf("twitch"), setOf("twitch")))
        assertEquals("Ни одной площадки — поставь галочку.", EfirVybor.sovet(emptySet(), setOf("twitch")))
    }
}
