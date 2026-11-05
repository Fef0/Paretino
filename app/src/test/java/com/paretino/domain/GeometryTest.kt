package com.paretino.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    private val square = Polygon(listOf(NPoint(0.1f, 0.1f), NPoint(0.3f, 0.1f), NPoint(0.3f, 0.3f), NPoint(0.1f, 0.3f)))

    @Test fun centerAndArea() {
        assertEquals(0.2f, square.center.x, 1e-5f)
        assertEquals(0.2f, square.center.y, 1e-5f)
        assertEquals(0.04f, square.area, 1e-5f)
    }

    @Test fun containsAndDistance() {
        assertTrue(square.contains(NPoint(0.2f, 0.2f)))
        assertFalse(square.contains(NPoint(0.35f, 0.2f)))
        assertEquals(0f, square.distanceTo(NPoint(0.2f, 0.2f)), 0f)
        assertEquals(0.05f, square.distanceTo(NPoint(0.35f, 0.2f)), 1e-5f)
    }

    @Test fun encodeRoundTrip() {
        val back = Polygon.decode(square.encode())
        assertEquals(square.points.size, back.points.size)
        square.points.zip(back.points).forEach { (a, b) -> assertEquals(a.x, b.x, 1e-5f); assertEquals(a.y, b.y, 1e-5f) }
    }

    @Test fun hitTestPrefersSmallestContaining() {
        val small = Polygon.circle(NPoint(0.2f, 0.2f), 0.02f, 1f)
        val picked = hitTest(listOf(square, small), { it }, NPoint(0.2f, 0.2f), 0.01f)
        assertEquals(small, picked)
    }

    @Test fun hitTestToleranceOutside() {
        assertEquals(square, hitTest(listOf(square), { it }, NPoint(0.305f, 0.2f), 0.01f))
        assertNull(hitTest(listOf(square), { it }, NPoint(0.5f, 0.5f), 0.01f))
    }

    @Test fun mirrorFlipsX() {
        val m = square.mirrored()
        assertEquals(0.8f, m.center.x, 1e-5f)
        assertEquals(square.area, m.area, 1e-6f)
    }
}
