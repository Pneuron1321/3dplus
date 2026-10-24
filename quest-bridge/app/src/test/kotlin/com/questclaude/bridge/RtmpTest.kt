package com.questclaude.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Эфир без шлема: H.264 и AAC (как от кодировщиков Android) → наш Rtmp → настоящий ffmpeg
 * в роли сервера (как Twitch) → файл FLV → ffprobe: оба потока на месте, длительность верна.
 * Нет ffmpeg на машине — проверка пропускается.
 */
class RtmpTest {
    private fun has(p: String) = try {
        ProcessBuilder(p, "-version").redirectErrorStream(true).start().run { inputStream.readBytes(); waitFor() == 0 }
    } catch (e: IOException) { false }

    private fun run(vararg c: String) {
        val p = ProcessBuilder(*c).redirectErrorStream(true).start()
        val out = String(p.inputStream.readBytes())
        assertTrue(out, p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0)
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Test fun amf0RoundTrip() {
        val b = Flv.Amf0().str("_result").num(4.0).nul().obj(linkedMapOf("level" to "status", "code" to "NetStream.Publish.Start"))
            .bool(true).ecma(linkedMapOf("width" to 1920)).bytes()
        val v = Flv.amf0(b)
        assertEquals("_result", v[0])
        assertEquals(4.0, v[1])
        assertEquals(null, v[2])
        assertEquals("NetStream.Publish.Start", (v[3] as Map<*, *>)["code"])
        assertEquals(true, v[4])
        assertEquals(1920.0, (v[5] as Map<*, *>)["width"])
    }

    @Test fun annexBToAvcc() {
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x28, 0x11)
        val pps = byteArrayOf(0x68, 0x22, 0x33)
        val idr = byteArrayOf(0x65, 0x01, 0x02, 0x03)
        val a = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps + byteArrayOf(0, 0, 1) + idr
        val (s, p) = Flv.spsPps(a)
        assertArrayEquals(sps, s)
        assertArrayEquals(pps, p)
        assertArrayEquals(byteArrayOf(0, 0, 0, 4) + idr, Flv.avcc(a))      // SPS/PPS — только в заголовке
        val cfg = Flv.avcConfig(sps, pps)
        assertEquals(1, cfg[0].toInt())
        assertEquals(0x64, cfg[1].toInt())                                  // профиль High
        assertEquals(0xe1, cfg[5].toInt() and 0xff)
        assertEquals(0x17, Flv.video(byteArrayOf(1), key = true)[0].toInt())
        assertEquals(0x27, Flv.video(byteArrayOf(1), key = false)[0].toInt())
        assertEquals(0xaf, Flv.audio(byteArrayOf(1))[0].toInt() and 0xff)
    }

    /** Кадры H.264 из файла Annex-B: (данные Annex-B кадра, ключевой). Кадры разделены AUD (тип 9). */
    private fun kadry(a: ByteArray): List<Pair<ByteArray, Boolean>> {
        val nals = Flv.nals(a)
        val starts = nals.filter { Flv.nalType(a, it.first) == 9 }.map { it.first - 3 } + a.size
        return starts.zipWithNext { s, e ->
            val chunk = a.copyOfRange(maxOf(0, s), e)
            chunk to Flv.nals(chunk).any { Flv.nalType(chunk, it.first) == 5 }
        }
    }

    /** Кадры AAC из ADTS и AudioSpecificConfig. */
    private fun aac(a: ByteArray): Pair<ByteArray, List<ByteArray>> {
        var i = 0
        var asc = ByteArray(0)
        val out = mutableListOf<ByteArray>()
        while (i + 7 <= a.size) {
            val b = { k: Int -> a[i + k].toInt() and 0xff }
            if (b(0) != 0xff || b(1) and 0xf0 != 0xf0) { i++; continue }
            val hdr = if (b(1) and 1 == 1) 7 else 9
            val profile = b(2) ushr 6 and 3
            val sr = b(2) ushr 2 and 0xf
            val ch = (b(2) and 1 shl 2) or (b(3) ushr 6 and 3)
            val len = (b(3) and 3 shl 11) or (b(4) shl 3) or (b(5) ushr 5 and 7)
            val v = ((profile + 1) shl 11) or (sr shl 7) or (ch shl 3)
            asc = byteArrayOf((v ushr 8).toByte(), v.toByte())
            out += a.copyOfRange(i + hdr, i + len)
            i += len
        }
        return asc to out
    }

    @Test fun publishesToRealFfmpeg() {
        assumeTrue("нет ffmpeg", has("ffmpeg") && has("ffprobe"))
        val d = File.createTempFile("rtmp", "").apply { delete(); mkdirs() }
        val v = File(d, "v.h264"); val a = File(d, "a.aac"); val out = File(d, "out.flv")
        run("ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30", "-t", "3",
            "-c:v", "libx264", "-bf", "0", "-g", "30", "-pix_fmt", "yuv420p", "-x264-params", "aud=1", "-f", "h264", v.path)
        run("ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "3",
            "-ac", "2", "-c:a", "aac", "-b:a", "128k", "-f", "adts", a.path)
        val port = freePort()
        val srv = ProcessBuilder("ffmpeg", "-loglevel", "error", "-y", "-listen", "1",
            "-i", "rtmp://127.0.0.1:$port/live/TESTKEY", "-c", "copy", "-f", "flv", out.path)
            .redirectErrorStream(true).start()
        try {
            val r = Rtmp("rtmp://127.0.0.1:$port/live", "TESTKEY")
            var tries = 0
            while (true) {
                try { r.connect(); break } catch (e: IOException) {
                    if (++tries > 30) throw e
                    Thread.sleep(200)
                }
            }
            assertTrue(r.alive)
            val h = v.readBytes()
            val frames = kadry(h)
            val (sps, pps) = Flv.spsPps(frames[0].first)
            val (asc, aframes) = aac(a.readBytes())
            r.meta(320, 240, 30, 1000, 128, 48000)
            r.video(Flv.video(Flv.avcConfig(sps!!, pps!!), key = true, config = true), 0)
            r.audio(Flv.audio(asc, config = true), 0)
            // по времени: кадр видео — 1000/30 мс, кадр AAC — 1024 сэмпла при 48 кГц
            val all = frames.mapIndexed { k, (f, key) -> Triple(k * 1000L / 30, true, Flv.video(Flv.avcc(f), key)) } +
                aframes.mapIndexed { k, f -> Triple(k * 1024L * 1000 / 48000, false, Flv.audio(f)) }
            for ((ms, isVideo, p) in all.sortedBy { it.first }) if (isVideo) r.video(p, ms) else r.audio(p, ms)
            assertTrue(r.sent > h.size / 2)
            r.close()
            assertTrue("сервер не закончил", srv.waitFor(15, TimeUnit.SECONDS))
        } finally {
            srv.destroy()
        }
        val probe = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,width,channels:format=duration",
            "-of", "default=nw=1", out.path).redirectErrorStream(true).start()
        val info = String(probe.inputStream.readBytes())
        probe.waitFor()
        assertTrue(info, info.contains("codec_name=h264"))
        assertTrue(info, info.contains("codec_name=aac"))
        assertTrue(info, info.contains("width=320"))
        assertTrue(info, info.contains("channels=2"))
        val dur = Regex("duration=([0-9.]+)").find(info)!!.groupValues[1].toDouble()
        assertTrue(info, dur in 2.5..3.5)
    }

    @Test fun wrongKeyIsNotInError() {
        assumeTrue("нет ffmpeg", has("ffmpeg"))
        val port = freePort()
        val srv = ProcessBuilder("ffmpeg", "-loglevel", "error", "-y", "-listen", "1",
            "-i", "rtmp://127.0.0.1:$port/live/TESTKEY", "-c", "copy", "-f", "null", "-")
            .redirectErrorStream(true).start()
        try {
            val r = Rtmp("rtmp://127.0.0.1:$port/live", "SECRET_live_12345", timeoutMs = 3000)
            var msg: String? = null
            var tries = 0
            while (msg == null && tries++ < 30) {
                try { r.connect(); msg = "" } catch (e: IOException) {
                    if (e.message?.contains("Connection refused") == true || e.message?.contains("ConnectException") == true) Thread.sleep(200)
                    else msg = e.message.orEmpty()
                }
            }
            r.close()
            assertFalse(msg.orEmpty(), msg.orEmpty().contains("SECRET_live_12345"))
        } finally {
            srv.destroy()
        }
    }
}
