package com.questclaude.bridge

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Смесь гостей: каждый слышит всех, кроме себя; шлем — всех гостей; эфир — всех; фон — с его
 * громкостью игры; заслонка эха; «говорит»; речь для субтитров. Opus — как у браузера (кодирует
 * гость) и обратно (раскодирует гость) — Concentus.
 */
class MikserTest {
    private class Svyaz : Komnata.Svyaz {
        val pakety = mutableListOf<ByteArray>()
        val teksty = mutableListOf<String>()
        override fun tekst(json: String) { teksty += json }
        override fun zakryt(prichina: String) {}
        override fun dvoichnoe(d: ByteArray, vid: Char, key: Boolean) { if (vid == 'a') pakety += d }
    }

    private val kl = Komnata.Klyuchi.iz(null) { }
    private val k = Komnata(kl)
    private val rechi = HashMap<String, Int>()
    @Volatile private var slushaet = false
    private val m = Mikser(k, { g, _, n -> rechi[g] = (rechi[g] ?: 0) + n }, { slushaet })

    private fun gost(imya: String, mikrofon: Boolean = true): Pair<Komnata.Uchastnik, Svyaz> {
        val s = Svyaz()
        val u = k.vhod(s, kl.moder, imya, false, mikrofon)!!
        return u to s
    }

    /** Кодировщик «браузера» гостя: тон частоты f. */
    private class Ton(val f: Double, val amp: Double = 6000.0) {
        val enc = OpusEncoder(48000, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply { bitrate = 48000 }
        var t = 0L
        val out = ByteArray(1500)
        fun paket(): ByteArray {
            val pcm = ShortArray(960) { (amp * sin(2 * PI * f * (t + it) / 48000)).toInt().toShort() }
            t += 960
            val n = enc.encode(pcm, 0, 960, out, 0, out.size)
            return out.copyOf(n)
        }
    }

    /** Мощность частоты f в сигнале (Гёрцель), нормированная. */
    private fun ton(a: ShortArray, f: Double, rate: Int = 48000): Double {
        val w = 2 * PI * f / rate
        val c = 2 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (v in a) { val s = v + c * s1 - s2; s2 = s1; s1 = s }
        return sqrt(s1 * s1 + s2 * s2 - c * s1 * s2) / a.size
    }

    private fun raskodirovat(pakety: List<ByteArray>): ShortArray {
        val d = OpusDecoder(48000, 1)
        val out = ArrayList<Short>()
        val pcm = ShortArray(960)
        for (p in pakety) {
            assertEquals(11, p[0].toInt())
            val n = d.decode(p, 3, p.size - 3, pcm, 0, 960, false)
            for (i in 0 until n) out += pcm[i]
        }
        return out.toShortArray()
    }

    @Test fun kazhdyySlyshitVsehKromeSebya() {
        val (a, sa) = gost("А")
        val (b, sb) = gost("Б")
        val (c, sc) = gost("Слушатель", mikrofon = false)       // модератор без микрофона — тоже слышит
        val ta = Ton(440.0); val tb = Ton(1000.0)
        val shlem = ShortArray(960)
        val shlemVse = ArrayList<Short>()
        val efir = ArrayList<Short>()
        val efirBuf = ShortArray(2048)
        for (i in 0 until 150) {                                   // 3 секунды
            val pa = ta.paket(); m.prinyat(a, i, pa, 0, pa.size)
            val pb = tb.paket(); m.prinyat(b, i, pb, 0, pb.size)
            m.fon(ShortArray(960) { (3000 * sin(2 * PI * 3000.0 * (i * 960 + it) / 48000)).toInt().toShort() }, 960)
            m.tik(shlem, 1000L + i * 20)
            if (i > 20) shlemVse.addAll(shlem.toList())
            if (i % 2 == 0) {                                       // эфир берёт куски по 1024 кадра
                java.util.Arrays.fill(efirBuf, 0)
                m.vEfir(efirBuf, 1024)
                if (i > 20) for (j in 0 until 1024) efir += efirBuf[2 * j]
            }
        }
        val A = raskodirovat(sa.pakety.drop(20)); val B = raskodirovat(sb.pakety.drop(20)); val C = raskodirovat(sc.pakety.drop(20))
        // А слышит Б (1000), не себя (440); Б — наоборот; слушатель — обоих
        assertTrue("А слышит Б", ton(A, 1000.0) > 20 * ton(A, 440.0))
        assertTrue("Б слышит А", ton(B, 440.0) > 20 * ton(B, 1000.0))
        assertTrue(ton(C, 440.0) > 100 && ton(C, 1000.0) > 100)
        // фон (игра) — у всех, громкость игры по умолчанию 0,5
        assertTrue(ton(A, 3000.0) > 100)
        // шлем и эфир — оба гостя, без фона (фон в эфире и так есть, в шлеме — свой звук игры)
        val sh = shlemVse.toShortArray(); val ef = efir.toShortArray()
        assertTrue(ton(sh, 440.0) > 100 && ton(sh, 1000.0) > 100)
        assertTrue(ton(sh, 3000.0) < 5)
        assertTrue(ton(ef, 440.0) > 100 && ton(ef, 1000.0) > 100)
        // говорят оба, третий — нет; речь для субтитров по 320 отсчётов 16 кГц на такт
        assertTrue(a.govorit && b.govorit && !c.govorit)
        assertTrue(rechi["g1"]!! > 100 * 320 && rechi["g2"]!! > 100 * 320)
        assertTrue(m.gostZvuchit(1000L + 150 * 20))
        assertFalse(m.gostZvuchit(1000L + 150 * 20 + 5000))
        assertTrue(m.status().contains("\"opus_proc\""))
    }

    @Test fun gromkostIgryUKazhdogoSvoya() {
        val (a, sa) = gost("Тихо")
        val (b, sb) = gost("Громко")
        k.sam(a, null, null, 0.0, null)
        k.sam(b, null, null, 1.0, null)
        val shlem = ShortArray(960)
        for (i in 0 until 60) {
            m.fon(ShortArray(960) { (4000 * sin(2 * PI * 700.0 * (i * 960 + it) / 48000)).toInt().toShort() }, 960)
            m.tik(shlem, i * 20L)
        }
        val A = raskodirovat(sa.pakety.drop(10)); val B = raskodirovat(sb.pakety.drop(10))
        assertTrue(ton(A, 700.0) < 3)
        assertTrue(ton(B, 700.0) > 300)
    }

    /** 2.5.3, «🔇 Только голоса»: игра у гостя — ноль, а голос Плюса (вставки эфира) звучит всегда. */
    @Test fun tolkoGolosaPlyusOstaetsya() {
        val (a, sa) = gost("Без наушников")
        k.sam(a, null, null, 0.0, null)
        val shlem = ShortArray(960)
        for (i in 0 until 60) {
            m.fon(ShortArray(960) { (4000 * sin(2 * PI * 700.0 * (i * 960 + it) / 48000)).toInt().toShort() }, 960)
            m.golos(ShortArray(960) { (4000 * sin(2 * PI * 1900.0 * (i * 960 + it) / 48000)).toInt().toShort() }, 960)
            m.tik(shlem, i * 20L)
        }
        val A = raskodirovat(sa.pakety.drop(10))
        assertTrue("игры нет", ton(A, 700.0) < 3)
        assertTrue("Плюс слышен", ton(A, 1900.0) > 300)
        assertTrue("Плюса нет в шлеме гостей", ton(shlem, 1900.0) < 5)
        assertTrue(m.status().contains("\"golos_ms\""))
    }

    @Test fun plusSlushaetGostiTishe() {
        val (a, _) = gost("А")
        val t = Ton(500.0)
        val shlem = ShortArray(960)
        var gromko = 0.0; var tiho = 0.0
        for (i in 0 until 100) {
            val p = t.paket(); m.prinyat(a, i, p, 0, p.size)
            slushaet = i >= 50
            m.tik(shlem, i * 20L)
            if (i in 20..45) gromko += ton(shlem, 500.0)
            if (i in 70..95) tiho += ton(shlem, 500.0)
        }
        assertTrue("−18 дБ ≈ в 8 раз", gromko > 6 * tiho)
    }

    @Test fun zaslonkaEha() {
        val (a, sa) = gost("Гость")
        val t = Ton(500.0, amp = 12000.0)
        val shlem = ShortArray(960)
        // эхо: микрофон слышит гостя из динамиков тихо (−26 дБ) — гостю его микрофон глушится
        for (i in 0 until 100) {
            val p = t.paket(); m.prinyat(a, i, p, 0, p.size)
            m.mic(ShortArray(320) { (300 * sin(2 * PI * 200.0 * (i * 320 + it) / 16000)).toInt().toShort() }, 320)
            m.tik(shlem, i * 20L)
        }
        val eho = raskodirovat(sa.pakety.drop(40))
        // Александр говорит громко поверх гостя — микрофон проходит
        sa.pakety.clear()
        for (i in 100 until 200) {
            val p = t.paket(); m.prinyat(a, i, p, 0, p.size)
            m.mic(ShortArray(320) { (6000 * sin(2 * PI * 200.0 * (i * 320 + it) / 16000)).toInt().toShort() }, 320)
            m.tik(shlem, i * 20L)
        }
        val rech = raskodirovat(sa.pakety.drop(40))
        val usilEho = ton(eho, 200.0) / 300
        val usilRech = ton(rech, 200.0) / 6000
        assertTrue("эхо заглушено (${usilEho / usilRech})", usilEho < usilRech * 0.45)
        // заслонка выключена — микрофон как есть
        m.eho = 0f
        sa.pakety.clear()
        for (i in 200 until 300) {
            val p = t.paket(); m.prinyat(a, i, p, 0, p.size)
            m.mic(ShortArray(320) { (300 * sin(2 * PI * 200.0 * (i * 320 + it) / 16000)).toInt().toShort() }, 320)
            m.tik(shlem, i * 20L)
        }
        val bez = raskodirovat(sa.pakety.drop(40))
        assertTrue(ton(bez, 200.0) / 300 > usilRech * 0.8)
    }

    @Test fun zaglushennyyNeSlyshen() {
        val (a, _) = gost("А")
        val (b, sb) = gost("Б")
        val t = Ton(440.0)
        k.upr(null, "zaglushit", a.id)
        val shlem = ShortArray(960)
        for (i in 0 until 60) { val p = t.paket(); m.prinyat(a, i, p, 0, p.size); m.tik(shlem, i * 20L) }
        assertTrue(ton(raskodirovat(sb.pakety.drop(10)), 440.0) < 3)
        assertFalse(a.govorit)
    }

    @Test fun ushelKanalProch() {
        val (a, _) = gost("А")
        val t = Ton(440.0)
        val p = t.paket(); m.prinyat(a, 0, p, 0, p.size)
        assertTrue(m.status().contains("\"id\":${a.id}"))
        m.ushel(a)
        assertFalse(m.status().contains("\"id\":${a.id},"))
    }
}
