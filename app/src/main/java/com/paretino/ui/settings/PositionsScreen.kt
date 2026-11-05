package com.paretino.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.paretino.data.MasterRepository
import com.paretino.data.WallRepository
import com.paretino.data.db.MasterDao
import com.paretino.data.db.WallDao
import com.paretino.domain.NPoint
import com.paretino.domain.Polygon
import com.paretino.domain.PositionGrid
import com.paretino.ui.components.OverlayHold
import com.paretino.ui.components.OverlayStyle
import com.paretino.ui.components.WallCanvas
import com.paretino.ui.components.rememberPhoto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditablePosition(val point: NPoint, val label: String, val led: Int?)

data class PositionsState(
    val loading: Boolean = true,
    val photo: String? = null,
    val holds: List<Polygon> = emptyList(),
    val positions: List<EditablePosition> = emptyList(),
    val selected: Int? = null,
    val dirty: Boolean = false,
)

@HiltViewModel
class PositionsViewModel @Inject constructor(
    private val walls: WallDao,
    private val masters: MasterDao,
    private val wallRepo: WallRepository,
    private val masterRepo: MasterRepository,
) : ViewModel() {
    val state = MutableStateFlow(PositionsState())

    init {
        viewModelScope.launch {
            val wall = walls.currentWall() ?: return@launch
            val master = masters.active().first() ?: masters.allMasters().maxByOrNull { it.createdAt }
            state.value = PositionsState(
                loading = false,
                photo = master?.rectifiedPath,
                holds = master?.let { m -> masters.holdList(m.id).map { Polygon.decode(it.contour) } }.orEmpty(),
                positions = walls.positionList(wall.id).map { EditablePosition(NPoint(it.x, it.y), it.label, it.ledIndex) },
            )
        }
    }

    private fun edit(block: (List<EditablePosition>) -> List<EditablePosition>) =
        state.update { it.copy(positions = block(it.positions), dirty = true, selected = null) }

    fun grid(cols: Int, rows: Int, margin: Float, staggered: Boolean) =
        edit { PositionGrid.generate(cols, rows, margin, margin, staggered).map { (p, l) -> EditablePosition(p, l, null) } }

    /** One anchor per hold of the active master: quick when every t-nut already has a hold. */
    fun fromHolds() = edit {
        val centers = state.value.holds.map { it.center }.sortedWith(compareBy({ (it.y * 20).toInt() }, { it.x }))
        centers.mapIndexed { i, c -> EditablePosition(c, "P${i + 1}", null) }
    }

    fun numberLeds() = edit { list ->
        val leds = PositionGrid.serpentine(list.map { it.point })
        list.mapIndexed { i, p -> p.copy(led = leds[i]) }
    }

    fun tap(p: NPoint) {
        val st = state.value
        val nearest = st.positions.indices.minByOrNull { st.positions[it].point.distanceTo(p) }
        if (nearest != null && st.positions[nearest].point.distanceTo(p) < 0.02f) {
            state.update { it.copy(selected = if (it.selected == nearest) null else nearest) }
        } else {
            edit { it + EditablePosition(p, "P${it.size + 1}", null) }
        }
    }

    fun deleteSelected() {
        val i = state.value.selected ?: return
        edit { list -> list.filterIndexed { j, _ -> j != i } }
    }

    fun clear() = edit { emptyList() }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val wall = walls.currentWall() ?: return@launch
        val list = state.value.positions
        wallRepo.replacePositions(wall.id, list.map { it.point to it.label }, list.map { it.led })
        masterRepo.resnapAll()
        onDone()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PositionsScreen(onBack: () -> Unit, vm: PositionsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val photo = rememberPhoto(s.photo)
    val measurer = rememberTextMeasurer()
    var gridDialog by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Anchor positions") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (s.selected != null) IconButton(vm::deleteSelected) { Icon(Icons.Filled.Delete, "Delete position") }
                    TextButton({ vm.save(onBack) }, enabled = s.dirty) { Text("Save") }
                },
            )
        },
    ) { padding ->
        if (s.loading) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding)) {
            WallCanvas(
                photo, s.holds.mapIndexed { i, h -> OverlayHold(i.toLong(), h, Color.White, OverlayStyle.Faint) },
                Modifier.weight(1f).fillMaxWidth().background(Color.Black),
                onTap = vm::tap,
            ) { toScreen, _ ->
                val style = TextStyle(color = Color.White, fontSize = 8.sp)
                s.positions.forEachIndexed { i, p ->
                    val o = toScreen(p.point)
                    val sel = i == s.selected
                    drawCircle(Color.Black.copy(alpha = 0.5f), 6.dp.toPx(), o)
                    drawCircle(if (sel) Color(0xFFF5B400) else if (p.led != null) Color(0xFF2FB36B) else accent, 4.dp.toPx(), o)
                    if (sel) drawCircle(Color(0xFFF5B400), 10.dp.toPx(), o, style = Stroke(2.dp.toPx()))
                    p.led?.let { drawText(measurer, "$it", o + Offset(5.dp.toPx(), -12.dp.toPx()), style) }
                }
            }
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.navigationBarsPadding().padding(vertical = 8.dp)) {
                    Text(
                        "${s.positions.size} positions · ${s.positions.count { it.led != null }} with LEDs. Tap the wall to add one.",
                        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                    )
                    LazyRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                        item { AssistChip({ gridDialog = true }, { Text("Generate grid") }) }
                        if (s.holds.isNotEmpty()) item { AssistChip(vm::fromHolds, { Text("From holds") }) }
                        item { AssistChip(vm::numberLeds, { Text("Number LEDs") }, enabled = s.positions.isNotEmpty()) }
                        item { AssistChip(vm::clear, { Text("Clear") }, enabled = s.positions.isNotEmpty()) }
                    }
                }
            }
        }
    }

    if (gridDialog) GridDialog(onDismiss = { gridDialog = false }) { c, r, m, st -> vm.grid(c, r, m, st); gridDialog = false }
}

@Composable
private fun GridDialog(onDismiss: () -> Unit, onOk: (Int, Int, Float, Boolean) -> Unit) {
    var cols by remember { mutableStateOf("18") }
    var rows by remember { mutableStateOf("14") }
    var margin by remember { mutableStateOf("4") }
    var staggered by remember { mutableStateOf(false) }
    val c = cols.toIntOrNull()?.takeIf { it in 1..80 }
    val r = rows.toIntOrNull()?.takeIf { it in 1..80 }
    val m = margin.toFloatOrNull()?.takeIf { it in 0f..40f }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("T-nut grid") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(cols, { cols = it }, "Columns", Modifier.weight(1f))
                    NumberField(rows, { rows = it }, "Rows", Modifier.weight(1f))
                }
                NumberField(margin, { margin = it }, "Border (% of wall)", Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(staggered, { staggered = it })
                    Text("Staggered rows")
                }
            }
        },
        confirmButton = { TextButton({ onOk(c!!, r!!, m!! / 100f, staggered) }, enabled = c != null && r != null && m != null) { Text("Generate") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) =
    OutlinedTextField(value, { onChange(it.filter { ch -> ch.isDigit() || ch == '.' }) }, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
