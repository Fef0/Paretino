package com.paretino.ui.path

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.paretino.data.PathRepository
import com.paretino.data.PhotoStore
import com.paretino.data.Prefs
import com.paretino.data.db.AscentRow
import com.paretino.data.db.ClimberDao
import com.paretino.data.db.MasterDao
import com.paretino.data.db.MasterEntity
import com.paretino.data.db.PathDao
import com.paretino.data.db.PathEntity
import com.paretino.data.db.RatingSummary
import com.paretino.domain.AscentStyle
import com.paretino.domain.GradeScale
import com.paretino.export.ShareSheetExporter
import com.paretino.export.WallLinkExporter
import com.paretino.ui.PathDetailRoute
import com.paretino.ui.common.GradeChip
import com.paretino.ui.common.Stars
import com.paretino.ui.common.formatDate
import com.paretino.ui.common.toOverlay
import com.paretino.ui.components.OverlayHold
import com.paretino.ui.components.WallCanvas
import com.paretino.ui.components.rememberPhoto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PathDetailState(
    val path: PathEntity? = null,
    val master: MasterEntity? = null,
    val holds: List<OverlayHold> = emptyList(),
    val ascents: List<AscentRow> = emptyList(),
    val favorite: Boolean = false,
    val myStars: Int = 0,
    val rating: RatingSummary = RatingSummary(null, 0),
    val scale: GradeScale = GradeScale.FONT,
    val myAttempts: Int = 0,
    val mySent: Boolean = false,
)

sealed interface DetailEvent {
    data class Message(val text: String) : DetailEvent
    data class Share(val intent: Intent) : DetailEvent
    data class Opened(val pathId: Long) : DetailEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PathDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    pathDao: PathDao,
    masterDao: MasterDao,
    climbers: ClimberDao,
    prefs: Prefs,
    private val repo: PathRepository,
    private val photos: PhotoStore,
    private val share: ShareSheetExporter,
    private val wallLink: WallLinkExporter,
) : ViewModel() {
    val pathId = handle.toRoute<PathDetailRoute>().id
    private val events = Channel<DetailEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val path = pathDao.path(pathId)
    private val master = path.flatMapLatest { p -> p?.let { masterDao.master(it.masterId) } ?: flowOf(null) }
    private val holds = pathDao.allHoldShapes().map { all -> all.filter { it.pathId == pathId }.map { it.toOverlay() } }
    private val mine = prefs.activeClimberId.flatMapLatest { me ->
        combine(climbers.isFavorite(me, pathId), climbers.rating(me, pathId)) { fav, r -> Triple(me, fav, r?.stars ?: 0) }
    }

    val state = combine(
        combine(path, master, holds) { p, m, h -> Triple(p, m, h) },
        climbers.ascentsForPath(pathId), mine, climbers.ratingSummary(pathId), prefs.gradeScale,
    ) { (p, m, h), ascents, (me, fav, stars), rating, scale ->
        val my = ascents.filter { it.ascent.climberId == me }
        PathDetailState(
            path = p, master = m, holds = h, ascents = ascents, favorite = fav, myStars = stars, rating = rating, scale = scale,
            myAttempts = my.sumOf { it.ascent.attempts }, mySent = my.any { it.ascent.style.isSend },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PathDetailState())

    fun toggleFavorite() = viewModelScope.launch { repo.toggleFavorite(pathId) }
    fun rate(stars: Int) = viewModelScope.launch { repo.rate(pathId, stars) }
    fun log(style: AscentStyle, attempts: Int, note: String) = viewModelScope.launch {
        repo.logAscent(pathId, style, attempts, note)
        events.send(DetailEvent.Message(if (style.isSend) "Send logged 🎉" else "Attempt logged"))
    }

    fun delete() = viewModelScope.launch { repo.delete(pathId) }
    fun duplicate() = viewModelScope.launch { events.send(DetailEvent.Opened(repo.duplicate(pathId))) }

    fun share() = viewModelScope.launch {
        val m = state.value.master ?: return@launch
        val dto = repo.exportDto(pathId)
        val wall = photos.load(m.rectifiedPath, 2000)
        events.send(DetailEvent.Share(share.shareIntent(dto, wall)))
    }

    fun lightOnWall() = viewModelScope.launch {
        val result = wallLink.show(repo.exportDto(pathId))
        events.send(DetailEvent.Message(result.exceptionOrNull()?.message ?: "Path shown on the wall"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PathDetailScreen(
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpen: (Long) -> Unit,
    vm: PathDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var logDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.eventFlow.collect { e ->
            when (e) {
                is DetailEvent.Message -> snackbar.showSnackbar(e.text)
                is DetailEvent.Share -> context.startActivity(e.intent)
                is DetailEvent.Opened -> onOpen(e.pathId)
            }
        }
    }

    val p = state.path
    val photo = rememberPhoto(state.master?.rectifiedPath)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paretino") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                actions = {
                    IconButton(vm::share) { Icon(Icons.Filled.Share, "Share") }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Edit") }, { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Filled.Edit, null) })
                            DropdownMenuItem({ Text("Duplicate") }, { menu = false; vm.duplicate() }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) })
                            DropdownMenuItem({ Text("Delete") }, { menu = false; confirmDelete = true }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            state.mySent -> "Sent in: "
                            state.myAttempts > 0 -> "Tried: "
                            else -> "Not tried yet"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (state.myAttempts > 0) Text(
                        "${state.myAttempts} attempt${if (state.myAttempts == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    OutlinedButton({ logDialog = true }) { Text(if (state.mySent) "LOG REPEAT" else "LOG ASCENT") }
                }
            }
        },
    ) { padding ->
        if (p == null) return@Scaffold
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
            item {
                Box(Modifier.fillMaxWidth().background(Color.Black)) {
                    WallCanvas(
                        photo, state.holds,
                        Modifier.fillMaxWidth().aspectRatio(photo?.let { it.width.toFloat() / it.height } ?: 1.3f).heightIn(max = 560.dp),
                        dimWall = true,
                    )
                    GradeChip(p.grade, state.scale, Modifier.align(Alignment.BottomStart).padding(12.dp))
                    Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        RoundAction(if (state.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite",
                            tint = if (state.favorite) Color(0xFFE5484D) else MaterialTheme.colorScheme.onSurface, onClick = vm::toggleFavorite)
                        RoundAction(Icons.Filled.Lightbulb, "Light on wall", onClick = vm::lightOnWall)
                    }
                }
            }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("${p.title} · ${state.master?.name.orEmpty()}", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        buildString {
                            if (p.setterName.isNotBlank()) append("Set by ${p.setterName} ")
                            append("on ${formatDate(p.createdAt)}")
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Stars(state.myStars.toFloat(), size = 28.dp, onRate = vm::rate)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            state.rating.avgStars?.let { "%.1f avg · %d rating%s".format(it, state.rating.count, if (state.rating.count == 1) "" else "s") } ?: "Tap to rate",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val sends = state.ascents.count { it.ascent.style.isSend }
                    InfoRow(Icons.Filled.CheckCircle, "$sends send${if (sends == 1) "" else "s"}", MaterialTheme.colorScheme.tertiary)
                    if (p.rules.isNotBlank()) InfoRow(Icons.AutoMirrored.Filled.Rule, p.rules, MaterialTheme.colorScheme.onSurface)
                    if (p.description.isNotBlank()) Text(p.description, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider()
                Text("Ascents", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleMedium)
            }
            if (state.ascents.isEmpty()) item {
                Text("No one has logged this yet.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.ascents, key = { it.ascent.id }) { a ->
                ListItem(
                    headlineContent = { Text("${a.climberName} · ${a.ascent.style.label}") },
                    supportingContent = {
                        Text(listOfNotNull(
                            formatDate(a.ascent.date),
                            "${a.ascent.attempts} attempt${if (a.ascent.attempts == 1) "" else "s"}",
                            a.ascent.note.ifBlank { null },
                        ).joinToString(" · "))
                    },
                )
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this path?") },
        text = { Text("Its ascents, ratings and favourites go with it.") },
        confirmButton = { TextButton({ confirmDelete = false; vm.delete(); onBack() }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
    if (logDialog) LogAscentDialog(
        alreadySent = state.mySent,
        onDismiss = { logDialog = false },
        onLog = { style, attempts, note -> logDialog = false; vm.log(style, attempts, note) },
    )
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick, Modifier.size(56.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surface),
    ) { Icon(icon, desc, tint = tint) }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint)
        Text(text, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LogAscentDialog(alreadySent: Boolean, onDismiss: () -> Unit, onLog: (AscentStyle, Int, String) -> Unit) {
    var sent by remember { mutableStateOf(true) }
    var attempts by remember { mutableIntStateOf(1) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (alreadySent) "Log a repeat" else "Log ascent") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(sent, { sent = true }, label = { Text("Sent it") })
                    FilterChip(!sent, { sent = false }, label = { Text("Just tried") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Attempts", Modifier.weight(1f))
                    TextButton({ if (attempts > 1) attempts-- }) { Text("−", style = MaterialTheme.typography.titleLarge) }
                    Text("$attempts", style = MaterialTheme.typography.titleLarge)
                    TextButton({ attempts++ }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                }
                if (sent && attempts == 1 && !alreadySent) Text("First go: that's a flash ⚡", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true)
            }
        },
        confirmButton = { Button({ onLog(if (sent) AscentStyle.SEND else AscentStyle.ATTEMPT, attempts, note.trim()) }) { Text("Log") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
