package com.paretino.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.paretino.domain.NPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val from = corners.flatMap { listOf(it.x * src.width, it.y * src.height) }.toFloatArray()
        val to = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())
        val transform = Matrix()
        require(transform.setPolyToPoly(from, 0, to, 0, 4)) { "The wall corners do not form a usable shape" }
        Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(src, transform, Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }
}
