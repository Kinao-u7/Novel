package com.example.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class AppThemeMode(val displayName: String) {
    SYSTEM("System Default"),
    LIGHT("Light"),
    DARK("Dark"),
    AMOLED("AMOLED Pure Black"),
    SEPIA("Warm Sepia")
}

data class AccentPreset(
    val name: String,
    val hex: String
)

val PRESET_ACCENT_COLORS = listOf(
    AccentPreset("Warm Gold", "#DEB887"),
    AccentPreset("Sandstone", "#E5B876"),
    AccentPreset("Amber Gold", "#F59E0B"),
    AccentPreset("Indigo", "#6366F1"),
    AccentPreset("Ocean Blue", "#2563EB"),
    AccentPreset("Emerald Teal", "#0D9488"),
    AccentPreset("Jade Green", "#10B981"),
    AccentPreset("Sunset Orange", "#EA580C"),
    AccentPreset("Ruby Red", "#E11D48"),
    AccentPreset("Rose Pink", "#EC4899"),
    AccentPreset("Royal Purple", "#8B5CF6"),
    AccentPreset("Deep Cyan", "#0284C7"),
    AccentPreset("Slate", "#64748B")
)

@Serializable
data class ThemeSettings(
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val accentColorHex: String = "#DEB887",
    val useDynamicColor: Boolean = false
)

@Serializable
enum class ReaderTheme(val displayName: String) {
    LIGHT("Light"),
    WARM("Warm Paper"),
    SEPIA("Sepia"),
    DARK("Dark"),
    AMOLED("AMOLED")
}

@Serializable
enum class ReaderFontWeight(val displayName: String, val weight: Int) {
    NORMAL("Normal", 400),
    MEDIUM("Medium", 500),
    BOLD("Bold", 700)
}

@Serializable
enum class ReaderTextAlign(val displayName: String) {
    LEFT("Left"),
    JUSTIFIED("Justified"),
    RIGHT("Right")
}

@Serializable
enum class ReadingMode(val displayName: String) {
    CONTINUOUS("Scroll"),
    PAGINATED("Paginated")
}

@Serializable
enum class FontType(val displayName: String, val fontFamilyName: String) {
    SANS("Sans-Serif", "sans-serif"),
    SERIF("Bookish Serif", "serif"),
    MONOSPACE("Monospace", "monospace"),
    ROUNDED("Rounded", "sans-serif-rounded")
}

@Serializable
enum class MarginType(val displayName: String, val horizontalPaddingDp: Int) {
    COMPACT("Compact", 12),
    NORMAL("Normal", 20),
    WIDE("Wide", 32)
}

@Serializable
enum class ToolbarPosition(val displayName: String) {
    TOP("Top"),
    BOTTOM("Bottom")
}

data class TermReplacementRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val original: String,
    val replacement: String,
    val isEnabled: Boolean = true,
    val sortOrder: Int = 0
)

@Serializable
data class ReaderSettings(
    val theme: ReaderTheme = ReaderTheme.SEPIA,
    val fontSizeSp: Int = 18,
    val fontWeight: ReaderFontWeight = ReaderFontWeight.NORMAL,
    val lineSpacingMultiplier: Float = 1.6f,
    val paragraphSpacingDp: Int = 12,
    val letterSpacingSp: Float = 0f,
    val textAlign: ReaderTextAlign = ReaderTextAlign.JUSTIFIED,
    val horizontalMarginDp: Int = 20,
    val verticalMarginDp: Int = 20,
    val contentWidthPercent: Int = 100,
    val paragraphIndentation: Boolean = false,
    val fontType: FontType = FontType.SERIF,
    val readingMode: ReadingMode = ReadingMode.CONTINUOUS,
    val autoScrollSpeed: Int = 0, // 0 = off, 1-5 = speed
    val keepScreenOn: Boolean = true,
    val toolbarPosition: ToolbarPosition = ToolbarPosition.BOTTOM,
    val showStatusBar: Boolean = true,
    val enabledToolbarActions: List<String> = listOf("prev_chapter", "next_chapter", "toc", "tts", "bookmark", "display"),
    val globalTermReplacementsEnabled: Boolean = true,
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsVoice: String? = null,
    val saveReadingPosition: Boolean = true,
    val volumeKeysScroll: Boolean = false
) {
    val isJustified: Boolean
        get() = textAlign == ReaderTextAlign.JUSTIFIED

    val marginType: MarginType
        get() = when {
            horizontalMarginDp <= 14 -> MarginType.COMPACT
            horizontalMarginDp >= 28 -> MarginType.WIDE
            else -> MarginType.NORMAL
        }

    fun copy(
        theme: ReaderTheme = this.theme,
        fontSizeSp: Int = this.fontSizeSp,
        fontWeight: ReaderFontWeight = this.fontWeight,
        lineSpacingMultiplier: Float = this.lineSpacingMultiplier,
        paragraphSpacingDp: Int = this.paragraphSpacingDp,
        letterSpacingSp: Float = this.letterSpacingSp,
        textAlign: ReaderTextAlign = this.textAlign,
        horizontalMarginDp: Int = this.horizontalMarginDp,
        verticalMarginDp: Int = this.verticalMarginDp,
        contentWidthPercent: Int = this.contentWidthPercent,
        paragraphIndentation: Boolean = this.paragraphIndentation,
        fontType: FontType = this.fontType,
        readingMode: ReadingMode = this.readingMode,
        autoScrollSpeed: Int = this.autoScrollSpeed,
        keepScreenOn: Boolean = this.keepScreenOn,
        toolbarPosition: ToolbarPosition = this.toolbarPosition,
        showStatusBar: Boolean = this.showStatusBar,
        enabledToolbarActions: List<String> = this.enabledToolbarActions,
        globalTermReplacementsEnabled: Boolean = this.globalTermReplacementsEnabled,
        ttsSpeechRate: Float = this.ttsSpeechRate,
        ttsPitch: Float = this.ttsPitch,
        ttsVoice: String? = this.ttsVoice,
        saveReadingPosition: Boolean = this.saveReadingPosition,
        volumeKeysScroll: Boolean = this.volumeKeysScroll,
        isJustified: Boolean? = null,
        marginType: MarginType? = null
    ): ReaderSettings {
        val resolvedTextAlign = if (isJustified != null) {
            if (isJustified) ReaderTextAlign.JUSTIFIED else ReaderTextAlign.LEFT
        } else {
            textAlign
        }
        val resolvedMarginDp = if (marginType != null) {
            marginType.horizontalPaddingDp
        } else {
            horizontalMarginDp
        }
        return ReaderSettings(
            theme = theme,
            fontSizeSp = fontSizeSp,
            fontWeight = fontWeight,
            lineSpacingMultiplier = lineSpacingMultiplier,
            paragraphSpacingDp = paragraphSpacingDp,
            letterSpacingSp = letterSpacingSp,
            textAlign = resolvedTextAlign,
            horizontalMarginDp = resolvedMarginDp,
            verticalMarginDp = verticalMarginDp,
            contentWidthPercent = contentWidthPercent,
            paragraphIndentation = paragraphIndentation,
            fontType = fontType,
            readingMode = readingMode,
            autoScrollSpeed = autoScrollSpeed,
            keepScreenOn = keepScreenOn,
            toolbarPosition = toolbarPosition,
            showStatusBar = showStatusBar,
            enabledToolbarActions = enabledToolbarActions,
            globalTermReplacementsEnabled = globalTermReplacementsEnabled,
            ttsSpeechRate = ttsSpeechRate,
            ttsPitch = ttsPitch,
            ttsVoice = ttsVoice,
            saveReadingPosition = saveReadingPosition,
            volumeKeysScroll = volumeKeysScroll
        )
    }
}

@Serializable
data class DownloadSettings(
    val wifiOnly: Boolean = true,
    val allowMobileData: Boolean = false,
    val simultaneousDownloads: Int = 3,
    val autoDownloadAhead: Int = 3,
    // Custom Download Location (SAF tree uri)
    val customDownloadLocationUri: String? = null,
    val customDownloadLocationDisplayName: String? = null,
    // Download Request Throttling
    val throttlingEnabled: Boolean = true,
    val throttleBatchSize: Int = 5, // requests before delay
    val throttleDelaySeconds: Float = 2.0f, // configurable delay duration
    val throttleRandomJitter: Boolean = true, // randomized jitter to delay timing
    // LRU Cache Size Cap (MB)
    val cacheSizeCapMb: Int = 250,
    val deleteAfterReading: Boolean = false
)

@Serializable
enum class DefaultShelf(val displayName: String) {
    ALL("All"),
    READING("Reading"),
    SAVED("Saved")
}

@Serializable
enum class LibraryViewMode(val displayName: String) {
    GRID("Grid"),
    LIST("List")
}
typealias ViewMode = LibraryViewMode

@Serializable
enum class LibrarySort(val displayName: String) {
    RECENTLY_READ("Recently Read"),
    TITLE("Title"),
    AUTHOR("Author"),
    TOTAL_CHAPTERS("Total Chapters"),
    RATING("Rating")
}
typealias LibrarySortOrder = LibrarySort

@Serializable
data class LibrarySettings(
    val updateOnLaunch: Boolean = true,
    val showUnreadBadges: Boolean = true,
    val defaultShelf: DefaultShelf = DefaultShelf.ALL,
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,
    val sort: LibrarySort = LibrarySort.RECENTLY_READ
)

@Serializable
enum class AutoBackup(val displayName: String) {
    OFF("Off"),
    DAILY("Daily"),
    WEEKLY("Weekly")
}

@Serializable
data class BackupSettings(
    val autoBackup: AutoBackup = AutoBackup.WEEKLY,
    val lastBackupAtMillis: Long = 0L
)

@Serializable
data class CultivationSettings(
    val qiRewards: Boolean = true,
    val breakthroughEffects: Boolean = true,
    val haptics: Boolean = true,
    val reduceMotion: Boolean = false,
    val dailyGoalMinutes: Int = 20,
    val reminderEnabled: Boolean = true,
    val reminderMinutesOfDay: Int = 1230 // 20:30 (8:30 PM)
)

@Serializable
data class ProfileSettings(
    val displayName: String = "Reader"
)

data class BackupCategories(
    val library: Boolean = true,
    val sources: Boolean = true,
    val history: Boolean = true,
    val categories: Boolean = true,
    val readStatus: Boolean = true,
    val appSettings: Boolean = true
)

@Serializable
data class NotificationSettings(
    val notifyOnDownload: Boolean = true,
    val notifyOnNovelUpdate: Boolean = true
)

@Serializable
enum class NavTab {
    LIBRARY,
    DISCOVER,
    CULTIVATE,
    DOWNLOADS,
    PROFILE
}

@Serializable
data class BottomBarSettings(
    val tabs: List<NavTab> = listOf(
        NavTab.LIBRARY,
        NavTab.DISCOVER,
        NavTab.CULTIVATE,
        NavTab.PROFILE
    )
)

@Serializable
data class AppSettings(
    val reader: ReaderSettings = ReaderSettings(),
    val theme: ThemeSettings = ThemeSettings(),
    val downloads: DownloadSettings = DownloadSettings(),
    val notifications: NotificationSettings = NotificationSettings(),
    val library: LibrarySettings = LibrarySettings(),
    val cultivation: CultivationSettings = CultivationSettings(),
    val backup: BackupSettings = BackupSettings(),
    val profile: ProfileSettings = ProfileSettings(),
    val bottomBar: BottomBarSettings = BottomBarSettings()
) {
    val isLibraryGridView: Boolean
        get() = library.viewMode == LibraryViewMode.GRID
}

data class AppNotificationItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String = NotificationTypes.INFO
)

data class Novel(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String? = null,
    val description: String,
    val genres: List<String> = emptyList(),
    val status: String = "Ongoing", // Ongoing, Completed, Local
    val sourceId: String,
    val sourceName: String,
    val inLibrary: Boolean = false,
    val lastReadChapterId: String? = null,
    val lastReadChapterTitle: String? = null,
    val lastReadChapterIndex: Int = 0,
    val lastReadScrollPosition: Int = 0, // paragraph or scroll offset
    val totalChapters: Int = 0,
    val downloadedChaptersCount: Int = 0,
    val rating: Float? = null,
    val lastReadTimestamp: Long = 0L,
    val isEpub: Boolean = false,
    val rawId: String? = null,
    val locale: String? = null,
    val sourceUrl: String? = null
)

data class Chapter(
    val id: String,
    val novelId: String,
    val title: String,
    val chapterIndex: Int,
    val releaseDate: String = "Recent",
    val isDownloaded: Boolean = false,
    val isRead: Boolean = false,
    val wordCount: Int = 0
)

data class ChapterContent(
    val chapterId: String,
    val novelId: String,
    val title: String,
    val paragraphs: List<String>,
    val isDownloaded: Boolean = false
)

data class Bookmark(
    val id: String,
    val novelId: String,
    val chapterId: String,
    val chapterTitle: String,
    val paragraphIndex: Int,
    val excerpt: String,
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

data class ReadingStats(
    val todayMinutesRead: Int = 42,
    val currentStreakDays: Int = 7,
    val totalChaptersRead: Int = 128,
    val totalWordsRead: Long = 345200L,
    val wordsPerMinute: Int = 240,
    val lastSessionTimestamp: Long = System.currentTimeMillis()
)

data class CustomNovelSource(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val language: String = "English",
    val description: String = "Custom web source"
)

sealed class CustomSourceResult {
    data class Added(val source: CustomNovelSource) : CustomSourceResult()
    data object InvalidUrl : CustomSourceResult()
    data object Duplicate : CustomSourceResult()
}

