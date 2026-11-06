package com.paretino.vision

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the shipped model on photos pushed to /data/local/tmp/paretino and writes the outlines to the app's files dir,
 * so ml/scripts/check_parity.py can compare them with the Python reference.
 */
@RunWith(AndroidJUnit4::class)
class HoldDetectionParityTest {

    @Test
    fun detectsHoldsOnPushedPhotos() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val detector = YoloHoldDetector(context, OpenCvHoldDetector())
        val listing = shell("ls /data/local/tmp/paretino").decodeToString().lines().filter { it.endsWith(".jpg") }
        assertTrue("no photos pushed", listing.isNotEmpty())
        val out = File(context.filesDir, "parity").apply { mkdirs() }
        for (name in listing) {
            val bytes = shell("cat /data/local/tmp/paretino/$name")
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val start = System.currentTimeMillis()
            val holds = detector.detect(bitmap)
            val ms = System.currentTimeMillis() - start
            Log.i("ParetinoParity", "$name ${bitmap.width}x${bitmap.height} holds=${holds.size} ms=$ms")
            File(out, "$name.txt").writeText(
                "ms $ms\n" + holds.joinToString("\n") { h -> h.outline.points.joinToString(" ") { "${it.x},${it.y}" } },
            )
            assertTrue("$name: no holds found", holds.isNotEmpty())
        }
    }

    private fun shell(command: String): ByteArray {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
}
