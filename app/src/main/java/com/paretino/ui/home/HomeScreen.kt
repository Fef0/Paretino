package com.paretino.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.paretino.data.Prefs
import com.paretino.data.db.ClimberDao
import com.paretino.data.db.ClimberEntity
import com.paretino.data.db.MasterDao
import com.paretino.data.db.MasterSummary
import com.paretino.data.db.PathDao
import com.paretino.data.db.PathSummary
import com.paretino.domain.GradeScale
import com.paretino.domain.Grades
import com.paretino.ui.common.Avatar
import com.paretino.ui.common.EmptyState
import com.paretino.ui.common.GradeChip
import com.paretino.ui.common.Stars
import com.paretino.ui.common.toOverlay
import com.paretino.ui.components.OverlayHold
import com.paretino.ui.components.WallCanvas
import com.paretino.ui.components.rememberPhoto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SortBy(val label: String) { NEWEST("Newest"), GRADE_UP("Easiest first"), GRADE_DOWN("Hardest first"), RATING("Best rated") }
enum class TickFilter(val label: String) { ALL("All"), TODO("To do"), SENT("Sent") }

data class HomeFilters(
    val query: String = "",
    val masterId: Long? = null,
    val favoritesOnly: Boolean = false,
    val tick: TickFilter = TickFilter.ALL,
    val grades: IntRange = 0..Grades.FONT.lastIndex,
    val sort: SortBy = SortBy.NEWEST,
)

data class PathCard(val summary: PathSummary, val holds: List<OverlayHold>)

data class HomeState(
    val loading: Boolean = true,
    val cards: List<PathCard> = emptyList(),
    val totalPaths: Int = 0,
    val masters: List<MasterSummary> = emptyList(),
    val activeMasterId: Long? = null,
    val filters: HomeFilters = HomeFilters(),
    val scale: GradeScale = GradeScale.FONT,
    val climbers: List<ClimberEntity> = emptyList(),
    val climber: ClimberEntity? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    paths: PathDao,
    masters: MasterDao,
    climbers: ClimberDao,
    private val prefs: Prefs,
) : ViewModel() {
    private val filters = MutableStateFlow(HomeFilters())

    private val summaries = prefs.activeClimberId.flatMapLatest { paths.summaries(it) }
    private val shapes = combine(paths.allHoldShapes(), summaries) { s, list -> list to s.groupBy { it.pathId } }

    val state = combine(
        shapes, masters.summaries(), filters, prefs.gradeScale,
        combine(climbers.climbers(), prefs.activeClimberId) { all, id -> all to all.firstOrNull { it.id == id } },
    ) { (list, holdsByPath), masterList, f, scale, (all, me) ->
        val filtered = list.filter { s ->
            (f.query.isBlank() || s.path.title.contains(f.query, true) || s.path.setterName.contains(f.query, true)) &&
                (f.masterId == null || s.path.masterId == f.masterId) &&
                (!f.favoritesOnly || s.isFavorite) &&
                when (f.tick) { TickFilter.ALL -> true; TickFilter.TODO -> !s.tickedByMe; TickFilter.SENT -> s.tickedByMe } &&
                (s.path.grade == null || s.path.grade in f.grades)
        }
        val sorted = when (f.sort) {
            SortBy.NEWEST -> filtered.sortedByDescending { it.path.createdAt }
            SortBy.GRADE_UP -> filtered.sortedBy { it.path.grade ?: Int.MAX_VALUE }
            SortBy.GRADE_DOWN -> filtered.sortedByDescending { it.path.grade ?: -1 }
            SortBy.RATING -> filtered.sortedByDescending { it.avgStars ?: 0f }
        }
        HomeState(
            loading = false,
            cards = sorted.map { s -> PathCard(s, holdsByPath[s.path.id].orEmpty().map { it.toOverlay() }) },
            totalPaths = list.size,
            masters = masterList,
            activeMasterId = masterList.firstOrNull { it.master.isActive }?.master?.id,
            filters = f,
            scale = scale,
            climbers = all,
            climber = me,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    fun update(block: (HomeFilters) -> HomeFilters) = filters.update(block)
    fun switchClimber(id: Long) = viewModelScope.launch { prefs.setActiveClimber(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpen: (Long) -> Unit,
    onNewPath: (masterId: Long) -> Unit,
    onNewMaster: () -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var climberMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var showGrades by remember { mutableStateOf(false) }
    val f = state.filters

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paretino", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                actions = {
                    Box {
                        IconButton({ sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            SortBy.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label, fontWeight = if (s == f.sort) FontWeight.Bold else null) },
                                    onClick = { vm.update { it.copy(sort = s) }; sortMenu = false },
                                )
                            }
                        }
                    }
                    state.climber?.let { me ->
                        Box {
                            IconButton({ climberMenu = true }) { Avatar(me.name, Color(me.colorArgb), 30.dp) }
                            DropdownMenu(climberMenu, { climberMenu = false }) {
                                state.climbers.forEach { c ->
                                    DropdownMenuItem(
                                        leadingIcon = { Avatar(c.name, Color(c.colorArgb), 24.dp) },
                                        text = { Text(c.name, fontWeight = if (c.id == me.id) FontWeight.Bold else null) },
                                        onClick = { vm.switchClimber(c.id); climberMenu = false },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            state.activeMasterId?.let { id ->
                ExtendedFloatingActionButton(
                    onClick = { onNewPath(f.masterId ?: id) },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("New path") },
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> Unit
            state.masters.isEmpty() -> EmptyState(
                Icons.Filled.AddAPhoto, "Photograph your wall",
                "A master is a photo of the wall as it is set now. Paretino finds the holds on it, then you build paths by tapping them.",
                Modifier.padding(padding),
            ) { Button(onNewMaster) { Text("Create the first master") } }

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    OutlinedTextField(
                        f.query, { q -> vm.update { it.copy(query = q) } },
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        placeholder = { Text("Search paths or setters") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        singleLine = true, shape = RoundedCornerShape(50),
                    )
                }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(f.favoritesOnly, { vm.update { it.copy(favoritesOnly = !it.favoritesOnly) } },
                                label = { Text("Favourites") },
                                leadingIcon = { Icon(Icons.Filled.Favorite, null, Modifier.size(16.dp)) })
                        }
                        items(TickFilter.entries.drop(1)) { t ->
                            FilterChip(f.tick == t, { vm.update { it.copy(tick = if (it.tick == t) TickFilter.ALL else t) } }, label = { Text(t.label) })
                        }
                        item {
                            val all = f.grades == 0..Grades.FONT.lastIndex
                            FilterChip(!all || showGrades, { showGrades = !showGrades }, label = {
                                Text(if (all) "Grade" else "${Grades.label(f.grades.first, state.scale)}–${Grades.label(f.grades.last, state.scale)}")
                            })
                        }
                        item {
                            FilterChip(f.masterId == null, { vm.update { it.copy(masterId = null) } }, label = { Text("All masters") })
                        }
                        items(state.masters, key = { it.master.id }) { m ->
                            FilterChip(f.masterId == m.master.id, { vm.update { it.copy(masterId = m.master.id) } },
                                label = { Text(m.master.name + if (m.master.isActive) " •" else "") })
                        }
                    }
                }
                if (showGrades) item {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        RangeSlider(
                            value = f.grades.first.toFloat()..f.grades.last.toFloat(),
                            onValueChange = { r -> vm.update { it.copy(grades = r.start.toInt()..r.endInclusive.toInt()) } },
                            valueRange = 0f..Grades.FONT.lastIndex.toFloat(),
                            steps = Grades.FONT.size - 2,
                        )
                    }
                }
                if (state.cards.isEmpty()) item {
                    EmptyState(
                        Icons.Filled.Terrain,
                        if (state.totalPaths == 0) "No paths yet" else "Nothing matches",
                        if (state.totalPaths == 0) "Tap New path and pick holds on the wall." else "Try clearing some filters.",
                        Modifier.padding(top = 24.dp),
                    )
                }
                items(state.cards, key = { it.summary.path.id }) { card ->
                    PathCardView(card, state.scale, onClick = { onOpen(card.summary.path.id) }, Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun PathCardView(card: PathCard, scale: GradeScale, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = card.summary
    val photo = rememberPhoto(s.rectifiedPath, 900)
    Card(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box {
            WallCanvas(
                photo, card.holds,
                Modifier.fillMaxWidth().aspectRatio(photo?.let { it.width.toFloat() / it.height } ?: 1.4f),
                zoomable = false, dimWall = true,
            )
            GradeChip(s.path.grade, scale, Modifier.align(Alignment.BottomStart).padding(10.dp))
            if (s.isFavorite) Icon(Icons.Filled.Favorite, "Favourite", Modifier.align(Alignment.TopEnd).padding(10.dp), tint = Color(0xFFE5484D))
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.path.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(s.masterName, s.path.setterName.ifBlank { null }?.let { "by $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Stars(s.avgStars ?: 0f, size = 14.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("${s.sendCount} send${if (s.sendCount == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (s.tickedByMe) Icon(Icons.Filled.CheckCircle, "Sent", tint = MaterialTheme.colorScheme.tertiary)
            else Icon(Icons.Filled.FavoriteBorder, null, tint = Color.Transparent)
        }
    }
}
