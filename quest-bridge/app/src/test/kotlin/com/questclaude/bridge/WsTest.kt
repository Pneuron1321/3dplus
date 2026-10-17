package com.questclaude.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** WebSocket: рукопожатие по RFC 6455, кадры клиента с маской, склейка, управляющие, пределы. */
class WsTest {
    @Test fun klyuchIzRfc() {
        // пример из RFC 6455, раздел 1.3
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", Ws.otvetKlyuch("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test fun tekstDvoichnoeIUpravlenie() {
        val d = ByteArray(70000) { (it * 7).toByte() }
        val potok = Ws.kadrKlienta(Ws.TEKST, "привет".toByteArray()) + Ws.kadrKlienta(Ws.DVOICHNYY, d) +
            Ws.kadrKlienta(Ws.PING, byteArrayOf(9)) + Ws.kadrKlienta(Ws.DVOICHNYY, ByteArray(200) { 1 })
        val ch = Ws.Chitatel(ByteArrayInputStream(potok), 1 shl 20)
        assertEquals("привет", ch.sleduyushee()!!.tekst)
        val b = ch.sleduyushee()!!
        assertEquals(Ws.DVOICHNYY, b.op)
        assertArrayEquals(d, b.dannye)
        assertEquals(Ws.PING, ch.sleduyushee()!!.op)
        assertEquals(200, ch.sleduyushee()!!.dannye.size)
        assertNull(ch.sleduyushee())
    }

    @Test fun prodolzheniyaSkleivayutsya() {
        val potok = Ws.kadrKlienta(Ws.TEKST, "раз ".toByteArray(), fin = false) +
            Ws.kadrKlienta(Ws.PONG, ByteArray(0)) +                        // управляющий посреди — можно
            Ws.kadrKlienta(0, "два ".toByteArray(), fin = false) + Ws.kadrKlienta(0, "три".toByteArray())
        val ch = Ws.Chitatel(ByteArrayInputStream(potok), 1000)
        assertEquals(Ws.PONG, ch.sleduyushee()!!.op)
        assertEquals("раз два три", ch.sleduyushee()!!.tekst)
    }

    @Test fun bezMaskiIBolshoeNelzya() {
        val bezMaski = Ws.zagolovok(Ws.TEKST, 2) + "hi".toByteArray()
        try { Ws.Chitatel(ByteArrayInputStream(bezMaski), 100).sleduyushee(); fail() } catch (e: Ws.Narushenie) { }
        try { Ws.Chitatel(ByteArrayInputStream(Ws.kadrKlienta(Ws.DVOICHNYY, ByteArray(101))), 100).sleduyushee(); fail() }
        catch (e: Ws.Narushenie) { }
        val kuski = Ws.kadrKlienta(Ws.DVOICHNYY, ByteArray(60), fin = false) + Ws.kadrKlienta(0, ByteArray(60))
        try { Ws.Chitatel(ByteArrayInputStream(kuski), 100).sleduyushee(); fail() } catch (e: Ws.Narushenie) { }
        val upravlenieKuskami = Ws.kadrKlienta(Ws.PING, ByteArray(1), fin = false)
        try { Ws.Chitatel(ByteArrayInputStream(upravlenieKuskami), 100).sleduyushee(); fail() } catch (e: Ws.Narushenie) { }
    }

    @Test fun zapisDlinyTriVida() {
        for (n in listOf(5, 300, 70000)) {
            val o = ByteArrayOutputStream()
            Ws.zapisat(o, Ws.DVOICHNYY, ByteArray(n))
            val a = o.toByteArray()
            assertEquals(0x82, a[0].toInt() and 0xff)
            val zag = when { n < 126 -> 2; n < 65536 -> 4; else -> 10 }
            assertEquals(zag + n, a.size)
            assertTrue(a[1].toInt() and 0x80 == 0)                          // сервер не маскирует
        }
    }

    @Test fun tishinaPosredineKadraNeRvyot() {
        // поток, который отдаёт по байту и между ними «таймаут» — кадр всё равно собирается
        val d = Ws.kadrKlienta(Ws.TEKST, "medlenno".toByteArray())
        var i = 0
        var tik = false
        val inp = object : java.io.InputStream() {
            override fun read(): Int {
                if (i > 0 && i < d.size) { tik = !tik; if (tik) throw java.net.SocketTimeoutException() }
                return if (i < d.size) d[i++].toInt() and 0xff else -1
            }
        }
        assertEquals("medlenno", Ws.Chitatel(inp, 100).sleduyushee()!!.tekst)
    }
}
