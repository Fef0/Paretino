package com.paretino.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallLogicTest {
    private fun hold(id: Long, x: Float, y: Float, color: Int = 0xFF2E7DF6.toInt(), r: Float = 0.01f, pos: Long? = null) =
        HoldRef(id, Polygon.circle(NPoint(x, y), r, 1f), color, pos)

    @Test fun snapPicksClosestFreeAnchor() {
        val anchors = listOf(Anchor(1, NPoint(0.1f, 0.1f)), Anchor(2, NPoint(0.2f, 0.1f)))
        // Two holds near anchor 1: the closer one wins it, the other falls back to anchor 2 if in reach.
        val result = PositionSnapper.snap(listOf(NPoint(0.11f, 0.1f), NPoint(0.13f, 0.1f)), anchors, tolerance = 0.08f)
        assertEquals(listOf(1L, 2L), result)
    }

    @Test fun snapLeavesFarHoldsUnassigned() {
        val anchors = listOf(Anchor(1, NPoint(0.1f, 0.1f)))
        assertNull(PositionSnapper.snap(listOf(NPoint(0.5f, 0.5f)), anchors, tolerance = 0.05f)[0])
    }

    @Test fun defaultToleranceFollowsGridSpacing() {
        val anchors = PositionGrid.generate(5, 5, 0f, 0f, false).mapIndexed { i, (p, _) -> Anchor(i.toLong(), p) }
        assertEquals(0.25f * 0.6f, PositionSnapper.defaultTolerance(anchors), 1e-4f)
    }

    @Test fun diffFindsAddedRemovedChanged() {
        val older = listOf(hold(1, 0.1f, 0.1f), hold(2, 0.5f, 0.5f), hold(3, 0.8f, 0.2f))
        val newer = listOf(
            hold(10, 0.101f, 0.1f),                              // same hold, slightly different outline
            hold(11, 0.5f, 0.5f, color = 0xFFE5484D.toInt()),   // swapped for a red one
            hold(12, 0.3f, 0.9f),                               // new
        )
        val d = MasterDiffCalculator.diff(older, newer)
        assertEquals(listOf(12L), d.added.map { it.id })
        assertEquals(listOf(3L), d.removed.map { it.id })
        assertEquals(listOf(2L to 11L), d.changed.map { it.first.id to it.second.id })
        assertEquals(listOf(1L to 10L), d.unchanged.map { it.first.id to it.second.id })
    }

    @Test fun diffMatchesByAnchorFirst() {
        // Same anchor, centre moved a lot (different hold shape on the same bolt) but same colour and size.
        val d = MasterDiffCalculator.diff(listOf(hold(1, 0.1f, 0.1f, pos = 7)), listOf(hold(2, 0.15f, 0.1f, pos = 7)))
        assertEquals(1, d.unchanged.size)
        assertTrue(d.added.isEmpty() && d.removed.isEmpty())
    }

    @Test fun diffFlagsBigSizeChange() {
        val d = MasterDiffCalculator.diff(listOf(hold(1, 0.5f, 0.5f, r = 0.01f)), listOf(hold(2, 0.5f, 0.5f, r = 0.03f)))
        assertEquals(1, d.changed.size)
    }

    @Test fun gridLabelsAndStagger() {
        val g = PositionGrid.generate(3, 2, 0.1f, 0.1f, staggered = true)
        assertEquals(3 + 2, g.size)
        assertEquals("A2", g[0].second) // top row is row 2 counting from the bottom
        assertEquals(0.1f, g[0].first.x, 1e-5f)
        assertEquals(0.3f, g[3].first.x, 1e-5f) // shifted half a step
        assertEquals("AA", PositionGrid.columnName(26))
    }

    @Test fun serpentineStartsBottomLeftAndSnakes() {
        val pts = listOf(NPoint(0.1f, 0.1f), NPoint(0.9f, 0.1f), NPoint(0.1f, 0.9f), NPoint(0.9f, 0.9f))
        // Bottom row left→right gets 0,1; top row right→left gets 2,3.
        assertEquals(listOf(3, 2, 0, 1), PositionGrid.serpentine(pts))
    }

    @Test fun gradesConvert() {
        assertEquals("6A+", Grades.label(6, GradeScale.FONT))
        assertEquals("V3", Grades.label(6, GradeScale.V))
        assertEquals("?", Grades.label(null, GradeScale.FONT))
        assertEquals(Grades.options(GradeScale.V).map { it.second }.distinct().size, Grades.options(GradeScale.V).size)
    }
}
