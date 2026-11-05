package com.paretino.ui.master

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.paretino.data.db.MasterDao
import com.paretino.data.db.MasterEntity
import com.paretino.data.db.PathDao
import com.paretino.data.db.PathEntity
import com.paretino.domain.HoldRef
import com.paretino.domain.MasterDiff
import com.paretino.domain.MasterDiffCalculator
import com.paretino.domain.Polygon
import com.paretino.ui.MasterDiffRoute
import com.paretino.ui.components.OverlayHold
import com.paretino.ui.components.OverlayStyle
import com.paretino.ui.components.WallCanvas
import com.paretino.ui.components.rememberPhoto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiffState(
    val older: MasterEntity? = null,
    val newer: MasterEntity? = null,
    val diff: MasterDiff? = null,
    /** Paths on the older master that use a hold that was removed or changed. */
    val affected: List<Pair<PathEntity, Int>> = emptyList(),
)

private val ADDED = Color(0xFF2FB36B)
private val REMOVED = Color(0xFFE5484D)
private val CHANGED = Color(0xFFF5A000)

@HiltViewModel
class MasterDiffViewModel @Inject constructor(handle: SavedStateHandle, masters: MasterDao, paths: PathDao) : ViewModel() {
    private val route = handle.toRoute<MasterDiffRoute>()
    val state = MutableStateFlow(DiffState())

    init {
        viewModelScope.launch {
            fun refs(list: List<com.paretino.data.db.MasterHoldEntity>) =
                list.map { HoldRef(it.id, Polygon.decode(it.contour), it.colorArgb, it.positionId) }
            val diff = MasterDiffCalculator.diff(refs(masters.holdList(route.older)), refs(masters.holdList(route.newer)))
            val gone = (diff.removed.map { it.id } + diff.changed.map { it.first.id }).toSet()
            val affected = paths.pathsOf(route.older).mapNotNull { p ->
                paths.holdList(p.id).count { it.masterHoldId in gone }.takeIf { it > 0 }?.let { p to it }
            }
            state.value = DiffState(masters.get(route.older), masters.get(route.newer), diff, affected)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MasterDiffScreen(onBack: () -> Unit, onOpenPath: (Long) -> Unit, vm: MasterDiffViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var showOlder by remember { mutableStateOf(false) }
    val diff = s.diff
    val photo = rememberPhoto((if (showOlder) s.older else s.newer)?.rectifiedPath)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("What changed") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (diff == null) return@Scaffold
        val overlay = buildList {
            diff.unchanged.forEach { (_, n) -> add(OverlayHold(n.id, n.outline, Color.White, OverlayStyle.Faint)) }
            diff.removed.forEach { add(OverlayHold(-it.id, it.outline, REMOVED, OverlayStyle.Dashed)) }
            diff.changed.forEach { (_, n) -> add(OverlayHold(n.id, n.outline, CHANGED, OverlayStyle.Selected)) }
            diff.added.forEach { add(OverlayHold(it.id, it.outline, ADDED, OverlayStyle.Selected)) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
            item {
                Box(Modifier.fillMaxWidth().background(Color.Black)) {
                    WallCanvas(photo, overlay, Modifier.fillMaxWidth().aspectRatio(photo?.let { it.width.toFloat() / it.height } ?: 1.4f), dimWall = true)
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
                    SegmentedButton(showOlder, { showOlder = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(s.older?.name.orEmpty(), maxLines = 1) }
                    SegmentedButton(!showOlder, { showOlder = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(s.newer?.name.orEmpty(), maxLines = 1) }
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Legend(ADDED, "${diff.added.size} added")
                    Legend(REMOVED, "${diff.removed.size} removed")
                    Legend(CHANGED, "${diff.changed.size} swapped for a different hold")
                    Legend(Color.LightGray, "${diff.unchanged.size} unchanged")
                }
                Text(
                    if (s.affected.isEmpty()) "No paths on ${s.older?.name} lose holds." else "Paths on ${s.older?.name} that lose holds",
                    Modifier.padding(16.dp, 20.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleMedium,
                )
            }
            items(s.affected, key = { it.first.id }) { (p, n) ->
                ListItem(
                    headlineContent = { Text(p.title) },
                    supportingContent = { Text("$n hold${if (n == 1) "" else "s"} gone or changed") },
                    modifier = Modifier.clickable { onOpenPath(p.id) },
                )
            }
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(color))
        Text(text, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
