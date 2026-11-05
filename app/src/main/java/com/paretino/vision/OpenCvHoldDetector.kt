package com.paretino.vision

import android.graphics.Bitmap
import com.paretino.domain.NPoint
import com.paretino.domain.Polygon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Classical hold detector, tuned on DIY plywood walls and checked on commercial gym walls.
 *
 * 1. Wall background: the dominant colour cluster (plywood) averaged locally over pixels close to it,
 *    so wood grain, lighting gradients and dense hold clusters don't fool it. Walls with no dominant
 *    colour (multi-colour gym panels) use a large median filter instead.
 * 2. Foreground = Lab distance from that background (lightness down-weighted), thresholded at a level
 *    derived from the wall's own noise.
 * 3. Huge foreground regions are lighting zones (sun stripes, spotlights): their background is
 *    re-estimated with the median filter and the threshold re-run.
 * 4. Touching holds are split along colour edges with a marker watershed.
 * 5. Contours are filtered (size, solidity, thickness), de-nested, grown back by the pixels the blur
 *    and watershed ate, and simplified.
 *
 * The parameters mirror the Python prototype the algorithm was tuned with.
 */
@Singleton
class OpenCvHoldDetector @Inject constructor() : HoldDetector {

    override suspend fun detect(wall: Bitmap, sensitivity: Float): List<DetectedHold> = withContext(Dispatchers.Default) {
        val rgba = Mat()
        Utils.bitmapToMat(wall, rgba)
        val full = Mat().also { Imgproc.cvtColor(rgba, it, Imgproc.COLOR_RGBA2RGB) }
        rgba.release()
        val s = WORK.toDouble() / max(full.cols(), full.rows())
        val img = Mat()
        Imgproc.resize(full, img, Size((full.cols() * s).roundToInt().toDouble(), (full.rows() * s).roundToInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(img, img, Size(5.0, 5.0), 0.0)
        val lab = Mat().also { Imgproc.cvtColor(img, it, Imgproc.COLOR_RGB2Lab) }
        val w = lab.cols(); val h = lab.rows()
        val labPx = ByteArray(w * h * 3).also { lab.get(0, 0, it) }

        var bg = background(lab)
        var mask = threshold(labPx, bg, w, h, sensitivity)
        val zones = lightingZones(mask, w, h)
        if (zones != null) {
            bg = blendZones(lab, bg, zones, w, h)
            mask = threshold(labPx, bg, w, h, sensitivity)
            zones.release()
        }
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, ellipse(OPEN_K))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, ellipse(CLOSE_K))
        val split = splitTouching(img, lab, mask)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(split, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val area = (w * h).toDouble()
        val kept = contours.filter { c -> plausible(c, area) }
        val result = suppressContained(kept, w, h).map { c ->
            val grown = grow(c, GROW)
            val color = meanColor(img, grown)
            DetectedHold(simplify(grown, w, h), color)
        }
        listOf(full, img, lab, bg, mask, split).forEach { it.release() }
        result
    }

    override suspend fun detectAt(wall: Bitmap, point: NPoint): DetectedHold? = withContext(Dispatchers.Default) {
        val rgba = Mat()
        Utils.bitmapToMat(wall, rgba)
        val img = Mat().also { Imgproc.cvtColor(rgba, it, Imgproc.COLOR_RGBA2RGB) }
        rgba.release()
        val s = WORK.toDouble() / max(img.cols(), img.rows())
        Imgproc.resize(img, img, Size(img.cols() * s, img.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(img, img, Size(5.0, 5.0), 0.0)
        val lab = Mat().also { Imgproc.cvtColor(img, it, Imgproc.COLOR_RGB2Lab) }
        val w = lab.cols(); val h = lab.rows()
        val px = (point.x * w).toInt().coerceIn(0, w - 1)
        val py = (point.y * h).toInt().coerceIn(0, h - 1)
        // Flood fill from the tap inside a window around it: follows the hold's edge.
        val r = (max(w, h) * 0.06).toInt()
        val roi = Rect(max(0, px - r), max(0, py - r), 0, 0).let {
            Rect(it.x, it.y, min(w, px + r) - it.x, min(h, py + r) - it.y)
        }
        val sub = lab.submat(roi)
        val ffMask = Mat.zeros(roi.height + 2, roi.width + 2, CvType.CV_8UC1)
        val diff = Scalar(10.0, 7.0, 7.0)
        Imgproc.floodFill(
            sub.clone(), ffMask, Point((px - roi.x).toDouble(), (py - roi.y).toDouble()), Scalar(0.0), Rect(),
            diff, diff, 8 or Imgproc.FLOODFILL_MASK_ONLY or (255 shl 8),
        )
        val region = ffMask.submat(1, roi.height + 1, 1, roi.width + 1)
        val filled = Core.countNonZero(region).toDouble() / (roi.width * roi.height)
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(region.clone(), contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val best = contours.maxByOrNull { Imgproc.contourArea(it) }
        val hold = if (best == null || filled > 0.6 || Imgproc.contourArea(best) < 12) {
            // Flood leaked into the wall or found nothing: a plain circle the user can live with.
            val c = NPoint(px.toFloat() / w, py.toFloat() / h)
            DetectedHold(Polygon.circle(c, 0.015f, w.toFloat() / h), sampleColor(img, px, py))
        } else {
            val shifted = MatOfPoint(*best.toArray().map { Point(it.x + roi.x, it.y + roi.y) }.toTypedArray())
            DetectedHold(simplify(grow(shifted, 1), w, h), meanColor(img, shifted))
        }
        listOf(img, lab, ffMask).forEach { it.release() }
        hold
    }

    // --- background -------------------------------------------------------------------------

    private fun background(lab: Mat): Mat {
        val w = lab.cols(); val h = lab.rows()
        val small = Mat()
        Imgproc.resize(lab, small, Size((w / BG_DIV).toDouble(), (h / BG_DIV).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val sw = small.cols(); val sh = small.rows(); val n = sw * sh
        val sb = ByteArray(n * 3).also { small.get(0, 0, it) }
        val sf = FloatArray(n * 3) { (sb[it].toInt() and 0xFF).toFloat() }
        val ks = max(3, (max(sw, sh) * BG_K).toInt() or 1)

        val samples = Mat(n, 3, CvType.CV_32F)
        samples.put(0, 0, FloatArray(n * 3) { i -> if (i % 3 == 0) sf[i] * W_L else sf[i] })
        val labels = Mat(); val centers = Mat()
        Core.setRNGSeed(1)
        Core.kmeans(samples, K, labels, TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 20, 1.0), 2, Core.KMEANS_PP_CENTERS, centers)
        val lab32 = IntArray(n).also { labels.get(0, 0, it) }
        val counts = IntArray(K).also { c -> lab32.forEach { c[it]++ } }
        val dom = counts.indices.maxBy { counts[it] }
        val frac = counts[dom].toFloat() / n
        samples.release(); labels.release()

        val bgSmall: Mat
        if (frac >= DOM_FRAC) {
            val c = FloatArray(3).also { centers.get(dom, 0, it) }
            c[0] /= W_L
            val dd = FloatArray(n) { i -> wdist(sf[i * 3] - c[0], sf[i * 3 + 1] - c[1], sf[i * 3 + 2] - c[2]) }
            var sum = 0f; var cnt = 0
            for (i in 0 until n) if (lab32[i] == dom) { sum += dd[i]; cnt++ }
            val nearT = (sum / max(cnt, 1) * NEAR_MULT).coerceIn(NEAR_MIN, NEAR_MAX)
            val near = FloatArray(n) { if (dd[it] < nearT) 1f else 0f }
            val num = Mat(sh, sw, CvType.CV_32FC3).apply { put(0, 0, FloatArray(n * 3) { sf[it] * near[it / 3] }) }
            val den = Mat(sh, sw, CvType.CV_32FC1).apply { put(0, 0, near) }
            Imgproc.blur(num, num, Size(ks.toDouble(), ks.toDouble()))
            Imgproc.blur(den, den, Size(ks.toDouble(), ks.toDouble()))
            val nb = FloatArray(n * 3).also { num.get(0, 0, it) }
            val db = FloatArray(n).also { den.get(0, 0, it) }
            val out = FloatArray(n * 3) { i ->
                val d = db[i / 3]
                if (d > 0.02f) nb[i] / d else c[i % 3]
            }
            bgSmall = Mat(sh, sw, CvType.CV_32FC3).apply { put(0, 0, out) }
            num.release(); den.release()
        } else {
            bgSmall = medianBackground(small, ks)
        }
        centers.release(); small.release()
        val bg = Mat()
        Imgproc.resize(bgSmall, bg, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        bgSmall.release()
        return bg
    }

    private fun medianBackground(small: Mat, ks: Int): Mat {
        val med = Mat()
        Imgproc.medianBlur(small, med, ks)
        return Mat().also { med.convertTo(it, CvType.CV_32FC3); med.release() }
    }

    /** Foreground mask: weighted Lab distance to the background over a noise-derived threshold. */
    private fun threshold(labPx: ByteArray, bg: Mat, w: Int, h: Int, sensitivity: Float): Mat {
        val n = w * h
        val bgPx = FloatArray(n * 3).also { bg.get(0, 0, it) }
        val d8 = ByteArray(n)
        val hist = IntArray(256)
        for (i in 0 until n) {
            val o = i * 3
            val dist = wdist(
                (labPx[o].toInt() and 0xFF) - bgPx[o],
                (labPx[o + 1].toInt() and 0xFF) - bgPx[o + 1],
                (labPx[o + 2].toInt() and 0xFF) - bgPx[o + 2],
            )
            val v = (dist * 2).toInt().coerceIn(0, 255)
            d8[i] = v.toByte(); hist[v]++
        }
        var acc = 0; var median = 0
        while (median < 255 && acc + hist[median] < n / 2) { acc += hist[median]; median++ }
        val dm = Mat(h, w, CvType.CV_8UC1).apply { put(0, 0, d8) }
        val mask = Mat()
        val otsu = Imgproc.threshold(dm, mask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        val t = min(otsu, median * NOISE_MULT + NOISE_ADD * 2).coerceIn(T_MIN * 2, T_MAX * 2) / sensitivity
        Imgproc.threshold(dm, mask, t, 255.0, Imgproc.THRESH_BINARY)
        dm.release()
        return mask
    }

    /** Regions too big to be holds are lighting zones; returns them dilated, or null if none. */
    private fun lightingZones(mask: Mat, w: Int, h: Int): Mat? {
        val cc = Mat(); val stats = Mat(); val cent = Mat()
        val n = Imgproc.connectedComponentsWithStats(mask, cc, stats, cent, 8, CvType.CV_32S)
        val big = BooleanArray(n) { it > 0 && stats.get(it, Imgproc.CC_STAT_AREA)[0] > ZONE_FRAC * w * h }
        if (big.none { it }) { cc.release(); stats.release(); cent.release(); return null }
        val labels = IntArray(w * h).also { cc.get(0, 0, it) }
        val zone = Mat(h, w, CvType.CV_8UC1).apply { put(0, 0, ByteArray(w * h) { if (big[labels[it]]) 1 else 0 }) }
        Imgproc.dilate(zone, zone, Mat.ones(15, 15, CvType.CV_8UC1))
        cc.release(); stats.release(); cent.release()
        return zone
    }

    private fun blendZones(lab: Mat, bg: Mat, zone: Mat, w: Int, h: Int): Mat {
        val small = Mat()
        Imgproc.resize(lab, small, Size((w / BG_DIV).toDouble(), (h / BG_DIV).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val ks = max(3, (max(small.cols(), small.rows()) * BG_K).toInt() or 1)
        val medSmall = medianBackground(small, ks)
        val med = Mat()
        Imgproc.resize(medSmall, med, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val zf = Mat().also { zone.convertTo(it, CvType.CV_32FC1) }
        Imgproc.GaussianBlur(zf, zf, Size(0.0, 0.0), 6.0)
        val z = FloatArray(w * h).also { zf.get(0, 0, it) }
        val b = FloatArray(w * h * 3).also { bg.get(0, 0, it) }
        val m = FloatArray(w * h * 3).also { med.get(0, 0, it) }
        for (i in b.indices) { val a = z[i / 3]; b[i] = b[i] * (1 - a) + m[i] * a }
        val out = Mat(h, w, CvType.CV_32FC3).apply { put(0, 0, b) }
        listOf(small, medSmall, med, zf, bg).forEach { it.release() }
        return out
    }

    // --- segmentation -------------------------------------------------------------------------

    /** Separate touching holds along colour edges: markers = mask minus Canny edges, then watershed. */
    private fun splitTouching(img: Mat, lab: Mat, mask: Mat): Mat {
        val w = mask.cols(); val h = mask.rows(); val n = w * h
        val channels = ArrayList<Mat>().also { Core.split(lab, it) }
        val edges = Mat.zeros(h, w, CvType.CV_8UC1)
        val e = Mat()
        for (ch in channels) { Imgproc.Canny(ch, e, CANNY_LO, CANNY_HI); Core.bitwise_or(edges, e, edges); ch.release() }
        val k3 = ellipse(3)
        Imgproc.dilate(edges, edges, k3)
        val core = Mat()
        Core.bitwise_not(edges, edges)
        Core.bitwise_and(mask, edges, core)
        Imgproc.erode(core, core, k3)

        val cc = Mat(); val stats = Mat(); val cent = Mat()
        val nSeeds = Imgproc.connectedComponentsWithStats(core, cc, stats, cent, 4, CvType.CV_32S)
        val minSeed = MIN_AREA * n * SEED_FRAC
        val ids = IntArray(nSeeds)
        var next = 2
        for (i in 1 until nSeeds) if (stats.get(i, Imgproc.CC_STAT_AREA)[0] >= minSeed) ids[i] = next++
        val seedLabels = IntArray(n).also { cc.get(0, 0, it) }
        val markers = IntArray(n) { ids[seedLabels[it]] }

        // Mask blobs that got no seed (small or edge-cut holds) become their own marker.
        val mcc = Mat()
        val nm = Imgproc.connectedComponents(mask, mcc, 8, CvType.CV_32S)
        val maskLabels = IntArray(n).also { mcc.get(0, 0, it) }
        val seeded = BooleanArray(nm)
        for (i in 0 until n) if (markers[i] > 0) seeded[maskLabels[i]] = true
        val maskPx = ByteArray(n).also { mask.get(0, 0, it) }
        val dil = Mat().also { Imgproc.dilate(mask, it, k3, Point(-1.0, -1.0), 2) }
        val dilPx = ByteArray(n).also { dil.get(0, 0, it) }
        for (i in 0 until n) {
            if (maskPx[i].toInt() != 0 && !seeded[maskLabels[i]]) markers[i] = maskLabels[i] + next
            if (dilPx[i].toInt() == 0) markers[i] = 1
        }
        val markerMat = Mat(h, w, CvType.CV_32SC1).apply { put(0, 0, markers) }
        Imgproc.watershed(img, markerMat)
        markerMat.get(0, 0, markers)

        val boundary = Mat(h, w, CvType.CV_8UC1).apply { put(0, 0, ByteArray(n) { if (markers[it] == -1) 1 else 0 }) }
        Imgproc.dilate(boundary, boundary, Imgproc.getStructuringElement(Imgproc.MORPH_CROSS, Size(3.0, 3.0)))
        val bPx = ByteArray(n).also { boundary.get(0, 0, it) }
        val out = Mat(h, w, CvType.CV_8UC1).apply {
            put(0, 0, ByteArray(n) { if (markers[it] > 1 && maskPx[it].toInt() != 0 && bPx[it].toInt() == 0) -1 else 0 })
        }
        listOf(edges, e, core, cc, stats, cent, mcc, dil, markerMat, boundary).forEach { it.release() }
        return out
    }

    private fun plausible(c: MatOfPoint, imageArea: Double): Boolean {
        val a = Imgproc.contourArea(c)
        if (a < MIN_AREA * imageArea || a > MAX_AREA * imageArea) return false
        val hullIdx = MatOfInt().also { Imgproc.convexHull(c, it) }
        val pts = c.toArray()
        val hull = MatOfPoint(*hullIdx.toArray().map { pts[it] }.toTypedArray())
        val hullArea = Imgproc.contourArea(hull)
        if (hullArea <= 0 || a / hullArea < MIN_SOLIDITY) return false
        val rect = Imgproc.minAreaRect(MatOfPoint2f(*pts))
        val long = max(rect.size.width, rect.size.height).coerceAtLeast(1.0)
        val short = min(rect.size.width, rect.size.height)
        return a / long >= MIN_THICK * WORK && short / long >= MIN_ASPECT
    }

    /** Drops a contour when most of its pixels are already covered by a larger kept one. */
    private fun suppressContained(contours: List<MatOfPoint>, w: Int, h: Int): List<MatOfPoint> {
        val cover = Mat.zeros(h, w, CvType.CV_8UC1)
        val kept = ArrayList<MatOfPoint>()
        for (c in contours.sortedByDescending { Imgproc.contourArea(it) }) {
            val r = Imgproc.boundingRect(c)
            val m = Mat.zeros(r.height, r.width, CvType.CV_8UC1)
            Imgproc.drawContours(m, listOf(c), -1, Scalar(255.0), -1, Imgproc.LINE_8, Mat(), Int.MAX_VALUE, Point(-r.x.toDouble(), -r.y.toDouble()))
            val total = Core.countNonZero(m)
            val overlap = Mat().also { Core.bitwise_and(m, cover.submat(r), it) }
            if (total > 0 && Core.countNonZero(overlap) <= CONTAIN * total) {
                kept += c
                val sub = cover.submat(r)
                Core.bitwise_or(sub, m, sub)
            }
            m.release(); overlap.release()
        }
        cover.release()
        return kept
    }

    /** Dilates one contour by [r] px to give back the edge eaten by blur, threshold and watershed lines. */
    private fun grow(c: MatOfPoint, r: Int): MatOfPoint {
        if (r <= 0) return c
        val b = Imgproc.boundingRect(c)
        val ox = b.x - r - 1; val oy = b.y - r - 1
        val m = Mat.zeros(b.height + 2 * r + 2, b.width + 2 * r + 2, CvType.CV_8UC1)
        Imgproc.drawContours(m, listOf(c), -1, Scalar(255.0), -1, Imgproc.LINE_8, Mat(), Int.MAX_VALUE, Point(-ox.toDouble(), -oy.toDouble()))
        Imgproc.dilate(m, m, ellipse(2 * r + 1))
        val cs = ArrayList<MatOfPoint>()
        Imgproc.findContours(m, cs, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        m.release()
        val best = cs.maxByOrNull { Imgproc.contourArea(it) } ?: return c
        return MatOfPoint(*best.toArray().map { Point(it.x + ox, it.y + oy) }.toTypedArray())
    }

    private fun simplify(c: MatOfPoint, w: Int, h: Int): Polygon {
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(MatOfPoint2f(*c.toArray()), approx, EPS * WORK, true)
        val pts = approx.toArray().takeIf { it.size >= 3 } ?: c.toArray()
        return Polygon(pts.map { NPoint((it.x / w).toFloat().coerceIn(0f, 1f), (it.y / h).toFloat().coerceIn(0f, 1f)) })
    }

    private fun meanColor(img: Mat, c: MatOfPoint): Int {
        val r = Imgproc.boundingRect(c).intersect(img.cols(), img.rows())
        if (r.width <= 0 || r.height <= 0) return 0xFF808080.toInt()
        val m = Mat.zeros(r.height, r.width, CvType.CV_8UC1)
        Imgproc.drawContours(m, listOf(c), -1, Scalar(255.0), -1, Imgproc.LINE_8, Mat(), Int.MAX_VALUE, Point(-r.x.toDouble(), -r.y.toDouble()))
        val mean = Core.mean(img.submat(r), m)
        m.release()
        return argb(mean.`val`[0], mean.`val`[1], mean.`val`[2])
    }

    private fun sampleColor(img: Mat, x: Int, y: Int): Int {
        val v = img.get(y, x)
        return argb(v[0], v[1], v[2])
    }

    private fun Rect.intersect(w: Int, h: Int): Rect {
        val x0 = max(0, x); val y0 = max(0, y)
        return Rect(x0, y0, min(w, x + width) - x0, min(h, y + height) - y0)
    }

    private fun argb(r: Double, g: Double, b: Double) =
        (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)

    private fun ellipse(k: Int) = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))

    private fun wdist(dl: Float, da: Float, db: Float): Float = sqrt((W_L * dl) * (W_L * dl) + da * da + db * db)

    private companion object {
        const val WORK = 1400
        const val BG_DIV = 4
        const val BG_K = 0.14
        const val K = 6
        const val DOM_FRAC = 0.30f
        const val NEAR_MULT = 2.0f
        const val NEAR_MIN = 12f
        const val NEAR_MAX = 30f
        const val W_L = 0.6f
        const val T_MIN = 10.0
        const val T_MAX = 45.0
        const val NOISE_MULT = 2.5
        const val NOISE_ADD = 6.0
        const val ZONE_FRAC = 0.01
        const val OPEN_K = 3
        const val CLOSE_K = 5
        const val CANNY_LO = 25.0
        const val CANNY_HI = 60.0
        const val SEED_FRAC = 0.3
        const val MIN_AREA = 0.00006
        const val MAX_AREA = 0.06
        const val MIN_SOLIDITY = 0.35
        const val MIN_THICK = 0.003
        const val MIN_ASPECT = 0.1
        const val CONTAIN = 0.6
        const val GROW = 2
        const val EPS = 0.003
    }
}
