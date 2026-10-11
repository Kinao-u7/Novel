package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.example.BuildConfig
import com.example.data.changelog.ChangelogRepository
import com.example.data.model.Novel
import com.example.data.repository.NovelRepository
import com.example.data.source.SourceManager
import com.example.data.source.webscrape.ScrapedNovelData
import com.example.ui.screens.BrowseScreen
import com.example.ui.screens.ConfirmNovelScreen
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.NovelDetailScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.ReaderScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.WebBrowserScreen
import com.example.ui.screens.SettingsSubPage
import com.example.ui.screens.WhatsNewDialog

enum class MainTab(val title: String) {
    LIBRARY("Library"),
    BROWSE("Discover"),
    DOWNLOADS("Downloads"),
    PROFILE("Profile")
}

sealed class Screen {
    object Main : Screen()
    data class NovelDetail(val novel: Novel) : Screen()
    data class Reader(val novel: Novel, val chapterId: String, val chapterIndex: Int) : Screen()
    data class Settings(val initialSubPage: SettingsSubPage = SettingsSubPage.HUB) : Screen()
    data class WebBrowser(val initialUrl: String = "https://wtr-lab.com") : Screen()
    data class ConfirmScrapedNovel(val scrapedData: ScrapedNovelData) : Screen()
}

@Composable
fun NovelReaderApp(
    repository: NovelRepository? = null
) {
    val context = LocalContext.current
    val repo = repository ?: remember { NovelRepository(context) }
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Main) }
    var currentTab by remember { mutableStateOf(MainTab.LIBRARY) }

    // First setup storage folder prompt
    val prefs = remember { context.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE) }
    var showSetupStoragePrompt by remember {
        mutableStateOf(!prefs.getBoolean("setup_storage_prompt_shown", false))
    }

    // What's New dialog: shown once per new version if installed versionCode > last seen
    val lastSeenVersion = remember { repo.getLastSeenChangelogVersion() }
    var whatsNewReleaseToShow by remember {
        mutableStateOf(
            if (BuildConfig.VERSION_CODE > lastSeenVersion) {
                ChangelogRepository.getReleaseForVersion(BuildConfig.VERSION_CODE)
            } else null
        )
    }

    val firstRunFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val doc = DocumentFile.fromTreeUri(context, treeUri)
            val displayName = doc?.name ?: treeUri.lastPathSegment ?: "Custom Folder"
            repo.updateDownloadSettings(
                repo.downloadSettings.value.copy(
                    customDownloadLocationUri = treeUri.toString(),
                    customDownloadLocationDisplayName = displayName
                )
            )
            Toast.makeText(context, "Storage folder set to: $displayName", Toast.LENGTH_SHORT).show()
        }
        prefs.edit().putBoolean("setup_storage_prompt_shown", true).apply()
        showSetupStoragePrompt = false
    }

    // Handle Android Back Navigation
    BackHandler(enabled = currentScreen !is Screen.Main) {
        when (val s = currentScreen) {
            is Screen.Reader -> {
                currentScreen = Screen.NovelDetail(s.novel)
            }
            is Screen.NovelDetail -> {
                currentScreen = Screen.Main
            }
            is Screen.Settings -> {
                currentScreen = Screen.Main
            }
            is Screen.WebBrowser -> {
                currentScreen = Screen.Main
            }
            is Screen.ConfirmScrapedNovel -> {
                currentScreen = Screen.WebBrowser(s.scrapedData.sourceUrl)
            }
            Screen.Main -> {}
        }
    }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            fadeIn(animationSpec = tween(120)) togetherWith ExitTransition.None
        },
        label = "ScreenTransition"
    ) { screen ->
        when (screen) {
            is Screen.Main -> {
                Scaffold(
                    bottomBar = {
                        Column {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                thickness = 1.dp
                            )
                            val navColors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 0.dp
                            ) {
                                NavigationBarItem(
                                    selected = currentTab == MainTab.LIBRARY,
                                    onClick = { currentTab = MainTab.LIBRARY },
                                    colors = navColors,
                                    icon = { Icon(Icons.Default.AutoStories, contentDescription = "Library") },
                                    label = {
                                        Text(
                                            "Library",
                                            fontSize = 11.sp,
                                            fontWeight = if (currentTab == MainTab.LIBRARY) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                )
                                NavigationBarItem(
                                    selected = currentTab == MainTab.BROWSE,
                                    onClick = { currentTab = MainTab.BROWSE },
                                    colors = navColors,
                                    icon = { Icon(Icons.Default.Explore, contentDescription = "Discover") },
                                    label = {
                                        Text(
                                            "Discover",
                                            fontSize = 11.sp,
                                            fontWeight = if (currentTab == MainTab.BROWSE) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                )
                                NavigationBarItem(
                                    selected = currentTab == MainTab.DOWNLOADS,
                                    onClick = { currentTab = MainTab.DOWNLOADS },
                                    colors = navColors,
                                    icon = { Icon(Icons.Default.CloudDownload, contentDescription = "Downloads") },
                                    label = {
                                        Text(
                                            "Downloads",
                                            fontSize = 11.sp,
                                            fontWeight = if (currentTab == MainTab.DOWNLOADS) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                )
                                NavigationBarItem(
                                    selected = currentTab == MainTab.PROFILE,
                                    onClick = { currentTab = MainTab.PROFILE },
                                    colors = navColors,
                                    icon = { Icon(Icons.Default.Person, contentDescription = "Profile") },
                                    label = {
                                        Text(
                                            "Profile",
                                            fontSize = 11.sp,
                                            fontWeight = if (currentTab == MainTab.PROFILE) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentTab) {
                            MainTab.LIBRARY -> LibraryScreen(
                                repository = repo,
                                onOpenNovel = { novel -> currentScreen = Screen.NovelDetail(novel) },
                                onReadChapter = { novel, chId, chIdx ->
                                    currentScreen = Screen.Reader(novel, chId, chIdx)
                                },
                                onNavigateToBrowse = { currentTab = MainTab.BROWSE },
                                onOpenWebBrowser = { url -> currentScreen = Screen.WebBrowser(url) }
                            )
                            MainTab.BROWSE -> BrowseScreen(
                                repository = repo,
                                onOpenNovel = { novel -> currentScreen = Screen.NovelDetail(novel) },
                                onOpenWebBrowser = { url -> currentScreen = Screen.WebBrowser(url) }
                            )
                            MainTab.DOWNLOADS -> DownloadsScreen(
                                repository = repo,
                                onOpenNovel = { novel -> currentScreen = Screen.NovelDetail(novel) },
                                onReadChapter = { novel, chId, chIdx ->
                                    currentScreen = Screen.Reader(novel, chId, chIdx)
                                }
                            )
                            MainTab.PROFILE -> ProfileScreen(
                                repository = repo,
                                onNavigateToSettingsSubPage = { subPage ->
                                    currentScreen = Screen.Settings(subPage)
                                },
                                onNavigateToDownloads = {
                                    currentTab = MainTab.DOWNLOADS
                                },
                                onOpenNovel = { novel ->
                                    currentScreen = Screen.NovelDetail(novel)
                                }
                            )
                        }
                    }
                }
            }

            is Screen.NovelDetail -> {
                NovelDetailScreen(
                    novel = screen.novel,
                    repository = repo,
                    onBack = { currentScreen = Screen.Main },
                    onReadChapter = { novel, chapterId, chapterIndex ->
                        currentScreen = Screen.Reader(novel, chapterId, chapterIndex)
                    },
                    onOpenInBrowser = { url -> currentScreen = Screen.WebBrowser(url) }
                )
            }

            is Screen.Reader -> {
                ReaderScreen(
                    novel = screen.novel,
                    initialChapterId = screen.chapterId,
                    initialChapterIndex = screen.chapterIndex,
                    repository = repo,
                    onBack = {
                        currentScreen = Screen.NovelDetail(screen.novel)
                    },
                    onOpenWebUrl = { url ->
                        currentScreen = Screen.WebBrowser(url)
                    }
                )
            }

            is Screen.Settings -> {
                SettingsScreen(
                    repository = repo,
                    initialSubPage = screen.initialSubPage,
                    onNavigateToDownloads = {
                        currentScreen = Screen.Main
                        currentTab = MainTab.DOWNLOADS
                    },
                    onReadChapter = { novel, chapterId, chapterIndex ->
                        currentScreen = Screen.Reader(novel, chapterId, chapterIndex)
                    },
                    onBack = {
                        currentScreen = Screen.Main
                    }
                )
            }

            is Screen.WebBrowser -> {
                WebBrowserScreen(
                    initialUrl = screen.initialUrl,
                    repository = repo,
                    onBack = { currentScreen = Screen.Main },
                    onNovelSaved = { savedNovel ->
                        currentScreen = Screen.NovelDetail(savedNovel)
                    },
                    onScrapedNovel = { scraped ->
                        currentScreen = Screen.ConfirmScrapedNovel(scraped)
                    }
                )
            }

            is Screen.ConfirmScrapedNovel -> {
                ConfirmNovelScreen(
                    scrapedData = screen.scrapedData,
                    repository = repo,
                    onBack = {
                        currentScreen = Screen.WebBrowser(screen.scrapedData.sourceUrl)
                    },
                    onNovelSaved = { savedNovel ->
                        currentScreen = Screen.NovelDetail(savedNovel)
                    }
                )
            }
        }
    }

    if (showSetupStoragePrompt) {
        AlertDialog(
            onDismissRequest = {
                prefs.edit().putBoolean("setup_storage_prompt_shown", true).apply()
                showSetupStoragePrompt = false
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text("Choose Download Location", fontWeight = FontWeight.Bold)
            },
            text = {
                Text("Would you like to select a custom folder on your device for storing downloaded novel chapters? You can also skip this and use the default internal app storage, or change it anytime in Settings.")
            },
            confirmButton = {
                Button(onClick = { firstRunFolderPicker.launch(null) }) {
                    Text("Select Folder")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        prefs.edit().putBoolean("setup_storage_prompt_shown", true).apply()
                        showSetupStoragePrompt = false
                    }
                ) {
                    Text("Skip (Use Internal)")
                }
            }
        )
    }

    whatsNewReleaseToShow?.let { release ->
        WhatsNewDialog(
            release = release,
            onDismiss = {
                repo.setLastSeenChangelogVersion(BuildConfig.VERSION_CODE)
                whatsNewReleaseToShow = null
            }
        )
    }
}
