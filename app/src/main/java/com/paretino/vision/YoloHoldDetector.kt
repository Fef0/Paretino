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
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
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
 *
 * Falls back to [OpenCvHoldDetector] if the model cannot be loaded.
 */
@Singleton
class YoloHoldDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fallback: OpenCvHoldDetector,
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
        }.onFailure { Log.e("Paretino", "Hold model failed to load, using OpenCV", it) }.getOrNull()
    }

    private class Found(val score: Float, val points: List<Point>, val box: DoubleArray)

    override suspend fun detect(wall: Bitmap, sensitivity: Float): List<DetectedHold> {
        val model = session ?: return fallback.detect(wall, sensitivity)
        return withContext(Dispatchers.Default) {
            val rgb = toRgb(wall)
            val w = rgb.cols(); val h = rgb.rows()
            // Slider 1 is the tuned threshold; higher sensitivity accepts less certain holds.
            val conf = (CONF / sensitivity.toDouble().pow(2.0)).coerceIn(0.04, 0.7).toFloat()
            val found = ArrayList<Found>()
            for ((x, y) in tiles(w, h)) {
                for (d in runTile(model, tile(rgb, x, y), conf)) {
                    val cut = (d.box[0] < EDGE && x > 0) || (d.box[1] < EDGE && y > 0) ||
                        (d.box[2] > TILE - EDGE && x + TILE < w) || (d.box[3] > TILE - EDGE && y + TILE < h)
                    outline(d)?.let { pts ->
                        val shifted = pts.map { Point(it.x + x, it.y + y) }
                        found += Found(d.score - if (cut) CUT_PENALTY else 0f, shifted, boundsOf(shifted))
                    }
                }
            }
            val result = merge(found).map { hold(rgb, it.points, w, h) }
            rgb.release()
            result
        }
    }

    override suspend fun detectAt(wall: Bitmap, point: NPoint): DetectedHold? {
        val model = session ?: return fallback.detectAt(wall, point)
        val hit = withContext(Dispatchers.Default) {
            val rgb = toRgb(wall)
            val w = rgb.cols(); val h = rgb.rows()
            val px = point.x * w; val py = point.y * h
            val x0 = (px - TILE / 2).roundToInt().coerceIn(0, max(w - TILE, 0))
            val y0 = (py - TILE / 2).roundToInt().coerceIn(0, max(h - TILE, 0))
            val tap = Point(px - x0.toDouble(), py - y0.toDouble())
            // The user says a hold is here, so even a faint detection counts if it covers the tap.
            val best = runTile(model, tile(rgb, x0, y0), TAP_CONF)
                .mapNotNull { d -> outline(d)?.let { d to it } }
                .filter { (_, pts) -> Imgproc.pointPolygonTest(MatOfPoint2f(*pts.toTypedArray()), tap, false) >= 0 }
                .maxByOrNull { (d, _) -> d.score }
            val r = best?.let { (_, pts) -> hold(rgb, pts.map { Point(it.x + x0, it.y + y0) }, w, h) }
            rgb.release()
            r
        }
        return hit ?: fallback.detectAt(wall, point)
    }

    private fun toRgb(wall: Bitmap): Mat {
        val rgba = Mat().also { Utils.bitmapToMat(wall, it) }
        val rgb = Mat().also { Imgproc.cvtColor(rgba, it, Imgproc.COLOR_RGBA2RGB) }
        rgba.release()
        val s = WORK.toDouble() / max(rgb.cols(), rgb.rows())
        if (s < 1) Imgproc.resize(rgb, rgb, Size(rgb.cols() * s, rgb.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
        if (rgb.cols() < TILE || rgb.rows() < TILE) {
            Core.copyMakeBorder(rgb, rgb, 0, max(TILE - rgb.rows(), 0), 0, max(TILE - rgb.cols(), 0), Core.BORDER_CONSTANT, Scalar(114.0, 114.0, 114.0))
        }
        return rgb
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
    private fun tile(rgb: Mat, x: Int, y: Int): FloatBuffer {
        val sub = rgb.submat(Rect(x, y, TILE, TILE)).clone()
        val px = ByteArray(TILE * TILE * 3).also { sub.get(0, 0, it) }
        sub.release()
        val plane = TILE * TILE
        val out = FloatArray(plane * 3)
        for (i in 0 until plane) {
            out[i] = (px[i * 3].toInt() and 0xFF) / 255f
            out[plane + i] = (px[i * 3 + 1].toInt() and 0xFF) / 255f
            out[2 * plane + i] = (px[i * 3 + 2].toInt() and 0xFF) / 255f
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
    private fun outline(d: Detection): List<Point>? {
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
        val low = Mat(lh, lw, CvType.CV_32FC1).apply { put(0, 0, logits) }
        val up = Mat()
        Imgproc.resize(low, up, Size(max((lw * k).toInt(), 2).toDouble(), max((lh * k).toInt(), 2).toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val mask = Mat()
        Imgproc.threshold(up, mask, 0.5, 255.0, Imgproc.THRESH_BINARY)
        mask.convertTo(mask, CvType.CV_8UC1)
        // Outside the predicted box is not this hold.
        val ox = lx0 * k; val oy = ly0 * k
        val keep = Mat.zeros(mask.size(), CvType.CV_8UC1)
        Imgproc.rectangle(keep, Point(x0 - ox, y0 - oy), Point(x1 - ox, y1 - oy), Scalar(255.0), -1)
        Core.bitwise_and(mask, keep, mask)
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        listOf(low, up, mask, keep).forEach { it.release() }
        val best = contours.maxByOrNull { Imgproc.contourArea(it) }?.takeIf { Imgproc.contourArea(it) >= MIN_AREA } ?: return null
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(MatOfPoint2f(*best.toArray()), approx, SIMPLIFY, true)
        return approx.toArray().takeIf { it.size >= 3 }?.map { Point(it.x + ox, it.y + oy) }
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
        val ma = raster(a, x0, y0, x1, y1); val mb = raster(b, x0, y0, x1, y1)
        val both = Mat().also { Core.bitwise_and(ma, mb, it) }
        val either = Mat().also { Core.bitwise_or(ma, mb, it) }
        val inter = Core.countNonZero(both).toDouble()
        val union = max(Core.countNonZero(either), 1).toDouble()
        val smaller = max(min(Core.countNonZero(ma), Core.countNonZero(mb)), 1).toDouble()
        listOf(ma, mb, both, either).forEach { it.release() }
        return inter / union > MERGE_IOU || inter / smaller > MERGE_CONTAINED
    }

    private fun raster(f: Found, x0: Int, y0: Int, x1: Int, y1: Int): Mat {
        val m = Mat.zeros(y1 - y0, x1 - x0, CvType.CV_8UC1)
        val pts = MatOfPoint(*f.points.map { Point(it.x - x0, it.y - y0) }.toTypedArray())
        Imgproc.fillPoly(m, listOf(pts), Scalar(1.0))
        return m
    }

    private fun boundsOf(p: List<Point>) = doubleArrayOf(p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x }, p.maxOf { it.y })

    private fun hold(rgb: Mat, points: List<Point>, w: Int, h: Int): DetectedHold {
        val b = boundsOf(points)
        val x0 = b[0].toInt().coerceIn(0, w - 1); val y0 = b[1].toInt().coerceIn(0, h - 1)
        val r = Rect(x0, y0, max(min(ceil(b[2]).toInt(), w) - x0, 1), max(min(ceil(b[3]).toInt(), h) - y0, 1))
        val m = Mat.zeros(r.height, r.width, CvType.CV_8UC1)
        Imgproc.fillPoly(m, listOf(MatOfPoint(*points.map { Point(it.x - r.x, it.y - r.y) }.toTypedArray())), Scalar(255.0))
        val mean = Core.mean(rgb.submat(r), m)
        m.release()
        val color = (0xFF shl 24) or (mean.`val`[0].toInt().coerceIn(0, 255) shl 16) or
            (mean.`val`[1].toInt().coerceIn(0, 255) shl 8) or mean.`val`[2].toInt().coerceIn(0, 255)
        return DetectedHold(
            Polygon(points.map { NPoint((it.x / w).toFloat().coerceIn(0f, 1f), (it.y / h).toFloat().coerceIn(0f, 1f)) }),
            color,
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
        const val TILE_NMS = 0.6
        const val EDGE = 4.0
        const val CUT_PENALTY = 0.5f
        const val MIN_AREA = 12.0
        const val SIMPLIFY = 0.8
        const val MERGE_IOU = 0.5
        const val MERGE_CONTAINED = 0.7
    }
}
