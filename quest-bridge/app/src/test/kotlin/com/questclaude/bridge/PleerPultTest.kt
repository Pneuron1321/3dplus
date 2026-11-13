package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Мини-плеер (2.5.7): состояние от плеера и очередь команд из шторки. */
class PleerPultTest {
    @Test fun sostoyanieIZhiv() {
        var t = 1_000L
        val p = PleerPult { t }
        assertFalse(p.zhiv())
        p.zapisat("{\"igraet\":true,\"trek\":\"Hope\"}")
        assertTrue(p.zhiv())
        t += 16_000
        assertFalse(p.zhiv())                                     // плеер давно молчит — шторка скажет «не отвечает»
    }

    @Test fun komandyPoOcheredi() {
        val p = PleerPult { 5L }
        assertTrue(p.komanda("sled"))
        assertTrue(p.komanda("vkl", "Hope \"remix\""))
        assertFalse(p.komanda("rm", "-rf"))                       // чужих команд нет
        assertEquals("{\"k\":\"sled\",\"a\":\"\",\"t\":5}", p.zhdat(0))
        assertEquals("{\"k\":\"vkl\",\"a\":\"Hope \\\"remix\\\"\",\"t\":5}", p.zhdat(0))
        assertEquals("{}", p.zhdat(10))
    }

    @Test fun gromkostTolkoPoslednyaya() {
        val p = PleerPult { 5L }
        p.komanda("gromkost", "20")
        p.komanda("sled")
        p.komanda("gromkost", "60")
        assertTrue(p.zhdat(0).contains("\"sled\""))
        assertTrue(p.zhdat(0).contains("\"60\""))
        assertEquals("{}", p.zhdat(0))
    }

    @Test fun ocheredKorotkaya() {
        val p = PleerPult { 5L }
        repeat(30) { p.komanda("vkl", "t$it") }
        assertTrue(p.zhdat(0).contains("t14"))                     // старое отброшено — 16 последних
    }
}
