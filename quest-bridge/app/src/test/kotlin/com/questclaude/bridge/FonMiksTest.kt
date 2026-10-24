package com.questclaude.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class FonMiksTest {
    private val B = FonMiks.BLOK

    /** Стерео тон [hz] на [sek] секунд, амплитуда [a]. */
    private fun ton(sek: Double, hz: Double = 440.0, a: Int = 10000): ShortArray {
        val n = (sek * Vstavka.RATE).toInt()
        return ShortArray(2 * n) { i -> (a * sin(2 * PI * hz * (i / 2) / Vstavka.RATE)).toInt().toShort() }
    }

    private fun pik(a: ShortArray, kadrov: Int): Int { var m = 0; for (i in 0 until 2 * kadrov) m = maxOf(m, abs(a[i].toInt())); return m }

    @Test fun ocheredZhdetMestaIStop() {
        val m = FonMiks(zapasKadrov = Vstavka.RATE)                      // запас — 1 с
        val metka = m.zhdatMesta(100)
        assertTrue(metka >= 0)
        assertTrue(m.dobavit(ton(2.0), metka))
        assertEquals(-1, m.zhdatMesta(100))                               // впереди 2 с — места нет
        val sh = ShortArray(2 * B)
        repeat(60) { m.vzyat(B, sh, null, false, false) }                 // ~1,3 с сыграно
        assertTrue(m.zhdatMesta(100) >= 0)
        // ждущий кусок: /fon/stop отпускает его с отказом, а старая метка в очередь не кладёт
        assertTrue(m.dobavit(ton(2.0), m.zhdatMesta(100)))
        var otvet = 0
        val t = thread { otvet = m.zhdatMesta(5000) }
        Thread.sleep(150)
        m.stop()
        t.join(2000)
        assertEquals(-1, otvet)
        assertEquals(0, m.ochered)
        assertFalse(m.dobavit(ton(0.5), metka))
    }

    @Test fun prigluschaetsyaPodGolosomIVozvraschaetsya() {
        val m = FonMiks()
        m.dobavit(ton(5.0), m.zhdatMesta(100))
        val sh = ShortArray(2 * B)
        val ef = ShortArray(2 * B)
        m.vzyat(B, sh, ef, false, false)
        val polnyy = pik(sh, B)
        repeat(8) { m.vzyat(B, sh, ef, true, false) }                     // 8 × 21 мс > 150 мс — уже внизу
        assertEquals(m.duck, m.gShlem, 1e-4f)
        assertEquals(1f, m.gEfir, 1e-4f)                                  // «в ухо» — зрители голоса не слышат, им полная
        assertTrue(pik(sh, B) < polnyy * 0.4)
        assertTrue(pik(ef, B) > polnyy * 0.9)
        m.vzyat(B, sh, ef, false, false)                                   // голос кончился — поднимается плавно
        assertTrue(m.gShlem > m.duck && m.gShlem < 0.4f)
        repeat(50) { m.vzyat(B, sh, ef, false, false) }                   // ~1 с
        assertEquals(1f, m.gShlem, 1e-4f)
    }

    @Test fun gromkostShlemaIEfiraOtdelno() {
        val m = FonMiks()
        m.gromShlem = 0.5f
        m.gromEfir = 1.2f
        m.dobavit(ton(1.0, a = 8000), m.zhdatMesta(100))
        val sh = ShortArray(2 * B)
        val ef = ShortArray(2 * B)
        m.vzyat(B, sh, ef, false, false)
        assertEquals(4000.0, pik(sh, B).toDouble(), 80.0)
        assertEquals(9600.0, pik(ef, B).toDouble(), 150.0)
    }

    @Test fun pustayaOcheredTishina() {
        val m = FonMiks()
        val sh = ShortArray(2 * B) { 777 }
        assertEquals(0, m.vzyat(B, sh, null, false, false))
        assertTrue(sh.all { it.toInt() == 0 })
        m.dobavit(ton(0.01), m.zhdatMesta(100))                           // 480 кадров — остаток тишиной
        assertEquals(480, m.vzyat(B, sh, null, false, false))
        assertTrue((2 * 480 until 2 * B).all { sh[it].toInt() == 0 })
    }

    @Test fun mikrofonGovoritNoNeEho() {
        val m = FonMiks()
        fun golos(a: Int) = ShortArray(320) { i -> (a * sin(2 * PI * 200 * i / 16000.0)).toInt().toShort() }
        m.mic(golos(300), 320, 1000)                                       // тихо (−44 дБ): не говорит
        assertFalse(m.micGovorit(1000))
        m.mic(golos(6000), 320, 1000)                                      // громко: говорит, держим 600 мс
        assertTrue(m.micGovorit(1500))
        assertFalse(m.micGovorit(1700))
        // музыка в шлеме громкая — тот же уровень микрофона, что ниже четверти её, — это эхо, не голос
        m.dobavit(ton(1.0, a = 30000), m.zhdatMesta(100))
        m.vzyat(B, ShortArray(2 * B), null, false, false)
        m.mic(golos(1500), 320, 5000)
        assertFalse(m.micGovorit(5100))
        m.mic(golos(16000), 320, 5000)
        assertTrue(m.micGovorit(5100))
    }

    @Test fun ekvalayzerVidinTon1kGc() {
        val m = FonMiks()
        m.dobavit(ton(0.5, hz = 1000.0, a = 16000), m.zhdatMesta(100))
        m.vzyat(B, ShortArray(2 * B), null, false, false)
        val sp = m.spektr
        val max = sp.indices.maxByOrNull { sp[it] }!!
        assertEquals(4, max)                                               // полоса 1000 Гц
        assertTrue(sp[4] > 0.8f)
        assertTrue(sp[0] < 0.35f && sp[9] < 0.35f)
    }

    @Test fun lentaKopitZapasPotomOtdayot() {
        val l = Lenta(maks = 9600, zapas = 2400)                           // 0,2 с, запас 50 мс
        val kus = ShortArray(2 * 1200) { 1000 }
        assertTrue(l.dobavit(kus, 1200))
        val out = ShortArray(2 * 1000)
        l.mix(out, 1000)
        assertTrue(out.all { it.toInt() == 0 })                            // меньше запаса — ещё не играем
        assertTrue(l.dobavit(kus, 1200))
        l.mix(out, 1000)
        assertTrue(out.all { it.toInt() == 1000 })
        l.mix(out, 1000); l.mix(out, 1000)                                 // кончилась — снова копить
        assertEquals(0, l.kadrov)
        assertTrue(l.dobavit(kus, 1200))
        val out2 = ShortArray(2 * 1000)
        l.mix(out2, 1000)
        assertTrue(out2.all { it.toInt() == 0 })
        repeat(7) { l.dobavit(kus, 1200) }
        assertFalse(l.dobavit(kus, 1200))                                  // полная: эфир не берёт — новое мимо
    }

    @Test fun v48stereo() {
        val mono = ShortArray(441) { 1000 }
        val st = Vstavka.v48stereo(mono, 44100, 1)
        assertEquals(2 * 480, st.size)
        assertTrue(st.all { it.toInt() == 1000 })
        val lr = ShortArray(2 * 4800) { if (it % 2 == 0) 100 else -100 }
        val st2 = Vstavka.v48stereo(lr, 48000, 2)
        assertArrayEquals(lr, st2)
    }
}
