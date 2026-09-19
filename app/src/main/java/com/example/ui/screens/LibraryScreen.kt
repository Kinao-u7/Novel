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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.GridView
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.example.ui.components.SecondaryOutlinedButton
import com.example.ui.theme.BookSerifFontFamily
import kotlinx.coroutines.launch

enum class LibrarySortOption(val displayName: String) {
    RECENT("Recently Read"),
    TITLE("Title (A-Z)"),
    PROGRESS("Progress"),
    CHAPTERS("Total Chapters")
}

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
    var isGridView by remember { mutableStateOf(false) }
    var sortOption by remember { mutableStateOf(LibrarySortOption.RECENT) }
    var showSortMenu by remember { mutableStateOf(false) }
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
                    // Grid / List toggle
                    IconButton(
                        onClick = { isGridView = !isGridView },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = "Toggle Grid View"
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
                                    onClick = {
                                        sortOption = opt
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Import EPUB Button
                    SecondaryOutlinedButton(
                        text = "EPUB",
                        onClick = {
                            epubPickerLauncher.launch(
                                arrayOf("application/epub+zip", "application/octet-stream", "*/*")
                            )
                        },
                        icon = if (isImporting) null else Icons.Default.FileOpen,
                        modifier = Modifier.height(38.dp)
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    // Open Web Browser Button
                    SecondaryOutlinedButton(
                        text = "+ Web",
                        onClick = { onOpenWebBrowser?.invoke("https://wtr-lab.com") },
                        icon = Icons.Default.Public,
                        modifier = Modifier.height(38.dp)
                    )
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

            // Filter Chips Row with outlined styling
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val filters = listOf("All", "Reading", "Unread", "Downloaded", "EPUBs", "Completed")
                items(filters) { filter ->
                    val isSelected = selectedFilter == filter
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedFilter = filter },
                        label = {
                            Text(
                                filter,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        shape = RoundedCornerShape(6.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            selectedLabelColor = MaterialTheme.colorScheme.primary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            selectedBorderColor = MaterialTheme.colorScheme.primary,
                            selectedBorderWidth = 1.2.dp
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

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

        // Floating Action Button
        FloatingActionButton(
            onClick = {
                epubPickerLauncher.launch(
                    arrayOf("application/epub+zip", "application/octet-stream", "*/*")
                )
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 16.dp, end = 16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(Icons.Default.Add, contentDescription = "Import EPUB")
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
    val progress = if (novel.totalChapters > 0) {
        (novel.lastReadChapterIndex.toFloat() / novel.totalChapters.toFloat()).coerceIn(0f, 1f)
    } else 0f

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
            verticalAlignment = Alignment.CenterVertically
        ) {
            NovelCoverImage(
                coverUrl = novel.coverUrl,
                title = novel.title,
                modifier = Modifier
                    .size(width = 64.dp, height = 92.dp)
                    .clip(RoundedCornerShape(8.dp))
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = novel.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = BookSerifFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = novel.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val readText = if (novel.lastReadChapterIndex > 0) {
                        "Ch. ${novel.lastReadChapterIndex} of ${novel.totalChapters}"
                    } else {
                        "Unread · ${novel.totalChapters} ch"
                    }
                    Text(
                        text = readText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.2.sp
                        )
                    )

                    if (novel.downloadedChaptersCount > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = "Downloaded",
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${novel.downloadedChaptersCount} dl",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Color(0xFF10B981),
                                    fontSize = 10.sp
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}
