package com.example.ui.components

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Novel
import com.example.data.repository.NovelRepository
import com.example.data.source.SourceManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelOptionsBottomSheet(
    novel: Novel,
    isInLibrary: Boolean,
    repository: NovelRepository,
    onDismiss: () -> Unit,
    onOpenInBrowser: ((String) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    var showEditDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
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
            // Header: Cover + Title + Author
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NovelCoverImage(
                    coverUrl = novel.coverUrl,
                    title = novel.title,
                    modifier = Modifier
                        .size(width = 44.dp, height = 62.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = novel.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = novel.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Option 1: Library Toggle
            NovelOptionItem(
                icon = if (isInLibrary) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                title = if (isInLibrary) "Remove from Library" else "Add to Library",
                iconTint = if (isInLibrary) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                textColor = if (isInLibrary) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                onClick = {
                    if (isInLibrary) {
                        repository.removeFromLibrary(novel.id)
                    } else {
                        repository.addToLibrary(novel.copy(inLibrary = true))
                    }
                    onDismiss()
                }
            )

            // Option 2: Edit novel info
            NovelOptionItem(
                icon = Icons.Default.Edit,
                title = "Edit novel info",
                onClick = {
                    showEditDialog = true
                }
            )

            // Option 3: Open in web browser
            NovelOptionItem(
                icon = Icons.Default.Public,
                title = "Open in web browser",
                onClick = {
                    onDismiss()
                    val targetUrl = resolveNovelWebUrl(novel)
                    onOpenInBrowser?.invoke(targetUrl)
                }
            )

            // Option 4: Delete downloads (if any downloaded chapters exist, hide for EPUBs)
            if (novel.downloadedChaptersCount > 0 && !novel.isEpub && novel.sourceId != "local_epub") {
                NovelOptionItem(
                    icon = Icons.Default.DeleteOutline,
                    title = "Delete downloaded chapters (${novel.downloadedChaptersCount})",
                    iconTint = MaterialTheme.colorScheme.error,
                    textColor = MaterialTheme.colorScheme.error,
                    onClick = {
                        coroutineScope.launch {
                            repository.deleteNovelDownloads(novel.id)
                        }
                        onDismiss()
                    }
                )
            }

            // Option 5: Clear reading history (if novel has history)
            if (novel.lastReadTimestamp > 0L) {
                NovelOptionItem(
                    icon = Icons.Default.HistoryToggleOff,
                    title = "Clear from reading history",
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = {
                        repository.clearNovelReadingHistory(novel.id)
                        onDismiss()
                    }
                )
            }
        }
    }

    if (showEditDialog) {
        EditNovelDialog(
            novel = novel,
            onDismiss = {
                showEditDialog = false
                onDismiss()
            },
            onSave = { updatedTitle, updatedAuthor, updatedDesc, updatedCover ->
                repository.updateNovelDetails(
                    novelId = novel.id,
                    newTitle = updatedTitle,
                    newAuthor = updatedAuthor,
                    newDescription = updatedDesc,
                    newCoverUrl = updatedCover
                )
                showEditDialog = false
                onDismiss()
            }
        )
    }
}

@Composable
private fun NovelOptionItem(
    icon: ImageVector,
    title: String,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            ),
            color = textColor
        )
    }
}

@Composable
fun EditNovelDialog(
    novel: Novel,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String?) -> Unit
) {
    var title by remember { mutableStateOf(novel.title) }
    var author by remember { mutableStateOf(novel.author) }
    var description by remember { mutableStateOf(novel.description) }
    var coverUrl by remember { mutableStateOf(novel.coverUrl ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Edit Novel Info",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Synopsis") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = coverUrl,
                    onValueChange = { coverUrl = it },
                    label = { Text("Cover Image URL (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        title.ifBlank { novel.title },
                        author.ifBlank { novel.author },
                        description,
                        coverUrl.ifBlank { null }
                    )
                }
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Helper to determine the web URL for a novel when opening in browser.
 * Always prefers the exact stored source URL verbatim without title searches.
 */
fun resolveNovelWebUrl(novel: Novel): String {
    // 1. Verbatim stored source URL from when the novel was originally added
    if (!novel.sourceUrl.isNullOrBlank()) {
        return novel.sourceUrl
    }
    // 2. Direct web ID or chapter URL
    if (novel.id.startsWith("http://") || novel.id.startsWith("https://")) {
        return novel.id
    }
    if (novel.lastReadChapterId?.startsWith("http://") == true || novel.lastReadChapterId?.startsWith("https://") == true) {
        return novel.lastReadChapterId
    }
    // 3. Fallback for older records with rawId
    if (!novel.rawId.isNullOrBlank()) {
        val loc = novel.locale?.ifBlank { "en" } ?: "en"
        return "https://wtr-lab.com/$loc/novel/${novel.rawId}"
    }
    // Default safe catalog fallback (never search for title)
    return "https://wtr-lab.com"
}
