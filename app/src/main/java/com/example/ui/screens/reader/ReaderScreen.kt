package com.example.ui.screens.reader

// TEMPORARY ReaderScreen for baseline reading functionality and testing.
// Will be replaced with the custom production Reader UI.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.FontType
import com.example.data.model.ReaderTheme
import com.example.ui.theme.AppTheme

private data class ThemeColors(val bg: Color, val text: Color)

private fun getReaderColors(theme: ReaderTheme): ThemeColors = when (theme) {
    ReaderTheme.LIGHT -> ThemeColors(Color(0xFFFAF9F6), Color(0xFF1A1A1A))
    ReaderTheme.WARM -> ThemeColors(Color(0xFFF7F0E3), Color(0xFF2C241B))
    ReaderTheme.SEPIA -> ThemeColors(Color(0xFFEFE6D5), Color(0xFF2D2319))
    ReaderTheme.DARK -> ThemeColors(Color(0xFF14191C), Color(0xFFD4D8DA))
    ReaderTheme.AMOLED -> ThemeColors(Color(0xFF000000), Color(0xFFCCCCCC))
}

private fun getFontFamily(fontType: FontType): FontFamily = when (fontType) {
    FontType.SANS -> FontFamily.SansSerif
    FontType.SERIF -> FontFamily.Serif
    FontType.MONOSPACE -> FontFamily.Monospace
    FontType.ROUNDED -> FontFamily.Default
}

@Composable
fun ReaderScreen(
    state: ReaderUiState,
    actions: ReaderActions,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    DisposableEffect(state.display.keepScreenOn) {
        view.keepScreenOn = state.display.keepScreenOn
        onDispose {
            view.keepScreenOn = false
        }
    }

    val readerColors = getReaderColors(state.display.theme)
    val fontFamily = getFontFamily(state.display.fontType)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(readerColors.bg)
    ) {
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = AppTheme.colors.ac,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            state.error != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = state.error,
                        color = AppTheme.colors.tx,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Button(
                        onClick = actions.onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppTheme.colors.ac,
                            contentColor = AppTheme.colors.on
                        ),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.reader_retry))
                    }
                }
            }
            state.chapter != null -> {
                val chapter = state.chapter
                val listState = rememberLazyListState()

                LaunchedEffect(chapter.index) {
                    if (state.startParagraph > 0 && state.startParagraph < chapter.paragraphs.size) {
                        listState.scrollToItem(state.startParagraph)
                    } else {
                        listState.scrollToItem(0)
                    }
                }

                val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
                LaunchedEffect(firstVisible) {
                    actions.onVisibleParagraph(firstVisible)
                }

                val isAtEnd by remember {
                    derivedStateOf {
                        val layoutInfo = listState.layoutInfo
                        val total = layoutInfo.totalItemsCount
                        if (total == 0) false
                        else {
                            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            lastVisible >= total - 1
                        }
                    }
                }
                LaunchedEffect(isAtEnd) {
                    if (isAtEnd) {
                        actions.onReachedEnd()
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = state.display.horizontalMarginDp.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { actions.onToggleControls() }
                            )
                        }
                ) {
                    item {
                        Spacer(Modifier.statusBarsPadding())
                        Spacer(Modifier.height(56.dp))
                        Text(
                            text = chapter.title,
                            fontFamily = fontFamily,
                            fontSize = (state.display.fontSizeSp + 4).sp,
                            fontWeight = FontWeight.Bold,
                            color = readerColors.text,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }

                    itemsIndexed(chapter.paragraphs) { idx, paragraph ->
                        val isBookmarked = state.bookmarkedParagraphs.contains(idx)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = state.display.paragraphSpacingDp.dp)
                        ) {
                            Text(
                                text = paragraph,
                                fontFamily = fontFamily,
                                fontSize = state.display.fontSizeSp.sp,
                                lineHeight = (state.display.fontSizeSp * state.display.lineSpacing).sp,
                                textAlign = if (state.display.justified) TextAlign.Justify else TextAlign.Start,
                                color = readerColors.text,
                                modifier = Modifier
                                    .weight(1f)
                            )
                            if (isBookmarked) {
                                Icon(
                                    imageVector = Icons.Default.Bookmark,
                                    contentDescription = stringResource(R.string.reader_bookmark),
                                    tint = AppTheme.colors.ac,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .padding(start = 4.dp)
                                )
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(32.dp))
                        if (chapter.hasNext) {
                            Button(
                                onClick = actions.onNextChapter,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppTheme.colors.ac,
                                    contentColor = AppTheme.colors.on
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.reader_next_chapter_btn))
                            }
                        }
                        Spacer(Modifier.height(72.dp))
                        Spacer(Modifier.navigationBarsPadding())
                    }
                }
            }
        }

        // Overlay Controls
        AnimatedVisibility(
            visible = state.controlsVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            TopControlsBar(
                state = state,
                onBack = actions.onBack,
                onToggleBookmark = {
                    actions.onToggleBookmark(state.startParagraph)
                }
            )
        }

        AnimatedVisibility(
            visible = state.controlsVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BottomControlsBar(
                state = state,
                actions = actions
            )
        }

        // Contents Sheet
        if (state.panel == ReaderPanel.CONTENTS) {
            ContentsSheet(
                state = state,
                onChapterSelected = actions.onChapterSelected,
                onDismiss = actions.onClosePanel
            )
        }

        // Display Settings Sheet
        if (state.panel == ReaderPanel.DISPLAY) {
            DisplaySheet(
                display = state.display,
                onDisplayChange = actions.onDisplayChange,
                onDismiss = actions.onClosePanel
            )
        }
    }
}

@Composable
private fun TopControlsBar(
    state: ReaderUiState,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit
) {
    val c = AppTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(c.cd.copy(alpha = 0.95f))
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 4.dp)
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.reader_back),
                tint = c.tx
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        ) {
            Text(
                text = state.novelTitle,
                fontFamily = AppTheme.fonts.heading,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = c.tx
            )
            state.chapter?.let { ch ->
                Text(
                    text = ch.title,
                    fontFamily = AppTheme.fonts.ui,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = c.mu
                )
            }
        }

        IconButton(
            onClick = onToggleBookmark,
            modifier = Modifier.size(48.dp)
        ) {
            val isBookmarked = state.bookmarkedParagraphs.isNotEmpty()
            Icon(
                imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = stringResource(R.string.reader_bookmark),
                tint = if (isBookmarked) c.ac else c.mu
            )
        }
    }
}

@Composable
private fun BottomControlsBar(
    state: ReaderUiState,
    actions: ReaderActions
) {
    val c = AppTheme.colors
    val hasPrev = state.chapter?.hasPrevious == true
    val hasNext = state.chapter?.hasNext == true

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround,
        modifier = Modifier
            .fillMaxWidth()
            .background(c.cd.copy(alpha = 0.95f))
            .navigationBarsPadding()
            .height(56.dp)
            .padding(horizontal = 12.dp)
    ) {
        IconButton(
            onClick = actions.onPreviousChapter,
            enabled = hasPrev,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.NavigateBefore,
                contentDescription = stringResource(R.string.reader_prev_chapter),
                tint = if (hasPrev) c.tx else c.ln
            )
        }

        IconButton(
            onClick = { actions.onOpenPanel(ReaderPanel.CONTENTS) },
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FormatListBulleted,
                contentDescription = stringResource(R.string.reader_contents),
                tint = c.tx
            )
        }

        IconButton(
            onClick = { actions.onOpenPanel(ReaderPanel.DISPLAY) },
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FormatColorText,
                contentDescription = stringResource(R.string.reader_display),
                tint = c.tx
            )
        }

        IconButton(
            onClick = actions.onNextChapter,
            enabled = hasNext,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.NavigateNext,
                contentDescription = stringResource(R.string.reader_next_chapter),
                tint = if (hasNext) c.tx else c.ln
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentsSheet(
    state: ReaderUiState,
    onChapterSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val c = AppTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = c.cd,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.reader_contents_title),
                fontFamily = AppTheme.fonts.heading,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = c.tx,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                items(state.contents) { item ->
                    val isCurrent = item.isCurrent
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isCurrent) c.ac.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable {
                                onChapterSelected(item.index)
                                onDismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = item.title ?: "Chapter ${item.number}",
                            color = if (isCurrent) c.ac else if (item.isRead) c.mu else c.tx,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = AppTheme.fonts.ui,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (item.isRead) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = c.mu,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplaySheet(
    display: ReaderDisplay,
    onDisplayChange: (ReaderDisplay) -> Unit,
    onDismiss: () -> Unit
) {
    val c = AppTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.cd,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.reader_display_title),
                fontFamily = AppTheme.fonts.heading,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = c.tx,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Font size
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    text = stringResource(R.string.reader_display_font_size),
                    color = c.tx,
                    fontFamily = AppTheme.fonts.ui,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        val newSize = (display.fontSizeSp - 1).coerceAtLeast(15)
                        onDisplayChange(display.copy(fontSizeSp = newSize))
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Text("-", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.tx)
                }
                Text(
                    text = "${display.fontSizeSp}",
                    color = c.ac,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                IconButton(
                    onClick = {
                        val newSize = (display.fontSizeSp + 1).coerceAtMost(28)
                        onDisplayChange(display.copy(fontSizeSp = newSize))
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.tx)
                }
            }

            // Line spacing
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    text = stringResource(R.string.reader_display_line_spacing),
                    color = c.tx,
                    fontFamily = AppTheme.fonts.ui,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                val spacings = listOf(1.4f, 1.6f, 1.8f, 2.0f)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    spacings.forEach { sp ->
                        val selected = kotlin.math.abs(display.lineSpacing - sp) < 0.05f
                        Box(
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) c.ac else c.ln)
                                .clickable { onDisplayChange(display.copy(lineSpacing = sp)) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${sp}x",
                                color = if (selected) c.on else c.tx,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Theme chips
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    text = stringResource(R.string.reader_display_theme),
                    color = c.tx,
                    fontFamily = AppTheme.fonts.ui,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReaderTheme.entries.forEach { th ->
                        val selected = display.theme == th
                        Box(
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) c.ac else c.ln)
                                .clickable { onDisplayChange(display.copy(theme = th)) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = th.displayName,
                                color = if (selected) c.on else c.tx,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Font chips
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    text = stringResource(R.string.reader_display_font),
                    color = c.tx,
                    fontFamily = AppTheme.fonts.ui,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FontType.entries.forEach { ft ->
                        val selected = display.fontType == ft
                        Box(
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) c.ac else c.ln)
                                .clickable { onDisplayChange(display.copy(fontType = ft)) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = ft.name.take(4),
                                color = if (selected) c.on else c.tx,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Justify text switch
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    text = stringResource(R.string.reader_display_justify),
                    color = c.tx,
                    fontFamily = AppTheme.fonts.ui,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = display.justified,
                    onCheckedChange = { onDisplayChange(display.copy(justified = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = c.on,
                        checkedTrackColor = c.ac
                    )
                )
            }
        }
    }
}
