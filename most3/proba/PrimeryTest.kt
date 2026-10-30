package com.questclaude.most3

// Картинки «до/после» шейдера. java.awt есть только на обычной JVM, в тестах Android его нет — поэтому файл здесь,
// а не в app/src/test. Запуск: scratchpad/acompile5 → gradle --offline -q test -Dprimery=1

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

// Примеры «до/после» в PNG. Запускается только с -Dprimery=1 (или PRIMERY=1); папка — -Dprimery.papka=...
class PrimeryTest {
    private fun vKartinku(rgb: FloatArray, w: Int, h: Int): BufferedImage {
        val im = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 3
            fun b(i: Int) = Math.round(rgb[o + i].coerceIn(0f, 1f) * 255f)
            im.setRGB(x, y, (b(0) shl 16) or (b(1) shl 8) or b(2))
        }
        return im
    }

    private fun varianty(stsena: (Int, Int) -> FloatArray, w: Int, h: Int): List<Pair<String, FloatArray>> {
        val k = stsena(w, h)
        return listOf(
            "original" to k,
            "monitor" to KartinkaCpu.obrabotatKadr(k, w, h, NABORY["monitor"]!!, 0f),
            "avtonoch" to Sceny.sAvtoNochyu(k, w, h, Nastrojki(avtoNoch = 1f)),
        )
    }

    @Test fun narisovat() {
        assumeTrue(System.getProperty("primery") == "1" || System.getenv("PRIMERY") == "1")
        val papka = File(System.getProperty("primery.papka")
            ?: "/tmp/claude-0/-home-user-3dplus/59c37c23-b121-5ad5-951f-36f554a1e480/scratchpad/most3-primery")
        papka.mkdirs()
        val sceny = listOf<Pair<String, (Int, Int) -> FloatArray>>("noch" to Sceny::noch, "den" to Sceny::den)
        for ((imya, f) in sceny) for ((v, rgb) in varianty(f, 640, 360))
            ImageIO.write(vKartinku(rgb, 640, 360), "png", File(papka, "${imya}_$v.png"))
        // сводная: 2 строки × 3 столбца, 1280 px
        val tw = 416; val th = 234; val zazor = 8; val podp = 22
        val sv = BufferedImage(3 * tw + 4 * zazor, 2 * (th + podp) + 3 * zazor, BufferedImage.TYPE_INT_RGB)
        val g = sv.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(40, 40, 40); g.fillRect(0, 0, sv.width, sv.height)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 15); g.color = Color.WHITE
        for ((r, s) in sceny.withIndex()) for ((c, p) in varianty(s.second, tw, th).withIndex()) {
            val x = zazor + c * (tw + zazor); val y = zazor + r * (th + podp + zazor)
            g.drawString("${s.first}: ${p.first}", x, y + 15)
            g.drawImage(vKartinku(p.second, tw, th), x, y + podp, null)
        }
        g.dispose()
        ImageIO.write(sv, "png", File(papka, "svodka.png"))
    }

    // Местная ночь и HDR: noch_noch_globalno / noch_noch_lokalno, hdr_bez / hdr_obrezka / hdr_tonmap / hdr_tonmap_hdr,
    // и сводка svodka2.png (3×3, 1280 px).
    private fun varianty2(w: Int, h: Int): List<List<Pair<String, FloatArray>>> {
        val noch = Sceny.noch(w, h); val den = Sceny.den(w, h)
        val nN = NABORY["noch"]!!
        val nit = Sceny.hdrNity(w - w % 2, h - h % 2)
        val p010 = Sceny.vP010(nit, w - w % 2, h - h % 2)
        val tm = KartinkaCpu.p010VKadr(p010, NABORY["hdr"]!!, hdr = true)
        return listOf(
            listOf("noch: original" to noch, "noch: noch globalno" to Sceny.sAvtoNochyu(noch, w, h, nN.copy(lokalno = 0f)),
                "noch: noch + lokalno ${nN.lokalno}" to Sceny.sAvtoNochyu(noch, w, h, nN)),
            listOf("den: original" to den, "den: monitor" to Sceny.sAvtoNochyu(den, w, h, NABORY["monitor"]!!),
                "den: noch + lokalno" to Sceny.sAvtoNochyu(den, w, h, nN)),
            listOf("hdr: obrezka (klip)" to Sceny.hdrObrezka(nit), "hdr: P010 -> ton-mapping" to tm,
                "hdr: ton-mapping + nabor hdr" to Sceny.sAvtoNochyu(tm, w, h, NABORY["hdr"]!!)),
        )
    }

    @Test fun narisovat2() {
        assumeTrue(System.getProperty("primery") == "1" || System.getenv("PRIMERY") == "1")
        val papka = File(System.getProperty("primery.papka")
            ?: "/tmp/claude-0/-home-user-3dplus/59c37c23-b121-5ad5-951f-36f554a1e480/scratchpad/most3-primery")
        papka.mkdirs()
        val w = 640; val h = 360
        val v = varianty2(w, h)
        ImageIO.write(vKartinku(v[0][1].second, w, h), "png", File(papka, "noch_noch_globalno.png"))
        ImageIO.write(vKartinku(v[0][2].second, w, h), "png", File(papka, "noch_noch_lokalno.png"))
        ImageIO.write(vKartinku(v[2][0].second, w, h), "png", File(papka, "hdr_obrezka.png"))
        ImageIO.write(vKartinku(v[2][1].second, w, h), "png", File(papka, "hdr_tonmap.png"))
        ImageIO.write(vKartinku(v[2][2].second, w, h), "png", File(papka, "hdr_tonmap_hdr.png"))
        // без поддержки HDR: PQ-сигнал показан как SDR BT.709 — блёкло
        val bez = KartinkaCpu.p010VKadr(Sceny.vP010(Sceny.hdrNity(w, h), w, h), Nastrojki(), hdr = false)
        ImageIO.write(vKartinku(bez, w, h), "png", File(papka, "hdr_bez.png"))
        val tw = 416; val th = 234; val zazor = 8; val podp = 22
        val sv = BufferedImage(3 * tw + 4 * zazor, 3 * (th + podp) + 4 * zazor, BufferedImage.TYPE_INT_RGB)
        val g = sv.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(40, 40, 40); g.fillRect(0, 0, sv.width, sv.height)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 15); g.color = Color.WHITE
        for ((r, ryad) in varianty2(tw, th).withIndex()) for ((c, p) in ryad.withIndex()) {
            val x = zazor + c * (tw + zazor); val y = zazor + r * (th + podp + zazor)
            g.drawString(p.first, x, y + 15)
            g.drawImage(vKartinku(p.second, tw, th), x, y + podp, null)
        }
        g.dispose()
        ImageIO.write(sv, "png", File(papka, "svodka2.png"))
    }
}
