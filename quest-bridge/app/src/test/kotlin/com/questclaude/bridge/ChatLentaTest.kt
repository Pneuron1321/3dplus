package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatLentaTest {
    @Before fun chisto() = ChatLenta.ochistit()

    @Test fun pishetVsePloshchadkiINeGasnet() {
        ChatLenta.pachka(listOf(
            mapOf("kto" to "Lena", "tekst" to "привет%20всем", "pl" to "kick", "id" to "a1"),
            mapOf("kto" to "Rex", "tekst" to "плюс,%20кто%20победит?", "pl" to "yt"),
            mapOf("kto" to "Tw", "tekst" to "gg")), now = 1000)
        val v = ChatLenta.vse()
        assertEquals(listOf("kick", "yt", "tw"), v.map { it.pl })
        assertEquals("привет всем", v[0].tekst)
        assertTrue(v[1].vazhno)                                   // к Плюсу — выделено
        assertTrue(ChatLenta.stroka(v[0]).contains("Kick Lena: привет всем"))
        assertEquals(3, ChatLenta.vse().size)                     // через час — всё ещё здесь (окно не гаснет)
    }

    @Test fun modIBanUbirayut() {
        ChatLenta.upr(mapOf("kto" to "Bad", "login" to "bad", "tekst" to "spam", "id" to "x1"))
        ChatLenta.upr(mapOf("kto" to "Ok", "tekst" to "hi", "id" to "x2"))
        val v0 = ChatLenta.versiya
        ChatLenta.upr(mapOf("ubrat" to "x2"))
        ChatLenta.upr(mapOf("ubrat_kto" to "BAD"))
        assertTrue(ChatLenta.vse().isEmpty())
        assertTrue(ChatLenta.versiya > v0)
    }

    @Test fun hranitPoslednie() {
        for (i in 1..ChatLenta.HRANIT + 30) ChatLenta.upr(mapOf("kto" to "u$i", "tekst" to "m$i"))
        val v = ChatLenta.vse()
        assertEquals(ChatLenta.HRANIT, v.size)
        assertEquals("u31", v.first().kto)
    }

    @Test fun pustoeNeMenyaetVersiyu() {
        val v0 = ChatLenta.versiya
        ChatLenta.upr(mapOf("ubrat" to "net-takogo"))
        assertEquals(v0, ChatLenta.versiya)
    }
}
