package com.paretino.vision

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A point in pixels. */
data class Pt(val x: Double, val y: Double)

/** Bilinear resize with pixel centres aligned, as ml/scripts/yolo_infer.py does through cv2.resize. */
fun resizeBilinear(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
    val out = FloatArray(dw * dh)
    for (y in 0 until dh) {
        val fy = ((y + 0.5) * sh / dh - 0.5).coerceIn(0.0, sh - 1.0)
        val y0 = fy.toInt(); val y1 = min(y0 + 1, sh - 1); val ty = (fy - y0).toFloat()
        for (x in 0 until dw) {
            val fx = ((x + 0.5) * sw / dw - 0.5).coerceIn(0.0, sw - 1.0)
            val x0 = fx.toInt(); val x1 = min(x0 + 1, sw - 1); val tx = (fx - x0).toFloat()
            val top = src[y0 * sw + x0] * (1 - tx) + src[y0 * sw + x1] * tx
            val bottom = src[y1 * sw + x0] * (1 - tx) + src[y1 * sw + x1] * tx
            out[y * dw + x] = top * (1 - ty) + bottom * ty
        }
    }
    return out
}

private val DX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
private val DY = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

/** Outer boundary, through pixel centres, of the 8-connected region in [mask] with the largest enclosed area. */
fun largestOutline(mask: BooleanArray, w: Int, h: Int): List<Pt>? {
    val seen = BooleanArray(mask.size)
    var best: List<Pt>? = null
    var bestArea = -1.0
    for (i in mask.indices) {
        if (!mask[i] || seen[i]) continue
        markRegion(mask, seen, w, h, i)
        val outline = traceBoundary(mask, w, h, i % w, i / w)
        val area = polygonArea(outline)
        if (area > bestArea) { best = outline; bestArea = area }
    }
    return best
}

private fun markRegion(mask: BooleanArray, seen: BooleanArray, w: Int, h: Int, start: Int) {
    val stack = ArrayDeque<Int>()
    stack.addLast(start)
    seen[start] = true
    while (stack.isNotEmpty()) {
        val i = stack.removeLast()
        for (d in 0 until 8) {
            val x = i % w + DX[d]; val y = i / w + DY[d]
            if (x !in 0 until w || y !in 0 until h) continue
            val n = y * w + x
            if (mask[n] && !seen[n]) { seen[n] = true; stack.addLast(n) }
        }
    }
}

/** Follows the boundary clockwise from ([sx], [sy]), the region's first pixel in scan order. */
private fun traceBoundary(mask: BooleanArray, w: Int, h: Int, sx: Int, sy: Int): List<Pt> {
    val points = arrayListOf(Pt(sx.toDouble(), sy.toDouble()))
    var x = sx; var y = sy; var search = 0; var first = -1
    while (true) {
        var d = -1
        for (i in 0 until 8) {
            val c = (search + i) % 8
            val nx = x + DX[c]; val ny = y + DY[c]
            if (nx in 0 until w && ny in 0 until h && mask[ny * w + nx]) { d = c; break }
        }
        if (d < 0) return points
        if (x == sx && y == sy && d == first) return points.dropLast(1)
        if (first < 0) first = d
        x += DX[d]; y += DY[d]
        points += Pt(x.toDouble(), y.toDouble())
        search = (d + 5) % 8
    }
}

fun polygonArea(p: List<Pt>): Double {
    var a = 0.0
    for (i in p.indices) { val q = p[(i + 1) % p.size]; a += p[i].x * q.y - q.x * p[i].y }
    return abs(a) / 2
}

/** Douglas-Peucker on a closed outline: no removed point lies further than [epsilon] from the result. */
fun simplify(closed: List<Pt>, epsilon: Double): List<Pt> {
    val n = closed.size
    if (n < 3) return closed
    val far = (1 until n).maxBy { hypot(closed[it].x - closed[0].x, closed[it].y - closed[0].y) }
    val keep = BooleanArray(n).also { it[0] = true; it[far] = true }
    val spans = ArrayDeque(listOf(0 to far, far to n))
    while (spans.isNotEmpty()) {
        val (a, b) = spans.removeLast()
        val worst = (a + 1 until b).maxByOrNull { lineDistance(closed[it], closed[a], closed[b % n]) } ?: continue
        if (lineDistance(closed[worst], closed[a], closed[b % n]) > epsilon) {
            keep[worst] = true
            spans.addLast(a to worst)
            spans.addLast(worst to b)
        }
    }
    return closed.filterIndexed { i, _ -> keep[i] }
}

private fun lineDistance(p: Pt, a: Pt, b: Pt): Double {
    val dx = b.x - a.x; val dy = b.y - a.y
    val length = hypot(dx, dy)
    return if (length == 0.0) hypot(p.x - a.x, p.y - a.y) else abs(dy * (p.x - a.x) - dx * (p.y - a.y)) / length
}

/** Rasterises [polygon] into a [w] x [h] window whose top-left pixel is ([x0], [y0]). */
fun fillPolygon(polygon: List<Pt>, x0: Int, y0: Int, w: Int, h: Int): BooleanArray {
    val out = BooleanArray(w * h)
    val crossings = ArrayList<Double>()
    for (row in 0 until h) {
        // Just off the pixel row, so a scan line never lands exactly on a vertex.
        val y = y0 + row + 1e-3
        crossings.clear()
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]
            if ((a.y > y) != (b.y > y)) crossings += a.x + (y - a.y) / (b.y - a.y) * (b.x - a.x)
        }
        crossings.sort()
        for (i in 0 until crossings.size - 1 step 2) {
            val from = max(ceil(crossings[i]).toInt() - x0, 0)
            val to = min(floor(crossings[i + 1]).toInt() - x0, w - 1)
            for (x in from..to) out[row * w + x] = true
        }
    }
    return out
}
