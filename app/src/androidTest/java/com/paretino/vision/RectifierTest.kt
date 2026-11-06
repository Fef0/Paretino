package com.paretino.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paretino.domain.NPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RectifierTest {

    /** A skewed quad with one colour per corner must come out straight, each colour in its own corner. */
    @Test
    fun straightensSkewedQuad() = runBlocking {
        val corners = listOf(NPoint(0.2f, 0.1f), NPoint(0.9f, 0.25f), NPoint(0.75f, 0.9f), NPoint(0.1f, 0.7f))
        val colours = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        val photo = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(photo).apply { drawColor(Color.BLACK) }
        val paint = Paint()
        corners.zip(colours).forEach { (c, colour) ->
            paint.color = colour
            canvas.drawCircle(c.x * 800, c.y * 600, 60f, paint)
        }
        val out = Rectifier.rectify(photo, corners, 400, 300)
        assertEquals(400, out.width)
        assertEquals(300, out.height)
        assertEquals(Color.RED, out.getPixel(6, 6))
        assertEquals(Color.GREEN, out.getPixel(393, 6))
        assertEquals(Color.BLUE, out.getPixel(393, 293))
        assertEquals(Color.YELLOW, out.getPixel(6, 293))
        assertEquals(Color.BLACK, out.getPixel(200, 150))
    }
}
