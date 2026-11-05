package com.paretino.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.paretino.data.WallRepository
import com.paretino.ui.home.HomeScreen
import com.paretino.ui.logbook.LogbookScreen
import com.paretino.ui.master.MasterDiffScreen
import com.paretino.ui.master.MasterEditorScreen
import com.paretino.ui.master.MastersScreen
import com.paretino.ui.onboarding.OnboardingScreen
import com.paretino.ui.path.PathDetailScreen
import com.paretino.ui.path.PathEditorScreen
import com.paretino.ui.settings.PositionsScreen
import com.paretino.ui.settings.SettingsScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable object HomeRoute
@Serializable object MastersRoute
@Serializable object LogbookRoute
@Serializable object SettingsRoute
@Serializable object OnboardingRoute
@Serializable object PositionsRoute
@Serializable data class PathDetailRoute(val id: Long)
/** [id] 0 creates a path on [masterId]. */
@Serializable data class PathEditorRoute(val id: Long = 0, val masterId: Long = 0)
/** [id] 0 creates a new master from a photo; otherwise edits that master's holds. */
@Serializable data class MasterEditorRoute(val id: Long = 0)
@Serializable data class MasterDiffRoute(val older: Long, val newer: Long)

private data class Tab(val route: Any, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, "Paths", Icons.AutoMirrored.Filled.List),
    Tab(MastersRoute, "Masters", Icons.Filled.Layers),
    Tab(LogbookRoute, "Logbook", Icons.Filled.ShowChart),
    Tab(SettingsRoute, "Settings", Icons.Filled.Settings),
)

@HiltViewModel
class RootViewModel @Inject constructor(walls: WallRepository) : ViewModel() {
    /** null while loading, then whether the wall has been set up. */
    val hasWall = walls.wall.map<_, Boolean?> { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun ParetinoNavHost(root: RootViewModel = hiltViewModel()) {
    val hasWall by root.hasWall.collectAsStateWithLifecycle()
    val ready = hasWall ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    // The host is rebuilt once the wall exists, so Home becomes the start destination.
    key(ready) { MainNav(ready) }
}

@Composable
private fun MainNav(ready: Boolean) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val onTab = tabs.any { t -> entry?.destination?.hasRoute(t.route::class) == true }

    Scaffold(
        bottomBar = {
            if (onTab) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = entry?.destination?.hasRoute(t.route::class) == true,
                        onClick = { nav.switchTab(t.route) },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            nav,
            startDestination = if (ready) HomeRoute else OnboardingRoute,
            modifier = Modifier.padding(bottom = if (onTab) padding.calculateBottomPadding() else 0.dp),
        ) {
            composable<OnboardingRoute> {
                OnboardingScreen()
            }
            composable<HomeRoute> {
                HomeScreen(
                    onOpen = { nav.navigate(PathDetailRoute(it)) },
                    onNewPath = { nav.navigate(PathEditorRoute(masterId = it)) },
                    onNewMaster = { nav.navigate(MasterEditorRoute()) },
                )
            }
            composable<MastersRoute> {
                MastersScreen(
                    onNewMaster = { nav.navigate(MasterEditorRoute()) },
                    onEditHolds = { nav.navigate(MasterEditorRoute(it)) },
                    onDiff = { a, b -> nav.navigate(MasterDiffRoute(a, b)) },
                    onNewPath = { nav.navigate(PathEditorRoute(masterId = it)) },
                )
            }
            composable<LogbookRoute> { LogbookScreen(onOpen = { nav.navigate(PathDetailRoute(it)) }) }
            composable<SettingsRoute> { SettingsScreen(onPositions = { nav.navigate(PositionsRoute) }) }
            composable<PositionsRoute> { PositionsScreen(onBack = nav::popBackStack) }
            composable<PathDetailRoute> {
                val r = it.toRoute<PathDetailRoute>()
                PathDetailScreen(
                    onBack = nav::popBackStack,
                    onEdit = { nav.navigate(PathEditorRoute(id = r.id)) },
                    onOpen = { id -> nav.navigate(PathDetailRoute(id)) { popUpTo<PathDetailRoute> { inclusive = true } } },
                )
            }
            composable<PathEditorRoute> {
                PathEditorScreen(
                    onBack = nav::popBackStack,
                    onSaved = { id ->
                        nav.navigate(PathDetailRoute(id)) { popUpTo<PathEditorRoute> { inclusive = true } }
                    },
                )
            }
            composable<MasterEditorRoute> {
                MasterEditorScreen(onBack = nav::popBackStack, onSaved = { nav.popBackStack() })
            }
            composable<MasterDiffRoute> {
                MasterDiffScreen(onBack = nav::popBackStack, onOpenPath = { id -> nav.navigate(PathDetailRoute(id)) })
            }
        }
    }
}

private fun NavHostController.switchTab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
