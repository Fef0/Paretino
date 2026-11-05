package com.paretino.ui.logbook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.paretino.data.Prefs
import com.paretino.data.db.AscentRow
import com.paretino.data.db.ClimberDao
import com.paretino.data.db.ClimberEntity
import com.paretino.data.db.PathDao
import com.paretino.data.db.PathSummary
import com.paretino.domain.AscentStyle
import com.paretino.domain.GradeScale
import com.paretino.domain.Grades
import com.paretino.ui.common.Avatar
import com.paretino.ui.common.EmptyState
import com.paretino.ui.common.formatDate
import com.paretino.ui.common.gradeColor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class LogbookState(
    val climber: ClimberEntity? = null,
    val ascents: List<AscentRow> = emptyList(),
    val favorites: List<PathSummary> = emptyList(),
    val scale: GradeScale = GradeScale.FONT,
) {
    private val sends get() = ascents.filter { it.ascent.style.isSend }
    /** One entry per path sent: repeats don't inflate the pyramid. */
    val sentPaths get() = sends.distinctBy { it.ascent.pathId }
    val flashes get() = ascents.count { it.ascent.style == AscentStyle.FLASH }
    val hardest get() = sentPaths.mapNotNull { it.pathGrade }.maxOrNull()
    val pyramid: List<Pair<Int, Int>> get() = sentPaths.mapNotNull { it.pathGrade }.groupingBy { it }.eachCount().toList().sortedBy { it.first }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LogbookViewModel @Inject constructor(climbers: ClimberDao, paths: PathDao, prefs: Prefs) : ViewModel() {
    val state = prefs.activeClimberId.flatMapLatest { id ->
        combine(climbers.climbers(), climbers.ascentsForClimber(id), paths.summaries(id), prefs.gradeScale) { all, ascents, summaries, scale ->
            LogbookState(all.firstOrNull { it.id == id }, ascents, summaries.filter { it.isFavorite }, scale)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogbookState())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogbookScreen(onOpen: (Long) -> Unit, vm: LogbookViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Logbook") },
            actions = { s.climber?.let { Avatar(it.name, Color(it.colorArgb), 32.dp, Modifier.padding(end = 12.dp)) } },
        )
    }) { padding ->
        if (s.ascents.isEmpty() && s.favorites.isEmpty()) {
            EmptyState(Icons.Filled.ShowChart, "Nothing logged yet", "Open a path and tap Log ascent after you climb it.", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = 24.dp)) {
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat("Sends", "${s.sentPaths.size}", Modifier.weight(1f))
                    Stat("Flashes", "${s.flashes}", Modifier.weight(1f))
                    Stat("Hardest", s.hardest?.let { Grades.label(it, s.scale) } ?: "–", Modifier.weight(1f))
                }
            }
            if (s.pyramid.isNotEmpty()) item {
                Text("Sends by grade", Modifier.padding(16.dp, 20.dp, 16.dp, 8.dp), style = MaterialTheme.typography.titleMedium)
                Pyramid(s.pyramid, s.scale, Modifier.fillMaxWidth().height(170.dp).padding(horizontal = 16.dp))
            }
            if (s.favorites.isNotEmpty()) {
                item { Text("Favourites", Modifier.padding(16.dp, 20.dp, 16.dp, 0.dp), style = MaterialTheme.typography.titleMedium) }
                items(s.favorites, key = { "f${it.path.id}" }) { f ->
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Favorite, null, tint = Color(0xFFE5484D)) },
                        headlineContent = { Text(f.path.title) },
                        supportingContent = { Text("${Grades.label(f.path.grade, s.scale)} · ${f.masterName}") },
                        modifier = Modifier.clickable { onOpen(f.path.id) },
                    )
                }
            }
            item { Text("Recent", Modifier.padding(16.dp, 20.dp, 16.dp, 0.dp), style = MaterialTheme.typography.titleMedium) }
            items(s.ascents, key = { it.ascent.id }) { a ->
                ListItem(
                    headlineContent = { Text(a.pathTitle) },
                    supportingContent = { Text("${a.ascent.style.label} · ${a.ascent.attempts} att. · ${formatDate(a.ascent.date)}") },
                    trailingContent = { Text(Grades.label(a.pathGrade, s.scale), fontWeight = FontWeight.Bold, color = gradeColor(a.pathGrade)) },
                    modifier = Modifier.clickable { onOpen(a.ascent.pathId) },
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Card(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Horizontal bars, hardest grade on top, like a climbing pyramid. */
@Composable
private fun Pyramid(data: List<Pair<Int, Int>>, scale: GradeScale, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurface
    val style = TextStyle(fontSize = 12.sp, color = labelColor, fontWeight = FontWeight.Medium)
    Canvas(modifier) {
        val rows = data.reversed()
        val max = rows.maxOf { it.second }.toFloat()
        val rowH = size.height / rows.size
        val labelW = 44.dp.toPx()
        rows.forEachIndexed { i, (grade, count) ->
            val y = i * rowH
            val barH = rowH * 0.7f
            drawText(measurer, Grades.label(grade, scale), Offset(0f, y + (rowH - 16.sp.toPx()) / 2), style)
            val w = (size.width - labelW - 36.dp.toPx()) * count / max
            drawRoundRect(gradeColor(grade), Offset(labelW, y + (rowH - barH) / 2), Size(w.coerceAtLeast(4f), barH), CornerRadius(4.dp.toPx()))
            drawText(measurer, "$count", Offset(labelW + w + 6.dp.toPx(), y + (rowH - 16.sp.toPx()) / 2), style)
        }
    }
}
