package com.questclaude.bridge

import io.github.jaredmdobson.concentus.OpusDecoder
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Стенд гостей на столе (2.5.0): настоящие Komnata, GostiServer и Mikser без Android, страница — из
 * assets/gosti. Часы смеси — 20 мс по таймеру; «фон эфира» — тон 660 Гц (как игра). Что прислали
 * гости — в папку отчёта: звук (WAV, раскодированный Opus), видео (VP8 → IVF, H.264 → .h264, JPEG),
 * итог — otchet.json. Запуск — proba-gosti/zapusk.sh (Chromium с поддельными камерой и микрофоном).
 *   args: порт папка_assets папка_отчёта секунд
 */
fun main(args: Array<String>) {
    val port = args[0].toInt()
    val assets = File(args[1])
    val out = File(args[2]).apply { mkdirs() }
    val sekund = args[3].toInt()
    val kl = Komnata.Klyuchi.iz(null) { }
    val komnata = Komnata(kl).apply { adres = "http://127.0.0.1:$port" }
    File(out, "klyuchi.json").writeText("{\"hozyain\":\"${kl.hozyain}\",\"moder\":\"${kl.moder}\",\"gost\":\"${kl.gost}\"}")
    val mikser = Mikser(komnata)

    class Pishet(val id: Int) {
        val dec = OpusDecoder(48000, 1)
        val pcm = ShortArray(5760)
        val wav = java.io.ByteArrayOutputStream()
        var paketov = 0
        var vp8: FileOutputStream? = null
        var vp8Kadrov = 0
        var vp8Klyuchevyh = 0
        var h264: FileOutputStream? = null
        var h264Kadrov = 0
        var jpeg = 0
        var razmerVp8 = ""
    }
    val pishut = java.util.concurrent.ConcurrentHashMap<Int, Pishet>()

    val media = object : GostiServer.Media {
        override fun zvuk(u: Komnata.Uchastnik, seq: Int, d: ByteArray, off: Int, len: Int) {
            mikser.prinyat(u, seq, d, off, len)
            val p = pishut.getOrPut(u.id) { Pishet(u.id) }
            synchronized(p) {
                val n = p.dec.decode(d, off, len, p.pcm, 0, 5760, false)
                for (i in 0 until n) { p.wav.write(p.pcm[i].toInt() and 0xff); p.wav.write((p.pcm[i].toInt() shr 8) and 0xff) }
                p.paketov++
            }
        }
        override fun video(u: Komnata.Uchastnik, key: Boolean, ms: Long, d: ByteArray, off: Int, len: Int, kodek: Char) {
            val p = pishut.getOrPut(u.id) { Pishet(u.id) }
            synchronized(p) {
                when (kodek) {
                    'v' -> {
                        val f = p.vp8 ?: FileOutputStream(File(out, "gost${u.id}.ivf")).also { p.vp8 = it; it.write(ivfZagolovok()) }
                        val b = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        b.putInt(len); b.putLong(p.vp8Kadrov.toLong())
                        f.write(b.array()); f.write(d, off, len)
                        p.vp8Kadrov++
                        if (key) { p.vp8Klyuchevyh++; GostiVideo.vp8Razmer(d.copyOfRange(off, off + len))?.let { p.razmerVp8 = "${it.first}x${it.second}" } }
                    }
                    'h' -> {
                        val f = p.h264 ?: FileOutputStream(File(out, "gost${u.id}.h264")).also { p.h264 = it }
                        f.write(d, off, len); p.h264Kadrov++
                    }
                    'j' -> { if (p.jpeg == 0) File(out, "gost${u.id}.jpg").writeBytes(d.copyOfRange(off, off + len)); p.jpeg++ }
                }
            }
        }
        override fun ushel(u: Komnata.Uchastnik) { mikser.ushel(u) }
    }

    val fayly = { imya: String -> File(assets, imya).takeIf { it.isFile }?.readBytes() }
    val server = GostiServer(port, komnata, fayly, media)
    server.start()
    println("стенд: http://127.0.0.1:$port  отчёт: $out")

    val rabotaet = java.util.concurrent.atomic.AtomicBoolean(true)
    val chasy = thread(name = "chasy") {
        val shlem = ShortArray(Mikser.KADR)
        var t = 0L
        var sled = System.nanoTime()
        while (rabotaet.get()) {
            val fon = ShortArray(Mikser.KADR) { (5000 * sin(2 * PI * 660.0 * (t + it) / 48000)).toInt().toShort() }
            t += Mikser.KADR
            mikser.fon(fon, fon.size)
            mikser.tik(shlem)
            sled += 20_000_000L
            val z = sled - System.nanoTime()
            if (z > 0) Thread.sleep(z / 1_000_000, (z % 1_000_000).toInt())
        }
    }
    val konec = System.currentTimeMillis() + sekund * 1000L
    while (System.currentTimeMillis() < konec && !File(out, "stop").exists()) Thread.sleep(200)
    rabotaet.set(false)
    chasy.join(1000)
    val otchet = StringBuilder("{\"lyudi\":${komnata.status()},\"mikser\":${mikser.status()},\"gosti\":[")
    otchet.append(pishut.values.joinToString(",") { p ->
        synchronized(p) {
            p.vp8?.close(); p.h264?.close()
            val w = p.wav.toByteArray()
            File(out, "gost${p.id}.wav").writeBytes(Rechi.wav(ShortArray(0)).copyOf(44).also { zag ->
                val b = java.nio.ByteBuffer.wrap(zag).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                b.putInt(4, 36 + w.size); b.putInt(24, 48000); b.putInt(28, 96000); b.putInt(40, w.size)
            } + w)
            "{\"id\":${p.id},\"paketov\":${p.paketov},\"vp8\":${p.vp8Kadrov},\"vp8_klyuch\":${p.vp8Klyuchevyh}," +
                "\"vp8_razmer\":\"${p.razmerVp8}\",\"h264\":${p.h264Kadrov},\"jpeg\":${p.jpeg}}"
        }
    })
    otchet.append("],\"set\":{\"prinyato\":${server.prinyato.get()},\"otpravleno\":${server.otpravleno.get()},\"vybrosheno\":${server.vybrosheno}}}")
    File(out, "otchet.json").writeText(otchet.toString())
    server.stop()
    println("стенд: готово")
}

private fun ivfZagolovok(): ByteArray {
    val b = java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    b.put("DKIF".toByteArray()); b.putShort(0); b.putShort(32); b.put("VP80".toByteArray())
    b.putShort(360); b.putShort(360); b.putInt(15); b.putInt(1); b.putInt(0); b.putInt(0)
    return b.array()
}
