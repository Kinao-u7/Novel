package com.example.ui.screens

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.example.data.model.*
import com.example.data.repository.NovelRepository
import com.example.tts.TtsManager
import com.example.ui.components.CleanSlider
import com.example.ui.components.DownloadedBadge
import com.example.ui.theme.calculateOnColor
import com.example.ui.theme.parseColorHex
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReaderColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val onSurface: Color,
    val accent: Color,
    val highlight: Color
)

fun getReaderColors(theme: ReaderTheme, accentColor: Color = Color(0xFFD48B28)): ReaderColors {
    return when (theme) {
        ReaderTheme.LIGHT -> ReaderColors(
            background = Color(0xFFFBFBFA),
            surface = Color(0xFFF0F0EF),
            text = Color(0xFF1E2022),
            onSurface = Color(0xFF2C3035),
            accent = accentColor,
            highlight = accentColor.copy(alpha = 0.22f)
        )
        ReaderTheme.WARM -> ReaderColors(
            background = Color(0xFFFAF6EE),
            surface = Color(0xFFEFE8DA),
            text = Color(0xFF2C2520),
            onSurface = Color(0xFF42372E),
            accent = accentColor,
            highlight = accentColor.copy(alpha = 0.24f)
        )
        ReaderTheme.SEPIA -> ReaderColors(
            background = Color(0xFFF3EBD9),
            surface = Color(0xFFE5DAC4),
            text = Color(0xFF382B1B),
            onSurface = Color(0xFF4E3D2A),
            accent = accentColor,
            highlight = accentColor.copy(alpha = 0.24f)
        )
        ReaderTheme.DARK -> ReaderColors(
            background = Color(0xFF131210),
            surface = Color(0xFF1E1B18),
            text = Color(0xFFE0D8CE),
            onSurface = Color(0xFFF0EBE3),
            accent = accentColor,
            highlight = accentColor.copy(alpha = 0.28f)
        )
        ReaderTheme.AMOLED -> ReaderColors(
            background = Color(0xFF000000),
            surface = Color(0xFF141311),
            text = Color(0xFFD5D0C8),
            onSurface = Color(0xFFEDE9E3),
            accent = accentColor,
            highlight = accentColor.copy(alpha = 0.30f)
        )
    }
}

sealed class ChapterLoadState {
    object Loading : ChapterLoadState()
    data class Success(val content: ChapterContent) : ChapterLoadState()
    data class Error(val message: String) : ChapterLoadState()
}

data class ToolbarActionDef(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val description: String
)

val ALL_TOOLBAR_ACTIONS = listOf(
    ToolbarActionDef("prev_chapter", "Previous", Icons.Default.KeyboardArrowLeft, "Previous Chapter"),
    ToolbarActionDef("next_chapter", "Next", Icons.Default.KeyboardArrowRight, "Next Chapter"),
    ToolbarActionDef("toc", "Contents", Icons.Default.List, "Table of Contents"),
    ToolbarActionDef("tts", "TTS", Icons.Default.Headphones, "Audiobook TTS"),
    ToolbarActionDef("bookmark", "Bookmark", Icons.Default.BookmarkBorder, "Bookmark"),
    ToolbarActionDef("download", "Download", Icons.Default.CloudDownload, "Download Chapter"),
    ToolbarActionDef("display", "Display", Icons.Default.FormatSize, "Display & Styling"),
    ToolbarActionDef("reading_mode", "Mode", Icons.Default.AutoStories, "Reading Mode"),
    ToolbarActionDef("progress", "Jump", Icons.Default.LinearScale, "Jump Progress")
)

fun applyTermReplacements(text: String, rules: List<TermReplacementRule>): String {
    val activeRules = rules.filter { it.isEnabled && it.original.isNotBlank() }
    if (activeRules.isEmpty() || text.isBlank()) return text

    // Sort by descending original length to prioritize specific longer phrases
    val sortedRules = activeRules.sortedByDescending { it.original.length }
    val pattern = Regex(sortedRules.joinToString("|") { Regex.escape(it.original) })

    return pattern.replace(text) { matchResult ->
        val matched = matchResult.value
        val rule = sortedRules.firstOrNull { it.original.equals(matched, ignoreCase = false) }
            ?: sortedRules.firstOrNull { it.original.equals(matched, ignoreCase = true) }
        rule?.replacement ?: matched
    }
}

@Composable
fun rememberBatteryPercent(): Int {
    val context = LocalContext.current
    var batteryPercent by remember { mutableIntStateOf(100) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        batteryPercent = (level * 100) / scale
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val stickyIntent = context.registerReceiver(receiver, filter)
        stickyIntent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                batteryPercent = (level * 100) / scale
            }
        }
        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) { }
        }
    }
    return batteryPercent
}

@Composable
fun rememberClockTime(): String {
    var timeStr by remember {
        mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
    }
    LaunchedEffect(Unit) {
        while (true) {
            timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(15_000L)
        }
    }
    return timeStr
}

data class TextPage(val startOffset: Int, val endOffset: Int)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
fun ReaderScreen(
    novel: Novel,
    initialChapterId: String,
    initialChapterIndex: Int,
    repository: NovelRepository,
    onBack: () -> Unit,
    onOpenWebUrl: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Preferences & Settings
    val readerSettings by repository.readerSettings.collectAsState()
    val themeSettings by repository.themeSettings.collectAsState()
    val termRules by repository.termReplacements.collectAsState()
    val appAccent = remember(themeSettings.accentColorHex) { parseColorHex(themeSettings.accentColorHex) }
    val readerColors = remember(readerSettings.theme, appAccent) { getReaderColors(readerSettings.theme, appAccent) }

    // Navigation and Chapters
    var currentChapterId by remember { mutableStateOf(initialChapterId) }
    var currentChapterIndex by remember { mutableIntStateOf(initialChapterIndex) }
    var chapterList by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var chapterContent by remember { mutableStateOf<ChapterContent?>(null) }
    var chapterLoadState by remember { mutableStateOf<ChapterLoadState>(ChapterLoadState.Loading) }
    var reloadTrigger by remember { mutableIntStateOf(0) }

    // Reading Modes State
    val initialScrollIndex = remember(currentChapterId) {
        repository.getChapterReadingPosition(novel.id, currentChapterId)
    }
    val listState = rememberSaveable(
        currentChapterId,
        saver = LazyListState.Saver
    ) {
        LazyListState(
            firstVisibleItemIndex = initialScrollIndex,
            firstVisibleItemScrollOffset = 0
        )
    }
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var currentReadingOffset by remember { mutableIntStateOf(0) }
    var pendingRestorePage by remember { mutableStateOf<Int?>(null) }

    // UI overlays & Sheets
    var showControls by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showTocSheet by remember { mutableStateOf(false) }
    var showBookmarksSheet by remember { mutableStateOf(false) }
    var showToolbarCustomizer by remember { mutableStateOf(false) }
    var showTermReplacementSheet by remember { mutableStateOf(false) }
    var showMoreMenuSheet by remember { mutableStateOf(false) }
    var showJumpSlider by remember { mutableStateOf(false) }

    // Quick Dictionary
    var showDictionaryDialog by remember { mutableStateOf(false) }
    var lookupWord by remember { mutableStateOf("") }
    var lookupDefinition by remember { mutableStateOf("") }

    // Audiobook TTS Manager
    val ttsManager = remember { TtsManager(context) }
    val isTtsPlaying by ttsManager.isPlaying.collectAsState()
    val ttsCurrentParagraphIndex by ttsManager.currentParagraphIndex.collectAsState()
    var isTtsSheetVisible by remember { mutableStateOf(false) }

    // Synchronize TTS speech rate immediately with saved readerSettings
    LaunchedEffect(readerSettings.ttsSpeechRate) {
        ttsManager.setSpeed(readerSettings.ttsSpeechRate)
    }

    // Status Area helpers
    val batteryPercent = rememberBatteryPercent()
    val clockTime = rememberClockTime()

    // Auto-scroll TTS cleanup on exit
    DisposableEffect(Unit) {
        onDispose {
            ttsManager.release()
        }
    }

    // Immersive mode: hide system bars when reading, restore on exit
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        } else {
            onDispose { }
        }
    }

    // Intercept Back Press: dismiss open sheets/controls first, or return to details
    BackHandler {
        when {
            showSettingsSheet -> showSettingsSheet = false
            showTocSheet -> showTocSheet = false
            showBookmarksSheet -> showBookmarksSheet = false
            showToolbarCustomizer -> showToolbarCustomizer = false
            showTermReplacementSheet -> showTermReplacementSheet = false
            showMoreMenuSheet -> showMoreMenuSheet = false
            showJumpSlider -> showJumpSlider = false
            showDictionaryDialog -> showDictionaryDialog = false
            isTtsSheetVisible -> {
                ttsManager.stop()
                isTtsSheetVisible = false
            }
            showControls -> showControls = false
            else -> onBack()
        }
    }

    // Load chapters list for novel
    LaunchedEffect(novel.id) {
        try {
            chapterList = repository.getChapterList(novel)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Load current chapter content with saved position restoration
    LaunchedEffect(currentChapterId, reloadTrigger) {
        chapterLoadState = ChapterLoadState.Loading
        try {
            val content = repository.getChapterContent(novel.id, currentChapterId)
            chapterContent = content
            chapterLoadState = ChapterLoadState.Success(content)

            // Restore saved position independently for this chapter
            val savedPos = repository.getChapterReadingPosition(novel.id, currentChapterId)
            val restoredPage = savedPos.coerceAtLeast(0)
            currentPageIndex = restoredPage
            pendingRestorePage = restoredPage

            // Update reading progress in repository
            repository.updateReadingProgress(
                novelId = novel.id,
                chapterId = currentChapterId,
                chapterTitle = content.title,
                chapterIndex = currentChapterIndex,
                scrollPosition = savedPos,
                wordsReadDelta = content.paragraphs.sumOf { it.split("\\s+".toRegex()).size }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            chapterLoadState = ChapterLoadState.Error(e.message ?: "Failed to load chapter content.")
        }
    }

    // Tier 1: in-memory only. No disk I/O.
    var pendingScrollIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(listState, currentChapterId) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { pendingScrollIndex = it }
    }

    // Tier 2: lightweight offset write, debounced 1200ms.
    LaunchedEffect(listState, currentChapterId) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .debounce(1_200L)
            .distinctUntilChanged()
            .collect { idx ->
                if (chapterLoadState is ChapterLoadState.Success
                        && chapterContent != null && idx > 0) {
                    repository.saveChapterReadingPosition(novel.id, currentChapterId, idx)
                }
            }
    }

    // Tier 3: full write, only on scroll-stop.
    LaunchedEffect(listState, currentChapterId) {
        snapshotFlow { listState.isScrollInProgress }
            .drop(1)
            .distinctUntilChanged()
            .filter { !it }
            .collect {
                if (chapterLoadState is ChapterLoadState.Success
                        && chapterContent != null) {
                    val idx = listState.firstVisibleItemIndex
                    if (idx > 0) {
                        repository.saveChapterReadingPosition(novel.id, currentChapterId, idx)
                        repository.updateReadingProgress(
                            novelId = novel.id,
                            chapterId = currentChapterId,
                            chapterTitle = chapterContent?.title ?: "Chapter $currentChapterIndex",
                            chapterIndex = currentChapterIndex,
                            scrollPosition = idx,
                            wordsReadDelta = 0
                        )
                    }
                }
            }
    }

    // Save reading progress as user navigates pages (Paginated Mode)
    LaunchedEffect(currentPageIndex) {
        if (chapterLoadState is ChapterLoadState.Success && chapterContent != null) {
            if (pendingRestorePage == null) {
                repository.saveChapterReadingPosition(novel.id, currentChapterId, currentPageIndex)
                repository.updateReadingProgress(
                    novelId = novel.id,
                    chapterId = currentChapterId,
                    chapterTitle = chapterContent?.title ?: "Chapter $currentChapterIndex",
                    chapterIndex = currentChapterIndex,
                    scrollPosition = currentPageIndex,
                    wordsReadDelta = 0
                )
            }
        }
    }

    // Persist reading position on exit or chapter change
    DisposableEffect(currentChapterId) {
        val chapterIdAtComposition = currentChapterId
        onDispose {
            val pos = if (readerSettings.readingMode == ReadingMode.PAGINATED) {
                currentPageIndex
            } else {
                listState.firstVisibleItemIndex
            }
            if (pos > 0) {
                repository.saveChapterReadingPositionAsync(novel.id, chapterIdAtComposition, pos)
                repository.persistReadingProgressAsync(
                    novelId = novel.id,
                    chapterId = chapterIdAtComposition,
                    chapterTitle = chapterContent?.title ?: "Chapter $currentChapterIndex",
                    chapterIndex = currentChapterIndex,
                    scrollPosition = pos,
                    wordsReadDelta = 0
                )
            }
        }
    }

    // Auto-scroll loop
    LaunchedEffect(readerSettings.autoScrollSpeed, readerSettings.readingMode) {
        if (readerSettings.autoScrollSpeed > 0 && readerSettings.readingMode == ReadingMode.CONTINUOUS) {
            val delayMs = (700L / readerSettings.autoScrollSpeed).coerceIn(100L, 800L)
            while (true) {
                delay(delayMs)
                listState.scrollBy(4f)
            }
        }
    }

    // Font family mapping
    val currentFontFamily = when (readerSettings.fontType) {
        FontType.SANS -> FontFamily.SansSerif
        FontType.SERIF -> FontFamily.Serif
        FontType.MONOSPACE -> FontFamily.Monospace
        FontType.ROUNDED -> FontFamily(android.graphics.Typeface.create("sans-serif-rounded", android.graphics.Typeface.NORMAL))
    }

    // Processed text with Term Replacements
    val displayParagraphs = remember(chapterContent?.paragraphs, termRules) {
        chapterContent?.paragraphs?.map { applyTermReplacements(it, termRules) } ?: emptyList()
    }

    val currentChapterInfo = remember(chapterList, currentChapterId, currentChapterIndex) {
        chapterList.firstOrNull { it.id == currentChapterId }
            ?: chapterList.firstOrNull { it.chapterIndex == currentChapterIndex }
    }

    val resolvedChapterTitle = remember(chapterContent?.title, currentChapterInfo?.title, currentChapterIndex) {
        val raw = chapterContent?.title?.takeIf { it.isNotBlank() }
            ?: currentChapterInfo?.title?.takeIf { it.isNotBlank() }
            ?: "Chapter $currentChapterIndex"
        com.example.data.source.webscrape.WtrLabExtractor.cleanWtrLabTokens(raw)
    }

    val displayTitle = remember(resolvedChapterTitle, termRules) {
        applyTermReplacements(resolvedChapterTitle, termRules)
    }

    val fullText = remember(displayParagraphs) {
        displayParagraphs.joinToString("\n\n")
    }

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val readerTextStyle = remember(
        currentFontFamily,
        readerSettings.fontSizeSp,
        readerSettings.lineSpacingMultiplier,
        readerSettings.isJustified,
        readerColors.text
    ) {
        TextStyle(
            fontFamily = currentFontFamily,
            fontSize = readerSettings.fontSizeSp.sp,
            lineHeight = (readerSettings.fontSizeSp * readerSettings.lineSpacingMultiplier).sp,
            color = readerColors.text,
            textAlign = if (readerSettings.isJustified) TextAlign.Justify else TextAlign.Start
        )
    }

    // Helper to navigate between chapters
    fun navigateToChapter(targetIndex: Int) {
        val target = targetIndex.coerceIn(1, chapterList.size.coerceAtLeast(1))
        val targetChapter = chapterList.firstOrNull { it.chapterIndex == target }
        if (targetChapter != null && targetChapter.id != currentChapterId) {
            currentChapterId = targetChapter.id
            currentChapterIndex = target
        }
    }

    // Enabled toolbar action definitions
    val enabledActionDefs = remember(readerSettings.enabledToolbarActions) {
        val set = readerSettings.enabledToolbarActions.toSet()
        ALL_TOOLBAR_ACTIONS.filter { set.contains(it.id) }
    }

    // Main Scaffold Layout
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(readerColors.background)
    ) {
        val horizontalPadding = readerSettings.marginType.horizontalPaddingDp.dp
        val topPadding = if (readerSettings.showStatusBar) 52.dp else 40.dp
        val bottomPadding = 48.dp

        val availableWidthPx = remember(maxWidth, horizontalPadding, density) {
            with(density) {
                (maxWidth - (horizontalPadding * 2)).roundToPx().coerceAtLeast(100)
            }
        }
        val availableHeightPx = remember(maxHeight, topPadding, bottomPadding, density) {
            with(density) {
                (maxHeight - topPadding - bottomPadding).roundToPx().coerceAtLeast(100)
            }
        }

        val headerHeightPx = remember(
            displayTitle,
            currentChapterIndex,
            currentFontFamily,
            readerSettings.fontSizeSp,
            availableWidthPx,
            density
        ) {
            if (displayTitle.isBlank()) 0 else {
                val titleStyle = TextStyle(
                    fontFamily = currentFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = (readerSettings.fontSizeSp + 5).sp,
                    lineHeight = (readerSettings.fontSizeSp + 9).sp
                )
                val titleResult = textMeasurer.measure(
                    text = displayTitle,
                    style = titleStyle,
                    constraints = Constraints(maxWidth = availableWidthPx)
                )
                with(density) { (12 + 6 + 12 + 1 + 20).dp.roundToPx() + 18.sp.roundToPx() } + titleResult.size.height
            }
        }

        val pages = remember(
            fullText,
            readerTextStyle,
            availableWidthPx,
            availableHeightPx,
            headerHeightPx
        ) {
            if (fullText.isEmpty() || availableWidthPx <= 0 || availableHeightPx <= 0) {
                listOf(TextPage(0, 0))
            } else {
                val layoutResult = textMeasurer.measure(
                    text = fullText,
                    style = readerTextStyle,
                    constraints = Constraints(maxWidth = availableWidthPx)
                )
                val totalLines = layoutResult.lineCount
                if (totalLines == 0) {
                    listOf(TextPage(0, 0))
                } else {
                    val pageList = mutableListOf<TextPage>()
                    var currentStartLine = 0

                    while (currentStartLine < totalLines) {
                        val pageAvailableHeight = if (pageList.isEmpty()) {
                            (availableHeightPx - headerHeightPx).coerceAtLeast(availableHeightPx / 3)
                        } else {
                            availableHeightPx
                        }

                        val pageStartTop = layoutResult.getLineTop(currentStartLine)
                        var currentEndLine = currentStartLine

                        while (currentEndLine + 1 < totalLines) {
                            val nextBottom = layoutResult.getLineBottom(currentEndLine + 1)
                            if (nextBottom - pageStartTop <= pageAvailableHeight) {
                                currentEndLine++
                            } else {
                                break
                            }
                        }

                        val startOffset = layoutResult.getLineStart(currentStartLine)
                        val endOffset = layoutResult.getLineEnd(currentEndLine)
                        pageList.add(TextPage(startOffset, endOffset))

                        currentStartLine = currentEndLine + 1
                    }

                    if (pageList.isEmpty()) listOf(TextPage(0, 0)) else pageList
                }
            }
        }

        val totalPages = pages.size.coerceAtLeast(1)

        // Reflow position preservation: update currentPageIndex to the page containing currentReadingOffset
        LaunchedEffect(pages) {
            if (pages.isNotEmpty()) {
                val pending = pendingRestorePage
                val targetIndex = if (pending != null) {
                    pendingRestorePage = null
                    val clamped = pending.coerceIn(0, pages.size - 1)
                    currentReadingOffset = pages.getOrNull(clamped)?.startOffset ?: 0
                    clamped
                } else {
                    pages.indexOfFirst { page ->
                        currentReadingOffset >= page.startOffset && currentReadingOffset < page.endOffset
                    }.let { idx ->
                        if (idx >= 0) {
                            idx
                        } else if (currentReadingOffset >= (pages.lastOrNull()?.endOffset ?: 0)) {
                            pages.size - 1
                        } else {
                            0
                        }
                    }
                }
                if (targetIndex != currentPageIndex) {
                    currentPageIndex = targetIndex.coerceIn(0, pages.size - 1)
                }
            }
        }

        // Track currentReadingOffset whenever page index changes
        LaunchedEffect(currentPageIndex, pages) {
            val start = pages.getOrNull(currentPageIndex)?.startOffset
            if (start != null) {
                currentReadingOffset = start
            }
        }
        // -------------------------------------------------------------
        // MAIN READING VIEWPORT
        // -------------------------------------------------------------
        when (val state = chapterLoadState) {
            is ChapterLoadState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = readerColors.accent,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Loading chapter…",
                            fontFamily = currentFontFamily,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = readerColors.text.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            is ChapterLoadState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Unable to load chapter",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = readerColors.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.message,
                            fontSize = 14.sp,
                            color = readerColors.text.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ElevatedButton(
                                onClick = { reloadTrigger++ },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Retry")
                            }

                            if (currentChapterId.startsWith("http://") || currentChapterId.startsWith("https://") || novel.sourceId == "custom_web") {
                                OutlinedButton(
                                    onClick = {
                                        val targetUrl = if (currentChapterId.startsWith("http")) currentChapterId else novel.id
                                        onOpenWebUrl?.invoke(targetUrl)
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Open in Browser")
                                }
                            }
                        }
                    }
                }
            }

            is ChapterLoadState.Success -> {
                if (readerSettings.readingMode == ReadingMode.CONTINUOUS) {
                    // -------------------------------------------------------------
                    // CONTINUOUS VERTICAL SCROLL MODE
                    // -------------------------------------------------------------
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(
                            top = if (readerSettings.showStatusBar) 52.dp else 40.dp,
                            bottom = 120.dp,
                            start = readerSettings.marginType.horizontalPaddingDp.dp,
                            end = readerSettings.marginType.horizontalPaddingDp.dp
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { showControls = !showControls })
                            }
                    ) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp, bottom = 24.dp)
                            ) {
                                Text(
                                    text = "CHAPTER $currentChapterIndex",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        letterSpacing = 2.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = readerColors.accent
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = displayTitle,
                                    fontFamily = currentFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = (readerSettings.fontSizeSp + 6).sp,
                                    color = readerColors.onSurface,
                                    lineHeight = (readerSettings.fontSizeSp + 10).sp
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                HorizontalDivider(
                                    color = readerColors.onSurface.copy(alpha = 0.12f),
                                    thickness = 1.dp
                                )
                            }
                        }

                        itemsIndexed(displayParagraphs) { index, para ->
                            val isHighlighted = isTtsPlaying && ttsCurrentParagraphIndex == index
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = (readerSettings.fontSizeSp * 0.7f).dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isHighlighted) readerColors.highlight.copy(alpha = 0.4f) else Color.Transparent)
                                    .combinedClickable(
                                        onClick = { showControls = !showControls },
                                        onLongClick = {
                                            val firstWord = para.split("\\s+".toRegex()).firstOrNull()?.replace("[^a-zA-Z]".toRegex(), "") ?: ""
                                            if (firstWord.length > 2) {
                                                lookupWord = firstWord
                                                lookupDefinition = getMockDefinition(firstWord)
                                                showDictionaryDialog = true
                                            }
                                        }
                                    )
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = para,
                                    fontFamily = currentFontFamily,
                                    fontSize = readerSettings.fontSizeSp.sp,
                                    lineHeight = (readerSettings.fontSizeSp * readerSettings.lineSpacingMultiplier).sp,
                                    color = readerColors.text,
                                    textAlign = if (readerSettings.isJustified) TextAlign.Justify else TextAlign.Start
                                )
                            }
                        }

                        // Chapter Navigation Footers
                        item {
                            Spacer(modifier = Modifier.height(32.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (currentChapterIndex > 1) {
                                    TextButton(
                                        onClick = { navigateToChapter(currentChapterIndex - 1) }
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowLeft, contentDescription = null, tint = readerColors.accent)
                                        Text("Previous Chapter", color = readerColors.accent)
                                    }
                                } else {
                                    Spacer(modifier = Modifier.width(10.dp))
                                }

                                if (currentChapterIndex < chapterList.size) {
                                    TextButton(
                                        onClick = { navigateToChapter(currentChapterIndex + 1) }
                                    ) {
                                        Text("Next Chapter", color = readerColors.accent)
                                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = readerColors.accent)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // -------------------------------------------------------------
                    // PAGINATED READING MODE
                    // -------------------------------------------------------------
                    val page = pages.getOrNull(currentPageIndex)
                    val pageText = if (page != null && page.startOffset < page.endOffset && page.endOffset <= fullText.length) {
                        fullText.substring(page.startOffset, page.endOffset).trimEnd()
                    } else {
                        ""
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(currentPageIndex, totalPages, currentChapterIndex, chapterList.size) {
                                detectTapGestures(
                                    onTap = { offset ->
                                        val screenWidth = size.width
                                        if (offset.x < screenWidth * 0.32f) {
                                            // Left tap: previous page or previous chapter
                                            if (currentPageIndex > 0) {
                                                currentPageIndex--
                                            } else if (currentChapterIndex > 1) {
                                                navigateToChapter(currentChapterIndex - 1)
                                            }
                                        } else if (offset.x > screenWidth * 0.68f) {
                                            // Right tap: next page or next chapter
                                            if (currentPageIndex < totalPages - 1) {
                                                currentPageIndex++
                                            } else if (currentChapterIndex < chapterList.size) {
                                                navigateToChapter(currentChapterIndex + 1)
                                            }
                                        } else {
                                            // Center tap: toggle toolbar controls
                                            showControls = !showControls
                                        }
                                    }
                                )
                            }
                            .padding(
                                top = topPadding,
                                bottom = bottomPadding,
                                start = horizontalPadding,
                                end = horizontalPadding
                            )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (currentPageIndex == 0) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp, bottom = 20.dp)
                                ) {
                                    Text(
                                        text = "CHAPTER $currentChapterIndex",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            letterSpacing = 2.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = readerColors.accent
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = displayTitle,
                                        fontFamily = currentFontFamily,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = (readerSettings.fontSizeSp + 5).sp,
                                        color = readerColors.onSurface,
                                        lineHeight = (readerSettings.fontSizeSp + 9).sp
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    HorizontalDivider(
                                        color = readerColors.onSurface.copy(alpha = 0.12f),
                                        thickness = 1.dp
                                    )
                                }
                            }

                            if (pageText.isNotEmpty()) {
                                Text(
                                    text = pageText,
                                    style = readerTextStyle,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // Paginated Page Count / Navigation Pill at bottom
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = readerColors.surface.copy(alpha = 0.90f),
                            border = BorderStroke(1.dp, readerColors.accent.copy(alpha = 0.25f)),
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        if (currentPageIndex > 0) currentPageIndex--
                                        else if (currentChapterIndex > 1) navigateToChapter(currentChapterIndex - 1)
                                    },
                                    enabled = currentPageIndex > 0 || currentChapterIndex > 1,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ChevronLeft,
                                        contentDescription = "Previous Page",
                                        tint = if (currentPageIndex > 0 || currentChapterIndex > 1) readerColors.accent else readerColors.onSurface.copy(alpha = 0.3f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Text(
                                    text = "Page ${currentPageIndex + 1} of $totalPages",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = readerColors.onSurface,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )

                                IconButton(
                                    onClick = {
                                        if (currentPageIndex < totalPages - 1) currentPageIndex++
                                        else if (currentChapterIndex < chapterList.size) navigateToChapter(currentChapterIndex + 1)
                                    },
                                    enabled = currentPageIndex < totalPages - 1 || currentChapterIndex < chapterList.size,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = "Next Page",
                                        tint = if (currentPageIndex < totalPages - 1 || currentChapterIndex < chapterList.size) readerColors.accent else readerColors.onSurface.copy(alpha = 0.3f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // UNOBTRUSIVE TOP STATUS BAR (Time & Battery)
        // -------------------------------------------------------------
        if (readerSettings.showStatusBar) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayTitle,
                    fontSize = 11.sp,
                    color = readerColors.text.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "$batteryPercent%",
                        fontSize = 11.sp,
                        color = readerColors.text.copy(alpha = 0.5f)
                    )
                    Text(
                        text = clockTime,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = readerColors.text.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // -------------------------------------------------------------
        // TOP APP BAR / HEADER (When showControls is active)
        // -------------------------------------------------------------
        AnimatedVisibility(
            visible = showControls,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = readerColors.surface.copy(alpha = 0.96f),
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                border = BorderStroke(1.dp, readerColors.onSurface.copy(alpha = 0.08f)),
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = readerColors.onSurface)
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = displayTitle,
                            fontFamily = currentFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = readerColors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = novel.title,
                            fontSize = 11.sp,
                            color = readerColors.text.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Quick Top-bar actions
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Default.FormatSize, contentDescription = "Display", tint = readerColors.onSurface)
                    }
                    IconButton(onClick = { showTocSheet = true }) {
                        Icon(Icons.Default.List, contentDescription = "Contents", tint = readerColors.onSurface)
                    }
                    IconButton(onClick = { showMoreMenuSheet = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More Options", tint = readerColors.onSurface)
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // CUSTOMIZABLE TAP-TO-SHOW TOOLBAR (Configurable Top / Bottom)
        // -------------------------------------------------------------
        AnimatedVisibility(
            visible = showControls,
            enter = if (readerSettings.toolbarPosition == ToolbarPosition.TOP) {
                slideInVertically(initialOffsetY = { -it }) + fadeIn()
            } else {
                slideInVertically(initialOffsetY = { it }) + fadeIn()
            },
            exit = if (readerSettings.toolbarPosition == ToolbarPosition.TOP) {
                slideOutVertically(targetOffsetY = { -it }) + fadeOut()
            } else {
                slideOutVertically(targetOffsetY = { it }) + fadeOut()
            },
            modifier = Modifier.align(
                if (readerSettings.toolbarPosition == ToolbarPosition.TOP) Alignment.TopCenter else Alignment.BottomCenter
            )
        ) {
            Surface(
                color = readerColors.background,
                shape = RectangleShape,
                shadowElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (readerSettings.toolbarPosition == ToolbarPosition.TOP) 58.dp else 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    // Chapter Jump Progress Slider Bar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Ch. $currentChapterIndex of ${chapterList.size.coerceAtLeast(1)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = readerColors.text
                        )
                        if (chapterContent?.isDownloaded == true) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DownloadedBadge(
                                    accentColor = readerColors.accent,
                                    size = 14.dp
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Downloaded", style = MaterialTheme.typography.labelSmall.copy(color = readerColors.accent, fontSize = 10.sp))
                            }
                        }
                    }

                    // Progress Slider: seeks within the current chapter's content
                    val isContinuous = readerSettings.readingMode == ReadingMode.CONTINUOUS
                    val maxSliderValue = if (isContinuous) {
                        (displayParagraphs.size - 1).coerceAtLeast(0)
                    } else {
                        (totalPages - 1).coerceAtLeast(0)
                    }
                    val currentSliderValue = if (isContinuous) {
                        listState.firstVisibleItemIndex.coerceIn(0, maxSliderValue).toFloat()
                    } else {
                        currentPageIndex.coerceIn(0, maxSliderValue).toFloat()
                    }

                    CleanSlider(
                        value = if (maxSliderValue > 0) currentSliderValue else 0f,
                        onValueChange = { newValue ->
                            val rounded = newValue.roundToInt()
                            if (isContinuous) {
                                scope.launch {
                                    listState.scrollToItem(rounded.coerceIn(0, maxSliderValue))
                                }
                            } else {
                                currentPageIndex = rounded.coerceIn(0, maxSliderValue)
                            }
                        },
                        enabled = maxSliderValue > 0,
                        valueRange = 0f..maxSliderValue.coerceAtLeast(1).toFloat(),
                        steps = (maxSliderValue - 1).coerceAtLeast(0),
                        accentColor = readerColors.accent,
                        trackColor = readerColors.onSurface.copy(alpha = 0.18f),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Customizable Action Icons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // If more than 5 enabled actions, show first 4 and an Overflow More button
                        val displayedActions = if (enabledActionDefs.size > 5) {
                            enabledActionDefs.take(4)
                        } else {
                            enabledActionDefs
                        }

                        displayedActions.forEach { action ->
                            ToolbarActionButton(
                                action = action,
                                readerColors = readerColors,
                                isTtsPlaying = isTtsPlaying,
                                readingMode = readerSettings.readingMode,
                                onAction = {
                                    handleToolbarAction(
                                        actionId = action.id,
                                        currentChapterIndex = currentChapterIndex,
                                        chapterListSize = chapterList.size,
                                        chapterContent = chapterContent,
                                        isTtsPlaying = isTtsPlaying,
                                        onNavigate = { navigateToChapter(it) },
                                        onOpenToc = { showTocSheet = true },
                                        onOpenDisplay = { showSettingsSheet = true },
                                        onToggleTts = {
                                            isTtsSheetVisible = true
                                            if (chapterContent != null) {
                                                if (isTtsPlaying) ttsManager.stop()
                                                else ttsManager.startReading(chapterContent!!.paragraphs, 0)
                                            }
                                        },
                                        onAddBookmark = {
                                            if (chapterContent != null) {
                                                val excerpt = displayParagraphs.firstOrNull()?.take(100) ?: "Chapter bookmark"
                                                repository.addBookmark(
                                                    Bookmark(
                                                        id = "bm_${System.currentTimeMillis()}",
                                                        novelId = novel.id,
                                                        chapterId = currentChapterId,
                                                        chapterTitle = displayTitle,
                                                        paragraphIndex = 0,
                                                        excerpt = excerpt,
                                                        note = "Bookmark spot"
                                                    )
                                                )
                                                showBookmarksSheet = true
                                            }
                                        },
                                        onDownload = {
                                            val currentCh = chapterList.firstOrNull { it.id == currentChapterId }
                                            if (currentCh != null) {
                                                scope.launch {
                                                    val ok = repository.downloadChapter(novel, currentCh)
                                                    Toast.makeText(
                                                        context,
                                                        if (ok) "Chapter downloaded for offline reading" else "Download error",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        },
                                        onToggleMode = {
                                            val nextMode = if (readerSettings.readingMode == ReadingMode.CONTINUOUS) ReadingMode.PAGINATED else ReadingMode.CONTINUOUS
                                            repository.updateReaderSettings(readerSettings.copy(readingMode = nextMode))
                                        },
                                        onToggleProgress = {
                                            showJumpSlider = !showJumpSlider
                                        }
                                    )
                                }
                            )
                        }

                        // Overflow / More action button
                        if (enabledActionDefs.size > 5 || enabledActionDefs.isEmpty()) {
                            IconButton(onClick = { showMoreMenuSheet = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More Actions", tint = readerColors.onSurface)
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // FLOATING TTS AUDIO CONTROLLER (When Active)
        // -------------------------------------------------------------
        AnimatedVisibility(
            visible = isTtsSheetVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (showControls) 120.dp else 24.dp, start = 16.dp, end = 16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = readerColors.surface),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, readerColors.accent.copy(alpha = 0.3f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Top header: Playback status and Close
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = readerColors.accent.copy(alpha = 0.15f),
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isTtsPlaying) Icons.Default.VolumeUp else Icons.Default.Headphones,
                                        contentDescription = null,
                                        tint = readerColors.accent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "Text-to-Speech",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = readerColors.accent
                                )
                                Text(
                                    text = if (ttsCurrentParagraphIndex >= 0) "Paragraph ${ttsCurrentParagraphIndex + 1} of ${displayParagraphs.size}" else "Playback Paused",
                                    style = MaterialTheme.typography.bodySmall.copy(color = readerColors.onSurface.copy(alpha = 0.8f), fontSize = 11.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                ttsManager.stop()
                                isTtsSheetVisible = false
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close TTS",
                                tint = readerColors.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Middle row: Rewind, Play/Pause, Fast-Forward
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                if (displayParagraphs.isNotEmpty()) {
                                    val prev = (ttsCurrentParagraphIndex - 1).coerceAtLeast(0)
                                    ttsManager.startReading(displayParagraphs, prev)
                                }
                            },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastRewind,
                                contentDescription = "Previous Paragraph",
                                tint = readerColors.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        FilledIconButton(
                            onClick = {
                                if (isTtsPlaying) ttsManager.pause()
                                else if (displayParagraphs.isNotEmpty()) {
                                    val startIdx = if (ttsCurrentParagraphIndex >= 0) ttsCurrentParagraphIndex else 0
                                    ttsManager.startReading(displayParagraphs, startIdx)
                                }
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = readerColors.accent,
                                contentColor = calculateOnColor(readerColors.accent)
                            ),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = if (isTtsPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isTtsPlaying) "Pause" else "Play",
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        IconButton(
                            onClick = {
                                if (displayParagraphs.isNotEmpty()) {
                                    val next = (ttsCurrentParagraphIndex + 1).coerceAtMost(displayParagraphs.size - 1)
                                    ttsManager.startReading(displayParagraphs, next)
                                }
                            },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastForward,
                                contentDescription = "Next Paragraph",
                                tint = readerColors.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Bottom row: Compact TTS Speed Control Presets (1x, 1.25x, 1.5x, 2x)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Speed",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = readerColors.text.copy(alpha = 0.7f),
                            modifier = Modifier.padding(end = 8.dp)
                        )

                        val speedPresets = listOf(1.0f to "1x", 1.25f to "1.25x", 1.5f to "1.5x", 2.0f to "2x")
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            speedPresets.forEach { (rate, label) ->
                                val isSelected = kotlin.math.abs(readerSettings.ttsSpeechRate - rate) < 0.05f
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) readerColors.accent else readerColors.surface.copy(alpha = 0.5f),
                                    contentColor = if (isSelected) calculateOnColor(readerColors.accent) else readerColors.onSurface,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) readerColors.accent else readerColors.onSurface.copy(alpha = 0.15f)
                                    ),
                                    modifier = Modifier
                                        .height(28.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            ttsManager.setSpeed(rate)
                                            repository.updateReaderSettings(readerSettings.copy(ttsSpeechRate = rate))
                                        }
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.padding(horizontal = 10.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // DISPLAY / APPEARANCE SETTINGS MODAL BOTTOM SHEET
        // -------------------------------------------------------------
        if (showSettingsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSettingsSheet = false },
                containerColor = readerColors.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Display & Styling",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = readerColors.onSurface
                        )
                        TextButton(onClick = { showToolbarCustomizer = true }) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Toolbar")
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Theme Palette: Light, Warm, Sepia, Dark, AMOLED
                    Text("Theme Palette", style = MaterialTheme.typography.labelMedium, color = readerColors.text)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReaderTheme.values().forEach { theme ->
                            val isSelected = readerSettings.theme == theme
                            val colors = getReaderColors(theme)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = colors.background,
                                border = if (isSelected)
                                    BorderStroke(2.dp, readerColors.accent)
                                else
                                    BorderStroke(1.dp, readerColors.onSurface.copy(alpha = 0.15f)),
                                shadowElevation = if (isSelected) 3.dp else 0.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        repository.updateReaderSettings(readerSettings.copy(theme = theme))
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = theme.displayName,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = colors.text,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Reading Mode: Continuous Scroll vs Paginated
                    Text("Reading Mode", style = MaterialTheme.typography.labelMedium, color = readerColors.text)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReadingMode.values().forEach { mode ->
                            FilterChip(
                                selected = readerSettings.readingMode == mode,
                                onClick = {
                                    repository.updateReaderSettings(readerSettings.copy(readingMode = mode))
                                },
                                label = {
                                    Text(
                                        text = mode.displayName,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (mode == ReadingMode.PAGINATED) Icons.Default.AutoStories else Icons.Default.SwapVert,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Font Size Slider with A- / A+
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Font Size", style = MaterialTheme.typography.labelMedium, color = readerColors.text)
                        Text("${readerSettings.fontSizeSp} sp", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = readerColors.accent)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "A",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = readerColors.text.copy(alpha = 0.7f),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        CleanSlider(
                            value = readerSettings.fontSizeSp.toFloat(),
                            onValueChange = {
                                repository.updateReaderSettings(readerSettings.copy(fontSizeSp = it.toInt()))
                            },
                            valueRange = 14f..32f,
                            steps = 17,
                            accentColor = readerColors.accent,
                            trackColor = readerColors.onSurface.copy(alpha = 0.18f),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "A",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = readerColors.text,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Font Family Selector
                    Text("Font Family", style = MaterialTheme.typography.labelMedium, color = readerColors.text)
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val allFonts = FontType.values()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            allFonts.take(2).forEach { font ->
                                FilterChip(
                                    selected = readerSettings.fontType == font,
                                    onClick = {
                                        repository.updateReaderSettings(readerSettings.copy(fontType = font))
                                    },
                                    label = {
                                        Text(
                                            text = font.displayName,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            allFonts.drop(2).forEach { font ->
                                FilterChip(
                                    selected = readerSettings.fontType == font,
                                    onClick = {
                                        repository.updateReaderSettings(readerSettings.copy(fontType = font))
                                    },
                                    label = {
                                        Text(
                                            text = font.displayName,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Horizontal Margins
                    Text("Horizontal Margins", style = MaterialTheme.typography.labelMedium, color = readerColors.text)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MarginType.values().forEach { m ->
                            FilterChip(
                                selected = readerSettings.marginType == m,
                                onClick = {
                                    repository.updateReaderSettings(readerSettings.copy(marginType = m))
                                },
                                label = {
                                    Text(
                                        text = m.displayName,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Text Alignment & Status Bar Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Justify Text", style = MaterialTheme.typography.bodyMedium, color = readerColors.text)
                        Switch(
                            checked = readerSettings.isJustified,
                            onCheckedChange = {
                                repository.updateReaderSettings(readerSettings.copy(isJustified = it))
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show Clock & Battery", style = MaterialTheme.typography.bodyMedium, color = readerColors.text)
                        Switch(
                            checked = readerSettings.showStatusBar,
                            onCheckedChange = {
                                repository.updateReaderSettings(readerSettings.copy(showStatusBar = it))
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Term Replacement & Toolbar Management Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showSettingsSheet = false
                                showTermReplacementSheet = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Spellcheck, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Term Rules")
                        }

                        OutlinedButton(
                            onClick = {
                                showSettingsSheet = false
                                showToolbarCustomizer = true
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Toolbar Config")
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // CUSTOMIZE TOOLBAR MODAL BOTTOM SHEET
        // -------------------------------------------------------------
        if (showToolbarCustomizer) {
            ModalBottomSheet(
                onDismissRequest = { showToolbarCustomizer = false },
                containerColor = readerColors.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Customize Reader Toolbar",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = readerColors.onSurface
                    )
                    Text(
                        text = "Select which actions appear on your tap-to-show toolbar",
                        style = MaterialTheme.typography.bodySmall,
                        color = readerColors.text.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Toolbar Position Selector
                    Text("Toolbar Position", style = MaterialTheme.typography.labelMedium, color = readerColors.text)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ToolbarPosition.values().forEach { pos ->
                            FilterChip(
                                selected = readerSettings.toolbarPosition == pos,
                                onClick = {
                                    repository.updateReaderSettings(readerSettings.copy(toolbarPosition = pos))
                                },
                                label = { Text(pos.displayName) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Text("Active Actions", style = MaterialTheme.typography.labelMedium, color = readerColors.text)
                    Spacer(modifier = Modifier.height(6.dp))

                    ALL_TOOLBAR_ACTIONS.forEach { action ->
                        val isEnabled = readerSettings.enabledToolbarActions.contains(action.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(action.icon, contentDescription = null, tint = readerColors.accent, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(action.title, style = MaterialTheme.typography.bodyMedium, color = readerColors.onSurface)
                            }

                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { checked ->
                                    val currentList = readerSettings.enabledToolbarActions.toMutableList()
                                    if (checked) {
                                        if (!currentList.contains(action.id)) currentList.add(action.id)
                                    } else {
                                        currentList.remove(action.id)
                                    }
                                    repository.updateReaderSettings(readerSettings.copy(enabledToolbarActions = currentList))
                                }
                            )
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // TERM REPLACEMENT RULES MODAL BOTTOM SHEET
        // -------------------------------------------------------------
        if (showTermReplacementSheet) {
            var newOriginal by remember { mutableStateOf("") }
            var newReplacement by remember { mutableStateOf("") }

            ModalBottomSheet(
                onDismissRequest = { showTermReplacementSheet = false },
                containerColor = readerColors.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Term Replacement Rules",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = readerColors.onSurface
                    )
                    Text(
                        text = "Define words or phrases to replace in reader text (does not alter stored novel files)",
                        style = MaterialTheme.typography.bodySmall,
                        color = readerColors.text.copy(alpha = 0.7f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Add new rule form
                    OutlinedTextField(
                        value = newOriginal,
                        onValueChange = { newOriginal = it },
                        label = { Text("Original Term (e.g. colour)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newReplacement,
                        onValueChange = { newReplacement = it },
                        label = { Text("Replacement (leave blank to remove)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (newOriginal.isNotBlank()) {
                                repository.addTermReplacementRule(
                                    TermReplacementRule(
                                        original = newOriginal.trim(),
                                        replacement = newReplacement.trim(),
                                        isEnabled = true
                                    )
                                )
                                newOriginal = ""
                                newReplacement = ""
                            }
                        },
                        enabled = newOriginal.isNotBlank(),
                        modifier = Modifier.align(Alignment.End),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Rule")
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Text("Defined Rules (${termRules.size})", style = MaterialTheme.typography.labelMedium, color = readerColors.text)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (termRules.isEmpty()) {
                        Text(
                            text = "No custom replacement rules defined yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = readerColors.text.copy(alpha = 0.6f),
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        termRules.forEach { rule ->
                            Card(
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = readerColors.background),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "\"${rule.original}\"  ➔  ${if (rule.replacement.isEmpty()) "[Remove]" else "\"${rule.replacement}\""}",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            color = readerColors.onSurface
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Switch(
                                            checked = rule.isEnabled,
                                            onCheckedChange = { repository.toggleTermReplacementRule(rule.id, it) }
                                        )
                                        IconButton(onClick = { repository.deleteTermReplacementRule(rule.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // MORE OPTIONS OVERFLOW BOTTOM SHEET
        // -------------------------------------------------------------
        if (showMoreMenuSheet) {
            ModalBottomSheet(
                onDismissRequest = { showMoreMenuSheet = false },
                containerColor = readerColors.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        text = "Reader Actions",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = readerColors.onSurface
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    ALL_TOOLBAR_ACTIONS.forEach { action ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Transparent,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showMoreMenuSheet = false
                                    handleToolbarAction(
                                        actionId = action.id,
                                        currentChapterIndex = currentChapterIndex,
                                        chapterListSize = chapterList.size,
                                        chapterContent = chapterContent,
                                        isTtsPlaying = isTtsPlaying,
                                        onNavigate = { navigateToChapter(it) },
                                        onOpenToc = { showTocSheet = true },
                                        onOpenDisplay = { showSettingsSheet = true },
                                        onToggleTts = {
                                            isTtsSheetVisible = true
                                            if (chapterContent != null) {
                                                if (isTtsPlaying) ttsManager.stop()
                                                else ttsManager.startReading(chapterContent!!.paragraphs, 0)
                                            }
                                        },
                                        onAddBookmark = {
                                            if (chapterContent != null) {
                                                val excerpt = displayParagraphs.firstOrNull()?.take(100) ?: "Chapter bookmark"
                                                repository.addBookmark(
                                                    Bookmark(
                                                        id = "bm_${System.currentTimeMillis()}",
                                                        novelId = novel.id,
                                                        chapterId = currentChapterId,
                                                        chapterTitle = displayTitle,
                                                        paragraphIndex = 0,
                                                        excerpt = excerpt,
                                                        note = "Bookmark spot"
                                                    )
                                                )
                                                showBookmarksSheet = true
                                            }
                                        },
                                        onDownload = {
                                            val currentCh = chapterList.firstOrNull { it.id == currentChapterId }
                                            if (currentCh != null) {
                                                scope.launch {
                                                    val ok = repository.downloadChapter(novel, currentCh)
                                                    Toast.makeText(
                                                        context,
                                                        if (ok) "Chapter downloaded for offline reading" else "Download error",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        },
                                        onToggleMode = {
                                            val nextMode = if (readerSettings.readingMode == ReadingMode.CONTINUOUS) ReadingMode.PAGINATED else ReadingMode.CONTINUOUS
                                            repository.updateReaderSettings(readerSettings.copy(readingMode = nextMode))
                                        },
                                        onToggleProgress = {
                                            showJumpSlider = !showJumpSlider
                                        }
                                    )
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(action.icon, contentDescription = null, tint = readerColors.accent)
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(action.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = readerColors.onSurface)
                                    Text(action.description, style = MaterialTheme.typography.labelSmall, color = readerColors.text.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            showMoreMenuSheet = false
                            showToolbarCustomizer = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Customize Toolbar Actions")
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // TABLE OF CONTENTS MODAL SHEET
        // -------------------------------------------------------------
        if (showTocSheet) {
            ModalBottomSheet(
                onDismissRequest = { showTocSheet = false },
                containerColor = readerColors.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp)
                ) {
                    Text(
                        text = "Table of Contents",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = readerColors.onSurface,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(chapterList) { _, ch ->
                            val isCurrent = ch.id == currentChapterId
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isCurrent) readerColors.accent.copy(alpha = 0.15f) else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        currentChapterId = ch.id
                                        currentChapterIndex = ch.chapterIndex
                                        showTocSheet = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = ch.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isCurrent) readerColors.accent else readerColors.onSurface
                                        ),
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (ch.isDownloaded) {
                                        DownloadedBadge(
                                            accentColor = readerColors.accent,
                                            size = 15.dp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // BOOKMARKS MODAL SHEET
        // -------------------------------------------------------------
        if (showBookmarksSheet) {
            val bookmarksList by repository.bookmarks.collectAsState()
            val novelBookmarks = remember(bookmarksList, novel.id) {
                repository.getBookmarksForNovel(novel.id)
            }

            ModalBottomSheet(
                onDismissRequest = { showBookmarksSheet = false },
                containerColor = readerColors.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp)
                ) {
                    Text(
                        text = "Bookmarks & Highlights",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = readerColors.onSurface,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    if (novelBookmarks.isEmpty()) {
                        Text(
                            text = "No bookmarks added yet. Tap the bookmark icon in the toolbar while reading to save your spot.",
                            style = MaterialTheme.typography.bodySmall,
                            color = readerColors.text.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(novelBookmarks) { _, bm ->
                                Card(
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(containerColor = readerColors.background),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = bm.chapterTitle,
                                                style = MaterialTheme.typography.labelMedium.copy(color = readerColors.accent, fontWeight = FontWeight.Bold)
                                            )
                                            Text(
                                                text = "\"${bm.excerpt}\"",
                                                style = MaterialTheme.typography.bodySmall.copy(color = readerColors.text),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(onClick = { repository.removeBookmark(bm.id) }) {
                                            Icon(Icons.Default.Close, contentDescription = "Delete", tint = readerColors.text.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // QUICK DICTIONARY LOOKUP DIALOG
        // -------------------------------------------------------------
        if (showDictionaryDialog) {
            ModalBottomSheet(
                onDismissRequest = { showDictionaryDialog = false },
                containerColor = readerColors.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lookupWord,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = readerColors.onSurface
                        )
                        Surface(shape = RoundedCornerShape(4.dp), color = readerColors.accent.copy(alpha = 0.2f)) {
                            Text(
                                text = "Dictionary & Lore",
                                fontSize = 11.sp,
                                color = readerColors.accent,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = lookupDefinition,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = readerColors.text
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    ElevatedButton(
                        onClick = { showDictionaryDialog = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarActionButton(
    action: ToolbarActionDef,
    readerColors: ReaderColors,
    isTtsPlaying: Boolean,
    readingMode: ReadingMode,
    onAction: () -> Unit
) {
    IconButton(onClick = onAction) {
        val tint = when (action.id) {
            "tts" -> if (isTtsPlaying) readerColors.accent else readerColors.onSurface
            "reading_mode" -> if (readingMode == ReadingMode.PAGINATED) readerColors.accent else readerColors.onSurface
            else -> readerColors.onSurface
        }
        val icon = when (action.id) {
            "reading_mode" -> if (readingMode == ReadingMode.PAGINATED) Icons.Default.AutoStories else Icons.Default.SwapVert
            else -> action.icon
        }
        val description = when (action.id) {
            "reading_mode" -> if (readingMode == ReadingMode.PAGINATED) "Reading Mode: Paginated" else "Reading Mode: Continuous"
            else -> action.description
        }
        Icon(icon, contentDescription = description, tint = tint)
    }
}

private fun handleToolbarAction(
    actionId: String,
    currentChapterIndex: Int,
    chapterListSize: Int,
    chapterContent: ChapterContent?,
    isTtsPlaying: Boolean,
    onNavigate: (Int) -> Unit,
    onOpenToc: () -> Unit,
    onOpenDisplay: () -> Unit,
    onToggleTts: () -> Unit,
    onAddBookmark: () -> Unit,
    onDownload: () -> Unit,
    onToggleMode: () -> Unit,
    onToggleProgress: () -> Unit
) {
    when (actionId) {
        "prev_chapter" -> if (currentChapterIndex > 1) onNavigate(currentChapterIndex - 1)
        "next_chapter" -> if (currentChapterIndex < chapterListSize) onNavigate(currentChapterIndex + 1)
        "toc" -> onOpenToc()
        "tts" -> onToggleTts()
        "bookmark" -> onAddBookmark()
        "download" -> onDownload()
        "display" -> onOpenDisplay()
        "reading_mode" -> onToggleMode()
        "progress" -> onToggleProgress()
    }
}

// Built-in lexical and fantasy/web novel term dictionary
private fun getMockDefinition(word: String): String {
    val clean = word.lowercase()
    return when {
        clean.contains("shadow") -> "noun: A dark area or shape produced by a body coming between rays of light and a surface.\n\n[Web Novel Lore]: Often refers to the elusive Divine Aspect or soul essence connected to the underworld or stealth arts."
        clean.contains("mana") -> "noun (Fantasy): The pervasive spiritual energy circulating through living matter and reality, harnessed by mages and spellcasters to construct magical formulas."
        clean.contains("spell") -> "noun: A spoken word or form of words held to have magic power.\n\n[Nightmare Lore]: In progression fantasy, an omnipresent universal system that tests aspirants through ancient dream trials."
        clean.contains("crimson") -> "adjective: Of a rich deep red color inclining to purple.\n\n[Mystery Lore]: Associated with the mysterious blood moon and occult rituals governing the Spirit World."
        clean.contains("loop") -> "noun: A sequence of recurring events.\n\n[Time Loop Lore]: A temporal anomaly where consciousness resets to an anchor date, allowing infinite iterations of study and discovery."
        clean.contains("dimension") -> "noun: An aspect or feature of a situation, or a measurable extent of a particular kind (such as length, breadth, depth, or time)."
        else -> "Definition: \"$word\"\n\nA recognized literary term used across web novels, fantasy fiction, and classic literature to convey atmospheric resonance, character resolve, and dramatic setting."
    }
}
