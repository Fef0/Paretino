package com.paretino.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskGeometryTest {
    private fun mask(w: Int, h: Int, on: (Int, Int) -> Boolean) = BooleanArray(w * h) { on(it % w, it / w) }

    @Test fun resizeKeepsFlatValuesAndInterpolates() {
        assertArrayEquals(FloatArray(16) { 0.7f }, resizeBilinear(FloatArray(4) { 0.7f }, 2, 2, 4, 4), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0.25f, 0.75f, 1f), resizeBilinear(floatArrayOf(0f, 1f), 2, 1, 4, 1), 1e-6f)
    }

    @Test fun outlineOfSquareRunsThroughPixelCentres() {
        val outline = largestOutline(mask(10, 10) { x, y -> x in 2..6 && y in 3..7 }, 10, 10)!!
        assertEquals(16.0, polygonArea(outline), 1e-9)
        assertEquals(16, outline.size)
        assertEquals(outline.size, outline.toSet().size)
    }

    @Test fun largestRegionWinsAndHolesAreIgnored() {
        val on = mask(20, 10) { x, y -> (x in 1..2 && y in 1..2) || (x in 6..14 && y in 1..8 && !(x in 9..11 && y in 3..5)) }
        assertEquals(56.0, polygonArea(largestOutline(on, 20, 10)!!), 1e-9)
    }

    @Test fun regionsOnImageEdgesAndDiagonalsAreTraced() {
        assertEquals(9.0, polygonArea(largestOutline(BooleanArray(16) { true }, 4, 4)!!), 1e-9)
        assertTrue(largestOutline(mask(5, 5) { x, y -> x == y }, 5, 5)!!.contains(Pt(4.0, 4.0)))
    }

    @Test fun singlePixelAndEmptyMask() {
        assertEquals(listOf(Pt(1.0, 1.0)), largestOutline(mask(3, 3) { x, y -> x == 1 && y == 1 }, 3, 3))
        assertNull(largestOutline(BooleanArray(9), 3, 3))
    }

    @Test fun simplifyReducesSquareToItsCorners() {
        val outline = largestOutline(mask(30, 30) { x, y -> x in 5..24 && y in 5..24 }, 30, 30)!!
        assertEquals(setOf(Pt(5.0, 5.0), Pt(24.0, 5.0), Pt(24.0, 24.0), Pt(5.0, 24.0)), simplify(outline, 0.8).toSet())
    }

    @Test fun simplifyKeepsShapeWithinTolerance() {
        val circle = largestOutline(mask(60, 60) { x, y -> (x - 30) * (x - 30) + (y - 30) * (y - 30) <= 400 }, 60, 60)!!
        val simple = simplify(circle, 0.8)
        assertTrue(simple.size in 8 until circle.size / 2)
        assertEquals(polygonArea(circle), polygonArea(simple), polygonArea(circle) * 0.05)
    }

    @Test fun fillCoversPolygonInsideItsWindow() {
        val square = listOf(Pt(12.0, 22.0), Pt(16.0, 22.0), Pt(16.0, 26.0), Pt(12.0, 26.0))
        val filled = fillPolygon(square, 10, 20, 10, 10)
        assertEquals(20, filled.count { it })
        assertTrue(filled[2 * 10 + 2])
        assertFalse(filled[1 * 10 + 2])
        assertEquals(4, fillPolygon(square, 15, 24, 2, 2).count { it })
    }
}
