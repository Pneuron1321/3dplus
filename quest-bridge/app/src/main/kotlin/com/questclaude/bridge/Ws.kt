package com.questclaude.bridge

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * WebSocket (RFC 6455), сторона сервера — для гостей эфира (2.5.0): страница гостя в браузере
 * держит одно соединение, по нему — управление (текст, JSON) и звук с картинкой (двоичные кадры).
 * Только то, что нужно: рукопожатие, чтение кадров клиента (всегда с маской), склейка
 * продолжений, ping/pong/close, запись без маски. Обычная JVM — WsTest.
 */
object Ws {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    const val TEKST = 1
    const val DVOICHNYY = 2
    const val ZAKRYT = 8
    const val PING = 9
    const val PONG = 10

    /** Sec-WebSocket-Accept для ключа клиента. */
    fun otvetKlyuch(klyuch: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((klyuch.trim() + GUID).toByteArray(Charsets.ISO_8859_1)))

    /** Ответ на рукопожатие (101). */
    fun rukopozhatie(klyuch: String): ByteArray =
        ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: ${otvetKlyuch(klyuch)}\r\n\r\n").toByteArray(Charsets.ISO_8859_1)

    class Soobshchenie(val op: Int, val dannye: ByteArray) {
        val tekst: String get() = String(dannye, Charsets.UTF_8)
    }

    class Narushenie(msg: String) : IOException(msg)

    /**
     * Читатель кадров одного соединения. [sleduyushee] возвращает целое сообщение (текст или
     * двоичное, продолжения склеены) или управляющий кадр (ping, pong, close) — как есть;
     * null — соединение закрыто. Больше [maks] байт в сообщении — Narushenie.
     */
    class Chitatel(private val inp: InputStream, private val maks: Int) {
        private var sborOp = 0
        private var sbor: java.io.ByteArrayOutputStream? = null
        private var terpenie = 0

        /**
         * Таймаут сокета (SocketTimeoutException) наружу — только пока ждём первый байт кадра: середину
         * кадра дочитываем, сколько бы ни ждали (до TERPENIE таймаутов), иначе поток рассыпался бы.
         */
        fun sleduyushee(): Soobshchenie? {
            while (true) {
                val b0 = inp.read()
                if (b0 < 0) return null
                terpenie = 0
                val b1 = bayt()
                val fin = b0 and 0x80 != 0
                if (b0 and 0x70 != 0) throw Narushenie("RSV без расширений")
                val op = b0 and 0x0f
                if (b1 and 0x80 == 0) throw Narushenie("кадр клиента без маски")
                var dl = (b1 and 0x7f).toLong()
                if (dl == 126L) dl = ((bayt() shl 8) or bayt()).toLong()
                else if (dl == 127L) { dl = 0; repeat(8) { dl = (dl shl 8) or bayt().toLong() } }
                if (dl < 0 || dl > maks) throw Narushenie("кадр больше $maks байт")
                val maska = ByteArray(4).also { tochno(it, 4) }
                val d = ByteArray(dl.toInt()).also { tochno(it, it.size) }
                for (j in d.indices) d[j] = (d[j].toInt() xor maska[j and 3].toInt()).toByte()
                if (op >= 8) {                                  // управляющий: целиком, ≤ 125 байт
                    if (!fin || dl > 125) throw Narushenie("управляющий кадр по частям")
                    if (op != ZAKRYT && op != PING && op != PONG) throw Narushenie("неизвестный код $op")
                    return Soobshchenie(op, d)
                }
                when (op) {
                    TEKST, DVOICHNYY -> {
                        if (sbor != null) throw Narushenie("новое сообщение посреди продолжений")
                        if (fin) return Soobshchenie(op, d)
                        sborOp = op
                        sbor = java.io.ByteArrayOutputStream().also { it.write(d) }
                    }
                    0 -> {
                        val s = sbor ?: throw Narushenie("продолжение без начала")
                        if (s.size() + d.size > maks) throw Narushenie("сообщение больше $maks байт")
                        s.write(d)
                        if (fin) { sbor = null; return Soobshchenie(sborOp, s.toByteArray()) }
                    }
                    else -> throw Narushenie("неизвестный код $op")
                }
            }
        }

        private fun bayt(): Int {
            while (true) {
                val b = try { inp.read() } catch (e: java.net.SocketTimeoutException) { if (++terpenie > TERPENIE) throw e; continue }
                if (b < 0) throw EOFException()
                return b
            }
        }

        private fun tochno(a: ByteArray, n: Int) {
            var got = 0
            while (got < n) {
                val r = try { inp.read(a, got, n - got) } catch (e: java.net.SocketTimeoutException) {
                    if (++terpenie > TERPENIE) throw e; continue
                }
                if (r < 0) throw EOFException()
                got += r
            }
        }
    }

    /** Сколько таймаутов сокета ждать посреди кадра (по 1 с — полминуты). */
    const val TERPENIE = 30

    /** Заголовок кадра сервера (без маски) для [dl] байт. */
    fun zagolovok(op: Int, dl: Int): ByteArray = when {
        dl < 126 -> byteArrayOf((0x80 or op).toByte(), dl.toByte())
        dl < 65536 -> byteArrayOf((0x80 or op).toByte(), 126, (dl shr 8).toByte(), dl.toByte())
        else -> ByteArray(10).also {
            it[0] = (0x80 or op).toByte(); it[1] = 127
            for (j in 0 until 8) it[2 + j] = (dl.toLong() shr (8 * (7 - j))).toByte()
        }
    }

    /** Записать кадр целиком (вызывать с одного потока или под замком). */
    fun zapisat(out: OutputStream, op: Int, d: ByteArray, off: Int = 0, len: Int = d.size) {
        out.write(zagolovok(op, len))
        out.write(d, off, len)
    }

    /** Кадр клиента (с маской) — для проверок на столе. */
    fun kadrKlienta(op: Int, d: ByteArray, fin: Boolean = true, maska: ByteArray = byteArrayOf(1, 2, 3, 4)): ByteArray {
        val z = zagolovok(op, d.size).copyOf()
        if (!fin) z[0] = (z[0].toInt() and 0x7f).toByte()
        z[1] = (z[1].toInt() or 0x80).toByte()
        val m = ByteArray(d.size) { (d[it].toInt() xor maska[it and 3].toInt()).toByte() }
        return z + maska + m
    }
}
