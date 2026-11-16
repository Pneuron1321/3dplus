package com.questclaude.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/** Радар: повороты через 0/360, румбы, подпись под радаром (по-английски). */
class RadarTest {
    @Test fun raznica() {
        assertEquals(20f, Radar.raznica(350f, 10f), 1e-4f)
        assertEquals(-20f, Radar.raznica(10f, 350f), 1e-4f)
        assertEquals(180f, Radar.raznica(0f, 180f), 1e-4f)
        assertEquals(-90f, Radar.raznica(90f, 0f), 1e-4f)
    }

    @Test fun storona() {
        assertEquals("N", Radar.storona(0f))
        assertEquals("N", Radar.storona(359f))
        assertEquals("NE", Radar.storona(45f))
        assertEquals("WSW", Radar.storona(247f))
        assertEquals("S", Radar.storona(-180f))
    }

    @Test fun podpis() {
        assertEquals("247° WSW", Radar.podpis(247.4f, "absolute"))
        assertEquals("≈0° N", Radar.podpis(359.8f, "relative"))
        assertEquals("no compass", Radar.podpis(null, "relative"))
        assertEquals("no compass", Radar.podpis(10f, "none"))
    }

    @Test fun norm() {
        assertEquals(350f, Radar.norm(-10f), 1e-4f)
        assertEquals(10f, Radar.norm(370f), 1e-4f)
    }
}
