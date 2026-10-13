package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/** Роза ветров в 3D: влезает в картинку, дальние грани первыми, крутится сама как попало (не по компасу), видна с обеих сторон. */
class Roza3DTest {
    private val c = Roza3D.KARTINKA / 2f

    private fun kadr(t: Double, d: Roza3D.Dvizhenie) = Roza3D.kadr(t, d, c, c, Roza3D.MASSHTAB)

    @Test fun vlezaetVKartinku() {
        for (seed in 1L..4L) {
            val d = Roza3D.Dvizhenie(Random(seed))
            var t = 0.0
            while (t < 120.0) {
                val k = kadr(t, d)
                for (g in k.grani) for (i in g.x.indices) {
                    assertTrue("t=$t x=${g.x[i]}", g.x[i] in 1f..Roza3D.KARTINKA - 1f)
                    assertTrue("t=$t y=${g.y[i]}", g.y[i] in 1f..Roza3D.KARTINKA - 1f)
                }
                k.sever.let { (x, y, kegl) ->                          // буква N — тоже внутри
                    val pol = kegl * 0.5f
                    assertTrue("N x=$x", x - pol >= 0f && x + pol <= Roza3D.KARTINKA)
                    assertTrue("N y=$y", y - pol >= 0f && y + pol <= Roza3D.KARTINKA)
                }
                t += 0.1
            }
        }
    }

    @Test fun dalnieGraniPervymi() {
        val d = Roza3D.Dvizhenie(Random(7))
        for (t in listOf(0.0, 3.3, 7.9, 12.1, 41.0)) {
            val z = kadr(t, d).grani.map { it.z }
            assertEquals(z.sorted(), z)
        }
    }

    @Test fun obratnyeNeRisuyutsya() {
        val vse = kadr(0.0, Roza3D.Dvizhenie(Random(3))).grani.size
        assertTrue("граней $vse", vse in 30..200)                    // примерно половина сетки
    }

    /** 11.10: «крутилась рандомом, не по северу»: у каждого эфира своё движение, сторона кручения меняется. */
    @Test fun kruchenieSluchaynoe() {
        val a = Roza3D.Dvizhenie(Random(1))
        val b = Roza3D.Dvizhenie(Random(2))
        assertNotEquals(a.vrashchenie(10.0), b.vrashchenie(10.0), 1e-3)
        for (d in listOf(a, b)) {
            var vpravo = false
            var vlevo = false
            var bystree = 0.0
            var t = 0.0
            while (t < 120.0) {
                val w = (d.vrashchenie(t + 0.05) - d.vrashchenie(t)) / 0.05
                if (w > 0.5) vpravo = true
                if (w < -0.5) vlevo = true
                bystree = maxOf(bystree, abs(w))
                t += 0.05
            }
            assertTrue("крутится то туда, то обратно", vpravo && vlevo)
            assertTrue("не мельтешит: $bystree рад/с", bystree < 7.0)
        }
    }

    @Test fun vidnaSObeihStoron() {
        for (seed in 1L..4L) {
            val d = Roza3D.Dvizhenie(Random(seed))
            var licom = false
            var spinoy = false
            var t = 0.0
            while (t < 120.0) {
                val z = Roza3D.povorot(t, d)[8]                        // куда смотрит ось розы: к зрителю или от
                if (z > 0.5) licom = true
                if (z < -0.5) spinoy = true
                t += 0.05
            }
            assertTrue("seed $seed", licom && spinoy)
        }
    }

    @Test fun cvetaPlyusa() {
        val d = Roza3D.Dvizhenie(Random(5))
        val cveta = (0 until 80).flatMap { kadr(it * 0.2, d).grani.map { g -> g.cvet } }.toSet()
        fun est(baza: Int) = cveta.any { blizko(it, baza) }
        assertTrue("жёлтые лучи", est(Roza3D.ZHELT))
        assertTrue("розовые лучи", est(Roza3D.ROZ))
        assertTrue("красный обод", est(Roza3D.KRASN))
    }

    /** Тот же оттенок при любом свете: цвет — база, умноженная на один множитель. */
    private fun blizko(c: Int, baza: Int): Boolean {
        val r = (c shr 16 and 255).toDouble(); val g = (c shr 8 and 255).toDouble(); val b = (c and 255).toDouble()
        val br = (baza shr 16 and 255).toDouble(); val bg = (baza shr 8 and 255).toDouble(); val bb = (baza and 255).toDouble()
        if (r >= 254 || g >= 254 || b >= 254) return false               // упёрся в белое — не сравнить
        val k = r / br
        return abs(g - bg * k) < 4 && abs(b - bb * k) < 4
    }
}
