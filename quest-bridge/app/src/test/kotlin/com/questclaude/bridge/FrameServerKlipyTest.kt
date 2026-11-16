package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI

/** /klipy… в FrameServer (2.7.0): открыть, закрыть, состояние; моста клипов нет — 503. */
class FrameServerKlipyTest {
    private val port = ServerSocket(0).use { it.localPort }

    private fun get(path: String): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 2000; c.readTimeout = 2000
        val code = c.responseCode
        return code to String((if (code < 400) c.inputStream else c.errorStream).readBytes(), Charsets.UTF_8)
    }

    @Test fun klipy() {
        var otkryta = false
        var est = true
        val k = object : KlipyStranica.Upr {
            override fun status() = "{\"otkryta\":$otkryta}"
            override fun otkryt(): String { otkryta = true; return "{\"ok\":true,\"otkryta\":true}" }
            override fun zakryt(): String { otkryta = false; return "{\"ok\":true,\"otkryta\":false}" }
        }
        val s = FrameServer(port, { null }, { false }, klipy = { if (est) k else null })
        s.start()
        try {
            assertEquals(200 to "{\"otkryta\":false}", get("/klipy"))
            assertTrue(get("/klipy/otkryt").second.contains("\"ok\":true"))
            assertTrue(otkryta)
            assertEquals(200 to "{\"otkryta\":true}", get("/klipy"))
            get("/klipy/zakryt")
            assertEquals(false, otkryta)
            est = false
            assertEquals(503, get("/klipy").first)
        } finally {
            s.stop()
        }
    }
}
