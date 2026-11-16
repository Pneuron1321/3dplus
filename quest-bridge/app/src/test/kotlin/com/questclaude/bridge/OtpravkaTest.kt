package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class OtpravkaTest {
    /** Поддельный сервер: записывает, что пришло; может «оборваться» на n-м сообщении. */
    private class Fake(val rvat: Int = Int.MAX_VALUE) : Otpravka.Kanal {
        val got = CopyOnWriteArrayList<String>()
        override var alive = true
        override var sent = 0L
        override fun video(payload: ByteArray, ms: Long) = put("v${payload[0]}@$ms")
        override fun audio(payload: ByteArray, ms: Long) = put("a${payload[0]}@$ms")
        private fun put(s: String) {
            if (got.size >= rvat) { alive = false; throw IOException("обрыв") }
            got += s
            sent += 10
        }
        override fun close() { alive = false }
    }

    private fun zhdat(t: Long = 3000, u: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + t
        while (System.currentTimeMillis() < end) { if (u()) return true; Thread.sleep(10) }
        return false
    }

    @Test fun startsWithConfigAndKeyFrameAudioAfter() {
        val f = Fake()
        var keyAsked = 0
        val o = Otpravka({ f }, { keyAsked++ })
        o.videoConfig = byteArrayOf(9)
        o.audioConfig = byteArrayOf(8)
        o.start()
        assertTrue(zhdat { o.state == "efir" })
        o.audio(byteArrayOf(1), 900_000)              // до первого ключевого — не шлём
        o.video(byteArrayOf(2), 1_000_000, key = false)  // не ключевой — выброшен
        o.video(byteArrayOf(3), 1_033_000, key = true)   // ключевой: время эфира с него — 0
        o.audio(byteArrayOf(4), 1_040_000)
        o.video(byteArrayOf(5), 1_066_000, key = false)
        assertTrue(zhdat { f.got.size >= 5 })
        o.stop()
        assertEquals(listOf("v9@0", "v3@0", "a8@7", "a4@7", "v5@33"), f.got.toList())
        assertTrue(keyAsked >= 1)
        assertEquals(50L, o.sent)
    }

    @Test fun reconnectsAndStartsFromKeyFrameAgain() {
        val first = Fake(rvat = 2)
        val second = Fake()
        val kanaly = ArrayDeque(listOf<Otpravka.Kanal>(first, second))
        var keyAsked = 0
        val o = Otpravka({ kanaly.removeFirstOrNull() ?: throw IOException("нет сервера") }, { keyAsked++ }, pauzaMs = 50)
        o.videoConfig = byteArrayOf(9)
        o.start()
        assertTrue(zhdat { o.state == "efir" })
        o.video(byteArrayOf(1), 0, key = true)
        o.video(byteArrayOf(2), 33_000, key = false)      // обрыв на третьем сообщении
        assertTrue(zhdat { o.state == "obryv" || o.podklyucheniy == 2 })
        assertTrue(zhdat { o.podklyucheniy == 2 && o.state == "efir" })
        o.video(byteArrayOf(3), 66_000, key = false)      // после переподключения — ждём ключевой
        o.video(byteArrayOf(4), 100_000, key = true)
        o.video(byteArrayOf(5), 133_000, key = false)
        assertTrue(zhdat { second.got.size >= 3 })
        o.stop()
        assertEquals(listOf("v9@0", "v1@0"), first.got.toList())
        assertEquals(listOf("v9@0", "v4@0", "v5@33"), second.got.toList())
        assertTrue(keyAsked >= 2)
    }

    @Test fun serverDownKeepsTryingWithError() {
        var tries = 0
        val o = Otpravka({ tries++; throw IOException("сервер не ответил на connect") }, pauzaMs = 30)
        o.start()
        assertTrue(zhdat { tries >= 3 })
        assertEquals("podklyuchayus", o.state)
        assertEquals("сервер не ответил на connect", o.oshibka)
        o.stop()
        assertEquals("off", o.state)
    }

    @Test fun keyGoesOnlyToTwitch() {
        assertTrue(Otpravka.adresRazreshyon("rtmp://live.twitch.tv/app"))
        assertTrue(Otpravka.adresRazreshyon("rtmps://fra05.contribute.live-video.net/app"))
        assertTrue(Otpravka.adresRazreshyon("rtmp://127.0.0.1:1935/live"))
        assertEquals(false, Otpravka.adresRazreshyon("rtmp://evil.example.com/app"))
        assertEquals(false, Otpravka.adresRazreshyon("rtmp://twitch.tv.evil.com/app"))
        assertEquals(false, Otpravka.adresRazreshyon("http://live.twitch.tv/app"))
        assertEquals(false, Otpravka.adresRazreshyon("не адрес"))
    }

    @Test fun micUpsamplesIntoStereo() {
        val m = Mikrofon(zapas = 0)
        m.add(shortArrayOf(0, 300, 600, 900), 4)
        val out = ShortArray(12) { 10 }                   // 6 кадров стерео, звук игры = 10
        m.mix(out, 6)
        // 16 → 48 кГц: между отсчётами — по трети
        assertEquals(listOf(10, 10, 110, 110, 210, 210, 310, 310, 410, 410, 510, 510), out.map { it.toInt() })
        val loud = ShortArray(2) { 32000 }
        val m2 = Mikrofon(zapas = 0)
        m2.add(shortArrayOf(32000, 32000), 2)
        m2.mix(loud, 1)
        assertEquals(32767, loud[0].toInt())             // не переполняется
        assertEquals(24000, Mikrofon.myagko(24000))       // до колена — как есть
        assertEquals(26000, Mikrofon.myagko(32000))       // выше — вчетверо тише
        assertEquals(-26000, Mikrofon.myagko(-32000))
        val tiho = ShortArray(2)
        val m3 = Mikrofon(zapas = 0)
        m3.add(shortArrayOf(1000, 1000), 2)
        m3.mix(tiho, 1, gain = 3f)
        assertEquals(3000, tiho[0].toInt())               // голос втрое громче
    }

    /** Куски микрофона по 0,1 с приходят неровно (опаздывают на [opozdaniya] мс), звук эфира
     *  берёт по 1024 кадра (21,3 мс): сколько раз голос кончился посреди. */
    private fun propuskov(m: Mikrofon, opozdaniya: IntArray, sek: Int = 10): Int {
        val kusok = ShortArray(1600) { 1000 }
        val out = ShortArray(2048)
        var sled = 0
        var t = 0.0
        while (t < sek * 1000) {
            while (sled * 100 + opozdaniya[sled % opozdaniya.size] <= t) { m.add(kusok, 1600); sled++ }
            m.mix(out, 1024)
            t += 1024 * 1000.0 / 48000
        }
        return m.propuski
    }

    @Test fun micZapasBezMikropauz() {
        // «Когда говорю Плюсу, звук чуть прерывается» (08.10): без запаса — микропаузы, с запасом — нет
        val opozd = intArrayOf(0, 35, 5, 60, 10, 0, 45, 20, 55, 0)
        val bez = propuskov(Mikrofon(zapas = 0), opozd)
        val m = Mikrofon()
        val s = propuskov(m, opozd)
        assertTrue("без запаса: $bez, с запасом: $s", bez >= 5)            // 6 микропауз за 10 с
        assertEquals(0, s)
        assertEquals(0, m.sbrosy)
        assertEquals(0, propuskov(Mikrofon(), IntArray(1)))                // ровные куски — тоже ни одной
    }

    @Test fun micZapasIDogonyaet() {
        val m = Mikrofon()
        val golos = ShortArray(1600) { 1000 }
        val out = ShortArray(2048)
        m.add(golos, 1600)
        m.mix(out, 1024)
        assertTrue(out.all { it.toInt() == 0 })                          // запас ещё не набран — тишина
        m.add(golos, 1600)
        m.mix(out, 1024)
        assertTrue(out.all { it.toInt() == 1000 })                       // набран — голос
        val m2 = Mikrofon()
        repeat(4) { m2.add(golos, 1600) }                                // поток микрофона стоял — 0,4 с разом
        repeat(12) { m2.mix(out, 1024) }
        assertEquals(0, m2.sbrosy)                                       // не выбросили — догоняем
        assertEquals(0, m2.propuski)
        repeat(10) { m2.add(golos, 1600) }                               // отстали на секунду
        m2.mix(out, 1024)
        assertEquals(1, m2.sbrosy)                                       // выбросили до запаса
    }
}
