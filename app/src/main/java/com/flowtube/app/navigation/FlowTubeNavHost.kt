package com.flowtube.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.flowtube.app.di.AppContainer
import com.flowtube.app.model.ContentKey
import com.flowtube.app.ui.common.UnavailablePane
import com.flowtube.app.ui.home.HomeScreen
import com.flowtube.app.ui.home.HomeViewModel
import com.flowtube.app.ui.search.SearchScreen
import com.flowtube.app.ui.search.SearchViewModel

@Composable
fun FlowTubeNavHost(
    navController: NavHostController,
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier
    ) {
        composable(Screen.Home.route) {
            val homeViewModel: HomeViewModel = viewModel(
                factory = HomeViewModel.provideFactory(container.catalogRepository)
            )
            HomeScreen(
                viewModel = homeViewModel,
                onVideoClick = { key ->
                    navController.navigate(Screen.Watch.createRoute(key))
                }
            )
        }

        composable(Screen.Search.route) {
            val searchViewModel: SearchViewModel = viewModel(
                factory = SearchViewModel.provideFactory(
                    repository = container.catalogRepository,
                    videoService = container.videoService
                )
            )
            SearchScreen(
                viewModel = searchViewModel,
                onNavigateBack = { navController.popBackStack() },
                onVideoClick = { key ->
                    navController.navigate(Screen.Watch.createRoute(key))
                },
                onChannelClick = { key ->
                    navController.navigate(Screen.Channel.createRoute(key))
                },
                onPlaylistClick = { key ->
                    navController.navigate(Screen.PlaylistUnavailable.createRoute(key))
                }
            )
        }

        composable(Screen.Shorts.route) {
            val shortsViewModel: com.flowtube.app.ui.shorts.ShortsViewModel = viewModel(
                factory = com.flowtube.app.ui.shorts.ShortsViewModel.provideFactory(
                    container.videoService,
                    container.createPlayerController()
                )
            )
            com.flowtube.app.ui.shorts.ShortsScreen(shortsViewModel)
        }

        composable(Screen.Subscriptions.route) {
            val libraryViewModel: com.flowtube.app.ui.library.LibraryViewModel = viewModel(
                factory = com.flowtube.app.ui.library.LibraryViewModel.provideFactory(
                    historyRepository = container.historyRepository,
                    subscriptionRepository = container.subscriptionRepository,
                    playlistRepository = container.playlistRepository
                )
            )
            val feedViewModel: com.flowtube.app.ui.library.SubscriptionFeedViewModel = viewModel(
                factory = com.flowtube.app.ui.library.SubscriptionFeedViewModel.provideFactory(
                    com.flowtube.app.repository.SubscriptionFeedRepository(
                        container.subscriptionRepository,
                        container.videoService
                    )
                )
            )
            com.flowtube.app.ui.library.SubscriptionsScreen(
                viewModel = libraryViewModel,
                feedViewModel = feedViewModel,
                onChannelClick = { key ->
                    navController.navigate(Screen.Channel.createRoute(key))
                },
                onNavigateBack = { navController.popBackStack() },
                onVideoClick = { navController.navigate(Screen.Watch.createRoute(it)) }
            )
        }

        composable(Screen.Library.route) {
            val libraryViewModel: com.flowtube.app.ui.library.LibraryViewModel = viewModel(
                factory = com.flowtube.app.ui.library.LibraryViewModel.provideFactory(
                    historyRepository = container.historyRepository,
                    subscriptionRepository = container.subscriptionRepository,
                    playlistRepository = container.playlistRepository
                )
            )
            com.flowtube.app.ui.library.LibraryScreen(
                viewModel = libraryViewModel,
                onNavigateToHistory = { navController.navigate(Screen.History.route) },
                onNavigateToSubscriptions = { navController.navigate(Screen.Subscriptions.route) },
                onNavigateToPlaylists = { navController.navigate(Screen.Playlists.route) },
                onPlaylistClick = { id -> navController.navigate(Screen.PlaylistDetail.createRoute(id)) },
                onVideoClick = { key -> navController.navigate(Screen.Watch.createRoute(key)) }
            )
        }

        composable(Screen.History.route) {
            val libraryViewModel: com.flowtube.app.ui.library.LibraryViewModel = viewModel(
                factory = com.flowtube.app.ui.library.LibraryViewModel.provideFactory(
                    historyRepository = container.historyRepository,
                    subscriptionRepository = container.subscriptionRepository,
                    playlistRepository = container.playlistRepository
                )
            )
            com.flowtube.app.ui.library.HistoryScreen(
                viewModel = libraryViewModel,
                onVideoClick = { key -> navController.navigate(Screen.Watch.createRoute(key)) },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Playlists.route) {
            val libraryViewModel: com.flowtube.app.ui.library.LibraryViewModel = viewModel(
                factory = com.flowtube.app.ui.library.LibraryViewModel.provideFactory(
                    historyRepository = container.historyRepository,
                    subscriptionRepository = container.subscriptionRepository,
                    playlistRepository = container.playlistRepository
                )
            )
            com.flowtube.app.ui.library.PlaylistsScreen(
                viewModel = libraryViewModel,
                onPlaylistClick = { id -> navController.navigate(Screen.PlaylistDetail.createRoute(id)) },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.PlaylistDetail.route,
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType })
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: 0L
            val libraryViewModel: com.flowtube.app.ui.library.LibraryViewModel = viewModel(
                factory = com.flowtube.app.ui.library.LibraryViewModel.provideFactory(
                    historyRepository = container.historyRepository,
                    subscriptionRepository = container.subscriptionRepository,
                    playlistRepository = container.playlistRepository
                )
            )
            com.flowtube.app.ui.library.PlaylistDetailScreen(
                playlistId = playlistId,
                viewModel = libraryViewModel,
                onVideoClick = { key -> navController.navigate(Screen.Watch.createRoute(key)) },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            val settingsViewModel: com.flowtube.app.settings.SettingsViewModel = viewModel(
                factory = com.flowtube.app.settings.SettingsViewModel.provideFactory(container.settingsRepository)
            )
            com.flowtube.app.settings.SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Channel.route,
            arguments = listOf(
                navArgument("serviceId") { type = NavType.IntType },
                navArgument("nativeId") { type = NavType.StringType }
            )
        ) { entry ->
            val key = Screen.Channel.parseNavArgument(
                entry.arguments?.getInt("serviceId"),
                entry.arguments?.getString("nativeId")
            )
            if (key == null) {
                UnavailablePane("Channel")
            } else {
                val model: com.flowtube.app.ui.channel.ChannelViewModel = viewModel(
                    factory = com.flowtube.app.ui.channel.ChannelViewModel.provideFactory(container.videoService)
                )
                com.flowtube.app.ui.channel.ChannelScreen(
                    key = key,
                    viewModel = model,
                    onVideoClick = { navController.navigate(Screen.Watch.createRoute(it)) },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = Screen.ChannelUnavailable.route,
            arguments = listOf(
                navArgument("serviceId") { type = NavType.IntType },
                navArgument("nativeId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val rawServiceId = backStackEntry.arguments?.getInt("serviceId")
            val rawNativeId = backStackEntry.arguments?.getString("nativeId")
            val key = Screen.ChannelUnavailable.parseNavArgument(rawServiceId, rawNativeId)
            val displayId = key?.nativeId ?: ""
            UnavailablePane(
                featureName = "Channel ($displayId) Unavailable",
                modifier = Modifier.testTag("channel_unavailable_screen")
            )
        }

        composable(
            route = Screen.PlaylistUnavailable.route,
            arguments = listOf(
                navArgument("serviceId") { type = NavType.IntType },
                navArgument("nativeId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val rawServiceId = backStackEntry.arguments?.getInt("serviceId")
            val rawNativeId = backStackEntry.arguments?.getString("nativeId")
            val key = Screen.PlaylistUnavailable.parseNavArgument(rawServiceId, rawNativeId)
            val displayId = key?.nativeId ?: ""
            UnavailablePane(
                featureName = "Playlist ($displayId) Unavailable",
                modifier = Modifier.testTag("playlist_unavailable_screen")
            )
        }

        composable(
            route = Screen.Watch.route,
            arguments = listOf(
                navArgument("serviceId") { type = NavType.IntType },
                navArgument("nativeId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val rawServiceId = backStackEntry.arguments?.getInt("serviceId")
            val rawNativeId = backStackEntry.arguments?.getString("nativeId")
            val key = Screen.Watch.parseNavArgument(rawServiceId, rawNativeId)

            if (key == null) {
                UnavailablePane(
                    featureName = "Video Unavailable",
                    modifier = Modifier.testTag("invalid_watch_screen")
                )
            } else {
                val watchViewModel: com.flowtube.app.ui.watch.WatchViewModel = viewModel(
                    factory = com.flowtube.app.ui.watch.WatchViewModel.provideFactory(
                        videoService = container.videoService,
                        playerControllerFactory = { container.createPlayerController() },
                        catalogRepository = container.catalogRepository,
                        historyRepository = container.historyRepository,
                        subscriptionRepository = container.subscriptionRepository,
                        playlistRepository = container.playlistRepository
                    )
                )
                com.flowtube.app.ui.watch.WatchScreen(
                    contentKey = key,
                    viewModel = watchViewModel,
                    fullscreenHostHandlerFactory = container.fullscreenHostHandlerFactory,
                    onNavigateBack = { navController.popBackStack() },
                    onRelatedVideoClick = { navController.navigate(Screen.Watch.createRoute(it)) }
                )
            }
        }
    }
}
