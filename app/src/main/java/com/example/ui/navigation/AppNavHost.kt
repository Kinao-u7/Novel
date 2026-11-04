package com.example.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.R
import com.example.data.model.NavTab
import com.example.di.LocalAppContainer
import com.example.ui.screens.discover.DiscoverRoute
import com.example.ui.screens.library.LibraryRoute
import com.example.ui.screens.detail.DetailRoute
import com.example.ui.screens.browse.BrowseRoute
import com.example.ui.screens.profile.ProfileRoute
import com.example.ui.theme.AppTheme

private data class NavTabDef(
    val tab: NavTab,
    val route: Any,
    val labelRes: Int,
    val icon: ImageVector
)

private val NAV_TAB_DEFINITIONS: Map<NavTab, NavTabDef> = mapOf(
    NavTab.LIBRARY to NavTabDef(
        tab = NavTab.LIBRARY,
        route = Library,
        labelRes = R.string.nav_tab_library,
        icon = Icons.Default.AutoStories
    ),
    NavTab.DISCOVER to NavTabDef(
        tab = NavTab.DISCOVER,
        route = Discover,
        labelRes = R.string.nav_tab_discover,
        icon = Icons.Default.Explore
    ),
    NavTab.CULTIVATE to NavTabDef(
        tab = NavTab.CULTIVATE,
        route = Cultivate,
        labelRes = R.string.nav_tab_cultivate,
        icon = Icons.Default.SelfImprovement
    ),
    NavTab.DOWNLOADS to NavTabDef(
        tab = NavTab.DOWNLOADS,
        route = Downloads,
        labelRes = R.string.nav_tab_downloads,
        icon = Icons.Default.CloudDownload
    ),
    NavTab.PROFILE to NavTabDef(
        tab = NavTab.PROFILE,
        route = Profile(),
        labelRes = R.string.nav_tab_profile,
        icon = Icons.Default.Person
    )
)

@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController()
) {
    val container = LocalAppContainer.current
    val appSettings by container.settingsRepository.settings.collectAsStateWithLifecycle()
    val bottomBarTabs = appSettings.bottomBar.tabs

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val isSecondaryRoute = currentDestination?.let { destination ->
        destination.hasRoute(NovelDetail::class) ||
        destination.hasRoute(Reader::class) ||
        destination.hasRoute(BrowseSource::class) ||
        destination.hasRoute(WebBrowser::class)
    } ?: false

    val showBottomBar = !isSecondaryRoute

    fun navigateToTab(tab: NavTab) {
        val def = NAV_TAB_DEFINITIONS[tab] ?: return
        navController.navigate(def.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                val accent = AppTheme.colors.ac
                val muted = AppTheme.colors.mu
                val line = AppTheme.colors.ln
                val navItemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = accent,
                    selectedTextColor = accent,
                    unselectedIconColor = muted,
                    unselectedTextColor = muted,
                    indicatorColor = accent.copy(alpha = 0.14f)
                )
                NavigationBar(
                    containerColor = AppTheme.colors.cd.copy(alpha = 0.92f),
                    modifier = Modifier.drawBehind {
                        drawLine(
                            color = line,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                ) {
                    for (tab in bottomBarTabs) {
                        val tabDef = NAV_TAB_DEFINITIONS[tab] ?: continue
                        val isSelected = currentDestination?.hasRoute(tabDef.route::class) == true
                        val label = stringResource(tabDef.labelRes)
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = { navigateToTab(tab) },
                            icon = {
                                Icon(
                                    imageVector = tabDef.icon,
                                    contentDescription = label
                                )
                            },
                            label = { Text(text = label) },
                            colors = navItemColors
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            NavHost(
                navController = navController,
                startDestination = Library,
                modifier = Modifier.fillMaxSize()
            ) {
                composable<Library> {
                    LibraryRoute(
                        onNovelClick = { novelId ->
                            navController.navigate(NovelDetail(novelId))
                        },
                        onResume = { novelId ->
                            val libraryNovels = container.novelRepository.libraryNovels.value
                            val novel = libraryNovels.firstOrNull { it.id == novelId }
                            val chapterIndex = novel?.lastReadChapterIndex ?: 0
                            navController.navigate(Reader(novelId, chapterIndex))
                        },
                        onDiscoverClick = {
                            navigateToTab(NavTab.DISCOVER)
                        },
                        onWebBrowserClick = {
                            navController.navigate(WebBrowser())
                        },
                        contentPadding = innerPadding
                    )
                }

                composable<Discover> {
                    DiscoverRoute(
                        onOpenSource = { sourceId ->
                            navController.navigate(BrowseSource(sourceId))
                        },
                        contentPadding = innerPadding
                    )
                }

                composable<Cultivate> {
                    PlaceholderScreen(
                        title = stringResource(R.string.screen_cultivate_title),
                        subtitle = stringResource(R.string.placeholder_description),
                        bottomPadding = innerPadding.calculateBottomPadding()
                    )
                }

                composable<Downloads> {
                    PlaceholderScreen(
                        title = stringResource(R.string.screen_downloads_title),
                        subtitle = stringResource(R.string.placeholder_description),
                        bottomPadding = innerPadding.calculateBottomPadding()
                    )
                }

                composable<Profile> {
                    val hiddenTabs = NavTab.entries.filter { it !in bottomBarTabs }
                    ProfileRoute(
                        hiddenTabs = hiddenTabs,
                        tabDefinitions = NAV_TAB_DEFINITIONS.mapValues { it.value.labelRes to it.value.icon },
                        onNavigateToTab = { navigateToTab(it) },
                        contentPadding = innerPadding
                    )
                }

                composable<NovelDetail> {
                    DetailRoute(
                        onBack = {
                            navController.popBackStack()
                        },
                        onReadClick = { novelId, chapterIndex ->
                            navController.navigate(Reader(novelId, chapterIndex))
                        },
                        onChapterClick = { novelId, chapterIndex ->
                            navController.navigate(Reader(novelId, chapterIndex))
                        },
                        onOpenInBrowser = { url ->
                            navController.navigate(WebBrowser(initialUrl = url))
                        },
                        contentPadding = innerPadding
                    )
                }

                composable<Reader> { backStackEntry ->
                    val route = backStackEntry.toRoute<Reader>()
                    PlaceholderScreen(
                        title = stringResource(R.string.screen_reader_title, route.novelId, route.chapterIndex),
                        subtitle = stringResource(R.string.placeholder_description)
                    )
                }

                composable<BrowseSource> {
                    BrowseRoute(
                        onBack = { navController.popBackStack() },
                        onNovelClick = { novelId ->
                            navController.navigate(NovelDetail(novelId))
                        },
                        contentPadding = innerPadding
                    )
                }

                composable<WebBrowser> { backStackEntry ->
                    val route = backStackEntry.toRoute<WebBrowser>()
                    val initialUrl = route.initialUrl ?: "about:blank"
                    PlaceholderScreen(
                        title = stringResource(R.string.screen_web_browser_title),
                        subtitle = initialUrl
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(
    title: String,
    subtitle: String,
    bottomPadding: Dp = 0.dp,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = 18.dp, bottom = bottomPadding),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
