package com.paretino.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A point in rectified-wall space: (0,0) top-left corner of the wall, (1,1) bottom-right. */
data class NPoint(val x: Float, val y: Float) {
    fun distanceTo(o: NPoint) = hypot(x - o.x, y - o.y)
}

/** A hold outline in rectified-wall space. */
@JvmInline
value class Polygon(val points: List<NPoint>) {

    val center: NPoint
        get() {
            // Area-weighted centroid; falls back to the vertex mean for degenerate outlines.
            var a = 0f; var cx = 0f; var cy = 0f
            for (i in points.indices) {
                val p = points[i]; val q = points[(i + 1) % points.size]
                val cross = p.x * q.y - q.x * p.y
                a += cross; cx += (p.x + q.x) * cross; cy += (p.y + q.y) * cross
            }
            if (abs(a) < 1e-9f) return NPoint(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())
            return NPoint(cx / (3 * a), cy / (3 * a))
        }

    val area: Float
        get() {
            var a = 0f
            for (i in points.indices) {
                val p = points[i]; val q = points[(i + 1) % points.size]
                a += p.x * q.y - q.x * p.y
            }
            return abs(a) / 2
        }

    val bounds: Bounds
        get() = Bounds(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })

    fun contains(p: NPoint): Boolean {
        var inside = false
        var j = points.lastIndex
        for (i in points.indices) {
            val a = points[i]; val b = points[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** Distance from [p] to the outline (0 when inside). */
    fun distanceTo(p: NPoint): Float {
        if (contains(p)) return 0f
        var best = Float.MAX_VALUE
        for (i in points.indices) {
            best = minOf(best, segmentDistance(p, points[i], points[(i + 1) % points.size]))
        }
        return best
    }

    fun mirrored() = Polygon(points.map { NPoint(1f - it.x, it.y) }.reversed())

    fun encode(): String = points.joinToString(" ") { "%.5f,%.5f".format(java.util.Locale.ROOT, it.x, it.y) }

    companion object {
        fun decode(s: String): Polygon = Polygon(
            s.split(' ').filter { it.isNotBlank() }.map { pair ->
                val (x, y) = pair.split(',')
                NPoint(x.toFloat(), y.toFloat())
            }
        )

        /** A regular polygon, used when the user adds a hold by hand. */
        fun circle(c: NPoint, radius: Float, aspect: Float, n: Int = 16) = Polygon(
            List(n) { i ->
                val a = 2 * Math.PI * i / n
                NPoint(c.x + (radius * cos(a)).toFloat(), c.y + (radius * aspect * sin(a)).toFloat())
            }
        )
    }
}

data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

private fun segmentDistance(p: NPoint, a: NPoint, b: NPoint): Float {
    val dx = b.x - a.x; val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
    return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

/** Picks the hold under a tap: smallest outline containing it, else the nearest within [tolerance]. */
fun <T> hitTest(items: List<T>, shape: (T) -> Polygon, p: NPoint, tolerance: Float): T? {
    val containing = items.filter { shape(it).contains(p) }
    if (containing.isNotEmpty()) return containing.minBy { shape(it).area }
    return items.map { it to shape(it).distanceTo(p) }
        .filter { it.second <= tolerance }
        .minByOrNull { it.second }?.first
}
