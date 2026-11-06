package com.paretino.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.paretino.domain.NPoint
import com.paretino.domain.Polygon
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Hold detector on a small YOLO segmentation network (ONNX Runtime), trained on real walls and plywood composites
 * with phone-camera degradations (see ml/README.md).
 *
 * A rectified wall has a hundred or more small holds, too small for one 640 px pass, so the wall is cut into
 * overlapping tiles at near-native resolution and the masks are merged. Mirrors ml/scripts/yolo_infer.py.
 */
@Singleton
class YoloHoldDetector @Inject constructor(
    @ApplicationContext private val context: Context,
) : HoldDetector {

    private val env by lazy { OrtEnvironment.getEnvironment() }

    private val session: OrtSession? by lazy {
        runCatching {
            val model = context.assets.open(MODEL).use { it.readBytes() }
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(THREADS)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            env.createSession(model, options)
        }.onFailure { Log.e("Paretino", "Hold model failed to load", it) }.getOrNull()
    }

    private class Found(val score: Float, val points: List<Pt>, val box: DoubleArray)

    override suspend fun detect(wall: Bitmap, sensitivity: Float): List<DetectedHold> {
        val model = session ?: error("The hold model could not be loaded")
        return withContext(Dispatchers.Default) {
            val picture = picture(wall)
            val w = picture.tiledWidth; val h = picture.tiledHeight
            // Slider 1 is the tuned threshold; higher sensitivity accepts less certain holds.
            val conf = (CONF / sensitivity.toDouble().pow(2.0)).coerceIn(0.04, 0.7).toFloat()
            val found = ArrayList<Found>()
            for ((x, y) in tiles(w, h)) {
                for (d in runTile(model, tile(picture, x, y), conf)) {
                    val cut = (d.box[0] < EDGE && x > 0) || (d.box[1] < EDGE && y > 0) ||
                        (d.box[2] > TILE - EDGE && x + TILE < w) || (d.box[3] > TILE - EDGE && y + TILE < h)
                    outline(d)?.let { pts ->
                        val shifted = pts.map { Pt(it.x + x, it.y + y) }
                        found += Found(d.score - if (cut) CUT_PENALTY else 0f, shifted, boundsOf(shifted))
                    }
                }
            }
            merge(found).map { hold(picture, it.points) }
        }
    }

    override suspend fun detectAt(wall: Bitmap, point: NPoint): DetectedHold = withContext(Dispatchers.Default) {
        val picture = picture(wall)
        val px = point.x * picture.width; val py = point.y * picture.height
        session?.let { model ->
            val x0 = (px - TILE / 2).roundToInt().coerceIn(0, max(picture.tiledWidth - TILE, 0))
            val y0 = (py - TILE / 2).roundToInt().coerceIn(0, max(picture.tiledHeight - TILE, 0))
            val tap = NPoint(px - x0, py - y0)
            // The user says a hold is here, so even a faint detection counts if it covers the tap.
            runTile(model, tile(picture, x0, y0), TAP_CONF)
                .mapNotNull { d -> outline(d)?.let { d to it } }
                .filter { (_, pts) -> Polygon(pts.map { NPoint(it.x.toFloat(), it.y.toFloat()) }).contains(tap) }
                .maxByOrNull { (d, _) -> d.score }
                ?.let { (_, pts) -> hold(picture, pts.map { Pt(it.x + x0, it.y + y0) }) }
        } ?: DetectedHold( // Nothing found: a plain circle the user can live with.
            Polygon.circle(point, TAP_RADIUS, picture.width.toFloat() / picture.height),
            picture.pixels[py.toInt().coerceIn(0, picture.height - 1) * picture.width + px.toInt().coerceIn(0, picture.width - 1)],
        )
    }

    /** ARGB [pixels] of the working picture. Walls smaller than a tile read as grey padding beyond their edge. */
    private class Picture(val pixels: IntArray, val width: Int, val height: Int) {
        val tiledWidth = max(width, TILE)
        val tiledHeight = max(height, TILE)
    }

    private fun picture(wall: Bitmap): Picture {
        val s = WORK.toDouble() / max(wall.width, wall.height)
        val scaled = if (s < 1) Bitmap.createScaledBitmap(wall, (wall.width * s).toInt(), (wall.height * s).toInt(), true) else wall
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        return Picture(pixels, scaled.width, scaled.height).also { if (scaled !== wall) scaled.recycle() }
    }

    private fun tiles(w: Int, h: Int): List<Pair<Int, Int>> {
        fun axis(size: Int): List<Int> {
            val step = TILE - OVERLAP
            val starts = (0..max(size - TILE, 0) step step).toMutableList()
            if (starts.last() + TILE < size) starts += size - TILE
            return starts
        }
        return axis(h).flatMap { y -> axis(w).map { x -> x to y } }
    }

    /** Tile pixels as a planar float tensor in 0..1. */
    private fun tile(picture: Picture, x: Int, y: Int): FloatBuffer {
        val plane = TILE * TILE
        val out = FloatArray(plane * 3)
        for (row in 0 until TILE) for (col in 0 until TILE) {
            val inside = x + col < picture.width && y + row < picture.height
            val c = if (inside) picture.pixels[(y + row) * picture.width + x + col] else PADDING
            val i = row * TILE + col
            out[i] = (c shr 16 and 0xFF) / 255f
            out[plane + i] = (c shr 8 and 0xFF) / 255f
            out[2 * plane + i] = (c and 0xFF) / 255f
        }
        return FloatBuffer.wrap(out)
    }

    private class Detection(val score: Float, val box: DoubleArray, val coeffs: FloatArray, val proto: FloatArray)

    private fun runTile(model: OrtSession, pixels: FloatBuffer, conf: Float): List<Detection> {
        val input = OnnxTensor.createTensor(env, pixels, longArrayOf(1, 3, TILE.toLong(), TILE.toLong()))
        return input.use {
            model.run(mapOf(model.inputNames.first() to it)).use { outputs ->
                val pred = (outputs[0] as OnnxTensor).floatBuffer
                val proto = FloatArray(PROTO_CH * PROTO * PROTO).also { p -> (outputs[1] as OnnxTensor).floatBuffer.get(p) }
                val info = (outputs[0] as OnnxTensor).info.shape
                val rows = info[1].toInt(); val anchors = info[2].toInt() // cx cy w h score, then mask coefficients
                val data = FloatArray(rows * anchors).also { pred.get(it) }
                val candidates = ArrayList<Detection>()
                for (a in 0 until anchors) {
                    val score = data[4 * anchors + a]
                    if (score < conf) continue
                    val cx = data[a].toDouble(); val cy = data[anchors + a].toDouble()
                    val bw = data[2 * anchors + a].toDouble(); val bh = data[3 * anchors + a].toDouble()
                    candidates += Detection(
                        score, doubleArrayOf(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2),
                        FloatArray(PROTO_CH) { c -> data[(5 + c) * anchors + a] }, proto,
                    )
                }
                nms(candidates)
            }
        }
    }

    private fun nms(all: List<Detection>): List<Detection> {
        val kept = ArrayList<Detection>()
        for (d in all.sortedByDescending { it.score }) {
            if (kept.none { boxIou(it.box, d.box) > TILE_NMS }) kept += d
        }
        return kept
    }

    private fun boxIou(a: DoubleArray, b: DoubleArray): Double {
        val iw = min(a[2], b[2]) - max(a[0], b[0]); val ih = min(a[3], b[3]) - max(a[1], b[1])
        if (iw <= 0 || ih <= 0) return 0.0
        val inter = iw * ih
        return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)
    }

    /** The detection's mask inside its box, as an outline in tile pixels. */
    private fun outline(d: Detection): List<Pt>? {
        val k = TILE.toDouble() / PROTO
        val x0 = d.box[0].coerceIn(0.0, TILE.toDouble()); val y0 = d.box[1].coerceIn(0.0, TILE.toDouble())
        val x1 = d.box[2].coerceIn(0.0, TILE.toDouble()); val y1 = d.box[3].coerceIn(0.0, TILE.toDouble())
        if (x1 - x0 < 3 || y1 - y0 < 3) return null
        val lx0 = (x0 / k).toInt(); val ly0 = (y0 / k).toInt()
        val lx1 = min(ceil(x1 / k).toInt() + 1, PROTO); val ly1 = min(ceil(y1 / k).toInt() + 1, PROTO)
        val lw = lx1 - lx0; val lh = ly1 - ly0
        val logits = FloatArray(lw * lh)
        for (y in 0 until lh) for (x in 0 until lw) {
            var v = 0f
            val at = (y + ly0) * PROTO + x + lx0
            for (c in 0 until PROTO_CH) v += d.coeffs[c] * d.proto[c * PROTO * PROTO + at]
            logits[y * lw + x] = 1f / (1f + exp(-v.coerceIn(-30f, 30f)))
        }
        val upW = max((lw * k).toInt(), 2); val upH = max((lh * k).toInt(), 2)
        val up = resizeBilinear(logits, lw, lh, upW, upH)
        // Outside the predicted box is not this hold.
        val ox = lx0 * k; val oy = ly0 * k
        val mask = BooleanArray(upW * upH)
        for (y in (y0 - oy).toInt().coerceAtLeast(0)..(y1 - oy).toInt().coerceAtMost(upH - 1)) {
            for (x in (x0 - ox).toInt().coerceAtLeast(0)..(x1 - ox).toInt().coerceAtMost(upW - 1)) {
                mask[y * upW + x] = up[y * upW + x] > 0.5f
            }
        }
        val best = largestOutline(mask, upW, upH)?.takeIf { polygonArea(it) >= MIN_AREA } ?: return null
        return simplify(best, SIMPLIFY).takeIf { it.size >= 3 }?.map { Pt(it.x + ox, it.y + oy) }
    }

    /** Suppression across tiles on outlines; a fragment inside a kept hold loses to it. */
    private fun merge(found: List<Found>): List<Found> {
        val kept = ArrayList<Found>()
        for (f in found.sortedByDescending { it.score }) {
            if (kept.none { overlaps(it, f) }) kept += f
        }
        return kept
    }

    private fun overlaps(a: Found, b: Found): Boolean {
        val x0 = min(a.box[0], b.box[0]).toInt() - 1; val y0 = min(a.box[1], b.box[1]).toInt() - 1
        val x1 = max(a.box[2], b.box[2]).toInt() + 2; val y1 = max(a.box[3], b.box[3]).toInt() + 2
        if (a.box[2] < b.box[0] || b.box[2] < a.box[0] || a.box[3] < b.box[1] || b.box[3] < a.box[1]) return false
        val w = x1 - x0; val h = y1 - y0
        val ma = fillPolygon(a.points, x0, y0, w, h); val mb = fillPolygon(b.points, x0, y0, w, h)
        val inter = ma.indices.count { ma[it] && mb[it] }.toDouble()
        val areaA = ma.count { it }; val areaB = mb.count { it }
        val union = max(areaA + areaB - inter, 1.0)
        val smaller = max(min(areaA, areaB), 1).toDouble()
        return inter / union > MERGE_IOU || inter / smaller > MERGE_CONTAINED
    }

    private fun boundsOf(p: List<Pt>) = doubleArrayOf(p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x }, p.maxOf { it.y })

    private fun hold(picture: Picture, points: List<Pt>): DetectedHold {
        val w = picture.width; val h = picture.height
        val b = boundsOf(points)
        val x0 = b[0].toInt().coerceIn(0, w - 1); val y0 = b[1].toInt().coerceIn(0, h - 1)
        val rw = max(min(ceil(b[2]).toInt(), w) - x0, 1); val rh = max(min(ceil(b[3]).toInt(), h) - y0, 1)
        val inside = fillPolygon(points, x0, y0, rw, rh)
        var red = 0L; var green = 0L; var blue = 0L; var n = 0
        for (y in 0 until rh) for (x in 0 until rw) {
            if (!inside[y * rw + x]) continue
            val c = picture.pixels[(y + y0) * w + x + x0]
            red += c shr 16 and 0xFF; green += c shr 8 and 0xFF; blue += c and 0xFF; n++
        }
        val count = max(n, 1)
        return DetectedHold(
            Polygon(points.map { NPoint((it.x / w).toFloat().coerceIn(0f, 1f), (it.y / h).toFloat().coerceIn(0f, 1f)) }),
            (0xFF shl 24) or ((red / count).toInt() shl 16) or ((green / count).toInt() shl 8) or (blue / count).toInt(),
        )
    }

    private companion object {
        const val MODEL = "models/holds.onnx"
        const val THREADS = 4
        const val TILE = 640
        const val OVERLAP = 128
        const val WORK = 2000
        const val PROTO = 160
        const val PROTO_CH = 32
        const val CONF = 0.25
        const val TAP_CONF = 0.05f
        const val TAP_RADIUS = 0.015f
        const val TILE_NMS = 0.6
        const val EDGE = 4.0
        const val CUT_PENALTY = 0.5f
        const val MIN_AREA = 12.0
        const val SIMPLIFY = 0.8
        const val MERGE_IOU = 0.5
        const val MERGE_CONTAINED = 0.7
        const val PADDING = 0x727272
    }
}
