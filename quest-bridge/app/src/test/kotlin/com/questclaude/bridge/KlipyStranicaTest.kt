package com.questclaude.bridge

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.TimeZone

/** Страница клипов: срок 15 минут, неверный ключ — 404, Range, имя для «Скачать», путей из запроса нет. */
class KlipyStranicaTest {
    private val lo: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
    private val port = ServerSocket(0).use { it.localPort }
    private var t = 1_760_000_000_000L
    private val dannye = ByteArray(1000) { (it * 7).toByte() }
    private var otkryto = 0
    private var zakryto = 0

    private val galereya = object : KlipyStranica.Galereya {
        var vse = true
        val klipy = mutableListOf(
            KlipyStranica.Klip(11, "plus-klip-20261022-201500.mp4", t - 3_600_000, 150_000, 1000),
            KlipyStranica.Klip(12, "most3-igra-20261022-203000.mp4", t - 1_800_000, 95_500, 1000),
            KlipyStranica.Klip(13, "../../etc/\"passwd", t - 7_200_000, 0, 1000),
        )
        override fun spisok() = klipy.toList()
        override fun otkryt(k: KlipyStranica.Klip): KlipyStranica.Fayl {
            otkryto++
            return object : KlipyStranica.Fayl {
                override val dlina = dannye.size.toLong()
                override fun s(ot: Long): InputStream = ByteArrayInputStream(dannye, ot.toInt(), dannye.size - ot.toInt())
                override fun close() { zakryto++ }
            }
        }
        override fun prevyu(k: KlipyStranica.Klip) = byteArrayOf(-1, -40, -1, k.id.toByte())
        override val vidnoVse get() = vse
    }

    private var kl = 0
    private val s = KlipyStranica(galereya, { "192.168.1.50" }, port, lo, { t }, { "kl" + (++kl) + "abcdefg" },
        TimeZone.getTimeZone("Europe/Moscow"))

    @After fun stop() = s.zakrytVse()

    private fun tekst(o: KlipyStranica.Otvet) = String(o.telo, Charsets.UTF_8)
    private fun zag(o: KlipyStranica.Otvet, imya: String) = o.zagolovki.firstOrNull { it.startsWith("$imya:") }?.substringAfter(": ")

    @Test fun srok15Minut() {
        assertEquals(404, s.obrabotat("GET", "/k/kl1abcdefg/").kod)          // ещё не открыта
        s.otkrytNa(soket = false)
        assertEquals(900, s.ostalos)
        assertEquals("http://192.168.1.50:$port/k/kl1abcdefg/", s.adres())
        assertEquals(200, s.obrabotat("GET", "/k/kl1abcdefg/").kod)
        t += 899_000
        assertEquals(200, s.obrabotat("GET", "/k/kl1abcdefg/").kod)
        assertEquals(200, s.obrabotat("GET", "/k/kl1abcdefg/v/11.mp4").kod)
        t += 1_000
        assertFalse(s.otkryta)
        assertEquals(404, s.obrabotat("GET", "/k/kl1abcdefg/").kod)
        assertEquals(404, s.obrabotat("GET", "/k/kl1abcdefg/d/11.mp4").kod)
        assertNull(s.adres())
        // новое открытие — новый ключ, старый мёртв
        s.otkrytNa(soket = false)
        assertEquals(404, s.obrabotat("GET", "/k/kl1abcdefg/").kod)
        assertEquals(200, s.obrabotat("GET", "/k/kl2abcdefg/").kod)
    }

    @Test fun prodlenieTotZheKlyuch() {
        s.otkrytNa(soket = false)
        t += 600_000
        s.otkrytNa(soket = false)
        assertEquals(900, s.ostalos)
        assertEquals(200, s.obrabotat("GET", "/k/kl1abcdefg/").kod)
    }

    @Test fun neverniyKlyuch404() {
        s.otkrytNa(soket = false)
        for (p in listOf("/", "/index.html", "/k/", "/k//", "/k/kl1abcdef/", "/k/kl1abcdefgh/", "/k/KL1ABCDEFG/",
            "/klipy", "/k/x/v/11.mp4", "/favicon.ico")) assertEquals(p, 404, s.obrabotat("GET", p).kod)
        assertEquals(302, s.obrabotat("GET", "/k/kl1abcdefg").kod)
        assertEquals("/k/kl1abcdefg/", zag(s.obrabotat("GET", "/k/kl1abcdefg"), "Location"))
        assertEquals(405, s.obrabotat("POST", "/k/kl1abcdefg/").kod)
    }

    @Test fun perebor() {
        s.otkrytNa(soket = false)
        repeat(50) { s.obrabotat("GET", "/k/kl0abcdefg/p/11.jpg") }   // старая вкладка Safari — один и тот же ключ
        Thread.sleep(100)
        assertTrue(s.otkryta)
        repeat(KlipyStranica.MAKS_CHUZHIH) { s.obrabotat("GET", "/k/zz${it}zzzzzz/") }
        val konec = System.currentTimeMillis() + 2000
        while (s.otkryta && System.currentTimeMillis() < konec) Thread.sleep(20)
        assertFalse(s.otkryta)
        assertTrue(s.zakrytaPodbor)
        assertEquals(404, s.obrabotat("GET", "/k/kl1abcdefg/").kod)
    }

    @Test fun netObhodaPuti() {
        s.otkrytNa(soket = false)
        for (p in listOf("/k/kl1abcdefg/../../etc/passwd", "/k/kl1abcdefg/v/../11.mp4", "/k/kl1abcdefg/v/%2e%2e%2f11.mp4",
            "/k/kl1abcdefg/v/11.mp4/../", "/k/kl1abcdefg/v/99.mp4", "/k/kl1abcdefg/d/-1.mp4", "/k/kl1abcdefg/v/11",
            "/k/kl1abcdefg/x/11.mp4", "/k/kl1abcdefg/v//sdcard/Movies/Plus/a.mp4", "/k/kl1abcdefg/p/11.mp4x",
            "/k/kl1abcdefg/v/123456789012345678901.mp4", "/k/kl1abcdefg/v/11.mp4\u0000.jpg")) {
            assertEquals(p, 404, s.obrabotat("GET", p).kod)
        }
        assertEquals(0, otkryto)                                     // ни один файл не открывался
        assertEquals(200, s.obrabotat("GET", "/k/kl1abcdefg/v/13.mp4").kod)   // а по номеру из списка — да
        // клип пропал из галереи — по его номеру больше не отдаём (кэш списка — 3 с)
        galereya.klipy.removeAt(0)
        t += 4_000
        assertEquals(404, s.obrabotat("GET", "/k/kl1abcdefg/v/11.mp4").kod)
    }

    @Test fun range() {
        s.otkrytNa(soket = false)
        val p = "/k/kl1abcdefg/v/11.mp4"
        val ves = s.obrabotat("GET", p)
        assertEquals(200, ves.kod); assertEquals(1000L, ves.dlina); assertEquals("bytes", zag(ves, "Accept-Ranges"))
        assertNull(zag(ves, "Content-Disposition"))                // смотреть — не скачивать
        val o = s.obrabotat("GET", p, "bytes=0-1")                   // Safari сначала спрашивает 2 байта
        assertEquals(206, o.kod); assertEquals(0L, o.ot); assertEquals(2L, o.dlina)
        assertEquals("bytes 0-1/1000", zag(o, "Content-Range"))
        s.obrabotat("GET", p, "bytes=500-").let { assertEquals(206, it.kod); assertEquals(500L, it.ot); assertEquals(500L, it.dlina)
            assertEquals("bytes 500-999/1000", zag(it, "Content-Range")) }
        s.obrabotat("GET", p, "bytes=-100").let { assertEquals(900L, it.ot); assertEquals(100L, it.dlina) }
        s.obrabotat("GET", p, "bytes=990-5000").let { assertEquals("bytes 990-999/1000", zag(it, "Content-Range")) }
        s.obrabotat("GET", p, "bytes=1000-").let { assertEquals(416, it.kod); assertEquals("bytes */1000", zag(it, "Content-Range")) }
        assertEquals(200, s.obrabotat("GET", p, "items=1-2").kod)    // непонятный — весь файл
        assertEquals(200, s.obrabotat("GET", p, "bytes=9-3").kod)
        assertEquals(206, s.obrabotat("GET", p, "bytes=0-9, 20-29").kod)   // несколько — первый кусок
        assertEquals(otkryto, zakryto + 8)                            // 416 закрыл файл сам, остальные закроет отправка
    }

    @Test fun diapazon() {
        assertNull(KlipyStranica.diapazon(null, 10))
        assertEquals(0L..9L, KlipyStranica.diapazon("bytes=0-", 10))
        assertEquals(3L..4L, KlipyStranica.diapazon("bytes=3-4", 10))
        assertEquals(0L..9L, KlipyStranica.diapazon("bytes=-50", 10))
        assertTrue(KlipyStranica.diapazon("bytes=-0", 10)!!.isEmpty())
        assertTrue(KlipyStranica.diapazon("bytes=10-12", 10)!!.isEmpty())
        assertTrue(KlipyStranica.diapazon("bytes=99999999999999999999-", 10)!!.isEmpty())
        assertNull(KlipyStranica.diapazon("bytes=-", 10))
    }

    @Test fun skachatImya() {
        s.otkrytNa(soket = false)
        val d = s.obrabotat("GET", "/k/kl1abcdefg/d/12.mp4")
        assertEquals(200, d.kod)
        assertEquals("attachment; filename=\"most3-igra-20261022-203000.mp4\"", zag(d, "Content-Disposition"))
        assertEquals("video/mp4", d.tip)
        val c = zag(s.obrabotat("GET", "/k/kl1abcdefg/d/13.mp4"), "Content-Disposition")!!
        assertFalse(c, c.substringAfter("filename=\"").dropLast(1).let { it.contains('/') || it.contains('"') || it.startsWith(".") })
        assertTrue(c, c.endsWith(".mp4\""))
        assertEquals("attachment; filename=\"klip.mp4\"", KlipyStranica.vlozhenie("Клип игры"))
        assertEquals("attachment; filename=\"a_b.mp4\"", KlipyStranica.vlozhenie("a\r\nb".replace("\r\n", "/")))
        assertFalse(KlipyStranica.vlozhenie("x\r\nSet-Cookie: a=b.mp4").contains('\n'))
    }

    @Test fun stranica() {
        s.otkrytNa(soket = false)
        val h = tekst(s.obrabotat("GET", "/k/kl1abcdefg/"))
        // новые сверху: игра (20:30) — камера (20:15) — третий
        val i12 = h.indexOf("p/12.jpg"); val i11 = h.indexOf("p/11.jpg"); val i13 = h.indexOf("p/13.jpg")
        assertTrue(i12 in 0 until i11 && i11 < i13)
        assertTrue(h.contains("<b class=\"igra\">Игра</b>"))
        assertTrue(h.contains("<b class=\"kam\">Камера</b>"))
        assertTrue(h.contains("2:30 · 1 КБ"))                         // длительность и размер
        assertTrue(h.contains("1:36"))
        assertTrue(h.contains("href=\"d/12.mp4\" download>Скачать</a>"))
        assertTrue(h.contains("v.src='v/'+id+'.mp4'"))
        assertTrue(h.contains("#0E1014") && h.contains("#F2C230"))
        assertFalse(h.contains("kl1abcdefg"))                         // относительные ссылки — ключа в теле нет
        assertFalse(h.contains("etc/"))
        assertFalse(h.contains("Клипы игры (Мост 3) не видны"))
        galereya.vse = false
        assertTrue(tekst(s.obrabotat("GET", "/k/kl1abcdefg/")).contains("Клипы игры (Мост 3) не видны"))
        val p = s.obrabotat("GET", "/k/kl1abcdefg/p/12.jpg")
        assertEquals("image/jpeg", p.tip); assertArrayEquals(byteArrayOf(-1, -40, -1, 12), p.telo)
    }

    @Test fun statusBezKlyucha() {
        assertTrue(s.status().contains("\"otkryta\":false"))
        val o = s.otkryt()
        assertTrue(o, o.contains("\"ok\":true") && o.contains("\"otkryta\":true") && o.contains("\"sek\":900") &&
            o.contains("\"klipov\":3") && o.contains("\"ip\":\"192.168.1.50\"") && o.contains("\"port\":$port"))
        assertFalse(o.contains("kl1abcdefg"))
        assertFalse(s.status().contains("kl1abcdefg"))
        assertEquals("http://192.168.1.50:$port/k/••••••••••/", s.adresSkryt())
        assertTrue(s.zakryt().contains("\"otkryta\":false"))
        assertNull(s.adres())
    }

    private fun get(path: String, range: String? = null): Triple<Int, Map<String, String>, ByteArray> {
        val c = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 2000; c.readTimeout = 2000; c.instanceFollowRedirects = false
        if (range != null) c.setRequestProperty("Range", range)
        val code = c.responseCode
        val h = c.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
        return Triple(code, h, ((if (code < 400) c.inputStream else c.errorStream)?.readBytes() ?: ByteArray(0)))
    }

    @Test fun poSeti() {
        val zakryta = { try { get("/k/x/"); false } catch (e: ConnectException) { true } }
        assertTrue(zakryta())
        s.otkrytNa()
        val (kod, h, telo) = get("/k/kl1abcdefg/")
        assertEquals(200, kod)
        assertEquals("no-referrer", h["referrer-policy"])
        assertTrue(String(telo, Charsets.UTF_8).contains("Клипы"))
        assertEquals(404, get("/k/neto/").first)
        val r = get("/k/kl1abcdefg/d/11.mp4", "bytes=100-199")
        assertEquals(206, r.first)
        assertEquals("bytes 100-199/1000", r.second["content-range"])
        assertEquals("100", r.second["content-length"])
        assertTrue(r.second["content-disposition"]!!.startsWith("attachment; filename=\"plus-klip-"))
        assertArrayEquals(dannye.copyOfRange(100, 200), r.third)
        assertArrayEquals(dannye, get("/k/kl1abcdefg/v/11.mp4").third)
        s.zakrytVse()
        assertTrue(zakryta())
    }

    @Test fun samaZakryvaetsyaPoSroku() {
        s.otkrytNa()
        assertEquals(200, get("/k/kl1abcdefg/").first)
        t += 900_000
        val konec = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < konec) { try { get("/k/x/") } catch (e: ConnectException) { break }; Thread.sleep(100) }
        assertFalse(s.otkryta)
        assertTrue(try { get("/k/kl1abcdefg/"); false } catch (e: ConnectException) { true })
    }

    @Test fun formaty() {
        assertEquals("2:30", KlipyStranica.dlitelnost(150_000))
        assertEquals("0:05", KlipyStranica.dlitelnost(4_600))
        assertEquals("1:00:00", KlipyStranica.dlitelnost(3_600_000))
        assertEquals("88 МБ", KlipyStranica.razmer(88L shl 20))
        assertEquals("1,5 ГБ", KlipyStranica.razmer(3L shl 29))
        assertEquals("Игра", KlipyStranica.kto("most3-igra-20261022-203000.mp4").first)
        assertEquals("Камера", KlipyStranica.kto("plus-klip-20261022-203000.mp4").first)
        val k = KlipyStranica.sozdatKlyuch()
        assertTrue(k, k.matches(Regex("[a-km-np-z2-9]{10}")))
    }
}
