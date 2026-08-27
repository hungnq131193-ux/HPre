package com.hpre.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hpre.app.di.AppContainer
import com.hpre.app.R
import com.hpre.app.player.SessionPlayerController
import com.hpre.app.ui.player.MiniPlayer

private sealed class BottomNavItem(
    val route: String,
    @StringRes val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Home : BottomNavItem(
        route = Screen.Home.route,
        titleRes = R.string.nav_home,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home
    )

    data object Subscriptions : BottomNavItem(
        route = Screen.Subscriptions.route,
        titleRes = R.string.nav_subscriptions,
        selectedIcon = Icons.Filled.Subscriptions,
        unselectedIcon = Icons.Outlined.Subscriptions
    )

    data object Library : BottomNavItem(
        route = Screen.Library.route,
        titleRes = R.string.nav_library,
        selectedIcon = Icons.Filled.VideoLibrary,
        unselectedIcon = Icons.Outlined.VideoLibrary
    )
}

private val bottomNavItems = listOf(
    BottomNavItem.Home,
    BottomNavItem.Subscriptions,
    BottomNavItem.Library
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScaffold(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val isTopLevelDestination = currentRoute in listOf(
        Screen.Home.route,
        Screen.Subscriptions.route,
        Screen.Library.route
    )

    val isWatchScreen = currentRoute?.startsWith("watch/") == true
    val app = LocalContext.current.applicationContext as? com.hpre.app.HPreApplication

    DisposableEffect(isWatchScreen, app) {
        app?.playbackUiCoordinator?.setWatchVisible(isWatchScreen)
        onDispose {
            app?.playbackUiCoordinator?.setWatchVisible(false)
        }
    }

    val playerController = remember(container) { container.createPlayerController() }
    val playbackState by playerController.state.collectAsStateWithLifecycle()
    val hasActiveMedia = playbackState.key != null

    val playbackUiState by (app?.playbackUiCoordinator?.state ?: kotlinx.coroutines.flow.MutableStateFlow(com.hpre.app.player.PlaybackUiState())).collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(playbackUiState.backgroundPlaybackEnabled, playbackUiState.isInPip, playerController) {
        if (playerController is SessionPlayerController) {
            playerController.updateLifecyclePolicy(
                backgroundEnabled = playbackUiState.backgroundPlaybackEnabled,
                pipActiveOrEntering = playbackUiState.isInPip
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (isTopLevelDestination) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PlayCircleOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "HPre",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { navController.navigate(Screen.Search.route) },
                            modifier = Modifier.testTag("top_bar_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.action_search)
                            )
                        }
                        IconButton(
                            onClick = { navController.navigate(Screen.Settings.route) },
                            modifier = Modifier.testTag("top_bar_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.action_settings)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    windowInsets = WindowInsets.statusBars,
                    modifier = Modifier.testTag("root_top_bar")
                )
            }
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!isWatchScreen && hasActiveMedia) {
                    MiniPlayer(
                        playerController = playerController,
                        // The navigation bar below already consumes the system inset. Without it
                        // (search, channel, history, ...) the mini player would sit underneath the
                        // system navigation bar, so pad it here instead.
                        modifier = if (isTopLevelDestination) {
                            Modifier
                        } else {
                            Modifier.navigationBarsPadding()
                        },
                        onExpandWatch = { key ->
                            navController.navigate(Screen.Watch.createRoute(key))
                        },
                        onDismiss = {
                            if (playerController is SessionPlayerController) {
                                playerController.clearMedia()
                            } else {
                                playerController.pause()
                            }
                        }
                    )
                }

                if (isTopLevelDestination) {
                    NavigationBar(
                        windowInsets = WindowInsets.navigationBars,
                        modifier = Modifier.testTag("root_bottom_bar")
                    ) {
                        bottomNavItems.forEach { item ->
                            val selected = currentRoute == item.route
                            val title = stringResource(item.titleRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (currentRoute != item.route) {
                                        navController.navigate(item.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = title
                                    )
                                },
                                label = { Text(text = title) },
                                modifier = Modifier.testTag("bottom_nav_${item.route}")
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize().testTag("root_scaffold")
    ) { innerPadding ->
        HPreNavHost(
            navController = navController,
            container = container,
            modifier = Modifier.padding(innerPadding)
        )
    }
}
