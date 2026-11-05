package com.paretino.ui.path

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.paretino.data.PathRepository
import com.paretino.data.Prefs
import com.paretino.data.db.ClimberDao
import com.paretino.data.db.MasterDao
import com.paretino.data.db.MasterEntity
import com.paretino.data.db.MasterHoldEntity
import com.paretino.data.db.PathDao
import com.paretino.data.db.PathEntity
import com.paretino.data.db.PathHoldEntity
import com.paretino.domain.GradeScale
import com.paretino.domain.Grades
import com.paretino.domain.HoldRole
import com.paretino.domain.NPoint
import com.paretino.domain.PathRules
import com.paretino.domain.Polygon
import com.paretino.domain.hitTest
import com.paretino.ui.PathEditorRoute
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

data class Selection(val role: HoldRole, val colorArgb: Int, val label: String = "")

data class EditorState(
    val loading: Boolean = true,
    val master: MasterEntity? = null,
    val holds: List<MasterHoldEntity> = emptyList(),
    val shapes: Map<Long, Polygon> = emptyMap(),
    val selected: Map<Long, Selection> = emptyMap(),
    val brush: HoldRole = HoldRole.START,
    val title: String = "",
    val description: String = "",
    val grade: Int? = null,
    val setter: String = "",
    val rules: Set<String> = emptySet(),
    val customRule: String = "",
    val scale: GradeScale = GradeScale.FONT,
    val step: Int = 0,
    val editingHold: Long? = null,
    val isNew: Boolean = true,
)

@HiltViewModel
class PathEditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val paths: PathDao,
    private val masters: MasterDao,
    private val climbers: ClimberDao,
    private val prefs: Prefs,
    private val repo: PathRepository,
) : ViewModel() {
    private val route = handle.toRoute<PathEditorRoute>()
    private var original: PathEntity? = null
    val state = MutableStateFlow(EditorState())

    init {
        viewModelScope.launch {
            val existing = if (route.id != 0L) paths.get(route.id) else null
            original = existing
            val masterId = existing?.masterId ?: route.masterId
            val master = masters.get(masterId)
            val holds = masters.holdList(masterId)
            val me = climbers.allClimbers().firstOrNull { it.id == prefs.activeClimber() }
            val selected = existing?.let { p -> paths.holdList(p.id).associate { it.masterHoldId to Selection(it.role, it.colorArgb, it.label) } }.orEmpty()
            val presetRules = PathRules.PRESETS.toSet()
            val rules = existing?.rules?.split(" · ")?.filter { it.isNotBlank() }.orEmpty()
            state.value = EditorState(
                loading = false, master = master, holds = holds,
                shapes = holds.associate { it.id to Polygon.decode(it.contour) },
                selected = selected,
                brush = if (selected.isEmpty()) HoldRole.START else HoldRole.HAND,
                title = existing?.title.orEmpty(),
                description = existing?.description.orEmpty(),
                grade = existing?.grade,
                setter = existing?.setterName ?: me?.name.orEmpty(),
                rules = rules.filter { it in presetRules }.toSet(),
                customRule = rules.filterNot { it in presetRules }.joinToString(" · "),
                scale = prefs.gradeScale.first(),
                isNew = existing == null,
            )
        }
    }

    fun tap(p: NPoint) {
        val s = state.value
        val hit = hitTest(s.holds, { s.shapes.getValue(it.id) }, p, tolerance = 0.012f) ?: return
        state.update { st ->
            val current = st.selected[hit.id]
            val next = st.selected.toMutableMap()
            if (current != null && current.role == st.brush) next -= hit.id
            else next[hit.id] = Selection(st.brush, st.brush.defaultColor.toInt(), current?.label.orEmpty())
            // After the start holds, carry on with hand holds.
            val starts = next.values.count { it.role == HoldRole.START }
            val brush = if (st.brush == HoldRole.START && starts >= 2) HoldRole.HAND else st.brush
            st.copy(selected = next, brush = brush)
        }
    }

    fun longPress(p: NPoint) {
        val s = state.value
        val hit = hitTest(s.holds, { s.shapes.getValue(it.id) }, p, tolerance = 0.012f) ?: return
        if (hit.id !in s.selected) tap(p)
        state.update { it.copy(editingHold = hit.id) }
    }

    fun editHold(id: Long, sel: Selection?) = state.update { st ->
        st.copy(selected = if (sel == null) st.selected - id else st.selected + (id to sel))
    }

    /** Mirror left/right: each hold moves to the master hold nearest its mirrored position. */
    fun mirror() = state.update { st ->
        val mirrored = HashMap<Long, Selection>()
        for ((id, sel) in st.selected) {
            val c = st.shapes.getValue(id).center
            val target = NPoint(1 - c.x, c.y)
            val best = st.holds.minByOrNull { st.shapes.getValue(it.id).center.distanceTo(target) } ?: continue
            if (st.shapes.getValue(best.id).center.distanceTo(target) < 0.04f) mirrored[best.id] = sel
        }
        st.copy(selected = mirrored)
    }

    fun set(block: EditorState.() -> EditorState) = state.update(block)

    fun save(onSaved: (Long) -> Unit) = viewModelScope.launch {
        val s = state.value
        val master = s.master ?: return@launch
        val rules = (s.rules.toList().sortedBy { PathRules.PRESETS.indexOf(it) } + s.customRule.trim()).filter { it.isNotBlank() }
        val path = (original ?: PathEntity(masterId = master.id, title = "")).copy(
            title = s.title.trim(), description = s.description.trim(), grade = s.grade,
            setterName = s.setter.trim(), rules = rules.joinToString(" · "),
        )
        val holds = s.selected.map { (id, sel) -> PathHoldEntity(0, id, sel.role, sel.colorArgb, sel.label.trim()) }
        onSaved(repo.save(path, holds))
    }
}

val HOLD_PALETTE = listOf(
    0xFF2FB36B, 0xFF2E7DF6, 0xFFF5B400, 0xFFE5484D, 0xFF8E4EC6, 0xFF12A594,
    0xFFF76B15, 0xFFE93D82, 0xFFFFFFFF, 0xFF00C2FF, 0xFFB5E61D, 0xFF000000,
).map { it.toInt() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PathEditorScreen(onBack: () -> Unit, onSaved: (Long) -> Unit, vm: PathEditorViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val photo = rememberPhoto(s.master?.rectifiedPath)
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it); error = null } }

    val overlay = s.holds.map { h ->
        val sel = s.selected[h.id]
        if (sel == null) OverlayHold(h.id, s.shapes.getValue(h.id), Color.White, OverlayStyle.Faint)
        else OverlayHold(h.id, s.shapes.getValue(h.id), Color(sel.colorArgb), OverlayStyle.Selected, sel.role.tag, sel.label.ifBlank { null })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (s.isNew) "New path" else "Edit path") },
                navigationIcon = {
                    IconButton({ if (s.step == 1) vm.set { copy(step = 0) } else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (s.step == 0) IconButton(vm::mirror, enabled = s.selected.isNotEmpty()) { Icon(Icons.Filled.Flip, "Mirror") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (s.loading) return@Scaffold
        if (s.step == 0) Column(Modifier.fillMaxSize().padding(padding)) {
            WallCanvas(photo, overlay, Modifier.weight(1f).fillMaxWidth(), onTap = vm::tap, onLongPress = vm::longPress)
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.navigationBarsPadding().padding(vertical = 10.dp)) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(HoldRole.entries) { r ->
                            val n = s.selected.values.count { it.role == r }
                            FilterChip(
                                s.brush == r, { vm.set { copy(brush = r) } },
                                label = { Text(if (n > 0) "${r.label} $n" else r.label) },
                                leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(r.defaultColor))) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(r.defaultColor).copy(alpha = 0.25f)),
                            )
                        }
                    }
                    Row(Modifier.padding(horizontal = 16.dp).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (s.selected.isEmpty()) "Tap holds to add them. Long-press for colour and label."
                            else "${s.selected.size} holds · tap again to remove",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button({
                            if (s.selected.isEmpty()) error = "Pick at least one hold" else vm.set { copy(step = 1) }
                        }) { Text("Next") }
                    }
                }
            }
        } else DetailsForm(s, vm, Modifier.padding(padding), onSave = {
            when {
                s.title.isBlank() -> error = "Give the path a name"
                else -> vm.save(onSaved)
            }
        }) {
            WallCanvas(
                photo, overlay.filter { it.style == OverlayStyle.Selected },
                Modifier.fillMaxWidth().aspectRatio(photo?.let { it.width.toFloat() / it.height } ?: 1.4f),
                zoomable = false, dimWall = true,
            )
        }
    }

    s.editingHold?.let { id ->
        val sel = s.selected[id]
        if (sel == null) vm.set { copy(editingHold = null) } else HoldSheet(sel, onDismiss = { vm.set { copy(editingHold = null) } }, onChange = { vm.editHold(id, it) })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DetailsForm(s: EditorState, vm: PathEditorViewModel, modifier: Modifier, onSave: () -> Unit, preview: @Composable () -> Unit) {
    var gradeMenu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        preview()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(s.title, { t -> vm.set { copy(title = t) } }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ExposedDropdownMenuBox(gradeMenu, { gradeMenu = it }, Modifier.weight(1f)) {
                    OutlinedTextField(
                        Grades.label(s.grade, s.scale).takeIf { s.grade != null }.orEmpty(), {}, readOnly = true,
                        label = { Text("Grade") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(gradeMenu) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(gradeMenu, { gradeMenu = false }) {
                        Grades.options(s.scale).forEach { (i, label) ->
                            DropdownMenuItem({ Text(label) }, { vm.set { copy(grade = i) }; gradeMenu = false })
                        }
                    }
                }
                OutlinedTextField(s.setter, { t -> vm.set { copy(setter = t) } }, Modifier.weight(1f), label = { Text("Setter") }, singleLine = true)
            }
            Text("Rules", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PathRules.PRESETS.forEach { r ->
                    FilterChip(r in s.rules, { vm.set { copy(rules = if (r in rules) rules - r else rules + r) } }, label = { Text(r) },
                        leadingIcon = if (r in s.rules) { { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) } } else null)
                }
            }
            OutlinedTextField(s.customRule, { t -> vm.set { copy(customRule = t) } }, Modifier.fillMaxWidth(), label = { Text("Other rule") }, singleLine = true)
            OutlinedTextField(s.description, { t -> vm.set { copy(description = t) } }, Modifier.fillMaxWidth(), label = { Text("Description") }, minLines = 3)
            Button(onSave, Modifier.fillMaxWidth()) { Text("Save path") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HoldSheet(sel: Selection, onDismiss: () -> Unit, onChange: (Selection?) -> Unit) {
    ModalBottomSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Hold", style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldRole.entries.forEach { r ->
                    FilterChip(sel.role == r, { onChange(sel.copy(role = r, colorArgb = r.defaultColor.toInt())) }, label = { Text(r.label) })
                }
            }
            Text("Colour", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HOLD_PALETTE.forEach { c ->
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Color(c))
                            .border(if (c == sel.colorArgb) 3.dp else 1.dp, if (c == sel.colorArgb) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                            .clickable { onChange(sel.copy(colorArgb = c)) },
                    )
                }
            }
            OutlinedTextField(sel.label, { onChange(sel.copy(label = it)) }, Modifier.fillMaxWidth(), label = { Text("Label (e.g. \"L hand\", \"1\")") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ onChange(null); onDismiss() }) { Text("Remove hold") }
                TextButton(onDismiss) { Text("Done", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
