package com.paretino.domain

import kotlin.math.sqrt

/** A hold reduced to what wall-level logic needs. */
data class HoldRef(
    val id: Long,
    val outline: Polygon,
    val colorArgb: Int,
    val positionId: Long? = null,
) {
    val center: NPoint get() = outline.center
}

data class Anchor(val id: Long, val point: NPoint)

object PositionSnapper {
    /**
     * Assigns each hold the closest free anchor within [tolerance] (closest pairs first, one hold per
     * anchor). Holds without an anchor in reach map to null.
     */
    fun snap(holds: List<NPoint>, anchors: List<Anchor>, tolerance: Float = defaultTolerance(anchors)): List<Long?> {
        val pairs = buildList {
            holds.forEachIndexed { h, c ->
                anchors.forEach { a -> c.distanceTo(a.point).takeIf { it <= tolerance }?.let { add(Triple(h, a.id, it)) } }
            }
        }.sortedBy { it.third }
        val result = arrayOfNulls<Long>(holds.size)
        val used = HashSet<Long>()
        for ((h, a, _) in pairs) {
            if (result[h] == null && a !in used) { result[h] = a; used += a }
        }
        return result.toList()
    }

    /** Most of the way to the nearest neighbour on a regular t-nut grid. */
    fun defaultTolerance(anchors: List<Anchor>): Float {
        if (anchors.size < 2) return 0.05f
        val nn = anchors.map { a -> anchors.filter { it !== a }.minOf { it.point.distanceTo(a.point) } }.sorted()
        return nn[nn.size / 2] * 0.6f
    }
}

data class MasterDiff(
    /** On the newer master only. */
    val added: List<HoldRef>,
    /** On the older master only. */
    val removed: List<HoldRef>,
    /** Same spot, different hold (colour or size changed): older to newer. */
    val changed: List<Pair<HoldRef, HoldRef>>,
    val unchanged: List<Pair<HoldRef, HoldRef>>,
)

object MasterDiffCalculator {
    /**
     * Matches holds by anchor when both have one, else by nearest centre within [tolerance].
     * A matched pair counts as changed when colour differs by more than [colorThreshold] (RGB distance)
     * or the outline area by more than [areaRatio] times.
     */
    fun diff(
        older: List<HoldRef>,
        newer: List<HoldRef>,
        tolerance: Float = 0.02f,
        colorThreshold: Float = 70f,
        areaRatio: Float = 1.8f,
    ): MasterDiff {
        val pairs = ArrayList<Pair<HoldRef, HoldRef>>()
        val freeOld = older.toMutableList()
        val freeNew = newer.toMutableList()

        val newByPos = freeNew.filter { it.positionId != null }.associateBy { it.positionId }
        for (o in older) {
            val n = o.positionId?.let { newByPos[it] } ?: continue
            pairs += o to n; freeOld -= o; freeNew -= n
        }
        val candidates = buildList {
            for (o in freeOld) for (n in freeNew) {
                val d = o.center.distanceTo(n.center)
                if (d <= tolerance) add(Triple(o, n, d))
            }
        }.sortedBy { it.third }
        for ((o, n, _) in candidates) {
            if (o in freeOld && n in freeNew) { pairs += o to n; freeOld -= o; freeNew -= n }
        }
        val (changed, same) = pairs.partition { (o, n) -> isDifferent(o, n, colorThreshold, areaRatio) }
        return MasterDiff(added = freeNew, removed = freeOld, changed = changed, unchanged = same)
    }

    private fun isDifferent(a: HoldRef, b: HoldRef, colorThreshold: Float, areaRatio: Float): Boolean {
        val ratio = a.outline.area / b.outline.area.coerceAtLeast(1e-9f)
        return colorDistance(a.colorArgb, b.colorArgb) > colorThreshold || ratio > areaRatio || ratio < 1 / areaRatio
    }

    fun colorDistance(a: Int, b: Int): Float {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }
}

object PositionGrid {
    /**
     * Anchor points for a t-nut grid: [cols] x [rows] evenly spaced inside a [marginX]/[marginY] border,
     * every other row shifted by half a step when [staggered]. Labels go A1, B1... column letter, row number
     * from the bottom (how setters count).
     */
    fun generate(cols: Int, rows: Int, marginX: Float, marginY: Float, staggered: Boolean): List<Pair<NPoint, String>> {
        val stepX = if (cols > 1) (1 - 2 * marginX) / (cols - 1) else 0f
        val stepY = if (rows > 1) (1 - 2 * marginY) / (rows - 1) else 0f
        return buildList {
            for (r in 0 until rows) {
                val shift = if (staggered && r % 2 == 1) stepX / 2 else 0f
                val n = if (staggered && r % 2 == 1) cols - 1 else cols
                for (c in 0 until n) {
                    val label = columnName(c) + (rows - r)
                    add(NPoint(marginX + c * stepX + shift, marginY + r * stepY) to label)
                }
            }
        }
    }

    fun columnName(i: Int): String = if (i < 26) ('A' + i).toString() else columnName(i / 26 - 1) + ('A' + i % 26)

    /** LED indices for a serpentine strip starting bottom-left (the common way strips are run). */
    fun serpentine(points: List<NPoint>, rowTolerance: Float = 0.02f): List<Int> {
        val rows = ArrayList<MutableList<Int>>()
        points.indices.sortedByDescending { points[it].y }.forEach { i ->
            val row = rows.lastOrNull()?.takeIf { r -> kotlin.math.abs(points[r.first()].y - points[i].y) <= rowTolerance }
            if (row != null) row += i else rows += mutableListOf(i)
        }
        val result = IntArray(points.size)
        var next = 0
        rows.forEachIndexed { r, row ->
            val sorted = row.sortedBy { points[it].x }.let { if (r % 2 == 1) it.reversed() else it }
            sorted.forEach { result[it] = next++ }
        }
        return result.toList()
    }
}
