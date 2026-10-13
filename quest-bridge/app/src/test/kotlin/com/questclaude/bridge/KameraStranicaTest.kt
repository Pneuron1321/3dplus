package com.questclaude.bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI

/** Страница «подключить камеру»: ссылки Moblin и Larix Grove с адресом и ключом, открыта только своё время. */
class KameraStranicaTest {
    private val lo: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
    private val port = ServerSocket(0).use { it.localPort }
    private val rtmp = "rtmp://192.168.1.50:1935/kamera/k7m2x9p4qa"
    private val sobytiya = mutableListOf<Boolean>()
    private val s = KameraStranica(port, { rtmp }, lo) { sobytiya += it }

    @After fun stop() = s.zakryt()

    private fun get(path: String = "/"): Pair<Int, String> {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 2000; c.readTimeout = 2000
        val code = c.responseCode
        return code to String((if (code < 400) c.inputStream else c.errorStream).readBytes(), Charsets.UTF_8)
    }

    private fun zakryta() = try { get(); false } catch (e: ConnectException) { true }

    @Test fun larixGrove() {
        assertEquals("larix://set/v1?conn[][url]=rtmp%3A%2F%2F192.168.1.50%3A1935%2Fkamera%2Fk7m2x9p4qa" +
            "&conn[][name]=Quest&conn[][overwrite]=on&conn[][mode]=v" +
            "&enc[vid][format]=avc&enc[vid][res]=1280x720&enc[vid][fps]=30&enc[vid][bitrate]=2000&enc[vid][keyframe]=1",
            KameraStranica.larix(rtmp))
    }

    @Test fun moblinSsylka() {
        val m = KameraStranica.moblin(rtmp)
        assertTrue(m, m.startsWith("moblin://?%7B%22streams%22"))
        assertFalse(m, m.contains("\"") || m.contains("{") || m.contains(" "))
        val json = java.net.URLDecoder.decode(m.removePrefix("moblin://?"), "UTF-8")
        assertEquals("{\"streams\":[{\"name\":\"Quest\",\"url\":\"$rtmp\",\"selected\":true," +
            "\"video\":{\"codec\":\"H.264/AVC\",\"resolution\":\"1280x720\",\"fps\":30,\"bitrate\":2000000," +
            "\"bFrames\":false,\"maxKeyFrameInterval\":1}}]}", json)
    }

    @Test fun otkrytaIZakryta() {
        assertTrue(zakryta())
        s.otkryt()
        assertEquals(listOf(true), sobytiya)
        assertTrue(s.ostalos in 118..120)
        val (code, html) = get()
        assertEquals(200, code)
        assertTrue(html.contains("href=\"larix://set/v1?conn[][url]=rtmp%3A%2F%2F192.168.1.50%3A1935%2Fkamera%2Fk7m2x9p4qa&amp;conn[][name]=Quest"))
        assertTrue(html.contains(">$rtmp</textarea>"))
        assertTrue(html.contains("href=\"moblin://?%7B%22streams%22"))
        assertEquals(404, get("/drugoe").first)
        s.zakryt()
        assertEquals(listOf(true, false), sobytiya)
        assertTrue(zakryta())
    }

    @Test fun samaZakryvaetsya() {
        s.otkryt(1)
        assertEquals(200, get().first)
        val end = System.currentTimeMillis() + 3000
        while (!zakryta() && System.currentTimeMillis() < end) Thread.sleep(100)
        assertTrue(zakryta())
        assertFalse(s.otkryta)
        assertEquals(listOf(true, false), sobytiya)
    }

    @Test fun povtornoProdlevaet() {
        s.otkryt(1)
        s.otkryt(60)
        Thread.sleep(1500)
        assertEquals(200, get().first)
        assertEquals(listOf(true), sobytiya)
    }
}
