package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Chapter
import com.example.data.model.Novel
import com.example.data.repository.NovelRepository
import com.example.ui.components.EditorialSectionHeader
import com.example.ui.components.NovelCoverImage
import com.example.ui.components.NovelOptionsBottomSheet
import com.example.ui.components.PrimaryCtaButton
import com.example.ui.components.RatingBadge
import com.example.ui.components.SecondaryOutlinedButton
import com.example.ui.components.StatusPill
import com.example.ui.components.TagPill
import com.example.ui.theme.BookSerifFontFamily
import kotlinx.coroutines.launch

@Composable
fun NovelDetailScreen(
    novel: Novel,
    repository: NovelRepository,
    onBack: () -> Unit,
    onReadChapter: (Novel, String, Int) -> Unit,
    onOpenInBrowser: ((String) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val libraryNovels by repository.libraryNovels.collectAsState()
    val downloadedMap by repository.downloadedChapters.collectAsState()
    val readMap by repository.readChapters.collectAsState()

    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var isLoadingChapters by remember { mutableStateOf(true) }
    var isDescending by remember { mutableStateOf(false) }
    var chapterSearchQuery by remember { mutableStateOf("") }
    var isExpandedSynopsis by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }

    // Batch download state
    var isDownloadingBatch by remember { mutableStateOf(false) }
    var batchProgress by remember { mutableStateOf(Pair(0, 0)) }

    val isInLibrary = libraryNovels.any { it.id == novel.id }
    val currentNovelState = libraryNovels.firstOrNull { it.id == novel.id } ?: novel

    // Load chapters
    LaunchedEffect(novel.id) {
        isLoadingChapters = true
        try {
            chapters = repository.getChapterList(novel)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isLoadingChapters = false
        }
    }

    val downloadedSet = downloadedMap[novel.id] ?: emptySet()
    val readSet = readMap[novel.id] ?: emptySet()

    val sortedAndFilteredChapters = remember(chapters, isDescending, chapterSearchQuery, downloadedSet, readSet) {
        var list = chapters.map { ch ->
            ch.copy(
                isDownloaded = downloadedSet.contains(ch.id) || ch.isDownloaded,
                isRead = readSet.contains(ch.id)
            )
        }
        if (chapterSearchQuery.isNotBlank()) {
            list = list.filter { it.title.contains(chapterSearchQuery, ignoreCase = true) }
        }
        if (isDescending) list.reversed() else list
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = novel.sourceName.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                IconButton(onClick = { showOptionsMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Novel Options",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // Header Hero Section
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        NovelCoverImage(
                            coverUrl = novel.coverUrl,
                            title = novel.title,
                            modifier = Modifier.size(width = 112.dp, height = 162.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = novel.title,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = BookSerifFontFamily,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp,
                                lineHeight = 26.sp
                            )
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "by ${novel.author}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RatingBadge(rating = novel.rating)

                            Text("•", color = MaterialTheme.colorScheme.outlineVariant)

                            StatusPill(status = novel.status)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "${chapters.size.ifEmpty(novel.totalChapters)} chapters",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Action Buttons (Consolidated Single Row: Continue / Read + Download)
            item {
                Spacer(modifier = Modifier.height(14.dp))

                val nextChId = currentNovelState.lastReadChapterId ?: chapters.firstOrNull()?.id ?: "${novel.id}_ch_1"
                val nextChIdx = if (currentNovelState.lastReadChapterIndex > 0) currentNovelState.lastReadChapterIndex else 1
                val readButtonLabel = if (currentNovelState.lastReadChapterIndex > 0) {
                    "Continue Ch. $nextChIdx"
                } else {
                    "Start Reading"
                }

                val undownloadedChapters = chapters.filter { !downloadedSet.contains(it.id) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PrimaryCtaButton(
                        text = readButtonLabel,
                        onClick = { onReadChapter(novel, nextChId, nextChIdx) },
                        icon = Icons.Default.PlayArrow,
                        modifier = Modifier.weight(1.5f)
                    )

                    SecondaryOutlinedButton(
                        text = if (isDownloadingBatch) "Downloading..." else "Download",
                        onClick = {
                            if (!isDownloadingBatch && undownloadedChapters.isNotEmpty()) {
                                scope.launch {
                                    isDownloadingBatch = true
                                    repository.downloadChaptersBatch(novel, undownloadedChapters) { done, total ->
                                        batchProgress = Pair(done, total)
                                    }
                                    isDownloadingBatch = false
                                }
                            }
                        },
                        icon = Icons.Default.CloudDownload,
                        enabled = !isDownloadingBatch && undownloadedChapters.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Batch progress indicator
                AnimatedVisibility(visible = isDownloadingBatch) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = {
                                if (batchProgress.second > 0) batchProgress.first.toFloat() / batchProgress.second.toFloat() else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Saving offline: ${batchProgress.first} of ${batchProgress.second} chapters...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Synopsis
            item {
                Spacer(modifier = Modifier.height(20.dp))
                EditorialSectionHeader(title = "Synopsis")
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = novel.description,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (isExpandedSynopsis) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isExpandedSynopsis) "Show less" else "Read more",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier
                        .clickable { isExpandedSynopsis = !isExpandedSynopsis }
                        .padding(vertical = 4.dp)
                )

                // Genre tags
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(novel.genres) { g ->
                        TagPill(text = g)
                    }
                }
            }

            // Chapters Header & Search/Sort controls
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EditorialSectionHeader(title = "Chapters", count = chapters.size)

                    IconButton(onClick = { isDescending = !isDescending }) {
                        Icon(
                            imageVector = Icons.Default.Sort,
                            contentDescription = "Sort",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = chapterSearchQuery,
                    onValueChange = { chapterSearchQuery = it },
                    placeholder = { Text("Filter chapters...", fontSize = 13.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))
            }

            // Chapter List Items
            if (isLoadingChapters) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.5.dp
                        )
                    }
                }
            } else {
                items(sortedAndFilteredChapters, key = { it.id }) { ch ->
                    ChapterRowItem(
                        chapter = ch,
                        onClick = { onReadChapter(novel, ch.id, ch.chapterIndex) },
                        onDownload = {
                            scope.launch {
                                repository.downloadChapter(novel, ch)
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }

        if (showOptionsMenu) {
            NovelOptionsBottomSheet(
                novel = currentNovelState,
                isInLibrary = isInLibrary,
                repository = repository,
                onDismiss = { showOptionsMenu = false },
                onOpenInBrowser = onOpenInBrowser
            )
        }
    }
}

private fun Int.ifEmpty(fallback: Int): Int = if (this > 0) this else fallback

@Composable
fun ChapterRowItem(
    chapter: Chapter,
    onClick: () -> Unit,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (chapter.isRead) 0.3f else 0.55f)
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (chapter.isRead)
                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (chapter.isRead) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (chapter.isRead)
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        else
                            MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = chapter.releaseDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (chapter.isRead)
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    if (chapter.wordCount > 0) {
                        Text(
                            text = "${chapter.wordCount} words",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (chapter.isRead)
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            else
                                MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }

            // Single icon slot: download state only (no checkmark tick)
            if (chapter.isDownloaded) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = "Saved Offline",
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(18.dp)
                )
            } else {
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
