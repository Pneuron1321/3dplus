package com.questclaude.most3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Лестница режимов первого запуска и «вежливый» PROBE (разбор №2). */
class LestnicaTest {
    private fun r(f: Int, k: Int, vid: UvcVid, w: Int, h: Int, fps: Int) =
        UvcRezhim(f, k, vid, w, h, listOf(10_000_000 / fps), razmerKadra(vid, w, h))

    private val op = UvcOpisanie(0x100, 0, 1, 0x82, true, 2, 0xf, listOf(
        r(1, 1, UvcVid.NV12, 1920, 1080, 60), r(1, 2, UvcVid.NV12, 1920, 1080, 30),
        r(2, 1, UvcVid.YUY2, 1920, 1080, 60), r(3, 1, UvcVid.MJPEG, 1920, 1080, 60),
        r(1, 3, UvcVid.NV12, 1280, 720, 60), r(4, 1, UvcVid.P010, 1920, 1080, 30)), null)

    @Test fun poryadok() {
        val l = op.lestnica()
        assertEquals(listOf("NV12 1920×1080", "YUY2 1920×1080", "MJPEG 1920×1080", "NV12 1280×720", "P010 1920×1080"),
            l.map { "${it.vid} ${it.shirina}×${it.vysota}" })
        assertEquals(60.0, l[0].maxFps, 0.01)                    // NV12 1080 — шаг 60, а не 30
        assertEquals(2, op.lestnica(2).size)
        assertEquals(emptyList<UvcRezhim>(), op.copy(rezhimy = emptyList()).lestnica())
    }

    @Test fun vezhlivyjProbe() {
        val tek = ByteArray(26) { (it + 100).toByte() }          // то, что карта сама ответила на GET_CUR
        val n = UvcProbe.nalozhit(tek, op.rezhimy[2], 166_666, 26)
        assertEquals(1, n[0].toInt()); assertEquals(0, n[1].toInt())
        assertEquals(2, n[2].toInt()); assertEquals(1, n[3].toInt())
        assertEquals(166_666, UvcProbe.razobrat(n).interval)
        assertArrayEquals(tek.copyOfRange(8, 26), n.copyOfRange(8, 26))   // остальное — как у карты
        assertArrayEquals(UvcProbe.upakovat(op.rezhimy[2], 166_666, 26), UvcProbe.nalozhit(ByteArray(3), op.rezhimy[2], 166_666, 26))
    }

    @Test fun hex() {
        assertEquals("00 ff 10", Hex.str(byteArrayOf(0, -1, 16)))
        assertEquals("00 ff", Hex.str(byteArrayOf(0, -1, 16), 2))
    }
}
