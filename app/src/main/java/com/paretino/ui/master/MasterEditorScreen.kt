package com.paretino.ui.master

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.paretino.data.EditableHold
import com.paretino.data.MasterRepository
import com.paretino.data.PhotoStore
import com.paretino.data.Prefs
import com.paretino.data.db.ClimberDao
import com.paretino.data.db.MasterDao
import com.paretino.data.db.WallDao
import com.paretino.domain.NPoint
import com.paretino.domain.Polygon
import com.paretino.domain.hitTest
import com.paretino.ui.MasterEditorRoute
import com.paretino.ui.common.formatDate
import com.paretino.ui.components.OverlayHold
import com.paretino.ui.components.OverlayStyle
import com.paretino.ui.components.WallCanvas
import com.paretino.vision.HoldDetector
import com.paretino.vision.Rectifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MasterStep { PHOTO, CAMERA, CORNERS, HOLDS, SAVE }

data class MasterEditorState(
    val step: MasterStep = MasterStep.PHOTO,
    val isNew: Boolean = true,
    val busy: String? = null,
    val original: ImageBitmap? = null,
    val corners: List<NPoint> = DEFAULT_CORNERS,
    val rectified: ImageBitmap? = null,
    val holds: List<EditableHold> = emptyList(),
    val selected: Int? = null,
    val sensitivity: Float = 1f,
    val name: String = "",
    val setter: String = "",
    val message: String? = null,
)

/** When the wall fills the photo there is nothing to find: start from (almost) the whole frame. */
val DEFAULT_CORNERS = listOf(NPoint(0.03f, 0.03f), NPoint(0.97f, 0.03f), NPoint(0.97f, 0.97f), NPoint(0.03f, 0.97f))

@HiltViewModel
class MasterEditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val masters: MasterDao,
    private val walls: WallDao,
    private val climbers: ClimberDao,
    private val prefs: Prefs,
    private val photos: PhotoStore,
    private val repo: MasterRepository,
    private val detector: HoldDetector,
) : ViewModel() {
    private val masterId = handle.toRoute<MasterEditorRoute>().id
    val state = MutableStateFlow(MasterEditorState(isNew = masterId == 0L))
    private var photoName: String? = null
    private var rectified: Bitmap? = null

    init {
        viewModelScope.launch {
            val sens = prefs.sensitivity.first()
            val me = climbers.allClimbers().firstOrNull { it.id == prefs.activeClimber() }
            state.update { it.copy(sensitivity = sens, setter = me?.name.orEmpty(), name = "Set of ${formatDate(System.currentTimeMillis())}") }
            if (masterId != 0L) {
                val m = masters.get(masterId) ?: return@launch
                state.update { it.copy(busy = "Loading…") }
                val bmp = photos.load(m.rectifiedPath)
                rectified = bmp
                val holds = masters.holdList(masterId).map { EditableHold(it.id, Polygon.decode(it.contour), it.colorArgb, it.manual) }
                state.update { it.copy(busy = null, step = MasterStep.HOLDS, rectified = bmp.asImageBitmap(), holds = holds, name = m.name, setter = m.setterName) }
            }
        }
    }

    fun set(block: MasterEditorState.() -> MasterEditorState) = state.update(block)

    fun onPhoto(uri: Uri) = viewModelScope.launch {
        state.update { it.copy(busy = "Loading photo…", step = MasterStep.PHOTO) }
        runCatching {
            val name = photos.import(uri)
            photoName = name
            val preview = photos.load(name, 1600)
            val guess = Rectifier.findWallQuad(preview)
            state.update {
                it.copy(
                    busy = null, step = MasterStep.CORNERS, original = preview.asImageBitmap(), corners = guess ?: DEFAULT_CORNERS,
                    message = if (guess == null) "Drag the handles onto the four corners of the wall" else "Check the corners match the wall",
                )
            }
        }.onFailure { e -> state.update { it.copy(busy = null, message = "Could not open the photo: ${e.message}") } }
    }

    fun confirmCorners() = viewModelScope.launch {
        val name = photoName ?: return@launch
        state.update { it.copy(busy = "Straightening the wall…") }
        val wall = walls.currentWall() ?: return@launch
        val (w, h) = Rectifier.outputSize(wall.widthCm, wall.heightCm)
        val full = photos.load(name)
        val bmp = Rectifier.rectify(full, state.value.corners, w, h)
        full.recycle()
        rectified = bmp
        state.update { it.copy(rectified = bmp.asImageBitmap(), step = MasterStep.HOLDS) }
        detect(keepManual = false)
    }

    fun redetect(sensitivity: Float) = viewModelScope.launch {
        prefs.setSensitivity(sensitivity)
        state.update { it.copy(sensitivity = sensitivity) }
        detect(keepManual = true)
    }

    private suspend fun detect(keepManual: Boolean) {
        val bmp = rectified ?: return
        state.update { it.copy(busy = "Finding holds…", selected = null) }
        val found = runCatching { detector.detect(bmp, state.value.sensitivity) }.getOrElse { e ->
            state.update { it.copy(busy = null, message = "Detection failed: ${e.message}") }
            return
        }
        state.update { st ->
            val manual = if (keepManual) st.holds.filter { it.manual } else emptyList()
            // Re-detecting keeps hold ids where an outline lands on an existing hold, so paths survive.
            val existing = st.holds.filter { !it.manual && it.id != 0L }
            val used = HashSet<Long>()
            val auto = found.map { d ->
                val match = existing.firstOrNull { it.id !in used && it.outline.contains(d.outline.center) }
                match?.let { used += it.id }
                EditableHold(match?.id ?: 0L, d.outline, d.colorArgb, manual = false)
            }
            st.copy(busy = null, holds = auto + manual, message = "${auto.size} holds found. Tap a hold to remove it, tap the wall to add one.")
        }
    }

    fun tap(p: NPoint) = viewModelScope.launch {
        val st = state.value
        val hitIdx = hitTest(st.holds.indices.toList(), { st.holds[it].outline }, p, tolerance = 0.006f)
        when {
            hitIdx != null -> state.update { it.copy(selected = if (it.selected == hitIdx) null else hitIdx) }
            st.selected != null -> state.update { it.copy(selected = null) }
            else -> {
                val bmp = rectified ?: return@launch
                val hold = detector.detectAt(bmp, p) ?: return@launch
                state.update { it.copy(holds = it.holds + EditableHold(0, hold.outline, hold.colorArgb, manual = true), selected = it.holds.size) }
            }
        }
    }

    fun deleteSelected() = state.update { st ->
        val i = st.selected ?: return@update st
        st.copy(holds = st.holds.filterIndexed { j, _ -> j != i }, selected = null)
    }

    fun next(onSaved: () -> Unit) {
        if (state.value.isNew) state.update { it.copy(step = MasterStep.SAVE, selected = null) } else save(onSaved)
    }

    fun save(onSaved: () -> Unit) = viewModelScope.launch {
        val st = state.value
        state.update { it.copy(busy = "Saving…") }
        if (st.isNew) {
            repo.create(st.name.trim(), st.setter.trim(), photoName!!, st.corners, rectified!!, st.holds)
        } else {
            repo.updateHolds(masterId, st.holds)
            repo.rename(masterId, st.name.trim(), st.setter.trim())
        }
        onSaved()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MasterEditorScreen(onBack: () -> Unit, onSaved: () -> Unit, vm: MasterEditorViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmLeave by remember { mutableStateOf(false) }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.set { copy(message = null) } } }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::onPhoto) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.set { copy(step = MasterStep.CAMERA) } else vm.set { copy(message = "Camera permission is needed to take the photo") }
    }
    fun openCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) vm.set { copy(step = MasterStep.CAMERA) }
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    fun back() {
        when (s.step) {
            MasterStep.CAMERA, MasterStep.CORNERS -> vm.set { copy(step = MasterStep.PHOTO) }
            MasterStep.HOLDS -> if (s.isNew) vm.set { copy(step = MasterStep.CORNERS) } else confirmLeave = true
            MasterStep.SAVE -> vm.set { copy(step = MasterStep.HOLDS) }
            MasterStep.PHOTO -> onBack()
        }
    }
    BackHandler(enabled = s.step != MasterStep.PHOTO) { back() }

    Scaffold(
        topBar = {
            if (s.step != MasterStep.CAMERA) TopAppBar(
                title = {
                    Text(
                        when (s.step) {
                            MasterStep.PHOTO, MasterStep.CAMERA -> "New master"
                            MasterStep.CORNERS -> "Wall corners"
                            MasterStep.HOLDS -> if (s.isNew) "Check holds" else "Edit holds"
                            MasterStep.SAVE -> "Save master"
                        }
                    )
                },
                navigationIcon = { IconButton(::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (s.step == MasterStep.HOLDS && s.selected != null) IconButton(vm::deleteSelected) { Icon(Icons.Filled.Delete, "Remove hold") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (s.step) {
                MasterStep.PHOTO -> PhotoStep(onCamera = ::openCamera, onGallery = {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                })
                MasterStep.CAMERA -> CameraCapture(onCaptured = vm::onPhoto, onError = { vm.set { copy(message = it, step = MasterStep.PHOTO) } })
                MasterStep.CORNERS -> s.original?.let { img ->
                    Column(Modifier.fillMaxSize()) {
                        CornerEditor(img, s.corners, { c -> vm.set { copy(corners = c) } }, Modifier.weight(1f).background(Color.Black))
                        BottomBar {
                            Text("Drag each handle onto a corner of the wall.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Button(vm::confirmCorners) { Text("Next") }
                        }
                    }
                }
                MasterStep.HOLDS -> HoldsStep(s, vm, onSaved)
                MasterStep.SAVE -> SaveStep(s, vm, onSaved)
            }
            s.busy?.let { msg ->
                Surface(Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 6.dp) {
                    Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Text(msg, Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        title = { Text("Discard changes?") },
        confirmButton = { TextButton({ confirmLeave = false; onBack() }) { Text("Discard") } },
        dismissButton = { TextButton({ confirmLeave = false }) { Text("Keep editing") } },
    )
}

@Composable
private fun PhotoStep(onCamera: () -> Unit, onGallery: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Text("Photograph the whole wall", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Stand back so all four corners are in the frame. Even light helps; Paretino copes with sun stripes and shadows, " +
                "but avoid people in front of the wall.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onCamera, Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Filled.CameraAlt, null); Text("Take photo", Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onGallery, Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Filled.PhotoLibrary, null); Text("Choose from gallery", Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun HoldsStep(s: MasterEditorState, vm: MasterEditorViewModel, onSaved: () -> Unit) {
    var sensitivity by remember(s.sensitivity) { mutableStateOf(s.sensitivity) }
    val overlay = s.holds.mapIndexed { i, h ->
        when {
            i == s.selected -> OverlayHold(i.toLong(), h.outline, Color(0xFFF5B400), OverlayStyle.Selected)
            h.manual -> OverlayHold(i.toLong(), h.outline, Color(0xFF00E5FF), OverlayStyle.Faint)
            else -> OverlayHold(i.toLong(), h.outline, Color.White, OverlayStyle.Faint)
        }
    }
    Column(Modifier.fillMaxSize()) {
        WallCanvas(s.rectified, overlay, Modifier.weight(1f).fillMaxWidth().background(Color.Black), onTap = { vm.tap(it) })
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sensitivity", style = MaterialTheme.typography.labelLarge)
                    Slider(sensitivity, { sensitivity = it }, Modifier.weight(1f).padding(horizontal = 8.dp), valueRange = 0.5f..1.6f)
                    IconButton({ vm.redetect(sensitivity) }) { Icon(Icons.Filled.Refresh, "Detect again") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${s.holds.size} holds" + if (s.holds.any { it.manual }) " (${s.holds.count { it.manual }} added by hand)" else "",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    )
                    if (s.selected != null) FilledTonalButton(vm::deleteSelected) { Text("Remove") }
                    Spacer(Modifier.padding(4.dp))
                    Button({ vm.next(onSaved) }, enabled = s.holds.isNotEmpty() && s.busy == null) { Text(if (s.isNew) "Next" else "Save") }
                }
            }
        }
    }
}

@Composable
private fun SaveStep(s: MasterEditorState, vm: MasterEditorViewModel, onSaved: () -> Unit) {
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        WallCanvas(
            s.rectified, s.holds.mapIndexed { i, h -> OverlayHold(i.toLong(), h.outline, Color.White, OverlayStyle.Faint) },
            Modifier.fillMaxWidth().aspectRatio(s.rectified?.let { it.width.toFloat() / it.height } ?: 1.4f), zoomable = false,
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(s.name, { t -> vm.set { copy(name = t) } }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            OutlinedTextField(s.setter, { t -> vm.set { copy(setter = t) } }, Modifier.fillMaxWidth(), label = { Text("Set by") }, singleLine = true)
            Button({ vm.save(onSaved) }, Modifier.fillMaxWidth(), enabled = s.name.isNotBlank() && s.busy == null) { Text("Save master") }
        }
    }
}

@Composable
private fun BottomBar(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
