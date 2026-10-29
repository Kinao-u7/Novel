@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.ui.screens.detail

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.components.AppSheet
import com.example.ui.components.Cover
import com.example.ui.components.Pill
import com.example.ui.components.coverPaletteFor
import com.example.ui.components.glyphForTitle
import com.example.ui.theme.AppTheme

private enum class DetailMenu { None, Main, Category, Edit, Download }

@Composable
fun DetailScreen(
    state: DetailUiState,
    actions: DetailActions,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    val f = AppTheme.fonts
    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            actions.onMessageShown()
        }
    }

    var menu by remember { mutableStateOf(DetailMenu.None) }
    var descExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        snackbarHost = {
            SnackbarHost(snackbarHostState, Modifier.padding(bottom = 80.dp))
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when {
                state.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = c.ac)
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.detail_loading), color = c.mu, fontSize = 14.sp)
                        }
                    }
                    OverlayButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        desc = stringResource(R.string.detail_back),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 14.dp, top = 12.dp),
                        onClick = actions.onBack,
                    )
                }

                state.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp),
                        ) {
                            Text(
                                text = state.error,
                                color = c.tx,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = actions.onRetry,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = c.ac, contentColor = c.on),
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                            ) {
                                Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.detail_retry), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    OverlayButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        desc = stringResource(R.string.detail_back),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 14.dp, top = 12.dp),
                        onClick = actions.onBack,
                    )
                }

                state.novel == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.detail_not_found), color = c.mu, fontSize = 16.sp)
                    }
                    OverlayButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        desc = stringResource(R.string.detail_back),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 14.dp, top = 12.dp),
                        onClick = actions.onBack,
                    )
                }

                else -> {
                    val novel = state.novel

                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 110.dp),
                    ) {
                        item { Header(novel) }
                        item {
                            Column(Modifier.padding(horizontal = 20.dp)) {
                                Stats(novel = novel, onLibrary = actions.onToggleLibrary)
                                if (novel.description.isNotBlank()) {
                                    Text(
                                        novel.description,
                                        color = c.tx,
                                        fontSize = 14.sp,
                                        lineHeight = 21.sp,
                                        modifier = Modifier.padding(top = 18.dp),
                                        maxLines = if (descExpanded) Int.MAX_VALUE else 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    TextButton(
                                        onClick = { descExpanded = !descExpanded },
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                    ) {
                                        Text(
                                            if (descExpanded) stringResource(R.string.detail_show_less) else stringResource(R.string.detail_read_more),
                                            color = c.ac,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                                if (novel.genres.isNotEmpty()) {
                                    FlowRow(
                                        Modifier.padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        novel.genres.forEach { genre ->
                                            Text(
                                                genre,
                                                color = c.tx,
                                                fontSize = 12.sp,
                                                modifier = Modifier
                                                    .border(1.dp, c.ln, RoundedCornerShape(5.dp))
                                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                            )
                                        }
                                    }
                                }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = 26.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        stringResource(R.string.detail_chapters_count, novel.totalChapters),
                                        Modifier.weight(1f),
                                        color = c.tx,
                                        fontFamily = f.heading,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 19.sp,
                                    )
                                    IconButton(
                                        onClick = actions.onToggleSortOrder,
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.Sort,
                                            if (state.oldestFirst) stringResource(R.string.detail_sort_oldest) else stringResource(R.string.detail_sort_newest),
                                            tint = c.tx,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .graphicsLayer { rotationX = if (state.oldestFirst) 0f else 180f },
                                        )
                                    }
                                }
                            }
                        }

                        items(state.chapters, key = { it.index }) { chapter ->
                            ChapterRow(
                                chapter = chapter,
                                modifier = Modifier.padding(horizontal = 20.dp),
                                onClick = { actions.onChapterClick(chapter.index) },
                            )
                        }
                    }

                    // Top overlay action buttons
                    OverlayButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        desc = stringResource(R.string.detail_back),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 14.dp, top = 12.dp),
                        onClick = actions.onBack,
                    )
                    OverlayButton(
                        icon = Icons.Rounded.MoreVert,
                        desc = stringResource(R.string.detail_more_options),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(end = 14.dp, top = 12.dp),
                        onClick = { menu = DetailMenu.Main },
                    )

                    // Bottom Floating Action Bar
                    Row(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(c.bg)
                            .navigationBarsPadding()
                            .padding(18.dp, 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val readBtnLabel = if (novel.resumeChapterNumber != null) {
                            stringResource(R.string.detail_resume_chapter, novel.resumeChapterNumber)
                        } else {
                            stringResource(R.string.detail_start_reading)
                        }

                        Button(
                            onClick = actions.onReadClick,
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp),
                            shape = RoundedCornerShape(13.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = c.ac, contentColor = c.on),
                        ) {
                            Text(
                                readBtnLabel.uppercase(),
                                fontFamily = f.ui,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                letterSpacing = 1.7.sp,
                            )
                        }
                        OutlinedIconButton(
                            onClick = { menu = DetailMenu.Download },
                            modifier = Modifier.size(54.dp),
                            shape = RoundedCornerShape(13.dp),
                            border = BorderStroke(1.dp, c.ln),
                        ) {
                            Icon(Icons.Rounded.Download, stringResource(R.string.detail_download), tint = c.tx)
                        }
                    }

                    // Menus and dialogs
                    if (menu == DetailMenu.Main) {
                        AppSheet({ menu = DetailMenu.None }) {
                            Text(
                                novel.title,
                                color = c.tx,
                                fontFamily = f.heading,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 20.sp,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                            val close = { menu = DetailMenu.None }
                            MenuRow(Icons.Rounded.Edit, stringResource(R.string.detail_menu_edit_details)) {
                                menu = DetailMenu.Edit
                            }
                            MenuRow(Icons.Rounded.DoneAll, stringResource(R.string.detail_menu_mark_all_read)) {
                                actions.onMarkAllRead(true)
                                close()
                            }
                            MenuRow(Icons.Rounded.RadioButtonUnchecked, stringResource(R.string.detail_menu_mark_all_unread)) {
                                actions.onMarkAllRead(false)
                                close()
                            }
                            if (state.categoriesEnabled) {
                                MenuRow(Icons.Rounded.Folder, stringResource(R.string.detail_menu_add_to_category)) {
                                    menu = DetailMenu.Category
                                }
                            }
                            MenuRow(Icons.Rounded.Share, stringResource(R.string.detail_menu_share)) {
                                close()
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "${novel.title} by ${novel.author}")
                                }
                                ctx.startActivity(Intent.createChooser(send, null))
                            }
                            if (!novel.isEpub && !novel.sourceUrl.isNullOrBlank()) {
                                MenuRow(Icons.Rounded.Language, stringResource(R.string.detail_menu_open_in_browser)) {
                                    close()
                                    actions.onOpenInBrowser()
                                }
                            }
                        }
                    }

                    if (menu == DetailMenu.Download) {
                        AppSheet({ menu = DetailMenu.None }) {
                            Text(
                                stringResource(R.string.detail_download_title),
                                color = c.tx,
                                fontFamily = f.heading,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 20.sp,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                            DownloadOptionRow(stringResource(R.string.detail_download_next_5)) {
                                menu = DetailMenu.None
                                actions.onDownload(DownloadScope.NEXT_5)
                            }
                            DownloadOptionRow(stringResource(R.string.detail_download_next_25)) {
                                menu = DetailMenu.None
                                actions.onDownload(DownloadScope.NEXT_25)
                            }
                            DownloadOptionRow(stringResource(R.string.detail_download_all_unread)) {
                                menu = DetailMenu.None
                                actions.onDownload(DownloadScope.ALL_UNREAD)
                            }
                        }
                    }

                    if (menu == DetailMenu.Category) {
                        AppSheet({ menu = DetailMenu.None }) {
                            Text(
                                stringResource(R.string.detail_menu_add_to_category),
                                color = c.tx,
                                fontFamily = f.heading,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 20.sp,
                            )
                            FlowRow(
                                Modifier.padding(top = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                state.categories.forEach { cat ->
                                    val isSelected = state.novelCategoryIds.contains(cat.id)
                                    Pill(cat.name, isSelected) {
                                        actions.onToggleCategory(cat.id)
                                    }
                                }
                            }
                            Button(
                                onClick = { menu = DetailMenu.None },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 24.dp)
                                    .height(52.dp),
                                shape = RoundedCornerShape(13.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = c.ac, contentColor = c.on),
                            ) {
                                Text(
                                    stringResource(R.string.detail_done),
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.5.sp,
                                )
                            }
                        }
                    }

                    if (menu == DetailMenu.Edit) {
                        var editTitle by remember { mutableStateOf(novel.title) }
                        var editAuthor by remember { mutableStateOf(novel.author) }
                        AlertDialog(
                            onDismissRequest = { menu = DetailMenu.None },
                            containerColor = c.cd,
                            title = { Text(stringResource(R.string.detail_edit_dialog_title), color = c.tx) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        editTitle,
                                        { editTitle = it },
                                        label = { Text(stringResource(R.string.detail_edit_title_label)) },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = c.tx,
                                            unfocusedTextColor = c.tx,
                                            focusedBorderColor = c.ac,
                                            unfocusedBorderColor = c.ln,
                                        ),
                                    )
                                    OutlinedTextField(
                                        editAuthor,
                                        { editAuthor = it },
                                        label = { Text(stringResource(R.string.detail_edit_author_label)) },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = c.tx,
                                            unfocusedTextColor = c.tx,
                                            focusedBorderColor = c.ac,
                                            unfocusedBorderColor = c.ln,
                                        ),
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        actions.onSaveDetails(editTitle, editAuthor)
                                        menu = DetailMenu.None
                                    },
                                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                ) {
                                    Text(stringResource(R.string.detail_save), color = c.ac, fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                TextButton(
                                    onClick = { menu = DetailMenu.None },
                                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                ) {
                                    Text(stringResource(R.string.detail_cancel), color = c.mu)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(novel: DetailNovel) {
    val c = AppTheme.colors
    val f = AppTheme.fonts
    val palette = coverPaletteFor(novel.title)
    val glyph = glyphForTitle(novel.title)

    Box(
        Modifier
            .fillMaxWidth()
            .clipToBounds()
            .background(Brush.linearGradient(listOf(palette.c1, palette.c2))),
    ) {
        Text(
            glyph,
            fontFamily = f.brush,
            fontSize = 300.sp,
            color = palette.accent.copy(alpha = 0.16f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 30.dp, y = 10.dp)
                .blur(7.dp),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(70.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, c.bg))),
        )
        Row(
            Modifier
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 64.dp, bottom = 34.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Cover(
                title = novel.title,
                coverUrl = novel.coverUrl,
                modifier = Modifier.width(132.dp),
                glyphSize = 72.sp,
                titleSize = 14.sp,
            )
            Column(Modifier.weight(1f).padding(bottom = 22.dp)) {
                Text(
                    novel.title,
                    color = Color(0xFFF4EFE4),
                    fontFamily = f.heading,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 21.sp,
                    lineHeight = 26.sp,
                )
                MetaLine(Icons.Rounded.Create, novel.author, Modifier.padding(top = 12.dp))
                val statusText = if (novel.isCompleted) {
                    stringResource(R.string.detail_status_completed)
                } else {
                    stringResource(R.string.detail_status_ongoing)
                }
                MetaLine(Icons.Rounded.Schedule, "$statusText • ${novel.sourceName}", Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun MetaLine(icon: ImageVector, text: String, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = Color(0xFFE3DDD0), modifier = Modifier.size(16.dp))
        Text(text, color = Color(0xFFE3DDD0), fontSize = 14.sp)
    }
}

@Composable
private fun Stats(novel: DetailNovel, onLibrary: () -> Unit) {
    val c = AppTheme.colors
    val f = AppTheme.fonts
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        listOf(
            novel.totalChapters.toString() to stringResource(R.string.detail_chapters_label),
            novel.readCount.toString() to stringResource(R.string.detail_chapters_read_label),
        ).forEach { (n, label) ->
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(n, color = c.tx, fontFamily = f.heading, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(label, color = c.mu, fontSize = 11.sp)
            }
        }
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Switch, onClick = onLibrary)
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                if (novel.inLibrary) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                stringResource(R.string.detail_in_library),
                tint = if (novel.inLibrary) c.go else c.tx,
                modifier = Modifier.size(32.dp),
            )
            Text(
                if (novel.inLibrary) stringResource(R.string.detail_in_library) else stringResource(R.string.detail_add_to_library),
                color = c.mu,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: DetailChapter,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = AppTheme.colors
    val current = chapter.isCurrent
    val read = chapter.isRead

    val titleDisplay = if (chapter.title.isNullOrBlank()) {
        "Chapter ${chapter.number}"
    } else {
        "Chapter ${chapter.number} · ${chapter.title}"
    }

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.Button, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = titleDisplay,
                    color = if (current) c.ac else if (read) c.mu else c.tx,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (chapter.isDownloaded) {
                Icon(
                    Icons.Rounded.DownloadDone,
                    stringResource(R.string.detail_downloaded),
                    tint = c.ac,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(16.dp),
                )
            }
            if (read) {
                Icon(
                    Icons.Rounded.Check,
                    stringResource(R.string.detail_read),
                    tint = c.ac,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (current) {
                Text(
                    stringResource(R.string.detail_current),
                    color = c.ac,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        HorizontalDivider(color = c.ln)
    }
}

@Composable
private fun OverlayButton(
    icon: ImageVector,
    desc: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(shape)
                .background(Color(0x66000000))
                .border(1.dp, Color(0x30FFFFFF), shape)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, desc, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = AppTheme.colors
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .clickable(role = Role.Button, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, null, tint = c.mu, modifier = Modifier.size(20.dp))
            Text(label, color = c.tx, fontSize = 15.sp)
        }
        HorizontalDivider(color = c.ln)
    }
}

@Composable
private fun DownloadOptionRow(label: String, onClick: () -> Unit) {
    val c = AppTheme.colors
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.Button, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Rounded.Download, null, tint = c.ac, modifier = Modifier.size(20.dp))
            Text(label, color = c.tx, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = c.ln)
    }
}
