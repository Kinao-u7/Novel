package com.example.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Novel
import com.example.data.repository.NovelRepository
import com.example.ui.components.EmptyStateView
import com.example.ui.components.NovelCoverImage
import com.example.ui.theme.BookSerifFontFamily

@Composable
fun DownloadsScreen(
    repository: NovelRepository,
    onOpenNovel: (Novel) -> Unit,
    onReadChapter: (Novel, String, Int) -> Unit
) {
    val libraryNovels by repository.libraryNovels.collectAsState()
    val downloadedMap by repository.downloadedChapters.collectAsState()

    var novelToDelete by remember { mutableStateOf<Novel?>(null) }

    val downloadedNovels = remember(libraryNovels, downloadedMap) {
        libraryNovels.filter { novel ->
            val set = downloadedMap[novel.id] ?: emptySet()
            set.isNotEmpty() || novel.isEpub
        }
    }

    val totalDownloadedChapters = remember(downloadedMap, downloadedNovels) {
        downloadedNovels.sumOf { novel ->
            val set = downloadedMap[novel.id] ?: emptySet()
            if (novel.isEpub) novel.totalChapters else set.size
        }
    }

    var storageSizeBytes by remember { mutableStateOf<Long?>(null) }
    var isCalculatingStorage by remember { mutableStateOf(true) }

    LaunchedEffect(downloadedNovels) {
        isCalculatingStorage = true
        val bytes = withContext(Dispatchers.IO) {
            repository.getStorageUsageBytes()
        }
        storageSizeBytes = bytes
        isCalculatingStorage = false
    }

    val storageFormatted = remember(storageSizeBytes, isCalculatingStorage) {
        if (isCalculatingStorage || storageSizeBytes == null) {
            "Calculating..."
        } else {
            val bytes = storageSizeBytes ?: 0L
            val mb = bytes.toDouble() / (1024 * 1024)
            if (mb < 1.0) String.format(java.util.Locale.US, "%.1f KB", bytes.toDouble() / 1024)
            else String.format(java.util.Locale.US, "%.2f MB", mb)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Offline Downloads",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = BookSerifFontFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Storage Usage Banner
        Card(
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LOCAL STORAGE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "$totalDownloadedChapters chapters ($storageFormatted)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "100% readable without internet connection",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (downloadedNovels.isEmpty()) {
            EmptyStateView(
                icon = Icons.Default.CloudDone,
                title = "No offline chapters yet",
                description = "Download chapters from any novel details page or import EPUB files to read offline without an active internet connection."
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 80.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(downloadedNovels, key = { it.id }) { novel ->
                    val downloadedCount = if (novel.isEpub) novel.totalChapters else (downloadedMap[novel.id]?.size ?: 0)

                    Card(
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenNovel(novel) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                    .clip(RoundedCornerShape(6.dp))
                            ) {
                                NovelCoverImage(
                                    coverUrl = novel.coverUrl,
                                    title = novel.title,
                                    size = DpSize(60.dp, 86.dp),
                                    modifier = Modifier.size(width = 60.dp, height = 86.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

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
                                Text(
                                    text = novel.author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "$downloadedCount chapters stored locally",
                                    style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF10B981), fontWeight = FontWeight.SemiBold)
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        val chId = novel.lastReadChapterId ?: "${novel.id}_ch_1"
                                        val chIdx = if (novel.lastReadChapterIndex > 0) novel.lastReadChapterIndex else 1
                                        onReadChapter(novel, chId, chIdx)
                                    }
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Read", tint = MaterialTheme.colorScheme.primary)
                                }

                                if (!novel.isEpub) {
                                    IconButton(
                                        onClick = { novelToDelete = novel }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete Offline Cache", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation Dialog for single novel cache deletion
    novelToDelete?.let { targetNovel ->
        AlertDialog(
            onDismissRequest = { novelToDelete = null },
            title = {
                Text(
                    "Delete Offline Chapters?",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = BookSerifFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                )
            },
            text = {
                Text("Are you sure you want to remove offline downloads for \"${targetNovel.title}\"? You can always re-download them whenever you're connected.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.deleteDownloadedNovelChapters(targetNovel.id)
                        novelToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { novelToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

