package com.questclaude.most3

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Раскладка usbdevfs_urb для arm64 (offsetof на C, 22.10) и пик PCM. */
class ZvukTest {
    @Test fun urbRaskladka() {
        val b = ByteBuffer.allocate(Urb.RAZMER + Urb.ISO * 8).order(ByteOrder.LITTLE_ENDIAN)
        b.put(5, 77)                                                   // мусор должен стереться
        Urb.zapolnit(b, 0x83, 0x1122334455667788L, 8, 208, 0x0AABBCCDDL)
        assertEquals(0, b.get(0).toInt())                              // USBDEVFS_URB_TYPE_ISO
        assertEquals(0x83, b.get(1).toInt() and 0xff)
        assertEquals(0, b.get(5).toInt())
        assertEquals(Urb.ISO_ASAP, b.getInt(8))
        assertEquals(0x1122334455667788L, b.getLong(16))
        assertEquals(208 * 8, b.getInt(24))
        assertEquals(8, b.getInt(36))
        assertEquals(0x0AABBCCDDL, b.getLong(48))
        for (i in 0 until 8) assertEquals(208, b.getInt(56 + 12 * i))
        b.putInt(56 + 12 * 3 + 4, 192); b.putInt(56 + 12 * 3 + 8, -18)
        assertEquals(192 to -18, Urb.paket(b, 3))
    }

    @Test fun ioctlNomera() {
        // _IOR('U', 10, struct usbdevfs_urb[56]) и _IOW('U', 12, void*) — как напечатал gcc
        assertEquals(0x8038550AL, Urb.SUBMITURB)
        assertEquals(0x4008550CL, Urb.REAPURB)
        assertEquals(0x4008550DL, Urb.REAPURBNDELAY)
        assertEquals(0x550BL, Urb.DISCARDURB)
    }

    @Test fun pikIDb() {
        val b = byteArrayOf(0, 0, 0x00, 0x40, 0x00, 0x80.toByte(), 1, 0)   // 0, 16384, -32768, 1
        assertEquals(32767, Pcm.pik(b, b.size))
        assertEquals(16384, Pcm.pik(b, 4))
        assertEquals(-90, Pcm.db(0))
        assertEquals(-6, Pcm.db(16384))
        assertEquals(0, Pcm.db(32767))
    }

    @Test fun json() {
        assertEquals("\"а\\\"б\\n\\u0001\"", Json.str("а\"б\n\u0001"))
    }
}

/** 3.2.1: «шаги» и «ровно» — что именно они делают со звуком и что направление (разница ушей) не трогают. */
class ObrabotkaTest {
    private fun sinus(hz: Int, f: Double, ampL: Double, ampR: Double, sek: Double = 0.5): ByteArray {
        val n = (hz * sek).toInt(); val b = ByteArray(n * 4)
        for (i in 0 until n) {
            val s = Math.sin(2 * Math.PI * f * i / hz)
            val l = Math.round(s * ampL * 32767).toInt(); val r = Math.round(s * ampR * 32767).toInt()
            b[4 * i] = l.toByte(); b[4 * i + 1] = (l shr 8).toByte(); b[4 * i + 2] = r.toByte(); b[4 * i + 3] = (r shr 8).toByte()
        }
        return b
    }
    /** Среднеквадратичный уровень канала во второй половине (фильтры и огибающая уже установились), дБ. */
    private fun db(b: ByteArray, kanal: Int): Double {
        var s = 0.0; var n = 0; var i = b.size / 8 * 4
        while (i + 3 < b.size) {
            val v = ((b[i + 2 * kanal].toInt() and 0xff) or (b[i + 2 * kanal + 1].toInt() shl 8)).toShort() / 32768.0
            s += v * v; n++; i += 4
        }
        return 10 * Math.log10(s / n)
    }
    private fun raznica(f: Double, amp: Double, nastroit: (Obrabotka) -> Unit): Double {
        val b = sinus(48000, f, amp, amp); val do_ = db(b, 0)
        Obrabotka(48000, 2).also(nastroit).obrabotat(b, b.size)
        return db(b, 0) - do_
    }

    @Test fun vyklyuchenoNeTrogaet() {
        val b = sinus(48000, 1000.0, 0.3, 0.1); val kopiya = b.copyOf()
        Obrabotka(48000, 2).obrabotat(b, b.size)
        org.junit.Assert.assertArrayEquals(kopiya, b)
    }

    @Test fun shagiGromcheGulTishe() {
        val shagi = raznica(3000.0, 0.05) { it.shagi = true }
        val gul = raznica(50.0, 0.05) { it.shagi = true }
        org.junit.Assert.assertTrue("полоса шагов $shagi дБ", shagi > 3.0 && shagi < 5.0)      // +7 дБ полосы −3 дБ запаса
        org.junit.Assert.assertTrue("гул $gul дБ", gul < -8.0 && gul > -10.0)                    // −6 дБ полки −3 дБ запаса
    }

    @Test fun rovnoTihoeGromcheGromkoeSzhato() {
        val tiho = raznica(1000.0, 0.003) { it.rovno = true }                                    // −50 дБ: только подъём
        val gromko = raznica(1000.0, 0.9) { it.rovno = true }                                    // −1 дБ: сжато
        org.junit.Assert.assertTrue("тихое $tiho дБ", tiho > 8.0 && tiho < 10.0)
        org.junit.Assert.assertTrue("громкое $gromko дБ", gromko < -8.0)
        assertEquals(9.0, Obrabotka(48000, 2).usilenieDb(-60.0), 0.001)
        assertEquals(-30.0 + 30.0 / 3 + 9.0, Obrabotka(48000, 2).usilenieDb(0.0), 0.001)         // 0 дБ → −11 дБ
    }

    @Test fun napravlenieSohranyaetsya() {
        // звук справа тише на 12 дБ, чем слева: после любой обработки разница ушей та же
        for (nastroit in listOf<(Obrabotka) -> Unit>({ it.shagi = true }, { it.rovno = true }, { it.shagi = true; it.rovno = true })) {
            val b = sinus(48000, 2500.0, 0.4, 0.1); val do_ = db(b, 0) - db(b, 1)
            Obrabotka(48000, 2).also(nastroit).obrabotat(b, b.size)
            assertEquals(do_, db(b, 0) - db(b, 1), 0.1)
        }
    }

    @Test fun neRezhetPiki() {
        val b = sinus(48000, 3000.0, 0.95, 0.95)
        Obrabotka(48000, 2).also { it.shagi = true; it.rovno = true }.obrabotat(b, b.size)
        org.junit.Assert.assertTrue(Pcm.pik(b.copyOfRange(b.size / 2, b.size), b.size / 2) < 32767)
    }
}
