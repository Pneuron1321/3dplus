package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Фразы для субтитров: начало и конец по тишине, запас до начала, рез длинной, музыка без пауз, очередь. */
class RechTest {
    private var t = 0L
    private val frazy = mutableListOf<Rech.Fraza>()
    private val r = Rech("g1", { frazy += it }, { t })
    private val rnd = java.util.Random(1)

    /** Слоги: 150 мс голоса, 100 мс паузы (речь), или ровный тон (музыка). */
    private fun pcm(ms: Int, rech: Boolean, amp: Double = 5000.0, slogi: Boolean = true): ShortArray =
        ShortArray(ms * 16) { i ->
            if (!rech) (rnd.nextGaussian() * 30).toInt().toShort()
            else {
                val v = (i / 16) % 250 < 150 || !slogi
                ((if (v) amp else 60.0) * sin(2 * PI * 220 * i / 16000.0) + rnd.nextGaussian() * 30).toInt().toShort()
            }
        }

    private fun podat(a: ShortArray) {
        var i = 0
        while (i < a.size) { val n = minOf(1600, a.size - i); r.dobavit(a.copyOfRange(i, i + n), n); i += n; t += n / 16 }
    }

    @Test fun frazaMezhduPauzami() {
        podat(pcm(1000, false))
        podat(pcm(2000, true))
        podat(pcm(1000, false))
        assertEquals(1, frazy.size)
        val f = frazy.single()
        // 2 с речи + запас 0,3 + хвост 0,2 (± кусок на начало)
        assertTrue("длина ${f.dlitMs}", f.dlitMs in 2300..2600)
        assertTrue(f.tihih > 0.25f)
        assertEquals("g1", f.golos)
        assertTrue(f.nachaloMs in 600L..1100L)
    }

    @Test fun korotkoeNeFraza() {
        podat(pcm(500, false))
        podat(pcm(200, true, slogi = false))
        podat(pcm(1000, false))
        assertTrue(frazy.isEmpty())
    }

    @Test fun dlinnayaRezhetsyaVTihomMeste() {
        podat(pcm(500, false))
        podat(pcm(15000, true))
        podat(pcm(1000, false))
        assertTrue(frazy.size >= 2)
        assertTrue(frazy.all { it.dlitMs <= 7100 })
        val vsego = frazy.sumOf { it.dlitMs }
        assertTrue("ничего не потеряно: $vsego", vsego >= 15000)
    }

    @Test fun muzykaBezPauzMaloTihih() {
        podat(pcm(500, false))
        podat(pcm(5000, true, slogi = false))
        podat(pcm(1000, false))
        assertTrue(frazy.isNotEmpty())
        assertTrue("тихих у ровного звука мало: ${frazy[0].tihih}", frazy[0].tihih < 0.15f)
    }

    @Test fun shumPodnimaetsyaNeRech() {
        // ровный громкий шум долго — порог растёт, шум перестаёт быть «речью»
        val shum = ShortArray(16 * 20000) { (rnd.nextGaussian() * 400).toInt().toShort() }
        podat(shum)
        val bylo = frazy.size
        podat(ShortArray(16 * 5000) { (rnd.nextGaussian() * 400).toInt().toShort() })
        assertEquals(bylo, frazy.size)
    }

    @Test fun ocheredRechi() {
        var ms = 0L
        val q = Rechi { ms }
        q.podat("ya", pcm(3000, true), 48000)
        assertNull(q.sleduyushaya(0))                                      // эфира нет — не слушаем
        q.efir = true
        assertNull(q.sleduyushaya(0))                                      // демон спросил — жив
        assertTrue(q.demonZhiv && q.slushaetYa)
        val rech = pcm(2000, true) + pcm(1000, false)
        q.podat("ya", rech, rech.size)
        val f = q.sleduyushaya(100)!!
        assertEquals("ya", f.golos)
        q.upr(mapOf("igra" to "0", "gde" to "vnizu"))
        q.podat("igra", rech, rech.size)
        assertNull(q.sleduyushaya(50))
        assertFalse(q.uPlitki)
        ms += 40_000
        assertFalse(q.demonZhiv)                                          // давно не спрашивал
        val w = Rechi.wav(ShortArray(100))
        assertEquals(244, w.size)
        assertEquals("RIFF", String(w, 0, 4))
    }
}
