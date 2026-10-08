package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.model.*
import com.example.data.source.SourceManager
import com.example.data.source.webscrape.ScrapedNovelData
import com.example.data.source.webscrape.WebScrapeSource
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Composite Facade for Novel operations, coordinating between focused manager classes:
 * - [LibraryManager]: library novels, reading progress, categorization, bookmarks
 * - [StorageManager]: internal & SAF storage, chapter files, EPUB parsing, cache
 * - [DownloadManager]: chapter download queue, batch progress, throttling
 * - [PreferencesManager]: reader/theme/download/notification settings, stats, custom sources
 * - [BackupManager]: backup creation and restoration orchestration
 */
class NovelRepository(private val context: Context) {

    internal val storageManager = StorageManager(context)
    internal val preferencesManager = PreferencesManager(context)
    internal val libraryManager = LibraryManager(preferencesManager)
    internal val downloadManager = DownloadManager(storageManager, preferencesManager, libraryManager)
    internal val backupManager = BackupManager(libraryManager, preferencesManager)

    // Public StateFlows matching exact original signatures
    val libraryNovels: StateFlow<List<Novel>> = libraryManager.libraryNovels
    val downloadedChapters: StateFlow<Map<String, Set<String>>> = downloadManager.downloadedChapters
    val readChapters: StateFlow<Map<String, Set<String>>> = libraryManager.readChapters
    val bookmarks: StateFlow<List<Bookmark>> = libraryManager.bookmarks
    val readerSettings: StateFlow<ReaderSettings> = preferencesManager.readerSettings
    val termReplacements: StateFlow<List<TermReplacementRule>> = preferencesManager.termReplacements
    val themeSettings: StateFlow<ThemeSettings> = preferencesManager.themeSettings
    val downloadSettings: StateFlow<DownloadSettings> = preferencesManager.downloadSettings
    val notificationSettings: StateFlow<NotificationSettings> = preferencesManager.notificationSettings
    val notifications: StateFlow<List<AppNotificationItem>> = preferencesManager.notifications
    val readingStats: StateFlow<ReadingStats> = preferencesManager.readingStats
    val customSources: StateFlow<List<CustomNovelSource>> = preferencesManager.customSources
    val requestedSources: StateFlow<List<String>> = preferencesManager.requestedSources

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        SourceManager.registerSource(WebScrapeSource(context))
        repoScope.launch {
            migrateEpubCoversIfNeeded()
        }
    }

    private fun migrateEpubCoversIfNeeded() {
        if (preferencesManager.isEpubCoversMigrated()) return
        try {
            val cacheDirPath = context.cacheDir.absolutePath
            val coversDir = File(context.filesDir, "covers")
            if (!coversDir.exists()) coversDir.mkdirs()

            val novels = libraryManager.libraryNovels.value
            for (novel in novels) {
                val coverUrl = novel.coverUrl
                if (coverUrl != null && (coverUrl.startsWith(cacheDirPath) || coverUrl.contains("/cache/"))) {
                    val oldFile = File(coverUrl)
                    if (oldFile.exists() && oldFile.isFile) {
                        val newFile = File(coversDir, oldFile.name)
                        oldFile.copyTo(newFile, overwrite = true)
                        libraryManager.updateNovelCoverUrl(novel.id, newFile.absolutePath)
                    }
                }
            }
            preferencesManager.setEpubCoversMigrated(true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- LIBRARY METHODS ---

    fun isLibraryGridView(): Boolean = libraryManager.isLibraryGridView()

    fun setLibraryGridView(isGrid: Boolean) = libraryManager.setLibraryGridView(isGrid)

    fun addToLibrary(novel: Novel) = libraryManager.addToLibrary(novel)

    fun removeFromLibrary(novelId: String) = libraryManager.removeFromLibrary(novelId)

    fun updateNovelDetails(
        novelId: String,
        newTitle: String,
        newAuthor: String,
        newDescription: String,
        newCoverUrl: String?
    ) = libraryManager.updateNovelDetails(novelId, newTitle, newAuthor, newDescription, newCoverUrl)

    fun clearNovelReadingHistory(novelId: String) = libraryManager.clearNovelReadingHistory(novelId)

    fun isNovelInLibrary(novelId: String): Boolean = libraryManager.isNovelInLibrary(novelId)

    fun updateReadingProgress(
        novelId: String,
        chapterId: String,
        chapterTitle: String,
        chapterIndex: Int,
        scrollPosition: Int,
        wordsReadDelta: Int = 0
    ) = libraryManager.updateReadingProgress(novelId, chapterId, chapterTitle, chapterIndex, scrollPosition, wordsReadDelta)

    fun persistReadingProgressAsync(
        novelId: String,
        chapterId: String,
        chapterTitle: String,
        chapterIndex: Int,
        scrollPosition: Int,
        wordsReadDelta: Int = 0
    ) = libraryManager.persistReadingProgressAsync(novelId, chapterId, chapterTitle, chapterIndex, scrollPosition, wordsReadDelta)

    fun saveChapterReadingPositionAsync(
        novelId: String,
        chapterId: String,
        position: Int
    ) = libraryManager.saveChapterReadingPositionAsync(novelId, chapterId, position)

    fun getReadingHistory(): List<Novel> = libraryManager.getReadingHistory()

    // --- CHAPTERS & OFFLINE STORAGE ---

    suspend fun getChapterList(novel: Novel, forceRefresh: Boolean = false): List<Chapter> = withContext(Dispatchers.IO) {
        val downloadedSet = downloadManager.downloadedChapters.value[novel.id] ?: emptySet()
        val readSet = libraryManager.readChapters.value[novel.id] ?: emptySet()
        val customUriStr = preferencesManager.downloadSettings.value.customDownloadLocationUri

        if (!forceRefresh) {
            // 1. Check in-memory cache
            val cachedChapters = storageManager.getCachedChapters(novel.id)
            if (!cachedChapters.isNullOrEmpty()) {
                return@withContext cachedChapters.map { ch ->
                    ch.copy(
                        isDownloaded = downloadedSet.contains(ch.id) || storageManager.isChapterFilePresent(novel.id, ch.id, customUriStr),
                        isRead = readSet.contains(ch.id)
                    )
                }
            }

            // 2. Check local disk storage (internal filesDir or custom SAF)
            val diskChapters = storageManager.loadNovelChaptersFromDisk(novel.id, customUriStr)
            if (diskChapters.isNotEmpty()) {
                storageManager.putCachedChapters(novel.id, diskChapters)
                return@withContext diskChapters.map { ch ->
                    ch.copy(
                        isDownloaded = downloadedSet.contains(ch.id) || storageManager.isChapterFilePresent(novel.id, ch.id, customUriStr),
                        isRead = readSet.contains(ch.id)
                    )
                }
            }
        }

        // 3. Fallback: Fetch from Source (only when no local data exists, or user explicitly requested refresh)
        // For local EPUB or Web scraped novels, chapters are local - do not fall back to online dummy sources!
        if (novel.sourceId == WebScrapeSource.SOURCE_ID || novel.isEpub) {
            val diskChapters = storageManager.loadNovelChaptersFromDisk(novel.id, customUriStr)
            if (diskChapters.isNotEmpty()) {
                storageManager.putCachedChapters(novel.id, diskChapters)
                return@withContext diskChapters.map { ch ->
                    ch.copy(
                        isDownloaded = downloadedSet.contains(ch.id) || storageManager.isChapterFilePresent(novel.id, ch.id, customUriStr),
                        isRead = readSet.contains(ch.id)
                    )
                }
            }
            return@withContext emptyList()
        }

        val source = SourceManager.getSourceById(novel.sourceId) ?: SourceManager.getActiveSource()
        val rawChapters = try {
            source.getChapterList(novel.id)
        } catch (e: Exception) {
            emptyList()
        }

        if (rawChapters.isNotEmpty()) {
            storageManager.putCachedChapters(novel.id, rawChapters)
            storageManager.saveNovelChaptersToDisk(novel.id, rawChapters, customUriStr)
        }

        rawChapters.map { ch ->
            ch.copy(
                isDownloaded = downloadedSet.contains(ch.id) || storageManager.isChapterFilePresent(novel.id, ch.id, customUriStr),
                isRead = readSet.contains(ch.id)
            )
        }
    }

    fun getCachedChapters(novelId: String): List<Chapter>? = storageManager.getCachedChapters(novelId)

    fun loadChaptersFromDiskFast(novelId: String): List<Chapter> {
        val customUriStr = preferencesManager.downloadSettings.value.customDownloadLocationUri
        val list = storageManager.loadNovelChaptersFromDisk(novelId, customUriStr)
        if (list.isNotEmpty()) {
            storageManager.putCachedChapters(novelId, list)
        }
        return list
    }

    suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent = withContext(Dispatchers.IO) {
        val customUriStr = preferencesManager.downloadSettings.value.customDownloadLocationUri

        // 1. Check local cache (memory)
        storageManager.getCachedChapterContent(chapterId)?.let { return@withContext it.copy(isDownloaded = true) }

        // 2. Check local disk file
        val offlineContent = storageManager.readChapterFromDisk(novelId, chapterId, customUriStr)
        if (offlineContent != null) {
            storageManager.putCachedChapterContent(chapterId, offlineContent)
            return@withContext offlineContent.copy(isDownloaded = true)
        }

        // 3. Fetch from Source
        val novel = libraryManager.libraryNovels.value.firstOrNull { it.id == novelId }
        val source = if (novel != null) {
            SourceManager.getSourceById(novel.sourceId) ?: SourceManager.getActiveSource()
        } else {
            SourceManager.getActiveSource()
        }

        val content = source.getChapterContent(novelId, chapterId)
        // Automatically save fetched chapter to disk for instant offline access next time
        storageManager.saveChapterToDisk(content, customUriStr)
        storageManager.putCachedChapterContent(chapterId, content)
        downloadManager.markChapterDownloaded(novelId, chapterId)
        content
    }

    suspend fun downloadChapter(novel: Novel, chapter: Chapter): Boolean {
        return downloadManager.downloadChapter(novel, chapter) {
            getChapterContent(novel.id, chapter.id)
        }
    }

    suspend fun downloadChaptersBatch(
        novel: Novel,
        chaptersToDownload: List<Chapter>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) {
        downloadManager.downloadChaptersBatch(novel, chaptersToDownload, onProgress) { ch ->
            getChapterContent(novel.id, ch.id)
        }
    }

    suspend fun deleteDownloadedNovelChapters(novelId: String) = downloadManager.deleteDownloadedNovelChapters(novelId)

    // --- READ CHAPTERS TRACKING ---

    fun markChapterAsRead(novelId: String, chapterId: String) = libraryManager.markChapterAsRead(novelId, chapterId)

    // --- EPUB IMPORT ---

    suspend fun importEpub(uri: Uri, fileName: String): Novel = withContext(Dispatchers.IO) {
        val customUriStr = preferencesManager.downloadSettings.value.customDownloadLocationUri
        val parsed = storageManager.parseEpub(uri, fileName)

        // Store chapters in memory and save to disk
        storageManager.putCachedChapters(parsed.novel.id, parsed.chapters)
        storageManager.saveNovelChaptersToDisk(parsed.novel.id, parsed.chapters, customUriStr)

        for ((chId, content) in parsed.chapterContents) {
            storageManager.putCachedChapterContent(chId, content)
            storageManager.saveChapterToDisk(content, customUriStr)
        }

        // Add novel to library
        libraryManager.addToLibrary(parsed.novel)

        // Mark all chapters downloaded
        val chapterIds = parsed.chapters.map { it.id }.toSet()
        downloadManager.markChaptersDownloaded(parsed.novel.id, chapterIds)

        parsed.novel
    }

    // --- WEB SCRAPED NOVELS ---

    fun saveWebScrapedNovel(
        scrapedData: ScrapedNovelData,
        customTitle: String,
        customAuthor: String,
        customSynopsis: String,
        customTags: List<String>,
        customCoverUrl: String?
    ): Novel {
        val novelId = "web_" + java.util.UUID.randomUUID().toString().take(8)
        val host = try {
            java.net.URI(scrapedData.sourceUrl).host?.removePrefix("www.") ?: "Web"
        } catch (e: Exception) {
            "Web"
        }

        val cleanTitle = com.example.data.source.webscrape.WtrLabExtractor.cleanWtrLabTokens(customTitle.ifBlank { "Untitled Web Novel" })
        val cleanAuthor = com.example.data.source.webscrape.WtrLabExtractor.cleanWtrLabTokens(customAuthor.ifBlank { "Unknown Author" })
        val cleanSynopsis = com.example.data.source.webscrape.WtrLabExtractor.cleanWtrLabTokens(customSynopsis.ifBlank { "Added from $host" })

        val chapters = scrapedData.chapters.mapIndexed { index, ch ->
            Chapter(
                id = ch.url,
                novelId = novelId,
                title = com.example.data.source.webscrape.WtrLabExtractor.cleanWtrLabTokens(ch.title),
                chapterIndex = index + 1,
                releaseDate = "Web",
                isDownloaded = false,
                isRead = false,
                wordCount = 0
            )
        }

        val customUriStr = preferencesManager.downloadSettings.value.customDownloadLocationUri
        storageManager.putCachedChapters(novelId, chapters)
        storageManager.saveNovelChaptersToDisk(novelId, chapters, customUriStr)

        val novel = Novel(
            id = novelId,
            title = cleanTitle,
            author = cleanAuthor,
            coverUrl = customCoverUrl?.takeIf { it.isNotBlank() },
            description = cleanSynopsis,
            genres = customTags.ifEmpty { listOf("Web Novel") },
            status = "Web",
            sourceId = WebScrapeSource.SOURCE_ID,
            sourceName = "Web ($host)",
            inLibrary = true,
            totalChapters = chapters.size,
            downloadedChaptersCount = 0,
            rating = scrapedData.rating?.takeIf { it > 0f } ?: 4.8f,
            lastReadTimestamp = System.currentTimeMillis(),
            rawId = scrapedData.rawId,
            locale = scrapedData.locale,
            sourceUrl = scrapedData.sourceUrl
        )

        libraryManager.addToLibrary(novel)
        return novel
    }

    // --- BOOKMARKS ---

    fun addBookmark(bookmark: Bookmark) = libraryManager.addBookmark(bookmark)

    fun removeBookmark(bookmarkId: String) = libraryManager.removeBookmark(bookmarkId)

    fun getBookmarksForNovel(novelId: String): List<Bookmark> = libraryManager.getBookmarksForNovel(novelId)

    // --- READER SETTINGS ---

    fun updateReaderSettings(settings: ReaderSettings) = preferencesManager.updateReaderSettings(settings)

    // --- CHAPTER READING POSITION PERSISTENCE ---

    fun getChapterReadingPosition(novelId: String, chapterId: String): Int =
        preferencesManager.getChapterReadingPosition(novelId, chapterId)

    fun saveChapterReadingPosition(novelId: String, chapterId: String, position: Int) =
        preferencesManager.saveChapterReadingPosition(novelId, chapterId, position)

    // --- TERM REPLACEMENT RULES ---

    fun addTermReplacementRule(rule: TermReplacementRule) = preferencesManager.addTermReplacementRule(rule)

    fun updateTermReplacementRule(rule: TermReplacementRule) = preferencesManager.updateTermReplacementRule(rule)

    fun deleteTermReplacementRule(id: String) = preferencesManager.deleteTermReplacementRule(id)

    fun toggleTermReplacementRule(id: String, isEnabled: Boolean) = preferencesManager.toggleTermReplacementRule(id, isEnabled)

    // --- APP THEME & ACCENT SETTINGS ---

    fun updateThemeSettings(settings: ThemeSettings) = preferencesManager.updateThemeSettings(settings)

    // --- STATS ---

    fun recordReadingSession(minutesDelta: Int, wordsDelta: Int) = preferencesManager.recordReadingSession(minutesDelta, wordsDelta)

    // --- DOWNLOAD SETTINGS ---

    fun updateDownloadSettings(settings: DownloadSettings) = preferencesManager.updateDownloadSettings(settings)

    // --- NOTIFICATIONS ---

    fun updateNotificationSettings(settings: NotificationSettings) = preferencesManager.updateNotificationSettings(settings)

    fun addNotification(title: String, message: String, type: String = "info") = preferencesManager.addNotification(title, message, type)

    fun clearNotifications() = preferencesManager.clearNotifications()

    // --- STORAGE & CACHE MANAGEMENT ---

    fun getStorageUsageBytes(): Long = storageManager.getStorageUsageBytes()

    fun getCacheSizeBytes(): Long = storageManager.getCacheSizeBytes()

    fun clearCache(): Long = storageManager.clearCache()

    suspend fun deleteAllDownloadedContent(): Int = downloadManager.deleteAllDownloadedContent()

    suspend fun deleteNovelDownloads(novelId: String) = downloadManager.deleteNovelDownloads(novelId)

    fun resetAppPreferences() = preferencesManager.resetAppPreferences()

    // --- BACKUP & RESTORE ---

    suspend fun createBackupJson(categories: BackupCategories): String = backupManager.createBackupJson(categories)

    suspend fun restoreBackupFromJson(jsonString: String): Boolean = backupManager.restoreBackupFromJson(jsonString)

    fun saveBackupToCustomStorage(jsonString: String, fileName: String): String? {
        val customUriStr = preferencesManager.downloadSettings.value.customDownloadLocationUri
        return storageManager.saveBackupToCustomStorage(jsonString, fileName, customUriStr)
    }

    // --- CHANGELOG / WHAT'S NEW ---

    fun getLastSeenChangelogVersion(): Int = preferencesManager.getLastSeenChangelogVersion()

    fun setLastSeenChangelogVersion(versionCode: Int) = preferencesManager.setLastSeenChangelogVersion(versionCode)

    // --- CUSTOM SOURCES ---

    fun addCustomSource(name: String, url: String, language: String = "English", description: String = "Custom web source") =
        preferencesManager.addCustomSource(name, url, language, description)

    fun removeCustomSource(id: String) = preferencesManager.removeCustomSource(id)

    // --- REQUESTED SOURCES ---

    fun requestSource(siteName: String, url: String) = preferencesManager.requestSource(siteName, url)
}
