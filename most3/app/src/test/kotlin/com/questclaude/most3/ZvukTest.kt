package com.questclaude.most3

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Раскладка usbdevfs_urb для arm64 (offsetof на C, 22.10) и пик PCM. */
class ZvukTest {
    @Test fun urbRaskladka() {
        val b = ByteBuffer.allocate(Urb.RAZMER + Urb.ISO * 8).order(ByteOrder.LITTLE_ENDIAN)
        b.put(5, 77)                                                   // мусор должен стереться
        Urb.zapolnit(b, 0x83, 0x1122334455667788L, 8, 208, 0x0AABBCCDDL)
        assertEquals(0, b.get(0).toInt())                              // USBDEVFS_URB_TYPE_ISO
        assertEquals(0x83, b.get(1).toInt() and 0xff)
        assertEquals(0, b.get(5).toInt())
        assertEquals(Urb.ISO_ASAP, b.getInt(8))
        assertEquals(0x1122334455667788L, b.getLong(16))
        assertEquals(208 * 8, b.getInt(24))
        assertEquals(8, b.getInt(36))
        assertEquals(0x0AABBCCDDL, b.getLong(48))
        for (i in 0 until 8) assertEquals(208, b.getInt(56 + 12 * i))
        b.putInt(56 + 12 * 3 + 4, 192); b.putInt(56 + 12 * 3 + 8, -18)
        assertEquals(192 to -18, Urb.paket(b, 3))
    }

    @Test fun ioctlNomera() {
        // _IOR('U', 10, struct usbdevfs_urb[56]) и _IOW('U', 12, void*) — как напечатал gcc
        assertEquals(0x8038550AL, Urb.SUBMITURB)
        assertEquals(0x4008550CL, Urb.REAPURB)
        assertEquals(0x4008550DL, Urb.REAPURBNDELAY)
        assertEquals(0x550BL, Urb.DISCARDURB)
    }

    @Test fun pikIDb() {
        val b = byteArrayOf(0, 0, 0x00, 0x40, 0x00, 0x80.toByte(), 1, 0)   // 0, 16384, -32768, 1
        assertEquals(32767, Pcm.pik(b, b.size))
        assertEquals(16384, Pcm.pik(b, 4))
        assertEquals(-90, Pcm.db(0))
        assertEquals(-6, Pcm.db(16384))
        assertEquals(0, Pcm.db(32767))
    }

    @Test fun json() {
        assertEquals("\"а\\\"б\\n\\u0001\"", Json.str("а\"б\n\u0001"))
    }
}
