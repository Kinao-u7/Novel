package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.local.db.NovelDatabaseHolder
import com.example.data.local.db.normalizeSourceUrl
import com.example.data.local.db.toEntity
import com.example.data.local.db.toModel
import com.example.data.model.*
import com.example.data.source.SourceManager
import com.example.data.source.webscrape.ScrapedNovelData
import com.example.data.source.webscrape.WebScrapeSource
import com.example.data.source.webscrape.WtrLabExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Modern Room-backed Repository for Novel operations.
 *
 * Adheres to Architectural Invariants:
 * - Room is the single source of truth for library, chapters, read state, positions and bookmarks.
 * - LibraryNovels derives downloaded counts and last read info in the query (no stored counters).
 * - Safe insert-ignore plus update transactions; never OnConflictStrategy.REPLACE.
 * - Reading sets CACHE tier; explicit downloads set DOWNLOADED tier.
 * - Debounced position writes (1-second debounce, flushed on chapter leave).
 */
class NovelRepository(context: Context) {

    private val appContext: Context = context.applicationContext ?: context
    private val database = NovelDatabaseHolder.getInstance(appContext)
    private val novelDao = database.novelDao()
    private val chapterDao = database.chapterDao()
    private val bookmarkDao = database.bookmarkDao()

    val settingsRepository = SettingsRepository.getInstance(appContext)
    internal val storageManager = StorageManager(appContext)
    internal val preferencesManager = PreferencesManager(appContext, settingsRepository)
    internal val downloadManager = DownloadManager(storageManager, preferencesManager)
    internal val backupManager = BackupManager(this, preferencesManager)

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Position debounce tracking
    private data class PendingPosition(
        val novelId: String,
        val chapterId: String,
        val paragraphIndex: Int,
        val charOffset: Int
    )
    private var pendingPosition: PendingPosition? = null
    private var positionDebounceJob: Job? = null
    private val positionMutex = Mutex()

    // 1. Reactive Library StateFlow (starts empty, driven by Room query with derived counters)
    val libraryNovels: StateFlow<List<Novel>> = novelDao.getLibraryNovelsWithChapterInfoFlow()
        .map { list -> list.map { it.toModel() } }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, emptyList())

    // 2. Reactive Downloaded StateFlow (derived from storage_tier DOWNLOADED and EPUB)
    val downloadedChapters: StateFlow<Map<String, Set<String>>> = chapterDao.getAllDownloadedChaptersFlow()
        .map { list ->
            val map = mutableMapOf<String, MutableSet<String>>()
            for (item in list) {
                map.getOrPut(item.novelId) { mutableSetOf() }.add(item.id)
            }
            map.mapValues { it.value.toSet() }
        }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, emptyMap())

    // 3. Reactive Read Chapters StateFlow
    val readChapters: StateFlow<Map<String, Set<String>>> = chapterDao.getAllReadChaptersFlow()
        .map { list ->
            val map = mutableMapOf<String, MutableSet<String>>()
            for (item in list) {
                map.getOrPut(item.novelId) { mutableSetOf() }.add(item.id)
            }
            map.mapValues { it.value.toSet() }
        }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, emptyMap())

    // 4. Reactive Bookmarks StateFlow
    val bookmarks: StateFlow<List<Bookmark>> = bookmarkDao.getAllBookmarksFlow()
        .map { list -> list.map { it.toModel() } }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, emptyList())

    // Settings & preferences StateFlows
    val readerSettings: StateFlow<ReaderSettings> = settingsRepository.readerSettings
    val termReplacements: StateFlow<List<TermReplacementRule>> = preferencesManager.termReplacements
    val themeSettings: StateFlow<ThemeSettings> = settingsRepository.themeSettings
    val downloadSettings: StateFlow<DownloadSettings> = settingsRepository.downloadSettings
    val notificationSettings: StateFlow<NotificationSettings> = settingsRepository.notificationSettings
    val notifications: StateFlow<List<AppNotificationItem>> = preferencesManager.notifications
    val readingStats: StateFlow<ReadingStats> = preferencesManager.readingStats
    val customSources: StateFlow<List<CustomNovelSource>> = preferencesManager.customSources
    val requestedSources: StateFlow<List<String>> = preferencesManager.requestedSources

    init {
        SourceManager.registerSource(WebScrapeSource(appContext))
        repoScope.launch {
            migrateEpubCoversIfNeeded()
        }
    }

    private suspend fun migrateEpubCoversIfNeeded() = withContext(Dispatchers.IO) {
        if (preferencesManager.isEpubCoversMigrated()) return@withContext
        try {
            val cacheDirPath = appContext.cacheDir.absolutePath
            val coversDir = File(appContext.filesDir, "covers")
            if (!coversDir.exists()) coversDir.mkdirs()

            val novels = libraryNovels.value
            for (novel in novels) {
                val coverUrl = novel.coverUrl
                if (coverUrl != null && (coverUrl.startsWith(cacheDirPath) || coverUrl.contains("/cache/"))) {
                    val oldFile = File(coverUrl)
                    if (oldFile.exists() && oldFile.isFile) {
                        val newFile = File(coversDir, oldFile.name)
                        oldFile.copyTo(newFile, overwrite = true)
                        novelDao.updateDetails(
                            id = novel.id,
                            title = novel.title,
                            author = novel.author,
                            description = novel.description,
                            coverUrl = newFile.absolutePath
                        )
                    }
                }
            }
            preferencesManager.setEpubCoversMigrated(true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- LIBRARY METHODS ---

    fun isLibraryGridView(): Boolean = settingsRepository.isLibraryGridView.value

    fun setLibraryGridView(isGrid: Boolean) {
        settingsRepository.setLibraryGridViewSync(isGrid)
    }

    suspend fun addToLibrary(novel: Novel) = withContext(Dispatchers.IO) {
        val entity = novel.toEntity().copy(inLibrary = true)
        novelDao.upsertSafe(entity)
    }

    // TODO: remove in Stage 2b
    fun addToLibraryAsync(novel: Novel) {
        repoScope.launch {
            try {
                addToLibrary(novel)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun removeFromLibrary(novelId: String) = withContext(Dispatchers.IO) {
        novelDao.setInLibrary(novelId, false)
    }

    // TODO: remove in Stage 2b
    fun removeFromLibraryAsync(novelId: String) {
        repoScope.launch {
            try {
                removeFromLibrary(novelId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun removeNovelCompletely(novelId: String) = withContext(Dispatchers.IO) {
        // Cascade delete in Room deletes chapters, bookmarks, download_queue
        novelDao.deleteById(novelId)
        val customUri = settingsRepository.downloadSettings.value.customDownloadLocationUri
        storageManager.deleteNovelChapters(novelId, isEpub = false, customUriStr = customUri)
    }

    // TODO: remove in Stage 2b
    fun removeNovelCompletelyAsync(novelId: String) {
        repoScope.launch {
            try {
                removeNovelCompletely(novelId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun updateNovelDetails(
        novelId: String,
        newTitle: String,
        newAuthor: String,
        newDescription: String,
        newCoverUrl: String?
    ) = withContext(Dispatchers.IO) {
        novelDao.updateDetails(
            id = novelId,
            title = newTitle.trim(),
            author = newAuthor.trim(),
            description = newDescription.trim(),
            coverUrl = newCoverUrl?.trim()?.ifEmpty { null }
        )
    }

    // TODO: remove in Stage 2b
    fun updateNovelDetailsAsync(
        novelId: String,
        newTitle: String,
        newAuthor: String,
        newDescription: String,
        newCoverUrl: String?
    ) {
        repoScope.launch {
            try {
                updateNovelDetails(novelId, newTitle, newAuthor, newDescription, newCoverUrl)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun clearNovelReadingHistory(novelId: String) = withContext(Dispatchers.IO) {
        // Keeps last_read_chapter_id, sets last_read_timestamp = 0
        novelDao.clearLastReadTimestamp(novelId)
    }

    // TODO: remove in Stage 2b
    fun clearNovelReadingHistoryAsync(novelId: String) {
        repoScope.launch {
            try {
                clearNovelReadingHistory(novelId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun isNovelInLibrary(novelId: String): Boolean {
        // In-memory snapshot check: no Room call on the main thread
        return libraryNovels.value.any { it.id == novelId }
    }

    fun getReadingHistory(): List<Novel> {
        // In-memory snapshot check: no Room call on the main thread
        return libraryNovels.value
            .filter { it.lastReadTimestamp > 0L }
            .sortedByDescending { it.lastReadTimestamp }
    }

    // --- READING PROGRESS & POSITION ---

    suspend fun updateReadingProgress(
        novelId: String,
        chapterId: String,
        chapterTitle: String = "",
        chapterIndex: Int = 0,
        scrollPosition: Int = 0,
        wordsReadDelta: Int = 0
    ) = withContext(Dispatchers.IO) {
        flushReadingPosition()
        // updateReadingProgress only sets last_read_chapter_id and last_read_timestamp
        novelDao.updateLastRead(novelId, chapterId, System.currentTimeMillis())
        if (wordsReadDelta > 0) {
            preferencesManager.recordReadingSession(minutesDelta = 1, wordsDelta = wordsReadDelta)
        }
    }

    // TODO: remove in Stage 2b
    fun updateReadingProgressAsync(
        novelId: String,
        chapterId: String,
        chapterTitle: String = "",
        chapterIndex: Int = 0,
        scrollPosition: Int = 0,
        wordsReadDelta: Int = 0
    ) {
        repoScope.launch {
            try {
                updateReadingProgress(novelId, chapterId, chapterTitle, chapterIndex, scrollPosition, wordsReadDelta)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // TODO: remove in Stage 2b
    fun persistReadingProgressAsync(
        novelId: String,
        chapterId: String,
        chapterTitle: String = "",
        chapterIndex: Int = 0,
        scrollPosition: Int = 0,
        wordsReadDelta: Int = 0
    ) = updateReadingProgressAsync(novelId, chapterId, chapterTitle, chapterIndex, scrollPosition, wordsReadDelta)

    fun getChapterReadingPosition(novelId: String, chapterId: String): Int {
        // Read fast cached position
        return preferencesManager.getChapterReadingPosition(novelId, chapterId)
    }

    fun saveChapterReadingPosition(novelId: String, chapterId: String, position: Int) {
        saveChapterReadingPositionDebounced(novelId, chapterId, position, 0)
    }

    fun saveChapterReadingPositionAsync(novelId: String, chapterId: String, position: Int) {
        saveChapterReadingPositionDebounced(novelId, chapterId, position, 0)
    }

    fun saveChapterReadingPositionDebounced(novelId: String, chapterId: String, paragraphIndex: Int, charOffset: Int = 0) {
        // Immediate sync cache update for fast reads
        preferencesManager.saveChapterReadingPosition(novelId, chapterId, paragraphIndex)
        repoScope.launch {
            positionMutex.withLock {
                pendingPosition = PendingPosition(novelId, chapterId, paragraphIndex, charOffset)
                positionDebounceJob?.cancel()
                positionDebounceJob = repoScope.launch {
                    delay(1000L) // 1-second debounce
                    flushReadingPosition()
                }
            }
        }
    }

    suspend fun flushReadingPosition() = withContext(Dispatchers.IO) {
        val pos = positionMutex.withLock {
            val p = pendingPosition
            pendingPosition = null
            positionDebounceJob?.cancel()
            positionDebounceJob = null
            p
        } ?: return@withContext

        chapterDao.updatePosition(pos.novelId, pos.chapterId, pos.paragraphIndex, pos.charOffset)
    }

    // --- READ STATE TRACKING ---

    suspend fun markChapterAsRead(novelId: String, chapterId: String) = withContext(Dispatchers.IO) {
        // markChapterAsRead only sets is_read (skips write if already read) and never touches last_read_*
        chapterDao.markChapterRead(novelId, chapterId)
    }

    // TODO: remove in Stage 2b
    fun markChapterAsReadAsync(novelId: String, chapterId: String) {
        repoScope.launch {
            try {
                markChapterAsRead(novelId, chapterId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getReadChapterIds(novelId: String): Flow<Set<String>> {
        return chapterDao.getReadChapterIdsFlow(novelId)
            .map { it.toSet() }
            .distinctUntilChanged()
    }

    // --- CHAPTERS & OFFLINE STORAGE ---

    suspend fun getChapterList(novel: Novel, forceRefresh: Boolean = false): List<Chapter> = withContext(Dispatchers.IO) {
        // 1. Check Room first
        val roomChapters = chapterDao.getChapters(novel.id)

        if (!forceRefresh && roomChapters.isNotEmpty()) {
            return@withContext roomChapters.map { it.toModel() }
        }

        // Web and EPUB novels never fetch over network: their chapters are inserted when added/imported
        if (novel.isEpub || novel.sourceId == "local_epub" || novel.sourceId == WebScrapeSource.SOURCE_ID) {
            return@withContext roomChapters.map { it.toModel() }
        }

        // 2. Fetch from source when Room has no chapters or forceRefresh requested
        val source = SourceManager.getSourceById(novel.sourceId) ?: SourceManager.getActiveSource()
        val rawChapters = try {
            source.getChapterList(novel.id)
        } catch (e: Exception) {
            emptyList()
        }

        if (rawChapters.isNotEmpty()) {
            // Persist in Room using insert-ignore plus updateMetadata (refreshChapters transaction)
            val entities = rawChapters.map { it.toEntity(storageTier = "NONE", storageLocation = "INTERNAL") }
            chapterDao.refreshChapters(novel.id, entities)

            // Cache to disk & memory for Stage 4 storage continuity
            val customUri = settingsRepository.downloadSettings.value.customDownloadLocationUri
            storageManager.putCachedChapters(novel.id, rawChapters)
            storageManager.saveNovelChaptersToDisk(novel.id, rawChapters, customUri)

            return@withContext chapterDao.getChapters(novel.id).map { it.toModel() }
        }

        roomChapters.map { it.toModel() }
    }

    fun getCachedChapters(novelId: String): List<Chapter>? = storageManager.getCachedChapters(novelId)

    // Dead for chapter list in Stage 1b-2; left for Stage 4
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

        // Reading sets CACHE tier when it is NONE and updates last_accessed_at; never sets DOWNLOADED
        chapterDao.recordChapterAccess(novelId, chapterId, System.currentTimeMillis())

        // 1. Check local cache (memory)
        storageManager.getCachedChapterContent(chapterId)?.let { return@withContext it }

        // 2. Check local disk file
        val offlineContent = storageManager.readChapterFromDisk(novelId, chapterId, customUriStr)
        if (offlineContent != null) {
            storageManager.putCachedChapterContent(chapterId, offlineContent)
            return@withContext offlineContent
        }

        // 3. Fetch from Source
        val novel = libraryNovels.value.firstOrNull { it.id == novelId }
        val source = if (novel != null) {
            SourceManager.getSourceById(novel.sourceId) ?: SourceManager.getActiveSource()
        } else {
            SourceManager.getActiveSource()
        }

        val content = source.getChapterContent(novelId, chapterId)
        storageManager.saveChapterToDisk(content, customUriStr)
        storageManager.putCachedChapterContent(chapterId, content)
        content
    }

    suspend fun downloadChapter(novel: Novel, chapter: Chapter): Boolean = withContext(Dispatchers.IO) {
        val success = downloadManager.downloadChapter(novel, chapter) {
            getChapterContent(novel.id, chapter.id)
        }
        if (success) {
            // Explicit download sets storage_tier DOWNLOADED and storage_location INTERNAL
            chapterDao.markChapterDownloaded(novel.id, chapter.id)
        }
        success
    }

    suspend fun downloadChaptersBatch(
        novel: Novel,
        chaptersToDownload: List<Chapter>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        downloadManager.downloadChaptersBatch(novel, chaptersToDownload, onProgress) { ch ->
            val content = getChapterContent(novel.id, ch.id)
            chapterDao.markChapterDownloaded(novel.id, ch.id)
            content
        }
    }

    suspend fun deleteDownloadedNovelChapters(novelId: String) = withContext(Dispatchers.IO) {
        val novel = libraryNovels.value.find { it.id == novelId }
        val isEpub = novel?.let { it.isEpub || it.sourceId == "local_epub" } ?: novelId.startsWith("epub_")
        if (isEpub) return@withContext

        // Deleting downloads resets DOWNLOADED and CACHE rows to NONE; never touches EPUB rows
        chapterDao.deleteNovelDownloads(novelId)
        val customUri = preferencesManager.downloadSettings.value.customDownloadLocationUri
        storageManager.deleteNovelChapters(novelId, isEpub = false, customUriStr = customUri)
        downloadManager.deleteDownloadedNovelChapters(novelId, isEpub = false)
    }

    suspend fun deleteAllDownloadedContent(): Int = withContext(Dispatchers.IO) {
        val epubNovelIds = libraryNovels.value
            .filter { it.isEpub || it.sourceId == "local_epub" || it.id.startsWith("epub_") }
            .map { it.id }
            .toSet()

        // Deleting downloads resets DOWNLOADED and CACHE rows to NONE; never touches EPUB rows
        chapterDao.deleteAllDownloads()
        val customUri = preferencesManager.downloadSettings.value.customDownloadLocationUri
        storageManager.deleteAllDownloadedContent(customUri, epubNovelIds)
    }

    suspend fun deleteNovelDownloads(novelId: String) {
        deleteDownloadedNovelChapters(novelId)
    }

    // --- EPUB IMPORT ---

    suspend fun importEpub(uri: Uri, fileName: String): Novel = withContext(Dispatchers.IO) {
        val customUriStr = preferencesManager.downloadSettings.value.customDownloadLocationUri
        val parsed = storageManager.parseEpub(uri, fileName)

        // Save chapter contents and files to disk
        storageManager.putCachedChapters(parsed.novel.id, parsed.chapters)
        storageManager.saveNovelChaptersToDisk(parsed.novel.id, parsed.chapters, customUriStr)

        for ((chId, content) in parsed.chapterContents) {
            storageManager.putCachedChapterContent(chId, content)
            storageManager.saveChapterToDisk(content, customUriStr)
        }

        // New novels keep last_read_timestamp = 0; insert-ignore plus update
        val novelEntity = parsed.novel.toEntity().copy(
            inLibrary = true,
            isEpub = true,
            lastReadTimestamp = 0L
        )
        novelDao.upsertSafe(novelEntity)

        // EPUB rows get storage_tier EPUB and storage_location INTERNAL
        val chapterEntities = parsed.chapters.map { ch ->
            ch.toEntity(
                storageTier = "EPUB",
                storageLocation = "INTERNAL",
                fileHash = ch.id,
                lastAccessedAt = System.currentTimeMillis()
            )
        }
        chapterDao.refreshChapters(parsed.novel.id, chapterEntities)

        parsed.novel.copy(inLibrary = true, lastReadTimestamp = 0L)
    }

    // --- WEB SCRAPED NOVELS ---

    suspend fun saveWebScrapedNovel(
        scrapedData: ScrapedNovelData,
        customTitle: String,
        customAuthor: String,
        customSynopsis: String,
        customTags: List<String>,
        customCoverUrl: String?
    ): Novel = withContext(Dispatchers.IO) {
        val cleanTitle = WtrLabExtractor.cleanWtrLabTokens(customTitle.ifBlank { "Untitled Web Novel" })
        val cleanAuthor = WtrLabExtractor.cleanWtrLabTokens(customAuthor.ifBlank { "Unknown Author" })
        val cleanSynopsis = WtrLabExtractor.cleanWtrLabTokens(customSynopsis.ifBlank { "Web Novel" })
        val normalizedUrl = normalizeSourceUrl(scrapedData.sourceUrl)

        // 4. Dedupe web novels by (source_id, raw_id), then by normalized source_url, and return existing novel
        val existingNovel = (scrapedData.rawId?.let { novelDao.getNovelBySourceAndRawId(WebScrapeSource.SOURCE_ID, it) })
            ?: (normalizedUrl?.let { novelDao.getNovelBySourceUrl(it) })

        if (existingNovel != null) {
            // Novel already exists: mark in_library = true and refresh chapters
            novelDao.setInLibrary(existingNovel.id, true)
            val chapterEntities = scrapedData.chapters.mapIndexed { index, ch ->
                Chapter(
                    id = ch.url,
                    novelId = existingNovel.id,
                    title = WtrLabExtractor.cleanWtrLabTokens(ch.title),
                    chapterIndex = index + 1,
                    releaseDate = "Web",
                    isDownloaded = false,
                    isRead = false,
                    wordCount = 0
                ).toEntity(storageTier = "NONE", storageLocation = "INTERNAL")
            }
            chapterDao.refreshChapters(existingNovel.id, chapterEntities)
            return@withContext existingNovel.toModel(
                downloadedCount = chapterDao.getDownloadedCount(existingNovel.id)
            ).copy(inLibrary = true)
        }

        // Create new web novel
        val novelId = "web_" + java.util.UUID.randomUUID().toString().take(8)
        val host = try {
            java.net.URI(scrapedData.sourceUrl).host?.removePrefix("www.") ?: "Web"
        } catch (e: Exception) {
            "Web"
        }

        val chapters = scrapedData.chapters.mapIndexed { index, ch ->
            Chapter(
                id = ch.url,
                novelId = novelId,
                title = WtrLabExtractor.cleanWtrLabTokens(ch.title),
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

        val novelEntity = Novel(
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
            lastReadTimestamp = 0L, // New novels keep last_read_timestamp = 0
            rawId = scrapedData.rawId,
            locale = scrapedData.locale,
            sourceUrl = normalizedUrl
        ).toEntity()

        // Insert novel and chapters using insert-ignore plus update
        novelDao.upsertSafe(novelEntity)
        val chapterEntities = chapters.map { it.toEntity(storageTier = "NONE", storageLocation = "INTERNAL") }
        chapterDao.refreshChapters(novelId, chapterEntities)

        novelEntity.toModel()
    }

    // TEMPORARY: remove in Stage 2b
    fun saveWebScrapedNovelAsync(
        scrapedData: ScrapedNovelData,
        customTitle: String,
        customAuthor: String,
        customSynopsis: String,
        customTags: List<String>,
        customCoverUrl: String?,
        onResult: (Novel) -> Unit
    ) {
        repoScope.launch(Dispatchers.IO) {
            try {
                val novel = saveWebScrapedNovel(
                    scrapedData = scrapedData,
                    customTitle = customTitle,
                    customAuthor = customAuthor,
                    customSynopsis = customSynopsis,
                    customTags = customTags,
                    customCoverUrl = customCoverUrl
                )
                withContext(Dispatchers.Main) {
                    onResult(novel)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- BOOKMARKS ---

    suspend fun addBookmark(bookmark: Bookmark) = withContext(Dispatchers.IO) {
        bookmarkDao.insertIgnore(bookmark.toEntity())
    }

    // TODO: remove in Stage 2b
    fun addBookmarkAsync(bookmark: Bookmark) {
        repoScope.launch {
            try {
                addBookmark(bookmark)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun removeBookmark(bookmarkId: String) = withContext(Dispatchers.IO) {
        bookmarkDao.deleteById(bookmarkId)
    }

    // TODO: remove in Stage 2b
    fun removeBookmarkAsync(bookmarkId: String) {
        repoScope.launch {
            try {
                removeBookmark(bookmarkId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getBookmarksForNovel(novelId: String): List<Bookmark> {
        // In-memory snapshot check: no Room call on the main thread
        return bookmarks.value.filter { it.novelId == novelId }
    }

    fun getBookmarksForNovelFlow(novelId: String): Flow<List<Bookmark>> {
        return bookmarkDao.getBookmarksForNovelFlow(novelId)
            .map { list -> list.map { it.toModel() } }
            .distinctUntilChanged()
    }

    // --- READER SETTINGS ---

    fun updateReaderSettings(settings: ReaderSettings) = settingsRepository.updateReaderSettingsSync(settings)

    // --- TERM REPLACEMENT RULES ---

    fun addTermReplacementRule(rule: TermReplacementRule) = preferencesManager.addTermReplacementRule(rule)

    fun updateTermReplacementRule(rule: TermReplacementRule) = preferencesManager.updateTermReplacementRule(rule)

    fun deleteTermReplacementRule(id: String) = preferencesManager.deleteTermReplacementRule(id)

    fun toggleTermReplacementRule(id: String, isEnabled: Boolean) = preferencesManager.toggleTermReplacementRule(id, isEnabled)

    // --- APP THEME & ACCENT SETTINGS ---

    fun updateThemeSettings(settings: ThemeSettings) = settingsRepository.updateThemeSettingsSync(settings)

    // --- STATS ---

    fun recordReadingSession(minutesDelta: Int, wordsDelta: Int) = preferencesManager.recordReadingSession(minutesDelta, wordsDelta)

    // --- DOWNLOAD SETTINGS ---

    fun updateDownloadSettings(settings: DownloadSettings) = settingsRepository.updateDownloadSettingsSync(settings)

    // --- NOTIFICATIONS ---

    fun updateNotificationSettings(settings: NotificationSettings) = settingsRepository.updateNotificationSettingsSync(settings)

    fun addNotification(title: String, message: String, type: String = "info") = preferencesManager.addNotification(title, message, type)

    fun clearNotifications() = preferencesManager.clearNotifications()

    // --- STORAGE & CACHE MANAGEMENT ---

    fun getStorageUsageBytes(): Long = storageManager.getStorageUsageBytes()

    fun getCacheSizeBytes(): Long = storageManager.getCacheSizeBytes()

    fun clearCache(): Long = storageManager.clearCache()

    fun resetAppPreferences() = settingsRepository.resetAllSettingsSync()

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
