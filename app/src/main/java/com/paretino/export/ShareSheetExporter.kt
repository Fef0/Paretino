package com.paretino.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/** Shares a path as a picture of the wall with its holds outlined, plus the JSON for other tools. */
@Singleton
class ShareSheetExporter @Inject constructor(@ApplicationContext private val context: Context) {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    suspend fun shareIntent(dto: PathExportDto, wall: Bitmap): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { deleteRecursively(); mkdirs() }
        val base = dto.title.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { "path" }
        val jsonFile = File(dir, "$base.json").apply { writeText(json.encodeToString(PathExportDto.serializer(), dto)) }
        val pngFile = File(dir, "$base.png")
        val image = render(wall, dto)
        pngFile.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        val authority = "${context.packageName}.files"
        val uris = arrayListOf(
            FileProvider.getUriForFile(context, authority, pngFile),
            FileProvider.getUriForFile(context, authority, jsonFile),
        )
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_TEXT, summary(dto))
            clipData = ClipData.newRawUri(null, uris[0]).apply { addItem(ClipData.Item(uris[1])) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send, dto.title)
    }

    private fun summary(dto: PathExportDto) = buildString {
        append(dto.title)
        dto.grade?.let { append(" · $it") }
        if (dto.setter.isNotBlank()) append("\nSet by ${dto.setter}")
        if (dto.rules.isNotBlank()) append("\n${dto.rules}")
        if (dto.description.isNotBlank()) append("\n\n${dto.description}")
    }

    companion object {
        /** Draws the path the way the app shows it: dimmed wall, outlined holds, START/FINISH tags. */
        fun render(wall: Bitmap, dto: PathExportDto): Bitmap {
            val out = wall.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(out)
            val w = out.width.toFloat(); val h = out.height.toFloat()
            val unit = max(w, h) / 1000f
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
            val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 11 * unit; isFakeBoldText = true; textAlign = Paint.Align.CENTER
            }
            for (hold in dto.holds) {
                val pts = hold.outline
                if (pts.size < 6) continue
                val path = Path().apply {
                    moveTo(pts[0] * w, pts[1] * h)
                    for (i in 2 until pts.size step 2) lineTo(pts[i] * w, pts[i + 1] * h)
                    close()
                }
                stroke.color = Color.BLACK; stroke.alpha = 120; stroke.strokeWidth = 6 * unit
                c.drawPath(path, stroke)
                stroke.color = Color.parseColor(hold.color); stroke.alpha = 255; stroke.strokeWidth = 3.5f * unit
                c.drawPath(path, stroke)
                val tag = hold.role.tag ?: hold.label.ifBlank { null } ?: continue
                val top = (1 until pts.size step 2).minOf { pts[it] } * h
                val cx = (0 until pts.size step 2).map { pts[it] }.average().toFloat() * w
                val tw = text.measureText(tag) + 10 * unit
                val r = RectF(cx - tw / 2, top - 22 * unit, cx + tw / 2, top - 6 * unit)
                tagPaint.color = Color.parseColor(hold.color)
                c.drawRoundRect(r, 3 * unit, 3 * unit, tagPaint)
                c.drawText(tag, cx, r.bottom - 4.5f * unit, text)
            }
            return out
        }
    }
}
