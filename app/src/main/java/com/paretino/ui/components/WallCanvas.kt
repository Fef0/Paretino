package com.paretino.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paretino.domain.NPoint
import com.paretino.domain.Polygon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

enum class OverlayStyle {
    /** A hold of the route: thick outline in the hold's colour. */
    Selected,
    /** Every other hold of the master while editing: thin, translucent. */
    Faint,
    /** Diff and "removed" marks. */
    Dashed,
}

data class OverlayHold(
    val key: Long,
    val outline: Polygon,
    val color: Color,
    val style: OverlayStyle = OverlayStyle.Selected,
    val tag: String? = null,
    val label: String? = null,
)

/**
 * A rectified wall photo with hold outlines on top. Fits the photo inside the available space; when
 * [zoomable], pinch to zoom and drag to pan. Taps report wall coordinates (0..1).
 */
@Composable
fun WallCanvas(
    image: ImageBitmap?,
    holds: List<OverlayHold>,
    modifier: Modifier = Modifier,
    zoomable: Boolean = true,
    dimWall: Boolean = false,
    onTap: ((NPoint) -> Unit)? = null,
    onLongPress: ((NPoint) -> Unit)? = null,
    extra: (DrawScope.(toScreen: (NPoint) -> Offset, zoom: Float) -> Unit)? = null,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val tagStyle = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    val labelStyle = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    val placeholder = MaterialTheme.colorScheme.surfaceContainerHigh

    BoxWithConstraints(modifier.background(Color.Black.copy(alpha = 0.04f))) {
        val box = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val fit = fitRect(image?.let { IntSize(it.width, it.height) }, box)

        fun toScreen(p: NPoint): Offset {
            val raw = Offset(fit.left + p.x * fit.width, fit.top + p.y * fit.height)
            val c = Offset(box.width / 2, box.height / 2)
            return c + (raw - c) * scale + offset
        }

        fun toWall(screen: Offset): NPoint {
            val c = Offset(box.width / 2, box.height / 2)
            val raw = c + (screen - c - offset) / scale
            return NPoint((raw.x - fit.left) / fit.width, (raw.y - fit.top) / fit.height)
        }

        var gestures: Modifier = Modifier
        if (zoomable) {
            gestures = gestures.pointerInput(box) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val multi = event.changes.size > 1
                        if (multi || scale > 1f) {
                            val newScale = (scale * zoom).coerceIn(1f, 8f)
                            val maxX = box.width * (newScale - 1) / 2
                            val maxY = box.height * (newScale - 1) / 2
                            offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                            scale = newScale
                            if (multi || pan != Offset.Zero) event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
        }
        if (onTap != null || onLongPress != null || zoomable) {
            gestures = gestures.pointerInput(box, onTap, onLongPress) {
                detectTapGestures(
                    onTap = { pos -> onTap?.invoke(toWall(pos)) },
                    onLongPress = { pos -> onLongPress?.invoke(toWall(pos)) },
                    onDoubleTap = if (zoomable) { pos ->
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else {
                            scale = 2.5f
                            val c = Offset(box.width / 2, box.height / 2)
                            offset = (c - pos) * 1.5f
                        }
                    } else null,
                )
            }
        }

        Canvas(Modifier.fillMaxSize().then(gestures)) {
            clipRect {
                withTransform({
                    translate(offset.x, offset.y)
                    scale(scale, scale, pivot = Offset(size.width / 2, size.height / 2))
                }) {
                    if (image != null) {
                        drawImage(
                            image,
                            dstOffset = androidx.compose.ui.unit.IntOffset(fit.left.toInt(), fit.top.toInt()),
                            dstSize = IntSize(fit.width.toInt(), fit.height.toInt()),
                        )
                        if (dimWall) drawRect(Color.Black.copy(alpha = 0.35f), Offset(fit.left, fit.top), Size(fit.width, fit.height))
                    } else {
                        drawRect(placeholder, Offset(fit.left, fit.top), Size(fit.width, fit.height))
                    }
                }
                // Outlines are drawn in screen space so lines stay crisp and the same width at any zoom.
                val unit = 1.dp.toPx()
                for (h in holds) drawHold(h, ::toScreen, unit)
                for (h in holds) {
                    val text = h.tag ?: h.label ?: continue
                    val pts = h.outline.points.map(::toScreen)
                    val top = pts.minOf { it.y }
                    val cx = (pts.minOf { it.x } + pts.maxOf { it.x }) / 2
                    val layout = measurer.measure(text, if (h.tag != null) tagStyle else labelStyle)
                    val w = layout.size.width + 8 * unit
                    val hgt = layout.size.height + 2 * unit
                    val tl = Offset(cx - w / 2, top - hgt - 3 * unit)
                    drawRoundRect(h.color, tl, Size(w, hgt), androidx.compose.ui.geometry.CornerRadius(3 * unit))
                    drawText(layout, topLeft = tl + Offset(4 * unit, unit))
                }
                extra?.invoke(this, ::toScreen, scale)
            }
        }
    }
}

private fun DrawScope.drawHold(h: OverlayHold, toScreen: (NPoint) -> Offset, unit: Float) {
    if (h.outline.points.size < 3) return
    val path = Path().apply {
        val pts = h.outline.points.map(toScreen)
        moveTo(pts[0].x, pts[0].y)
        for (p in pts.drop(1)) lineTo(p.x, p.y)
        close()
    }
    when (h.style) {
        OverlayStyle.Selected -> {
            drawPath(path, h.color.copy(alpha = 0.18f))
            drawPath(path, Color.Black.copy(alpha = 0.45f), style = Stroke(4.5f * unit, join = StrokeJoin.Round))
            drawPath(path, h.color, style = Stroke(2.5f * unit, join = StrokeJoin.Round))
        }
        OverlayStyle.Faint -> {
            drawPath(path, Color.Black.copy(alpha = 0.3f), style = Stroke(2f * unit, join = StrokeJoin.Round))
            drawPath(path, h.color.copy(alpha = 0.75f), style = Stroke(1f * unit, join = StrokeJoin.Round))
        }
        OverlayStyle.Dashed -> {
            drawPath(path, h.color.copy(alpha = 0.25f))
            drawPath(path, h.color, style = Stroke(2f * unit, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5 * unit, 4 * unit))))
        }
    }
}

data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float)

/** The rectangle an image of [image] size takes when fitted (letterboxed) into [box]. */
fun fitRect(image: IntSize?, box: Size): FitRect {
    if (image == null || image.width == 0 || box.width == 0f) return FitRect(0f, 0f, box.width, box.height)
    val s = min(box.width / image.width, box.height / image.height)
    val w = image.width * s; val h = image.height * s
    return FitRect((box.width - w) / 2, (box.height - h) / 2, w, h)
}

// --- photo loading ------------------------------------------------------------------------------

private val photoCache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

fun photoFile(context: Context, name: String) = File(File(context.filesDir, "photos"), name)

/** Decodes a stored photo at most [maxSide] px, cached in memory. Null while loading. */
@Composable
fun rememberPhoto(name: String?, maxSide: Int = 2000): ImageBitmap? {
    val context = LocalContext.current
    val key = "$name@$maxSide"
    return produceState(photoCache.get(key), key) {
        if (name == null) { value = null; return@produceState }
        value = photoCache.get(key) ?: withContext(Dispatchers.IO) {
            val f = photoFile(context, name)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()?.also { photoCache.put(key, it) }
        }
    }.value
}

/** Placeholder area with the wall's aspect ratio while nothing is loaded. */
@Composable
fun WallPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
}
