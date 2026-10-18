package com.questclaude.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Место окна камеры в кадре эфира 1920×1080 (начало координат — внизу слева, как в GL). */
class OknoTest {
    @Test fun ugolSpravaVnizu() {
        // 25 % ширины = 480, камера 16:9 → 270; отступ снизу 3 % высоты = 32, сбоку 4 % ширины = 77
        assertArrayEquals(intArrayOf(1920 - 480 - 77, 32, 480, 270), Okno.mesto(1920, 1080, 16f / 9, Okno.Vid()))
    }

    @Test fun ugolSlevaVverhu() {
        assertArrayEquals(intArrayOf(77, 1080 - 270 - 32, 480, 270), Okno.mesto(1920, 1080, 16f / 9, Okno.Vid("ugol", "lv", 0.25f)))
    }

    @Test fun vertikalnyTelefonNeVysheKadra() {
        val r = Okno.mesto(1920, 1080, 9f / 16, Okno.Vid("ugol", "pn", 0.5f))
        assertEquals(1080 * 9 / 10 - 64, r[3])                    // высота ограничена
        assertEquals((r[3] * 9f / 16).toInt(), r[2])
    }

    @Test fun krupnoSPolyami() {
        assertArrayEquals(intArrayOf(0, 0, 1920, 1080), Okno.mesto(1920, 1080, 16f / 9, Okno.Vid("krupno")))
        val r = Okno.mesto(1920, 1080, 4f / 3, Okno.Vid("krupno"))
        assertEquals(1080, r[3]); assertEquals(1440, r[2]); assertEquals(240, r[0])
    }

    @Test fun vidIzZaprosaIStroki() {
        val v = Okno.Vid.iz(mapOf("v" to "krupno", "gde" to "zz", "r" to "0,9"), Okno.Vid("ugol", "lv", 0.2f))
        assertEquals("krupno", v.rezhim)
        assertEquals("lv", v.gde)                                  // чужое — прежнее
        assertEquals(0.5f, v.dolya)                                // не больше половины
        val s = Okno.Vid.izStroki(Okno.Vid("ugol", "pv", 0.3f).toString())
        assertEquals("ugol pv 0.3 0 1 4.0", s.toString())
        assertEquals("ugol pn 0.25 0 1 4.0", Okno.Vid.izStroki("мусор").toString())
        assertEquals("ugol pn 0.25 0 1 4.0", Okno.Vid.izStroki("ugol pn 0.25").toString())     // файл старой версии
        val p = Okno.Vid.iz(mapOf("pov" to "90", "kray" to "0"), Okno.Vid())
        assertEquals("ugol pn 0.25 90 0 4.0", p.toString())
        assertEquals(90, Okno.Vid.izStroki(p.toString()).povorot)
        assertEquals(90, Okno.Vid.iz(mapOf("pov" to "45"), p).povorot)                      // чужой угол — прежний
        assertEquals(false, Okno.Vid.iz(mapOf("kray" to "1"), p).vplotnuyu)
        assertEquals(2.5f, Okno.Vid.iz(mapOf("bok" to "2,5"), p).sboku)
        assertEquals(20f, Okno.Vid.iz(mapOf("bok" to "90"), p).sboku)                       // не дальше 20 %
        assertEquals(0f, Okno.Vid.iz(mapOf("bok" to "-3"), p).sboku)
        assertEquals(4f, Okno.Vid.iz(mapOf("bok" to "NaN"), p).sboku)                        // чужое — прежнее
        assertEquals(1.5f, Okno.Vid.izStroki(Okno.Vid.iz(mapOf("bok" to "1.5"), p).toString()).sboku)
    }

    /** Александр 11.10 (2.3.8): «слишком близко к правому углу — на пару сантиметров от правого края, а по
     *  низу вплотную». Его файл с 2.3.3 — «до угла» без отступа сбоку; после обновления — сбоку 4 %. */
    @Test fun ot2_3_3SnizuVplotnuyuSbokuOtstup() {
        val ar = Okno.proporcii(1920, 1080, 90)
        val v = Okno.Vid.izStroki("ugol pn 0.2875 90 0")
        assertArrayEquals(intArrayOf(1920 - 310 - 77, 0, 310, 552), Okno.mesto(1920, 1080, ar, v))
        assertArrayEquals(intArrayOf(1280 - 207 - 51, 0, 207, 368), Okno.mesto(1280, 720, ar, v))   // 720p — те же доли
        val ugol = Okno.Vid.iz(mapOf("kray" to "0", "bok" to "0"), v)                               // kamera vplotnuyu
        assertArrayEquals(intArrayOf(1920 - 310, 0, 310, 552), Okno.mesto(1920, 1080, ar, ugol))
        val sleva = Okno.Vid.iz(mapOf("gde" to "ln"), v)
        assertEquals(77, Okno.mesto(1920, 1080, ar, sleva)[0])
    }

    @Test fun povernutoStoyaVplotnuyuINa15Bolshe() {
        // Александр 11.10: айфон вертикально, поток 16:9 боком → повернуть на 90° по часовой,
        // до угла, на 15 % больше: окно 25 % стоя — 270×480, ×1,15 → 310×552, вплотную справа внизу
        val ar = Okno.proporcii(1920, 1080, 90)
        assertEquals(9f / 16, ar)
        assertArrayEquals(intArrayOf(1920 - 270, 0, 270, 480), Okno.mesto(1920, 1080, ar, Okno.Vid("ugol", "pn", 0.25f, 90, true, 0f)))
        assertArrayEquals(intArrayOf(1920 - 310, 0, 310, 552), Okno.mesto(1920, 1080, ar, Okno.Vid("ugol", "pn", 0.2875f, 90, true, 0f)))
        assertEquals(16f / 9, Okno.proporcii(1920, 1080, 180))
    }

    /** Точка окна (u, v) → точка картинки камеры по матрице поворота (по столбцам). */
    private fun tochka(m: FloatArray, u: Float, v: Float) = listOf(m[0] * u + m[4] * v + m[12], m[1] * u + m[5] * v + m[13])

    @Test fun povorotPoChasovoy() {
        // 90° по часовой: верх картинки (t = 1) — у правого края окна (u = 1)
        val m = Okno.povorot(90)
        assertEquals(listOf(0f, 1f), tochka(m, 1f, 1f))                 // правый верх окна ← левый верх картинки
        assertEquals(listOf(1f, 1f), tochka(m, 1f, 0f))                 // правый низ окна ← правый верх картинки
        assertEquals(listOf(0f, 0f), tochka(m, 0f, 1f))                 // левый верх окна ← левый низ картинки
        assertEquals(listOf(1f, 1f), tochka(Okno.povorot(180), 0f, 0f)) // вверх ногами
        assertEquals(listOf(1f, 0f), tochka(Okno.povorot(270), 1f, 1f)) // против часовой: верх — у левого края
        assertEquals(listOf(0.25f, 0.75f), tochka(Okno.povorot(0), 0.25f, 0.75f))
    }
}
