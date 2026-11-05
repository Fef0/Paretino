package com.paretino.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.paretino.data.Prefs
import com.paretino.data.WallRepository
import com.paretino.data.db.ClimberDao
import com.paretino.data.db.ClimberEntity
import com.paretino.data.db.WallDao
import com.paretino.data.db.WallEntity
import com.paretino.domain.GradeScale
import com.paretino.export.BackupManager
import com.paretino.export.WallLinkExporter
import com.paretino.ui.common.Avatar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsState(
    val wall: WallEntity? = null,
    val positions: Int = 0,
    val wired: Int = 0,
    val climbers: List<ClimberEntity> = emptyList(),
    val activeClimber: Long = 0,
    val scale: GradeScale = GradeScale.FONT,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val walls: WallDao,
    private val wallRepo: WallRepository,
    private val climbers: ClimberDao,
    private val prefs: Prefs,
    private val backup: BackupManager,
    val wallLink: WallLinkExporter,
) : ViewModel() {
    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()

    private val positions = walls.wall().flatMapLatest { w -> w?.let { walls.positions(it.id) } ?: flowOf(emptyList()) }

    val state = combine(walls.wall(), positions, climbers.climbers(), prefs.activeClimberId, prefs.gradeScale) { w, pos, cl, active, scale ->
        SettingsState(w, pos.size, pos.count { it.ledIndex != null }, cl, active, scale)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    fun setScale(s: GradeScale) = viewModelScope.launch { prefs.setGradeScale(s) }
    fun setActive(id: Long) = viewModelScope.launch { prefs.setActiveClimber(id) }
    fun addClimber(name: String) = viewModelScope.launch {
        val colors = WallRepository.CLIMBER_COLORS
        val id = climbers.insert(ClimberEntity(name = name, colorArgb = colors[state.value.climbers.size % colors.size]))
        prefs.setActiveClimber(id)
    }
    fun rename(c: ClimberEntity, name: String) = viewModelScope.launch { climbers.update(c.copy(name = name)) }
    fun delete(c: ClimberEntity) = viewModelScope.launch {
        climbers.delete(c)
        if (c.id == state.value.activeClimber) state.value.climbers.firstOrNull { it.id != c.id }?.let { prefs.setActiveClimber(it.id) }
    }
    fun updateWall(name: String, w: Int, h: Int) = viewModelScope.launch {
        state.value.wall?.let { wallRepo.update(it.copy(name = name, widthCm = w, heightCm = h)) }
    }

    fun exportTo(uri: Uri, open: (Uri) -> java.io.OutputStream?) = viewModelScope.launch {
        runCatching { open(uri)!!.use { backup.export(it) } }
            .onSuccess { messages.send("Backup saved") }
            .onFailure { messages.send("Backup failed: ${it.message}") }
    }

    fun importFrom(uri: Uri, open: (Uri) -> java.io.InputStream?) = viewModelScope.launch {
        runCatching { open(uri)!!.use { backup.import(it) } }
            .onSuccess { messages.send("Restored $it paths") }
            .onFailure { messages.send("Restore failed: ${it.message}") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onPositions: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var addingClimber by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ClimberEntity?>(null) }
    var editingWall by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) { vm.messageFlow.collect { snackbar.showSnackbar(it) } }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { vm.exportTo(it) { u -> context.contentResolver.openOutputStream(u) } }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> confirmRestore = uri }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
            item { Header("Climbers") }
            items(s.climbers, key = { it.id }) { c ->
                ListItem(
                    leadingContent = { Avatar(c.name, Color(c.colorArgb)) },
                    headlineContent = { Text(c.name) },
                    supportingContent = if (c.id == s.activeClimber) { { Text("Climbing now") } } else null,
                    trailingContent = {
                        if (c.id == s.activeClimber) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        else if (s.climbers.size > 1) IconButton({ vm.delete(c) }) { Icon(Icons.Filled.Delete, "Delete ${c.name}") }
                    },
                    modifier = Modifier.clickable { if (c.id == s.activeClimber) editing = c else vm.setActive(c.id) },
                )
            }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Filled.PersonAdd, null) },
                    headlineContent = { Text("Add climber") },
                    modifier = Modifier.clickable { addingClimber = true },
                )
                HorizontalDivider()
                Header("Grades")
                SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp)) {
                    GradeScale.entries.forEachIndexed { i, g ->
                        SegmentedButton(s.scale == g, { vm.setScale(g) }, SegmentedButtonDefaults.itemShape(i, GradeScale.entries.size)) {
                            Text(if (g == GradeScale.FONT) "Font (6A+)" else "V scale (V3)")
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(top = 16.dp))
                Header("Wall")
                s.wall?.let { w ->
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Straighten, null) },
                        headlineContent = { Text(w.name) },
                        supportingContent = { Text("${w.widthCm} × ${w.heightCm} cm") },
                        modifier = Modifier.clickable { editingWall = true },
                    )
                }
                ListItem(
                    leadingContent = { Icon(Icons.Filled.GridOn, null) },
                    headlineContent = { Text("Anchor positions & LEDs") },
                    supportingContent = { Text(if (s.positions == 0) "Not set up" else "${s.positions} positions, ${s.wired} with an LED") },
                    modifier = Modifier.clickable(onClick = onPositions),
                )
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Bluetooth, null) },
                    headlineContent = { Text("Wall controller (ESP32)") },
                    supportingContent = { Text(if (vm.wallLink.isAvailable) "Connected" else "Coming soon: paths will light up on the wall") },
                )
                HorizontalDivider()
                Header("Backup")
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Upload, null) },
                    headlineContent = { Text("Export backup") },
                    supportingContent = { Text("Everything, photos included, in one zip") },
                    modifier = Modifier.clickable { exportLauncher.launch("paretino-backup.zip") },
                )
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Download, null) },
                    headlineContent = { Text("Restore backup") },
                    supportingContent = { Text("Replaces everything in the app") },
                    modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                )
            }
        }
    }

    if (addingClimber) NameDialog("Add climber", "", onDismiss = { addingClimber = false }) { vm.addClimber(it); addingClimber = false }
    editing?.let { c -> NameDialog("Rename", c.name, onDismiss = { editing = null }) { vm.rename(c, it); editing = null } }
    if (editingWall) s.wall?.let { w -> WallDialog(w, onDismiss = { editingWall = false }) { n, ww, hh -> vm.updateWall(n, ww, hh); editingWall = false } }
    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore this backup?") },
            text = { Text("Everything currently in Paretino is replaced by the backup.") },
            confirmButton = { TextButton({ vm.importFrom(uri) { u -> context.contentResolver.openInputStream(u) }; confirmRestore = null }) { Text("Restore") } },
            dismissButton = { TextButton({ confirmRestore = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(text: String) =
    Text(text, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton({ onOk(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WallDialog(wall: WallEntity, onDismiss: () -> Unit, onOk: (String, Int, Int) -> Unit) {
    var name by remember { mutableStateOf(wall.name) }
    var w by remember { mutableStateOf(wall.widthCm.toString()) }
    var h by remember { mutableStateOf(wall.heightCm.toString()) }
    val wi = w.toIntOrNull()?.takeIf { it in 50..5000 }
    val hi = h.toIntOrNull()?.takeIf { it in 50..5000 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wall") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(w, { w = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Width cm") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(h, { h = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Height cm") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Text("New masters use the new size; existing ones keep theirs.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton({ onOk(name.trim(), wi!!, hi!!) }, enabled = name.isNotBlank() && wi != null && hi != null) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
