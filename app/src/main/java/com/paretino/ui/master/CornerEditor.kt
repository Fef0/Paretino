package com.paretino.ui.master

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.paretino.domain.NPoint
import com.paretino.ui.components.fitRect

/**
 * Drag the four handles onto the wall corners (top-left, top-right, bottom-right, bottom-left).
 * A loupe shows the area under the finger while dragging.
 */
@Composable
fun CornerEditor(image: ImageBitmap, corners: List<NPoint>, onChange: (List<NPoint>) -> Unit, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(corners)
    var dragging by remember { mutableIntStateOf(-1) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val box = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val fit = fitRect(IntSize(image.width, image.height), box)
        fun toScreen(p: NPoint) = Offset(fit.left + p.x * fit.width, fit.top + p.y * fit.height)
        fun toImage(o: Offset) = NPoint(((o.x - fit.left) / fit.width).coerceIn(0f, 1f), ((o.y - fit.top) / fit.height).coerceIn(0f, 1f))

        Canvas(
            Modifier.fillMaxSize().pointerInput(fit) {
                detectDragGestures(
                    onDragStart = { start ->
                        val pts = current.map(::toScreen)
                        val i = pts.indices.minBy { (pts[it] - start).getDistance() }
                        dragging = if ((pts[i] - start).getDistance() < 64.dp.toPx()) i else -1
                    },
                    onDrag = { change, amount ->
                        if (dragging < 0) return@detectDragGestures
                        change.consume()
                        val pts = current.toMutableList()
                        pts[dragging] = toImage(toScreen(pts[dragging]) + amount)
                        onChange(pts)
                    },
                    onDragEnd = { dragging = -1 },
                    onDragCancel = { dragging = -1 },
                )
            },
        ) {
            drawImage(image, dstOffset = IntOffset(fit.left.toInt(), fit.top.toInt()), dstSize = IntSize(fit.width.toInt(), fit.height.toInt()))
            val pts = corners.map(::toScreen)
            val quad = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
            // Dim everything outside the wall.
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                addPath(quad)
            }
            drawPath(outside, Color.Black.copy(alpha = 0.45f))
            drawPath(quad, Color.White, style = Stroke(2.dp.toPx()))
            pts.forEachIndexed { i, p ->
                drawCircle(Color.Black.copy(alpha = 0.4f), 16.dp.toPx(), p)
                drawCircle(if (i == dragging) Color(0xFFF5B400) else Color.White, 12.dp.toPx(), p, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, 2.dp.toPx(), p)
            }
            if (dragging >= 0) {
                val r = 56.dp.toPx()
                val p = pts[dragging]
                val center = Offset(if (p.x < size.width / 2) size.width - r - 16.dp.toPx() else r + 16.dp.toPx(), r + 16.dp.toPx())
                val zoom = 3f
                val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, r)) }
                clipPath(circle) {
                    drawRect(Color.Black)
                    val scale = fit.width / image.width * zoom
                    val img = Offset((p.x - fit.left) / fit.width * image.width, (p.y - fit.top) / fit.height * image.height)
                    drawImage(
                        image,
                        dstOffset = IntOffset((center.x - img.x * scale).toInt(), (center.y - img.y * scale).toInt()),
                        dstSize = IntSize((image.width * scale).toInt(), (image.height * scale).toInt()),
                    )
                    drawLine(Color(0xFFF5B400), center - Offset(10.dp.toPx(), 0f), center + Offset(10.dp.toPx(), 0f), 1.5f.dp.toPx())
                    drawLine(Color(0xFFF5B400), center - Offset(0f, 10.dp.toPx()), center + Offset(0f, 10.dp.toPx()), 1.5f.dp.toPx())
                }
                drawCircle(Color.White, r, center, style = Stroke(3.dp.toPx()))
            }
        }
    }
}
