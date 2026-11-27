package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Почтовый ящик плашек (2.3.9): одна за раз, самая давняя из новых, только последнее, null — убрать. */
class YashchikTest {
    private fun zabrat(y: Yashchik<String>): Pair<String, String?>? {
        var r: Pair<String, String?>? = null
        y.zabrat { u, x -> r = u to x }
        return r
    }

    @Test fun poOdnoySamoeDavnee() {
        val y = Yashchik<String>()
        assertNull(y.polozhit("pv", "lico1"))
        assertNull(y.polozhit("ln", "roza1"))
        assertEquals(2, y.zhdut())
        assertEquals("pv" to "lico1", zabrat(y))           // давнее — первым
        assertEquals(1, y.zhdut())
        assertEquals("ln" to "roza1", zabrat(y))
        assertNull(zabrat(y))                               // нового нет — ничего не грузим
        assertFalse(y.zabrat { _, _ -> })
    }

    @Test fun tolkoPosledneeIBuferNazad() {
        val y = Yashchik<String>()
        y.polozhit("pc", "chat1")
        assertEquals("chat1", y.polozhit("pc", "chat2"))     // не успели забрать — прежняя назад писателю
        assertEquals("pc" to "chat2", zabrat(y))             // грузится только последнее
        assertNull(zabrat(y))
        assertEquals("chat2", y.polozhit("pc", "chat3"))     // забранная — тоже назад, когда пришла новая
    }

    @Test fun ubrat() {
        val y = Yashchik<String>()
        y.polozhit("nc", "titry")
        zabrat(y)
        assertEquals("titry", y.polozhit("nc", null))
        assertEquals("nc" to null, zabrat(y))                // null — убрать плашку
        assertTrue(y.vse().isEmpty())
    }

    @Test fun obnovlenieNeObgonyaetDrugih() {
        val y = Yashchik<String>()
        y.polozhit("pv", "lico1")
        y.polozhit("ln", "roza1")
        y.polozhit("pv", "lico2")                            // лицо обновилось — теперь оно новее розы
        assertEquals("ln" to "roza1", zabrat(y))
        assertEquals("pv" to "lico2", zabrat(y))
    }
}
