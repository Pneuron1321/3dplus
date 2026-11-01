package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchetTest {
    private var now = 1_000_000L
    private var fajl: String? = null
    private fun novyj() = Schet({ fajl }, { fajl = it }) { now }

    @Test fun skladyvaetPloshchadki() {
        val s = novyj()
        s.upr(mapOf("zr_tw" to "37545", "zr_kick" to "1%2C200", "pod_tw" to "61 665 570", "pod_yt" to "10"))
        assertEquals(mapOf("tw" to 37545L, "kick" to 1200L), s.zriteli())
        assertEquals(61_665_580L, s.podpischiki().values.sum())
        assertTrue(s.vidna)
        assertEquals("37,545", Schet.krasivo(37545))
    }

    @Test fun zriteliUstarevayut() {
        val s = novyj()
        s.upr(mapOf("zr_tw" to "5"))
        assertTrue(s.zrVidny)
        now += Schet.STARO_MS + 1
        assertFalse(s.zrVidny)
        assertFalse(s.vidna)
    }

    @Test fun minusUbiraetIMusorNeTrogaet() {
        val s = novyj()
        s.upr(mapOf("zr_kick" to "7", "pod_kick" to "3"))
        s.upr(mapOf("zr_kick" to "-", "pod_kick" to "abc"))
        assertTrue(s.zriteli().isEmpty())
        assertEquals(3L, s.podpischiki()["kick"])
    }

    @Test fun pomnitFolloverovNikIPokaz() {
        val s = novyj()
        s.upr(mapOf("pod_tw" to "42", "nik" to "3xsks_fu_gun%20%3Cb%3E", "zr" to "0", "zr_tw" to "9"))
        val s2 = novyj()
        assertEquals(42L, s2.podpischiki()["tw"])
        assertEquals("3xsks_fu_gunb", s2.nik)
        assertTrue(s2.nikViden)
        assertFalse(s2.pokazZr)
        assertTrue(s2.zriteli().isEmpty())               // зрители не помнятся — только живые
        s2.upr(mapOf("niki" to "0", "pod" to "0"))
        assertFalse(s2.nikViden)
        assertFalse(s2.vidna)
        assertEquals("net", s2.klyuch())
    }

    @Test fun klyuchMenyaetsyaTolkoSChislami() {
        val s = novyj()
        s.upr(mapOf("zr_tw" to "5"))
        val k = s.klyuch()
        s.upr(mapOf("zr_tw" to "5"))
        assertEquals(k, s.klyuch())
        s.upr(mapOf("zr_tw" to "6"))
        assertTrue(k != s.klyuch())
    }

    @Test fun ssylkaDonataPoOcheredi() {
        val s = novyj()
        s.upr(mapOf("nik" to "3xsks_fu_gun"))
        assertEquals("nik", s.nizSeychas(0))
        s.upr(mapOf("ssylka" to "https%3A%2F%2Fstreamelements.com%2F3xsks_fu_gun%2Ftip%2F"))
        assertEquals("streamelements.com/3xsks_fu_gun/tip", s.ssylka)
        assertEquals("nik", s.nizSeychas(Vizor.NK_NIK_MS - 1))
        assertEquals("donat", s.nizSeychas(Vizor.NK_NIK_MS))
        assertEquals("nik", s.nizSeychas(Vizor.NK_NIK_MS + Vizor.NK_DONAT_MS))
        assertEquals("streamelements.com/3xsks_fu_gun/tip", novyj().ssylka)      // помнит
        s.upr(mapOf("ssylka" to "javascript:alert(1)"))                          // мусор — убирает
        assertEquals("", s.ssylka)
        s.upr(mapOf("nik" to "", "ssylka" to "paypal.me/sasha"))
        assertEquals("donat", s.nizSeychas(0))
        s.upr(mapOf("niki" to "0"))
        assertEquals("", s.nizSeychas(0))
    }
}
