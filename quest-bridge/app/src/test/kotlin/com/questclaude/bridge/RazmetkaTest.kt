package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/** 2.8.7: чат в правом столбце — между окном камеры и счётчиками, где бы ни было окно. */
class RazmetkaTest {
    private val stolb = 1640 to 1920

    @Test fun kameraVnizuChatNadNej() {
        // окно камеры внизу справа (низ 40, верх 300): чат — от 312 до верха 900
        assertEquals(312 to 900, Razmetka.prosvet(40, 900, stolb.first, stolb.second, listOf(intArrayOf(1500, 40, 400, 260)), 12))
    }

    @Test fun kameraVyshePodSchetchikamiChatPodNej() {
        // окно подняли под счётчики (650..880): больший просвет — снизу
        assertEquals(40 to 638, Razmetka.prosvet(40, 900, stolb.first, stolb.second, listOf(intArrayOf(1600, 650, 300, 230)), 12))
    }

    @Test fun kameraNeVStolbceNeMeshaet() {
        assertEquals(40 to 900, Razmetka.prosvet(40, 900, stolb.first, stolb.second, listOf(intArrayOf(100, 40, 400, 260)), 12))
    }

    @Test fun kameraIPlusVStolbce() {
        // Плюс внизу (40..416), камера над ним (430..700): просвет — над камерой
        assertEquals(712 to 900, Razmetka.prosvet(40, 900, stolb.first, stolb.second,
            listOf(intArrayOf(1660, 40, 240, 376), intArrayOf(1500, 430, 420, 270)), 12))
    }

    @Test fun poSeredine() {
        assertEquals(200, Razmetka.poSeredine(600, 200))
        assertEquals(0, Razmetka.poSeredine(600, 700))       // не влезает — от низа, лишнее срежется сверху
        assertEquals(0, Razmetka.poSeredine(600, 0))
    }
}
