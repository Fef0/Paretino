package com.paretino.ui.master

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.paretino.data.MasterRepository
import com.paretino.data.db.MasterDao
import com.paretino.data.db.MasterSummary
import com.paretino.ui.common.EmptyState
import com.paretino.ui.common.formatDate
import com.paretino.ui.components.rememberPhoto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MastersViewModel @Inject constructor(dao: MasterDao, private val repo: MasterRepository) : ViewModel() {
    val masters = dao.summaries().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun setActive(id: Long) = viewModelScope.launch { repo.setActive(id) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
    fun rename(m: MasterSummary, name: String, setter: String) = viewModelScope.launch { repo.rename(m.master.id, name, setter) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MastersScreen(
    onNewMaster: () -> Unit,
    onEditHolds: (Long) -> Unit,
    onDiff: (older: Long, newer: Long) -> Unit,
    onNewPath: (Long) -> Unit,
    vm: MastersViewModel = hiltViewModel(),
) {
    val masters by vm.masters.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<MasterSummary?>(null) }
    var comparing by remember { mutableStateOf<MasterSummary?>(null) }
    var renaming by remember { mutableStateOf<MasterSummary?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Masters") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNewMaster, icon = { Icon(Icons.Filled.AddAPhoto, null) }, text = { Text("New master") })
        },
    ) { padding ->
        val list = masters ?: return@Scaffold
        if (list.isEmpty()) {
            EmptyState(
                Icons.Filled.AddAPhoto, "No masters yet",
                "Each time the holds are re-set, photograph the wall again: that's a new master. Paths belong to the master they were set on.",
                Modifier.padding(padding),
            ) { Button(onNewMaster) { Text("Photograph the wall") } }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(list, key = { it.master.id }) { m ->
                MasterCard(
                    m,
                    onSetActive = { vm.setActive(m.master.id) },
                    onNewPath = { onNewPath(m.master.id) },
                    onEditHolds = { onEditHolds(m.master.id) },
                    onCompare = { comparing = m },
                    onRename = { renaming = m },
                    onDelete = { deleting = m },
                    canCompare = list.size > 1,
                )
            }
        }
    }

    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${m.master.name}?") },
            text = { Text(if (m.pathCount > 0) "Its ${m.pathCount} paths and their logbook entries are deleted too." else "The photo and its holds are deleted.") },
            confirmButton = { TextButton({ vm.delete(m.master.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }
    comparing?.let { m ->
        AlertDialog(
            onDismissRequest = { comparing = null },
            title = { Text("Compare ${m.master.name} with…") },
            text = {
                Column {
                    masters.orEmpty().filter { it.master.id != m.master.id }.forEach { other ->
                        ListItem(
                            headlineContent = { Text(other.master.name) },
                            supportingContent = { Text(formatDate(other.master.createdAt)) },
                            modifier = Modifier.clickable {
                                comparing = null
                                val (old, new) = listOf(m.master, other.master).sortedBy { it.createdAt }
                                onDiff(old.id, new.id)
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ comparing = null }) { Text("Cancel") } },
        )
    }
    renaming?.let { m ->
        var name by remember { mutableStateOf(m.master.name) }
        var setter by remember { mutableStateOf(m.master.setterName) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename master") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(setter, { setter = it }, label = { Text("Set by") }, singleLine = true)
                }
            },
            confirmButton = { TextButton({ vm.rename(m, name.trim(), setter.trim()); renaming = null }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MasterCard(
    m: MasterSummary,
    onSetActive: () -> Unit,
    onNewPath: () -> Unit,
    onEditHolds: () -> Unit,
    onCompare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    canCompare: Boolean,
) {
    val photo = rememberPhoto(m.master.rectifiedPath, 900)
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Box {
            com.paretino.ui.components.WallCanvas(
                photo, emptyList(),
                Modifier.fillMaxWidth().aspectRatio(photo?.let { it.width.toFloat() / it.height } ?: 1.4f), zoomable = false,
            )
            if (m.master.isActive) AssistChip({}, { Text("Active") }, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.master.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${m.holdCount} holds · ${m.pathCount} paths · ${formatDate(m.master.createdAt)}" +
                        (m.master.setterName.ifBlank { null }?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!m.master.isActive) TextButton(onSetActive) { Text("Set active") }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("New path") }, { menu = false; onNewPath() }, leadingIcon = { Icon(Icons.Filled.Add, null) })
                    DropdownMenuItem({ Text("Edit holds") }, { menu = false; onEditHolds() }, leadingIcon = { Icon(Icons.Filled.Edit, null) })
                    if (canCompare) DropdownMenuItem({ Text("Compare with…") }, { menu = false; onCompare() }, leadingIcon = { Icon(Icons.Filled.CompareArrows, null) })
                    DropdownMenuItem({ Text("Rename") }, { menu = false; onRename() }, leadingIcon = { Icon(Icons.Filled.EditNote, null) })
                    DropdownMenuItem({ Text("Delete") }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
                }
            }
        }
    }
}
