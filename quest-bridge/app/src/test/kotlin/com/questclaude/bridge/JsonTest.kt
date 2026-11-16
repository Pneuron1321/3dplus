package com.questclaude.bridge

import com.questclaude.bridge.Json.bool
import com.questclaude.bridge.Json.int
import com.questclaude.bridge.Json.num
import com.questclaude.bridge.Json.obj
import com.questclaude.bridge.Json.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonTest {
    @Test fun soobshcheniyaStranicy() {
        val m = Json.obekt("""{"t":"vhod","k":"abc","imya":"Макс \"Max\" A","kamera":true,"mikrofon":false,
            "igra":0.35,"id":7,"n":null,"a":[1,{"x":[]}],"o":{"z":-1.5e2}}""")!!
        assertEquals("vhod", m.str("t"))
        assertEquals("Макс \"Max\" A", m.str("imya"))
        assertEquals(true, m.bool("kamera"))
        assertEquals(false, m.bool("mikrofon"))
        assertEquals(0.35, m.num("igra")!!, 1e-9)
        assertEquals(7, m.int("id"))
        assertNull(m["n"])
        assertEquals(-150.0, m.obj("o")!!.num("z")!!, 1e-9)
    }

    @Test fun bitoeINeObekt() {
        for (s in listOf("", "{", "{\"a\":}", "[1,2]", "{\"a\":1}x", "{\"a\":\"\u0001\"}", "{\"a\":tru}", "{'a':1}"))
            assertNull(s, Json.obekt(s))
        val glubokoe = "[".repeat(40) + "]".repeat(40)
        assertNull(Json.obekt("{\"a\":$glubokoe}"))
    }
}
