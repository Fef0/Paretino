package com.paretino.vision

import android.graphics.Bitmap
import com.paretino.domain.NPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

/** Perspective correction: maps the four wall corners on a photo to a straight wall image. */
object Rectifier {

    /** Output size for a wall of the given dimensions, longest side [maxSide] px. */
    fun outputSize(widthCm: Int, heightCm: Int, maxSide: Int = 2000): Pair<Int, Int> =
        if (widthCm >= heightCm) maxSide to (maxSide.toFloat() * heightCm / widthCm).roundToInt()
        else (maxSide.toFloat() * widthCm / heightCm).roundToInt() to maxSide

    /** [corners] are normalized to [src], ordered top-left, top-right, bottom-right, bottom-left. */
    suspend fun rectify(src: Bitmap, corners: List<NPoint>, outW: Int, outH: Int): Bitmap = withContext(Dispatchers.Default) {
        require(corners.size == 4)
        val m = Mat().also { Utils.bitmapToMat(src, it) }
        val from = MatOfPoint2f(*corners.map { Point(it.x.toDouble() * src.width, it.y.toDouble() * src.height) }.toTypedArray())
        val to = MatOfPoint2f(Point(0.0, 0.0), Point(outW.toDouble(), 0.0), Point(outW.toDouble(), outH.toDouble()), Point(0.0, outH.toDouble()))
        val h = Imgproc.getPerspectiveTransform(from, to)
        val out = Mat()
        Imgproc.warpPerspective(m, out, h, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_LINEAR)
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, bmp)
        listOf(m, out, h).forEach { it.release() }
        bmp
    }

    /**
     * Best-effort guess of the wall corners. The background (back wall, floor, mats) touches the photo's
     * edges while the board, photographed whole, does not: drop every colour region touching the border
     * and the largest blob left is the board. Null when nothing convincing is found.
     */
    suspend fun findWallQuad(src: Bitmap): List<NPoint>? = withContext(Dispatchers.Default) {
        val rgba = Mat().also { Utils.bitmapToMat(src, it) }
        val s = 400.0 / max(src.width, src.height)
        val small = Mat()
        Imgproc.resize(rgba, small, Size(src.width * s, src.height * s), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.cvtColor(small, small, Imgproc.COLOR_RGBA2RGB)
        Imgproc.GaussianBlur(small, small, Size(7.0, 7.0), 0.0)
        val lab = Mat().also { Imgproc.cvtColor(small, it, Imgproc.COLOR_RGB2Lab) }
        val w = lab.cols(); val h = lab.rows(); val n = w * h
        val px = ByteArray(n * 3).also { lab.get(0, 0, it) }
        val samples = Mat(n, 3, CvType.CV_32F).apply {
            // Lightness weighted down: sun and shade on the same surface stay one cluster.
            put(0, 0, FloatArray(n * 3) { i -> (px[i].toInt() and 0xFF) * if (i % 3 == 0) 0.35f else 1f })
        }
        val labels = Mat(); val centers = Mat()
        Core.setRNGSeed(1)
        Core.kmeans(samples, CLUSTERS, labels, TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 20, 1.0), 2, Core.KMEANS_PP_CENTERS, centers)
        val l = IntArray(n).also { labels.get(0, 0, it) }

        val background = ByteArray(n)
        val open3 = Mat.ones(3, 3, CvType.CV_8UC1)
        for (c in 0 until CLUSTERS) {
            val m = Mat(h, w, CvType.CV_8UC1).apply { put(0, 0, ByteArray(n) { if (l[it] == c) -1 else 0 }) }
            Imgproc.morphologyEx(m, m, Imgproc.MORPH_OPEN, open3)
            val cc = Mat(); val st = Mat(); val ce = Mat()
            val k = Imgproc.connectedComponentsWithStats(m, cc, st, ce, 8, CvType.CV_32S)
            val border = BooleanArray(k) { i ->
                if (i == 0) return@BooleanArray false
                val x = st.get(i, Imgproc.CC_STAT_LEFT)[0].toInt(); val y = st.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
                val bw = st.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt(); val bh = st.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val touches = x == 0 || y == 0 || x + bw >= w || y + bh >= h
                touches && st.get(i, Imgproc.CC_STAT_AREA)[0] > 0.01 * n
            }
            val lab32 = IntArray(n).also { cc.get(0, 0, it) }
            for (i in 0 until n) if (border[lab32[i]]) background[i] = 1
            listOf(m, cc, st, ce).forEach { it.release() }
        }
        val fg = Mat(h, w, CvType.CV_8UC1).apply { put(0, 0, ByteArray(n) { if (background[it].toInt() == 0) -1 else 0 }) }
        Imgproc.morphologyEx(fg, fg, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0)))
        Imgproc.morphologyEx(fg, fg, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0)))
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(fg, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val result = contours.maxByOrNull { Imgproc.contourArea(it) }
            ?.takeIf { Imgproc.contourArea(it) in 0.12 * n..0.97 * n }
            ?.let { c -> quad(c).takeIf { q -> Imgproc.contourArea(c) / polygonArea(q) > 0.85 } }
            ?.map { NPoint((it.x / w).toFloat().coerceIn(0f, 1f), (it.y / h).toFloat().coerceIn(0f, 1f)) }
        listOf(rgba, small, lab, samples, labels, centers, fg).forEach { it.release() }
        result
    }

    private fun polygonArea(p: List<Point>): Double {
        var a = 0.0
        for (i in p.indices) { val q = p[(i + 1) % p.size]; a += p[i].x * q.y - q.x * p[i].y }
        return kotlin.math.abs(a) / 2
    }

    private const val CLUSTERS = 5

    /** Four corners of a region: convex hull simplified until it has 4 vertices, else its min-area rectangle. */
    private fun quad(c: MatOfPoint): List<Point> {
        val hullIdx = org.opencv.core.MatOfInt().also { Imgproc.convexHull(c, it) }
        val pts = c.toArray()
        val hull = MatOfPoint2f(*hullIdx.toArray().map { pts[it] }.toTypedArray())
        val peri = Imgproc.arcLength(hull, true)
        var eps = 0.01
        while (eps < 0.2) {
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(hull, approx, eps * peri, true)
            if (approx.rows() == 4) return order(approx.toArray().toList())
            if (approx.rows() < 4) break
            eps += 0.01
        }
        val box = Array(4) { Point() }
        Imgproc.minAreaRect(hull).points(box)
        return order(box.toList())
    }

    /** Orders corners top-left, top-right, bottom-right, bottom-left. */
    fun order(p: List<Point>): List<Point> {
        val tl = p.minBy { it.x + it.y }
        val br = p.maxBy { it.x + it.y }
        val tr = p.maxBy { it.x - it.y }
        val bl = p.minBy { it.x - it.y }
        return listOf(tl, tr, br, bl)
    }
}
