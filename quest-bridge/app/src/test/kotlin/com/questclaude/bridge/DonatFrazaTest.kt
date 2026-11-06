package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class DonatFrazaTest {
    @Test fun dengiIBity() {
        assertEquals(" donated $5!", DonatStroka.fraza("$5"))
        assertEquals(" cheered 500 bits!", DonatStroka.fraza("500 bits"))
    }

    @Test fun mestaVKadre() {
        assertEquals(Vizor.CHAT_VERH, Vizor.SCHET_VERH, 0f)
        assertEquals(Vizor.H.toFloat() - 2f, Vizor.NK_VERH + Vizor.NK_H, 0f)
        // строка доната — ниже карточки топа и выше плиток гостей не уходит за левый край
        assertEquals(Vizor.LV.b + Vizor.ZAZOR, Vizor.DN_VERH, 0f)
    }
}
