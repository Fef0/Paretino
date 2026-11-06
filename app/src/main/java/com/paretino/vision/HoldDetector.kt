package com.paretino.vision

import android.graphics.Bitmap
import com.paretino.domain.NPoint
import com.paretino.domain.Polygon

/** A hold found on a rectified wall photo: its outline and dominant colour. */
data class DetectedHold(val outline: Polygon, val colorArgb: Int)

/**
 * Finds holds on a perspective-corrected wall photo. Coordinates are normalized to the bitmap.
 */
interface HoldDetector {
    /** [sensitivity] 1 is the tuned default; higher finds fainter holds, lower only clear ones. */
    suspend fun detect(wall: Bitmap, sensitivity: Float = 1f): List<DetectedHold>

    /** Outlines the hold under [point] when the user taps a missed one, or marks the spot if none is found. */
    suspend fun detectAt(wall: Bitmap, point: NPoint): DetectedHold
}
