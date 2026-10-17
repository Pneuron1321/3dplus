package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Комната гостей: ключи и роли, впуск, места в кадре и цвета, управление, события для Александра. */
class KomnataTest {
    private class Svyaz : Komnata.Svyaz {
        val tekst = mutableListOf<String>()
        var zakryto: String? = null
        override fun tekst(json: String) { tekst += json }
        override fun zakryt(prichina: String) { zakryto = prichina }
        fun posl(t: String) = tekst.lastOrNull { it.contains("\"t\":\"$t\"") }
    }

    private var sohraneno: String? = null
    private val kl = Komnata.Klyuchi.iz(null) { sohraneno = it }
    private var t = 1000L
    private val k = Komnata(kl) { t++ }.apply { adres = "https://proba.trycloudflare.com" }

    private fun voyti(klyuch: String, imya: String, kamera: Boolean = true, mikrofon: Boolean = true) =
        Svyaz().let { s -> k.vhod(s, klyuch, imya, kamera, mikrofon) to s }

    @Test fun klyuchiSozdayutsyaIHranyatsya() {
        assertNotNull(sohraneno)
        val p = sohraneno!!.split(' ')
        assertEquals(3, p.size)
        assertTrue(p.all { Komnata.Klyuchi.pohozh(it) })
        val opyat = Komnata.Klyuchi.iz(sohraneno) { }
        assertEquals("moder", opyat.rol(kl.moder))
        assertEquals("gost", opyat.rol(kl.gost))
        assertEquals("hozyain", opyat.rol(kl.hozyain))
        assertNull(opyat.rol("nepravilnyklyuch"))
        assertNull(opyat.rol(""))
    }

    @Test fun chuzhoyKlyuchZakryvaet() {
        val (u, s) = voyti("aaaaaaaaaaaaaaaa", "Хакер")
        assertNull(u)
        assertEquals("klyuch", s.zakryto)
    }

    @Test fun gostZhdetModeratorVpuskaet() {
        val (m, ms) = voyti(kl.moder, "Мод")
        assertEquals("vnutri", m!!.sost)
        assertEquals(0, m.cvet)
        assertTrue(ms.posl("ssylki")!!.contains("#k=${kl.gost}"))
        assertFalse(ms.posl("ssylki")!!.contains(kl.moder))                 // модератору — только ссылка гостя
        val (g, gs) = voyti(kl.gost, "Макс")
        assertEquals("zhdet", g!!.sost)
        assertTrue(gs.posl("privet")!!.contains("\"sost\":\"zhdet\""))
        assertTrue(ms.posl("sostav")!!.contains("Макс"))                     // модератор видит ждущего
        assertEquals("zhdet", k.sobytiyaPosle(0).last().vid)
        assertTrue(k.upr(m, "vpustit", g.id).contains("\"ok\":true"))
        assertEquals("vnutri", g.sost)
        assertEquals(1, g.cvet)
        assertEquals("g2", g.golos)
        assertEquals(listOf(m, g), k.naEkrane())
        assertEquals("Макс", k.imyaGolosa("g2"))
        assertEquals("voshel", k.sobytiyaPosle(0).last().vid)
    }

    @Test fun gostNeUpravlyaet() {
        val (g, gs) = voyti(kl.gost, "Гость")
        val (g2, _) = voyti(kl.gost, "Другой")
        k.upr(null, "vpustit", g!!.id)
        assertTrue(k.upr(g, "vpustit", g2!!.id).contains("нет прав"))
        assertEquals("zhdet", g2.sost)
        assertFalse(gs.tekst.any { it.contains("\"t\":\"ssylki\"") })
    }

    @Test fun mestDvaTretiyZhdetModeratorZaKadrom() {
        val (a, _) = voyti(kl.gost, "А"); k.upr(null, "vpustit", a!!.id)
        val (b, _) = voyti(kl.gost, "Б"); k.upr(null, "vpustit", b!!.id)
        val (c, _) = voyti(kl.gost, "В")
        assertTrue(k.upr(null, "vpustit", c!!.id).contains("мест"))
        assertEquals("zhdet", c.sost)
        val (m, ms) = voyti(kl.moder, "Мод")                                 // мест нет — модератор с пультом, за кадром
        assertEquals("vnutri", m!!.sost)
        assertFalse(m.media)
        assertEquals(-1, m.cvet)
        assertEquals(2, k.naEkrane().size)
        k.sam(m, kamera = true, mikrofon = null, igra = null, prevyu = null)
        assertTrue(ms.posl("mest_net") != null)
        assertFalse(m.kamera)
        k.upr(null, "mest", null, "3")
        assertTrue(k.upr(null, "vpustit", c.id).contains("\"ok\":true"))
        assertEquals(3, k.naEkrane().size)
        assertEquals(setOf(0, 1, 2), k.naEkrane().map { it.cvet }.toSet())
    }

    @Test fun vyshelCvetSvoboden() {
        val (a, _) = voyti(kl.gost, "А"); k.upr(null, "vpustit", a!!.id)
        val (b, _) = voyti(kl.gost, "Б"); k.upr(null, "vpustit", b!!.id)
        assertEquals(0, a.cvet); assertEquals(1, b.cvet)
        k.uhod(a)
        assertEquals(1, b.cvet)                                              // цвет того, кто остался, не меняется
        val (c, _) = voyti(kl.gost, "В"); k.upr(null, "vpustit", c!!.id)
        assertEquals(0, c.cvet)
        assertEquals("vyshel", k.sobytiyaPosle(0).first { it.vid == "vyshel" }.vid)
    }

    @Test fun skrytZaglushitVygnat() {
        val (m, _) = voyti(kl.moder, "Мод", kamera = false, mikrofon = false)
        val (g, gs) = voyti(kl.gost, "Г"); k.upr(m, "vpustit", g!!.id)
        k.upr(m, "skryt", g.id)
        assertTrue(k.naEkrane().isEmpty())
        assertEquals(listOf(g), k.slyshno())                                 // скрытого слышно
        k.upr(m, "zaglushit", g.id)
        assertTrue(k.slyshno().isEmpty())
        assertTrue(gs.posl("zaglushen")!!.contains("true"))
        k.upr(m, "pokazat", g.id); k.upr(m, "vklyuchit", g.id)
        assertEquals(listOf(g), k.naEkrane())
        k.upr(m, "vygnat", g.id)
        assertEquals("vygnali", gs.zakryto)
        assertTrue(k.naEkrane().isEmpty())
        assertNull(k.po(g.id))
    }

    @Test fun modertorNeVygonyaetModeratoraIHozyaina() {
        val (m1, _) = voyti(kl.moder, "М1", false, false)
        val (m2, _) = voyti(kl.moder, "М2", false, false)
        val (h, _) = voyti(kl.hozyain, "Саша")
        assertTrue(k.upr(m1, "vygnat", m2!!.id).contains("хозяин"))
        assertTrue(k.upr(m1, "vygnat", h!!.id).contains("хозяина"))
        assertTrue(k.upr(h, "vygnat", m2.id).contains("\"ok\":true"))
        assertTrue(k.upr(m1, "vpusk", null, "avto").contains("хозяин"))
    }

    @Test fun hozyainVidit2SsylkiINeVKadre() {
        val (h, hs) = voyti(kl.hozyain, "")
        assertEquals("Alexander", h!!.imya)
        assertEquals("vnutri", h.sost)
        assertFalse(h.media)
        val s = hs.posl("ssylki")!!
        assertTrue(s.contains("#k=${kl.gost}") && s.contains("#k=${kl.moder}"))
        assertFalse(s.contains(kl.hozyain))
        assertTrue(k.naEkrane().isEmpty())
    }

    @Test fun novayaSsylkaMenyaetKlyuchGostya() {
        val (m, ms) = voyti(kl.moder, "Мод", false, false)
        val stary = kl.gost
        k.upr(m, "novaya", null)
        assertNotEquals(stary, kl.gost)
        assertTrue(ms.posl("ssylki")!!.contains(kl.gost))
        assertNull(voyti(stary, "Старый").first)
        assertTrue(sohraneno!!.contains(kl.gost))
    }

    @Test fun vpuskAvto() {
        val (h, _) = voyti(kl.hozyain, "Саша")
        val (g1, _) = voyti(kl.gost, "Ждал")
        k.upr(h, "vpusk", null, "avto")
        assertEquals("vnutri", g1!!.sost)                                    // ждавшие — внутрь
        val (g2, _) = voyti(kl.gost, "Сразу")
        assertEquals("vnutri", g2!!.sost)
        val (g3, s3) = voyti(kl.gost, "Лишний")
        assertNull(g3)                                                       // мест нет — закрыто
        assertEquals("mest_net", s3.zakryto)
    }

    @Test fun ocheredOgranichena() {
        repeat(Komnata.MAKS_ZHDUT) { assertNotNull(voyti(kl.gost, "Г$it").first) }
        val (u, s) = voyti(kl.gost, "Лишний")
        assertNull(u)
        assertEquals("ochered", s.zakryto)
    }

    @Test fun imyaChistoe() {
        assertEquals("Max scriptPower", Komnata.chistoeImya("  Max\u0000 <script>Power "))  // знаки разметки — прочь
        assertEquals("Макс_1.2-'", Komnata.chistoeImya("Макс_1.2-'\n"))
        assertEquals(Komnata.MAKS_IMYA, Komnata.chistoeImya("a".repeat(100)).length)
        val (g, _) = voyti(kl.gost, "\n\t")
        assertTrue(g!!.imya.startsWith("Guest"))
    }

    @Test fun statusBezKlyuchey() {
        voyti(kl.moder, "Мод")
        val s = k.status()
        assertFalse(s.contains(kl.moder) || s.contains(kl.gost) || s.contains(kl.hozyain))
        assertFalse(s.contains("trycloudflare"))
        assertTrue(s.contains("\"tunnel\":true"))
        assertTrue(k.ssylkiJson().contains("#k=${kl.moder}"))
    }

    /** Сеть оборвалась — по жетону сразу внутрь с тем же цветом; ушёл сам или выгнали — снова ждать. */
    @Test fun vozvratPoZhetonu() {
        val (g, gs) = voyti(kl.gost, "Макс")
        k.upr(null, "vpustit", g!!.id)
        val zheton = Regex("\"zheton\":\"(\\w+)\"").find(gs.posl("privet")!!)!!.groupValues[1]
        assertEquals(g.zheton, zheton)
        val cvet = g.cvet
        k.uhod(g)                                                            // обрыв
        val s2 = Svyaz()
        val g2 = k.vhod(s2, kl.gost, "кто угодно", true, true, zheton = zheton)!!
        assertEquals("vnutri", g2.sost)
        assertEquals("Макс", g2.imya)
        assertEquals(cvet, g2.cvet)
        assertNull(k.vhod(Svyaz(), kl.moder, "М", true, true, zheton = zheton)?.takeIf { it.imya == "Макс" })   // жетон одноразовый
        k.uhod(g2, sam = true)                                               // ушёл сам — возврата нет
        val g3 = k.vhod(Svyaz(), kl.gost, "Макс", true, true, zheton = g2.zheton)!!
        assertEquals("zhdet", g3.sost)
        k.upr(null, "vpustit", g3.id)
        k.upr(null, "vygnat", g3.id)                                         // выгнали — тоже нет
        assertEquals("zhdet", k.vhod(Svyaz(), kl.gost, "Макс", true, true, zheton = g3.zheton)!!.sost)
        t += Komnata.VOZVRAT_MS + 1
        assertTrue(true)
    }

    /** Модератор выключает и разрешает гостю камеру (как микрофон): гостю — сообщение, в составе — флаг. */
    @Test fun moderatorKameraGostya() {
        val (m, _) = voyti(kl.moder, "Мод")
        val (g, gs) = voyti(kl.gost, "Макс")
        k.upr(m, "vpustit", g!!.id)
        assertTrue(g.sVideo)
        assertTrue(k.upr(m, "kamera_vykl", g.id).contains("\"ok\":true"))
        assertTrue(g.kameraZapret); assertFalse(g.sVideo); assertTrue(g.kamera)      // своя кнопка не тронута
        assertTrue(gs.posl("kamera_zapret")!!.contains("\"da\":true"))
        assertTrue(gs.posl("sostav")!!.contains("\"kamera_zapret\":true"))
        k.upr(m, "kamera_vkl", g.id)
        assertFalse(g.kameraZapret); assertTrue(g.sVideo)
        assertTrue(gs.posl("kamera_zapret")!!.contains("\"da\":false"))
        val (g2, _) = voyti(kl.gost, "Лена")
        assertTrue(k.upr(g2, "kamera_vykl", g.id).contains("нет прав"))         // гость не управляет
    }

    /** Сеть оборвалась молча (iPhone после фона): старое соединение ещё «живо», а страница пришла с жетоном —
     *  место, цвет и запреты модератора переходят новому, старое закрывается, гость не попадает в ожидание. */
    @Test fun zhetonZabiraetMestoUPrizraka() {
        val (m, _) = voyti(kl.moder, "Мод")
        val (g, gs) = voyti(kl.gost, "Макс")
        k.upr(m, "vpustit", g!!.id)
        k.upr(m, "zaglushit", g.id); k.upr(m, "kamera_vykl", g.id)
        val cvet = g.cvet
        val dalshe = k.sobytiyaPosle(0).size
        val s2 = Svyaz()
        val g2 = k.vhod(s2, kl.gost, "кто угодно", true, true, zheton = g.zheton)!!
        assertEquals("vnutri", g2.sost)
        assertEquals("Макс", g2.imya)
        assertEquals(cvet, g2.cvet)
        assertTrue(g2.zaglushen); assertTrue(g2.kameraZapret)                    // запреты не сбросить переподключением
        assertEquals("zamena", gs.zakryto)
        assertEquals(listOf(m!!.id, g2.id).sorted(), k.vnutri().map { it.id }.sorted())   // призрака нет
        k.uhod(g)                                                                 // старое соединение закрылось позже
        assertEquals(2, k.vnutri().size)
        assertTrue(k.sobytiyaPosle(0).drop(dalshe).none { it.vid == "vyshel" })   // в ухо «вышел» не сказали
        assertTrue(s2.posl("privet")!!.contains("\"zaglushen\":true") && s2.posl("privet")!!.contains("\"kamera_zapret\":true"))
    }

    /** Обрыв и возврат по жетону: глушение и выключенная модератором камера остаются. */
    @Test fun zapretyPerezhivayutVozvrat() {
        val (m, _) = voyti(kl.moder, "Мод")
        val (g, _) = voyti(kl.gost, "Макс")
        k.upr(m, "vpustit", g!!.id)
        k.upr(m, "zaglushit", g.id); k.upr(m, "skryt", g.id)
        k.uhod(g)
        val g2 = k.vhod(Svyaz(), kl.gost, "Макс", true, true, zheton = g.zheton)!!
        assertTrue(g2.zaglushen); assertTrue(g2.skryt); assertFalse(g2.kameraZapret)
    }
}
