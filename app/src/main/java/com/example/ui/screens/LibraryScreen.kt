package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Novel
import com.example.data.repository.NovelRepository
import com.example.ui.components.EmptyStateView
import com.example.ui.components.NovelCoverImage
import com.example.ui.components.NovelOptionsBottomSheet
import com.example.ui.components.PrimaryCtaButton
import com.example.ui.components.RatingBadge
import com.example.ui.components.SecondaryOutlinedButton
import com.example.ui.components.TagPill
import com.example.ui.theme.BookSerifFontFamily
import kotlinx.coroutines.launch

enum class LibrarySortOption(val displayName: String) {
    RECENT("Recently Read"),
    TITLE("Title (A-Z)"),
    PROGRESS("Progress"),
    CHAPTERS("Total Chapters")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: NovelRepository,
    onOpenNovel: (Novel) -> Unit,
    onReadChapter: (Novel, String, Int) -> Unit,
    onNavigateToBrowse: () -> Unit,
    onOpenWebBrowser: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libraryNovels by repository.libraryNovels.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }
    var isGridView by remember { mutableStateOf(repository.isLibraryGridView()) }
    var sortOption by remember { mutableStateOf(LibrarySortOption.RECENT) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showAddOptionsSheet by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var selectedNovelForOptions by remember { mutableStateOf<Novel?>(null) }

    // EPUB File Picker
    val epubPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isImporting = true
                try {
                    val fileName = uri.lastPathSegment ?: "Imported_Novel.epub"
                    val novel = repository.importEpub(uri, fileName)
                    onOpenNovel(novel)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    isImporting = false
                }
            }
        }
    }

    // Filter and Sort logic
    val filteredNovels = remember(libraryNovels, searchQuery, selectedFilter, sortOption) {
        val filtered = libraryNovels.filter { novel ->
            val matchesQuery = novel.title.contains(searchQuery, ignoreCase = true) ||
                    novel.author.contains(searchQuery, ignoreCase = true)
            val matchesFilter = when (selectedFilter) {
                "Reading" -> (novel.lastReadChapterIndex > 0 && novel.lastReadChapterIndex < novel.totalChapters)
                "Unread" -> novel.lastReadChapterIndex == 0
                "Completed" -> (novel.totalChapters > 0 && novel.lastReadChapterIndex >= novel.totalChapters)
                "Downloaded" -> novel.downloadedChaptersCount > 0
                "EPUBs" -> novel.isEpub
                else -> true
            }
            matchesQuery && matchesFilter
        }

        when (sortOption) {
            LibrarySortOption.RECENT -> filtered.sortedByDescending { it.lastReadTimestamp }
            LibrarySortOption.TITLE -> filtered.sortedBy { it.title.lowercase() }
            LibrarySortOption.PROGRESS -> filtered.sortedByDescending {
                if (it.totalChapters > 0) it.lastReadChapterIndex.toFloat() / it.totalChapters else 0f
            }
            LibrarySortOption.CHAPTERS -> filtered.sortedByDescending { it.totalChapters }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Header Title & Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Library",
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontFamily = BookSerifFontFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${libraryNovels.size} novels in collection",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Grid / List toggle with persistence
                    IconButton(
                        onClick = {
                            isGridView = !isGridView
                            repository.setLibraryGridView(isGridView)
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = if (isGridView) "Switch to List View" else "Switch to Grid View"
                        )
                    }

                    // Sort menu
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.Sort,
                                contentDescription = "Sort Library",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            LibrarySortOption.values().forEach { opt ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            opt.displayName,
                                            fontWeight = if (sortOption == opt) FontWeight.Bold else FontWeight.Normal,
                                            color = if (sortOption == opt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    leadingIcon = if (sortOption == opt) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    } else null,
                                    onClick = {
                                        sortOption = opt
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Import EPUB Button
                    IconButton(
                        onClick = {
                            epubPickerLauncher.launch(
                                arrayOf("application/epub+zip", "application/octet-stream", "*/*")
                            )
                        }
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.FileOpen,
                                contentDescription = "Import EPUB",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Fixed Browse Web Button (renders clean browser globe icon, not broken empty oval)
                    IconButton(
                        onClick = { onOpenWebBrowser?.invoke("https://wtr-lab.com") }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = "Browse Web",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search your novels...", fontSize = 13.5.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Dedicated Filter Tabs Row
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                val filterTabs = listOf("All", "Reading", "Unread", "Downloaded", "EPUBs", "Completed")
                items(filterTabs) { filter ->
                    val isSelected = selectedFilter == filter
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedFilter = filter },
                        label = {
                            Text(
                                text = filter,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Novels Content
            if (filteredNovels.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (libraryNovels.isEmpty()) {
                        EmptyStateView(
                            icon = Icons.Default.AutoStories,
                            title = "Your library is empty",
                            message = "Explore curated web novels or import your own EPUBs to build your reading collection.",
                            action = {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    PrimaryCtaButton(
                                        text = "Discover Novels",
                                        onClick = onNavigateToBrowse,
                                        icon = Icons.Default.Explore
                                    )
                                    SecondaryOutlinedButton(
                                        text = "Import EPUB",
                                        onClick = {
                                            epubPickerLauncher.launch(
                                                arrayOf("application/epub+zip", "application/octet-stream", "*/*")
                                            )
                                        },
                                        icon = Icons.Default.Add
                                    )
                                }
                            }
                        )
                    } else {
                        EmptyStateView(
                            icon = Icons.Default.Search,
                            title = "No matching novels",
                            message = "Try clearing your search term or choosing a different filter.",
                            action = {
                                SecondaryOutlinedButton(
                                    text = "Reset Filters",
                                    onClick = {
                                        searchQuery = ""
                                        selectedFilter = "All"
                                    }
                                )
                            }
                        )
                    }
                }
            } else if (isGridView) {
                // Modern Grid Layout (matching compact cover sizing and spacing)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(filteredNovels, key = { it.id }) { novel ->
                        LibraryGridItem(
                            novel = novel,
                            onClick = { onOpenNovel(novel) },
                            onLongClick = { selectedNovelForOptions = novel }
                        )
                    }
                }
            } else {
                // Simplified List Layout (no trailing play button or 3-dot menu)
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(filteredNovels, key = { it.id }) { novel ->
                        LibraryNovelItem(
                            novel = novel,
                            onClick = { onOpenNovel(novel) },
                            onLongClick = { selectedNovelForOptions = novel }
                        )
                    }
                }
            }
        }

        // Floating Action Button: Add Novel (inherits central theme button/FAB shape)
        FloatingActionButton(
            onClick = { showAddOptionsSheet = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .padding(bottom = 72.dp) // clear bottom navigation bar
                .testTag("library_add_fab"),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Novel")
        }

        // Add Options Bottom Sheet
        if (showAddOptionsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showAddOptionsSheet = false },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        text = "Add to Library",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                showAddOptionsSheet = false
                                onNavigateToBrowse()
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("Discover Sources", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Find novel websites and catalog sources", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                showAddOptionsSheet = false
                                onOpenWebBrowser?.invoke("https://wtr-lab.com")
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("Open In-App Web Browser", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Browse and scrape web novels directly", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                showAddOptionsSheet = false
                                epubPickerLauncher.launch(
                                    arrayOf("application/epub+zip", "application/octet-stream", "*/*")
                                )
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("Import Local EPUB", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Read your local EPUB files", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Shared Novel Options Bottom Sheet for long-press
        selectedNovelForOptions?.let { novel ->
            NovelOptionsBottomSheet(
                novel = novel,
                isInLibrary = repository.isNovelInLibrary(novel.id),
                repository = repository,
                onDismiss = { selectedNovelForOptions = null },
                onOpenInBrowser = { url -> onOpenWebBrowser?.invoke(url) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryGridItem(
    novel: Novel,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        NovelCoverImage(
            coverUrl = novel.coverUrl,
            title = novel.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(8.dp))
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = novel.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.5.sp,
                lineHeight = 16.sp
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryNovelItem(
    novel: Novel,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            NovelCoverImage(
                coverUrl = novel.coverUrl,
                title = novel.title,
                modifier = Modifier
                    .size(width = 72.dp, height = 104.dp)
                    .clip(RoundedCornerShape(8.dp))
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = novel.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = BookSerifFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    RatingBadge(rating = novel.rating)
                }

                Text(
                    text = novel.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${novel.totalChapters} chapters",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    val progressText = if (novel.lastReadChapterIndex > 0) {
                        "Ch. ${novel.lastReadChapterIndex} read"
                    } else {
                        "Unread"
                    }
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = novel.description.ifBlank { "No description available." },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (novel.genres.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(novel.genres.take(3)) { genre ->
                            TagPill(text = genre)
                        }
                    }
                }
            }
        }
    }
}
