package com.paretino.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paretino.data.db.PathHoldShape
import com.paretino.domain.GradeScale
import com.paretino.domain.Grades
import com.paretino.domain.Polygon
import com.paretino.ui.components.OverlayHold
import java.text.DateFormat
import java.util.Date

fun PathHoldShape.toOverlay() = OverlayHold(
    key = masterHoldId,
    outline = Polygon.decode(contour),
    color = Color(colorArgb),
    tag = role.tag,
    label = label.ifBlank { null },
)

fun formatDate(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

/** Grades get warmer as they get harder. */
fun gradeColor(index: Int?): Color = when {
    index == null -> Color(0xFF8C8C8C)
    index <= 4 -> Color(0xFF2FB36B)
    index <= 8 -> Color(0xFF2E7DF6)
    index <= 12 -> Color(0xFFF5A000)
    index <= 16 -> Color(0xFFE5484D)
    else -> Color(0xFF6B2E9E)
}

@Composable
fun GradeChip(grade: Int?, scale: GradeScale, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.BarChart, null, tint = gradeColor(grade), modifier = Modifier.size(18.dp))
            Text(Grades.label(grade, scale), Modifier.padding(start = 4.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun Stars(value: Float, modifier: Modifier = Modifier, size: Dp = 16.dp, onRate: ((Int) -> Unit)? = null) {
    Row(modifier) {
        for (i in 1..5) {
            val on = value >= i - 0.25f
            Icon(
                if (on) Icons.Filled.Star else Icons.Filled.StarBorder, "$i stars",
                tint = if (on) Color(0xFFF5B400) else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(size).then(if (onRate != null) Modifier.clickable { onRate(i) } else Modifier),
            )
        }
    }
}

@Composable
fun Avatar(name: String, color: Color, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.secondary)
        Text(title, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(body, Modifier.padding(top = 8.dp, bottom = 20.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}
