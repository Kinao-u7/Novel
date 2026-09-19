package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Novel
import com.example.data.repository.NovelRepository
import com.example.data.source.SourceManager
import com.example.data.source.webscrape.WebScrapeSource
import com.example.ui.components.EmptyStateView
import com.example.ui.components.NovelCoverImage
import com.example.ui.components.RatingBadge
import com.example.ui.components.TagPill
import com.example.ui.theme.BookSerifFontFamily

@Composable
fun BrowseScreen(
    repository: NovelRepository,
    onOpenNovel: (Novel) -> Unit,
    onOpenWebBrowser: (String) -> Unit = {}
) {
    val sources by SourceManager.sources.collectAsState()
    val activeSourceId by SourceManager.activeSourceId.collectAsState()
    val libraryNovels by repository.libraryNovels.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedGenre by remember { mutableStateOf("All") }
    var novelsList by remember { mutableStateOf<List<Novel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // Fetch novels when source or search changes
    LaunchedEffect(activeSourceId, searchQuery) {
        isLoading = true
        try {
            val source = SourceManager.getActiveSource()
            novelsList = if (searchQuery.isBlank()) {
                source.getPopularNovels()
            } else {
                source.searchNovels(searchQuery)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLoading = false
        }
    }

    val genres = remember(novelsList) {
        val all = mutableSetOf("All")
        novelsList.forEach { all.addAll(it.genres) }
        all.toList()
    }

    val displayedNovels = remember(novelsList, selectedGenre) {
        if (selectedGenre == "All") novelsList
        else novelsList.filter { it.genres.contains(selectedGenre) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Title Header with Web Browser shortcut
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Discover Novels",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontFamily = BookSerifFontFamily,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            OutlinedButton(
                onClick = { onOpenWebBrowser("https://wtr-lab.com") },
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Web Browser", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Search Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search titles, authors, or genres...", fontSize = 13.5.sp) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            },
            singleLine = true,
            trailingIcon = {
                if (searchQuery.isNotBlank()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
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

        // Source Plugin Selector Tabs
        val activeIndex = sources.indexOfFirst { it.id == activeSourceId }.coerceAtLeast(0)
        ScrollableTabRow(
            selectedTabIndex = activeIndex,
            edgePadding = 0.dp,
            divider = {},
            containerColor = Color.Transparent,
            indicator = { tabPositions ->
                if (activeIndex < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[activeIndex]),
                        height = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        ) {
            sources.forEach { src ->
                val isSelected = src.id == activeSourceId
                Tab(
                    selected = isSelected,
                    onClick = { SourceManager.setActiveSource(src.id) },
                    text = {
                        Text(
                            src.name,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Genre Filter Chips with Outlined Style
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(genres) { g ->
                val isSelected = selectedGenre == g
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedGenre = g },
                    label = {
                        Text(
                            g,
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

        // Results
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.5.dp
                )
            }
        } else if (activeSourceId == WebScrapeSource.SOURCE_ID) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 80.dp),
                modifier = Modifier.weight(1f)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Public,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Add Novels from Any Website",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Open our in-app web browser, navigate or paste any novel or chapter page, and tap the floating 'Add to Library' button to extract the novel and chapters.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { onOpenWebBrowser("https://wtr-lab.com") },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Open In-App Browser", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                val webNovels = libraryNovels.filter { it.sourceId == WebScrapeSource.SOURCE_ID }
                if (webNovels.isNotEmpty()) {
                    item {
                        Text(
                            text = "Novels Added from Web",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    items(webNovels, key = { it.id }) { novel ->
                        BrowseNovelCard(
                            novel = novel,
                            isInLibrary = true,
                            onClick = { onOpenNovel(novel) },
                            onToggleLibrary = { repository.removeFromLibrary(novel.id) }
                        )
                    }
                }
            }
        } else if (displayedNovels.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                EmptyStateView(
                    icon = Icons.Default.Search,
                    title = "No novels found",
                    message = "Try exploring another source catalog or clearing your filter."
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 80.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(displayedNovels, key = { it.id }) { novel ->
                    val inLib = libraryNovels.any { it.id == novel.id }
                    BrowseNovelCard(
                        novel = novel,
                        isInLibrary = inLib,
                        onClick = { onOpenNovel(novel) },
                        onToggleLibrary = {
                            if (inLib) {
                                repository.removeFromLibrary(novel.id)
                            } else {
                                repository.addToLibrary(novel)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun BrowseNovelCard(
    novel: Novel,
    isInLibrary: Boolean,
    onClick: () -> Unit,
    onToggleLibrary: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
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

            Column(
                modifier = Modifier.weight(1f)
            ) {
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

                    IconButton(
                        onClick = onToggleLibrary,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = if (isInLibrary) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (isInLibrary) "In Library" else "Add to Library",
                            tint = if (isInLibrary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
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
                    RatingBadge(rating = novel.rating)

                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    Text(
                        text = "${novel.totalChapters} chapters",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = novel.description,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

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

