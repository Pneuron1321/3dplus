package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Круг клипа камеры (2.5.9): куски с ключевых кадров, старое удаляется, снимок — с ключевого кадра. */
class KlipZapisTest {
    private val sps = byteArrayOf(0x67, 0x42, 0, 0x1f)
    private val pps = byteArrayOf(0x68, 0xce.toByte(), 0x3c, 0x80.toByte())

    private fun kadr(i: Int) = byteArrayOf(0, 0, 0, 1, if (i % 30 == 0) 0x65 else 0x41, i.toByte(), 7, 7)

    /** sek секунд видео 30 к/с, ключевой — раз в секунду, и звук 50 кадров/с. */
    private fun zapisat(z: KlipZapis, sek: Int, ot: Int = 0) {
        for (i in ot * 30 until (ot + sek) * 30) {
            val pts = i * 33_333L
            z.video(kadr(i), pts, i % 30 == 0)
            if (i % 3 == 0) { z.zvuk(byteArrayOf(0x21, i.toByte()), pts); z.zvuk(byteArrayOf(0x21, i.toByte()), pts + 20_000) }
        }
    }

    private fun papka(): File = Files.createTempDirectory("klip").toFile()

    @Test fun poslednieSekundySKlyuchevogo() {
        val z = KlipZapis(papka(), derzhatMs = 130_000, kuskomMs = 10_000)
        z.golova(sps, pps, 1920, 1080)
        z.zvukGolova(byteArrayOf(0x12, 0x10))
        zapisat(z, 60)
        val s = z.snimok(20)
        assertNotNull(s)
        s!!
        assertEquals(1920, s.golova.w)
        assertTrue("клип с последнего ключевого кадра не позже 20 с назад: ${s.otUs}", s.otUs in 38_900_000L..39_970_000L)
        var pervyy: KlipZapis.Obrazec? = null
        var video = 0; var zvuk = 0
        s.obrazcy { o -> if (pervyy == null) pervyy = o; if (o.video) video++ else zvuk++ }
        assertTrue(pervyy!!.video && pervyy!!.key)
        assertTrue("кадров $video", video in 20 * 30..21 * 30 + 1)
        assertTrue("звука $zvuk", zvuk in 400..430)
        assertEquals(59, z.sekund)
    }

    @Test fun staroeUdalyaetsya() {
        val dir = papka()
        val z = KlipZapis(dir, derzhatMs = 30_000, kuskomMs = 5_000)
        z.golova(sps, pps, 1280, 720)
        zapisat(z, 120)
        assertTrue("держит ~30 с, а не 120: ${z.sekund}", z.sekund in 30..42)
        assertTrue(dir.listFiles()!!.size <= 9)
        val s = z.snimok(120)!!
        assertTrue("просили больше, чем есть — отдаёт весь круг: ${s.sek}", s.sek in 30..42)
    }

    @Test fun pokaChitayutNeUdalyaet() {
        val dir = papka()
        val z = KlipZapis(dir, derzhatMs = 10_000, kuskomMs = 2_000)
        z.golova(sps, pps, 1280, 720)
        zapisat(z, 20)
        val s = z.snimok(10)!!
        zapisat(z, 30, ot = 20)                               // пишем дальше, пока снимок не прочитан
        var n = 0
        s.obrazcy { if (it.video) n++ }
        assertTrue(n >= 10 * 30)
    }

    @Test fun neprochitannyiOtpushchen() {
        val dir = papka()
        val z = KlipZapis(dir, derzhatMs = 10_000, kuskomMs = 2_000)
        z.golova(sps, pps, 1280, 720)
        zapisat(z, 20)
        z.snimok(10)!!.otpustit()                            // сохранение упало до чтения
        zapisat(z, 60, ot = 20)
        assertTrue("круг чистится дальше: ${z.sekund} с", z.sekund <= 14)
    }

    @Test fun novayaGolovaKrugZanovo() {
        val z = KlipZapis(papka())
        z.golova(sps, pps, 1920, 1080)
        zapisat(z, 10)
        z.golova(sps, pps, 1080, 1920)                        // телефон повернули — другой размер
        assertNull(z.snimok(10))
        zapisat(z, 3, ot = 10)
        assertEquals(1080, z.snimok(10)!!.golova.w)
    }

    /** 2.8.0 (Александр 29.10: «клип с эфира сохранился без звука»): заголовок звука эфира приходит раньше видео —
     *  первый заголовок видео не должен его стирать. */
    @Test fun zvukDoVideoNeTeryaetsya() {
        val z = KlipZapis(papka())
        z.zvukGolova(byteArrayOf(0x12, 0x10))                  // звук эфира заговорил первым
        z.golova(sps, pps, 1920, 1080)
        zapisat(z, 5)
        val s = z.snimok(5)!!
        assertNotNull(s.asc)
        var zvuk = 0
        s.obrazcy { o -> if (!o.video) zvuk++ }
        assertTrue("звука $zvuk", zvuk >= 90)
    }

    @Test fun bezGolovyNichego() {
        val z = KlipZapis(papka())
        zapisat(z, 5)
        assertNull(z.snimok(5))
        assertEquals(0, z.sekund)
    }

    @Test fun zagolovkiIzKadraVon() {
        val a = byteArrayOf(0, 0, 0, 1, 0x09, 0x10, 0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 9, 9)
        val b = KlipZapis.bezZagolovkov(a)
        assertEquals(listOf(5), Flv.nals(b).map { Flv.nalType(b, it.first) })
        val c = byteArrayOf(0, 0, 0, 1, 0x41, 5)
        assertTrue(KlipZapis.bezZagolovkov(c) === c)
    }

    @Test fun aacZagolovok() {
        assertEquals(44100 to 2, KlipZapis.aac(byteArrayOf(0x12, 0x10)))      // AAC-LC 44,1 кГц стерео
        assertEquals(48000 to 1, KlipZapis.aac(byteArrayOf(0x11, 0x88.toByte())))
    }
}
