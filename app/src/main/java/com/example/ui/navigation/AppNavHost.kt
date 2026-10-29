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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
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
                            label = { Text(text = label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Library,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable<Library> {
                val libraryNovels by container.novelRepository.libraryNovels.collectAsStateWithLifecycle()
                PlaceholderScreen(
                    title = stringResource(R.string.screen_library_title),
                    subtitle = stringResource(R.string.library_novels_count, libraryNovels.size)
                )
            }

            composable<Discover> {
                PlaceholderScreen(
                    title = stringResource(R.string.screen_discover_title),
                    subtitle = stringResource(R.string.placeholder_description)
                )
            }

            composable<Cultivate> {
                PlaceholderScreen(
                    title = stringResource(R.string.screen_cultivate_title),
                    subtitle = stringResource(R.string.placeholder_description)
                )
            }

            composable<Downloads> {
                PlaceholderScreen(
                    title = stringResource(R.string.screen_downloads_title),
                    subtitle = stringResource(R.string.placeholder_description)
                )
            }

            composable<Profile> {
                val hiddenTabs = NavTab.entries.filter { it !in bottomBarTabs }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.screen_profile_title),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.placeholder_description),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        if (hiddenTabs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = stringResource(R.string.profile_hidden_tabs_header),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            for (tab in hiddenTabs) {
                                val def = NAV_TAB_DEFINITIONS[tab] ?: continue
                                val tabLabel = stringResource(def.labelRes)
                                Button(
                                    onClick = { navigateToTab(tab) },
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = def.icon,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(text = tabLabel)
                                }
                            }
                        }
                    }
                }
            }

            composable<NovelDetail> { backStackEntry ->
                val route = backStackEntry.toRoute<NovelDetail>()
                PlaceholderScreen(
                    title = stringResource(R.string.screen_novel_detail_title, route.novelId),
                    subtitle = stringResource(R.string.placeholder_description)
                )
            }

            composable<Reader> { backStackEntry ->
                val route = backStackEntry.toRoute<Reader>()
                PlaceholderScreen(
                    title = stringResource(R.string.screen_reader_title, route.novelId, route.chapterIndex),
                    subtitle = stringResource(R.string.placeholder_description)
                )
            }

            composable<BrowseSource> { backStackEntry ->
                val route = backStackEntry.toRoute<BrowseSource>()
                PlaceholderScreen(
                    title = stringResource(R.string.screen_browse_source_title, route.sourceId),
                    subtitle = stringResource(R.string.placeholder_description)
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

@Composable
private fun PlaceholderScreen(
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
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
