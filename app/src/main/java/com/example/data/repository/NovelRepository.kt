package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.data.local.db.NovelDatabaseHolder
import com.example.data.local.db.getCustomSourceDedupeKey
import com.example.data.local.db.normalizeCustomSourceUrl
import com.example.data.local.db.normalizeSourceUrl
import com.example.data.local.db.toEntity
import com.example.data.local.db.toModel
import com.example.data.local.db.entity.NotificationEntity
import com.example.data.local.db.entity.RequestedSourceEntity
import com.example.data.model.*
import com.example.data.source.SourceManager
import com.example.data.source.webscrape.ScrapedNovelData
import com.example.data.source.webscrape.WebScrapeSource
import com.example.data.source.webscrape.WtrLabExtractor
import com.example.data.local.storage.ChapterStorage
import com.example.data.local.storage.StorageResolver
import com.example.data.local.storage.CacheReconciler
import androidx.room.withTransaction
import com.example.data.epub.EpubParser
import com.example.data.epub.ParsedEpub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Modern Room-backed Repository for Novel operations.
 *
 * Adheres to Architectural Invariants:
 * - Room is the single source of truth for library, chapters, read state, positions, bookmarks,
 *   term replacements, custom sources, requested sources and notifications.
 * - LibraryNovels derives downloaded counts and last read info in the query (no stored counters).
 * - Safe insert-ignore plus update transactions; never OnConflictStrategy.REPLACE.
 * - Reading sets CACHE tier; explicit downloads set DOWNLOADED tier.
 * - Debounced position writes (1-second debounce, flushed on chapter leave).
 * - Startup purge of unused Browse novels.
 */
class NovelRepository(context: Context) {

    private val appContext: Context = context.applicationContext ?: context
    private val database = NovelDatabaseHolder.getInstance(appContext)
    private val novelDao = database.novelDao()
    private val chapterDao = database.chapterDao()
    private val bookmarkDao = database.bookmarkDao()
    private val termReplacementDao = database.termReplacementDao()
    private val customSourceDao = database.customSourceDao()
    private val requestedSourceDao = database.requestedSourceDao()
    private val notificationDao = database.notificationDao()

    val settingsRepository = SettingsRepository.getInstance(appContext)
    val storageResolver = StorageResolver(appContext)
    val chapterStorage = ChapterStorage(storageResolver, appContext)
    val chapterRepository = ChapterRepository(chapterDao, novelDao, chapterStorage, storageResolver, settingsRepository, notificationDao)
    val cacheReconciler = CacheReconciler(chapterDao, storageResolver)
    internal val storageManager = StorageManager(appContext)
    internal val preferencesManager = PreferencesManager(appContext, settingsRepository)
    internal val downloadManager = DownloadManager(chapterRepository, settingsRepository)
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

    // 5. Reactive Term Replacements StateFlow (Room backed, global & local rules)
    val termReplacements: StateFlow<List<TermReplacementRule>> = termReplacementDao.getAllReplacementsFlow()
        .map { list -> list.map { it.toModel() } }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, emptyList())

    // 6. Reactive Custom Sources StateFlow (Room backed)
    val customSources: StateFlow<List<CustomNovelSource>> = customSourceDao.getAllSourcesFlow()
        .map { list -> list.map { it.toModel() } }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, emptyList())

    // 7. Reactive Requested Sources StateFlow (Room backed, keeps old "$siteName ($url)" string format)
    val requestedSources: StateFlow<List<String>> = requestedSourceDao.getAllRequestsFlow()
        .map { list ->
            list.map { item ->
                if (item.url.isNotBlank()) "${item.siteName} (${item.url})" else item.siteName
            }
        }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, emptyList())

    // 8. Reactive Notifications StateFlow (Room backed, newest 200 kept)
    val notifications: StateFlow<List<AppNotificationItem>> = notificationDao.getNotificationsFlow()
        .map { list -> list.map { it.toModel() } }
        .distinctUntilChanged()
        .stateIn(repoScope, SharingStarted.Eagerly, emptyList())

    // Unread notification count Flow
    val unreadNotificationCount: Flow<Int> = notificationDao.getUnreadCountFlow()
        .distinctUntilChanged()

    // Settings & preferences StateFlows
    val readerSettings: StateFlow<ReaderSettings> = settingsRepository.readerSettings
    val themeSettings: StateFlow<ThemeSettings> = settingsRepository.themeSettings
    val downloadSettings: StateFlow<DownloadSettings> = settingsRepository.downloadSettings
    val notificationSettings: StateFlow<NotificationSettings> = settingsRepository.notificationSettings
    val readingStats: StateFlow<ReadingStats> = preferencesManager.readingStats

    init {
        SourceManager.registerSource(WebScrapeSource(appContext))
        repoScope.launch(Dispatchers.IO) {
            customSources.collect { list ->
                SourceManager.setCustomSources(list)
            }
        }
        repoScope.launch(Dispatchers.IO) {
            migrateEpubCoversIfNeeded()
        }
        repoScope.launch(Dispatchers.IO) {
            try {
                // Startup purge & cache reconciler: runs once per app start in the background on Dispatchers.IO,
                // only after the library flow has emitted its first value.
                libraryNovels.first()
                purgeUnusedBrowseNovels()
                cacheReconciler.reconcile()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                android.util.Log.e("NovelRepository", "Failed to run startup reconciler or purge", e)
            }
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

    suspend fun removeFromLibrary(novelId: String) = withContext(Dispatchers.IO) {
        novelDao.setInLibrary(novelId, false)
    }

    suspend fun removeNovelCompletely(novelId: String) = withContext(Dispatchers.IO) {
        chapterRepository.removeNovelCompletely(novelId)
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

    suspend fun clearNovelReadingHistory(novelId: String) = withContext(Dispatchers.IO) {
        // Keeps last_read_chapter_id, sets last_read_timestamp = 0
        novelDao.clearLastReadTimestamp(novelId)
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

    fun getChapterReadingPosition(novelId: String, chapterId: String): Int {
        // Read fast cached position
        return preferencesManager.getChapterReadingPosition(novelId, chapterId)
    }

    fun saveChapterReadingPosition(novelId: String, chapterId: String, position: Int) {
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

            return@withContext chapterDao.getChapters(novel.id).map { it.toModel() }
        }

        roomChapters.map { it.toModel() }
    }

    suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent =
        chapterRepository.getChapterContent(novelId, chapterId)

    suspend fun downloadChapter(novel: Novel, chapter: Chapter): Boolean =
        chapterRepository.downloadChapter(novel, chapter)

    suspend fun downloadChaptersBatch(
        novel: Novel,
        chaptersToDownload: List<Chapter>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) = downloadManager.downloadChaptersBatch(novel, chaptersToDownload, onProgress)

    suspend fun deleteDownloadedNovelChapters(novelId: String) =
        chapterRepository.deleteNovelDownloads(novelId)

    suspend fun deleteAllDownloadedContent(): Int =
        chapterRepository.deleteAllDownloads()

    suspend fun deleteNovelDownloads(novelId: String) {
        deleteDownloadedNovelChapters(novelId)
    }

    // --- EPUB IMPORT ---

    suspend fun importEpub(uri: Uri, fileName: String): Novel = withContext(Dispatchers.IO) {
        // Read EPUB bytes ONCE from stream and close with use
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot open stream for URI: $uri")

        // 1. Dedupe by SHA-256 of file bytes
        val fileHash = storageResolver.sha256(bytes)
        val existingByHash = novelDao.getNovelBySourceAndRawId("local_epub", fileHash)
        if (existingByHash != null) {
            return@withContext existingByHash.toModel(
                downloadedCount = chapterDao.getDownloadedCount(existingByHash.id)
            ).copy(inLibrary = true)
        }

        // Parse EPUB metadata and chapters from the same bytes (closing stream)
        val parsed: ParsedEpub = ByteArrayInputStream(bytes).use { bais ->
            EpubParser.parseEpub(appContext, bais, fileName)
        }

        // 2. Dedupe by normalized title + author
        val existingByTitleAuthor = novelDao.getEpubNovelByTitleAndAuthor(parsed.novel.title, parsed.novel.author)
        if (existingByTitleAuthor != null) {
            return@withContext existingByTitleAuthor.toModel(
                downloadedCount = chapterDao.getDownloadedCount(existingByTitleAuthor.id)
            ).copy(inLibrary = true)
        }

        val novelId = parsed.novel.id
        val writtenFiles = mutableListOf<File>()

        try {
            // Write files first (outside and before the database transaction):
            // a. Save cover to covers/{novelId}.jpg and set coverUrl
            var coverUrl: String? = null
            if (parsed.coverBytes != null && parsed.coverBytes.isNotEmpty()) {
                val coverFile = storageResolver.getCoverFile(novelId)
                coverFile.parentFile?.mkdirs()
                coverFile.writeBytes(parsed.coverBytes)
                writtenFiles.add(coverFile)
                coverUrl = coverFile.absolutePath
            }

            // b. Copy original .epub to original_epubs/{novelId}.epub
            val originalEpubFile = storageResolver.getOriginalEpubFile(novelId)
            originalEpubFile.parentFile?.mkdirs()
            originalEpubFile.writeBytes(bytes)
            writtenFiles.add(originalEpubFile)

            // Permanent EPUB chapter files to epubs/{novelId}/{sha256}.json
            for (entry in parsed.chapterContents) {
                val content = entry.value
                chapterStorage.writeToEpub(content)
                val chapterFile = storageResolver.getEpubChapterFile(novelId, content.chapterId)
                writtenFiles.add(chapterFile)
            }

            // Insert novel and chapter rows in one non-blocking Room transaction
            val novelEntity = parsed.novel.toEntity().copy(
                coverUrl = coverUrl,
                inLibrary = true,
                isEpub = true,
                rawId = fileHash,
                lastReadTimestamp = 0L
            )

            val chapterEntities = parsed.chapters.map { ch ->
                ch.toEntity(
                    storageTier = "EPUB",
                    storageLocation = "INTERNAL",
                    fileHash = storageResolver.sha256(ch.id),
                    lastAccessedAt = System.currentTimeMillis()
                )
            }

            // Never block inside transaction; do not call nested transaction suspend functions
            database.withTransaction {
                val rowId = novelDao.insertIgnore(novelEntity)
                if (rowId == -1L) {
                    novelDao.update(novelEntity)
                }
                chapterDao.insertIgnore(chapterEntities)
                for (ch in chapterEntities) {
                    chapterDao.updateMetadata(
                        novelId = novelId,
                        id = ch.id,
                        title = ch.title,
                        chapterIndex = ch.chapterIndex,
                        wordCount = ch.wordCount,
                        releaseDate = ch.releaseDate
                    )
                }
            }

            novelEntity.toModel()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            for (file in writtenFiles) {
                try {
                    if (file.exists()) file.delete()
                } catch (_: Exception) {}
            }
            val epubDir = storageResolver.getNovelEpubDir(novelId)
            if (epubDir.exists() && epubDir.listFiles().isNullOrEmpty()) {
                try { epubDir.delete() } catch (_: Exception) {}
            }
            throw e
        }
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

    // --- BOOKMARKS ---

    suspend fun addBookmark(bookmark: Bookmark) = withContext(Dispatchers.IO) {
        bookmarkDao.insertIgnore(bookmark.toEntity())
    }

    suspend fun removeBookmark(bookmarkId: String) = withContext(Dispatchers.IO) {
        bookmarkDao.deleteById(bookmarkId)
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

    fun getTermReplacementsFlow(): Flow<List<TermReplacementRule>> {
        return termReplacementDao.getAllReplacementsFlow()
            .map { list -> list.map { it.toModel() } }
            .distinctUntilChanged()
    }

    fun getGlobalTermReplacementsFlow(): Flow<List<TermReplacementRule>> {
        return termReplacementDao.getGlobalReplacementsFlow()
            .map { list -> list.map { it.toModel() } }
            .distinctUntilChanged()
    }

    fun getTermReplacementsForNovelFlow(novelId: String): Flow<List<TermReplacementRule>> {
        return termReplacementDao.getReplacementsForNovelFlow(novelId)
            .map { list -> list.map { it.toModel() } }
            .distinctUntilChanged()
    }

    suspend fun addTermReplacement(
        rule: TermReplacementRule,
        novelId: String? = null,
        isRegex: Boolean = false,
        matchCase: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val entity = rule.toEntity(novelId = novelId, isRegex = isRegex, matchCase = matchCase)
        termReplacementDao.insertIgnore(entity)
    }

    suspend fun updateTermReplacement(rule: TermReplacementRule) = withContext(Dispatchers.IO) {
        val existing = termReplacementDao.getById(rule.id)
        val entity = if (existing != null) {
            existing.copy(
                pattern = rule.original,
                replacement = rule.replacement,
                isEnabled = rule.isEnabled
            )
        } else {
            rule.toEntity(novelId = null)
        }
        termReplacementDao.update(entity)
    }

    suspend fun deleteTermReplacement(id: String) = withContext(Dispatchers.IO) {
        termReplacementDao.deleteById(id)
    }

    suspend fun setTermReplacementEnabled(id: String, isEnabled: Boolean) = withContext(Dispatchers.IO) {
        termReplacementDao.setEnabled(id, isEnabled)
    }

    // --- APP THEME & ACCENT SETTINGS ---

    fun updateThemeSettings(settings: ThemeSettings) = settingsRepository.updateThemeSettingsSync(settings)

    // --- STATS ---

    fun recordReadingSession(minutesDelta: Int, wordsDelta: Int) = preferencesManager.recordReadingSession(minutesDelta, wordsDelta)

    // --- DOWNLOAD SETTINGS ---

    fun updateDownloadSettings(settings: DownloadSettings) = settingsRepository.updateDownloadSettingsSync(settings)

    // --- NOTIFICATIONS ---

    fun getNotificationsFlow(): Flow<List<AppNotificationItem>> {
        return notificationDao.getNotificationsFlow()
            .map { list -> list.map { it.toModel() } }
            .distinctUntilChanged()
    }

    fun getUnreadNotificationCountFlow(): Flow<Int> {
        return notificationDao.getUnreadCountFlow().distinctUntilChanged()
    }

    suspend fun postNotification(title: String, message: String, type: String = "info") = withContext(Dispatchers.IO) {
        val entity = NotificationEntity(
            id = java.util.UUID.randomUUID().toString(),
            title = title.trim(),
            message = message.trim(),
            type = type.trim(),
            timestamp = System.currentTimeMillis(),
            isRead = false
        )
        notificationDao.insertAndTrim(entity)
    }

    suspend fun markRead(id: String) = withContext(Dispatchers.IO) {
        notificationDao.markAsRead(id)
    }

    suspend fun markAllRead() = withContext(Dispatchers.IO) {
        notificationDao.markAllAsRead()
    }

    suspend fun clear(): Int = withContext(Dispatchers.IO) {
        notificationDao.clearAll()
    }

    fun updateNotificationSettings(settings: NotificationSettings) = settingsRepository.updateNotificationSettingsSync(settings)

    // --- STORAGE & CACHE MANAGEMENT ---

    suspend fun getCacheSizeBytes(): Long = chapterStorage.getCacheSizeBytes()

    suspend fun getDownloadsSizeBytes(): Long = chapterStorage.getDownloadsSizeBytes()

    suspend fun getEpubsSizeBytes(): Long = chapterStorage.getEpubsSizeBytes()

    suspend fun getStorageUsageBytes(): Long = getDownloadsSizeBytes() + getEpubsSizeBytes()

    suspend fun clearChapterCache(): Long = chapterRepository.clearChapterCache()

    suspend fun clearCache(): Long = clearChapterCache()

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

    fun getCustomSourcesFlow(): Flow<List<CustomNovelSource>> {
        return customSourceDao.getAllSourcesFlow()
            .map { list -> list.map { it.toModel() } }
            .distinctUntilChanged()
    }

    suspend fun addCustomSource(
        name: String,
        url: String,
        language: String = "English",
        description: String = "Custom web source"
    ): CustomSourceResult = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeCustomSourceUrl(url) ?: return@withContext CustomSourceResult.InvalidUrl
        val newKey = getCustomSourceDedupeKey(normalizedUrl) ?: return@withContext CustomSourceResult.InvalidUrl
        val existingSources = customSourceDao.getAllSources()
        if (existingSources.any { existing ->
            val existingKey = getCustomSourceDedupeKey(existing.url)
            existingKey != null && existingKey == newKey
        }) {
            return@withContext CustomSourceResult.Duplicate
        }
        val entity = CustomNovelSource(
            name = name.trim().ifBlank { "Custom Source" },
            url = normalizedUrl,
            language = language.trim().ifBlank { "English" },
            description = description.trim().ifBlank { "Custom web source" }
        ).toEntity(isEnabled = true)
        customSourceDao.insertIgnore(entity)
        CustomSourceResult.Added(entity.toModel())
    }

    suspend fun deleteCustomSource(id: String) = withContext(Dispatchers.IO) {
        customSourceDao.deleteById(id)
    }

    // --- REQUESTED SOURCES ---

    fun getRequestedSourcesFlow(): Flow<List<RequestedSourceEntity>> {
        return requestedSourceDao.getAllRequestsFlow().distinctUntilChanged()
    }

    suspend fun addRequestedSource(siteName: String, url: String) = withContext(Dispatchers.IO) {
        if (siteName.isBlank()) return@withContext
        val entity = RequestedSourceEntity(
            id = java.util.UUID.randomUUID().toString(),
            siteName = siteName.trim(),
            url = url.trim(),
            requestedAt = System.currentTimeMillis()
        )
        requestedSourceDao.insertIgnore(entity)
    }

    // --- STARTUP PURGE ---

    suspend fun purgeUnusedBrowseNovels(): Int = withContext(Dispatchers.IO) {
        novelDao.purgeUnusedBrowseNovels()
    }
}
