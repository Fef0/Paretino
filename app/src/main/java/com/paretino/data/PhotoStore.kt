package com.paretino.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/** Wall photos in app-private storage. The database keeps names relative to [dir]. */
@Singleton
class PhotoStore @Inject constructor(@ApplicationContext private val context: Context) {

    val dir: File get() = File(context.filesDir, "photos").apply { mkdirs() }

    fun file(name: String) = File(dir, name)

    /** Copies a picked or captured image in, upright and at most [MAX_SIDE] px. */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = decodeUpright(uri, MAX_SIDE)
        save(bitmap).also { bitmap.recycle() }
    }

    suspend fun save(bitmap: Bitmap, quality: Int = 92): String = withContext(Dispatchers.IO) {
        val name = "${UUID.randomUUID()}.jpg"
        file(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        name
    }

    suspend fun load(name: String, maxSide: Int = MAX_SIDE): Bitmap = withContext(Dispatchers.IO) {
        val f = file(name)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        BitmapFactory.decodeFile(f.path, opts) ?: error("Cannot read photo $name")
    }

    fun delete(vararg names: String) = names.forEach { file(it).delete() }

    private fun decodeUpright(uri: Uri, maxSide: Int): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        val raw = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Not an image")
        val rotation = resolver.openInputStream(uri).use { stream ->
            stream?.let { ExifInterface(it).rotationDegrees } ?: 0
        }
        val scale = minOf(1f, maxSide.toFloat() / max(raw.width, raw.height))
        if (rotation == 0 && scale == 1f) return raw
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }

    private fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= maxSide) s *= 2
        return s
    }

    companion object {
        const val MAX_SIDE = 3000
    }
}
