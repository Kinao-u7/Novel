package com.example.data.repository

import androidx.annotation.VisibleForTesting
import com.example.data.download.DownloadCoordinator
import com.example.data.download.DownloadResult
import com.example.data.local.db.dao.ChapterDao
import com.example.data.local.db.dao.DownloadQueueDao
import com.example.data.local.db.dao.NotificationDao
import com.example.data.local.db.dao.NovelDao
import com.example.data.local.db.entity.NotificationEntity
import com.example.data.local.db.toModel
import com.example.data.model.NotificationTypes
import com.example.data.local.storage.ChapterCache
import com.example.data.local.storage.ChapterReadException
import com.example.data.local.storage.ChapterStorage
import com.example.data.local.storage.StorageResolver
import com.example.data.local.storage.StorageUnavailableException
import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.Novel
import com.example.data.source.SourceManager
import com.example.data.source.SourceUnavailable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class EpubFileMissing(val novelId: String, val chapterId: String) :
    IllegalStateException("EPUB file missing for novel $novelId, chapter $chapterId")

class ChapterRowMissing(val novelId: String, val chapterId: String) :
    NoSuchElementException("Chapter row missing for novel $novelId, chapter $chapterId")

class NovelRowMissing(val novelId: String) :
    NoSuchElementException("Novel row missing for novel $novelId")

/**
 * Modern repository managing chapter reading, caching, and offline downloads.
 *
 * Adheres to Architectural Invariants:
 * - Room is single source of truth for permanent storage tier (NONE, DOWNLOADED, EPUB) and location.
 * - Reading never marks a chapter downloaded and writes only to the untracked ChapterCache (Rule 2).
 * - Reader order: EPUB, then DOWNLOADED, then cache, then network.
 * - Explicit download promotes cached files by copying (no network), then sets tier to DOWNLOADED with CAS,
 *   and deletes the cache copy only if the CAS succeeded.
 * - Reader fetch and download of the same chapter share one in-flight network fetch (single-flight).
 * - Delete downloads resets DOWNLOADED rows to NONE and deletes files, never EPUB; cache is untouched.
 * - Clear cache deletes the cache folder only.
 * - All disk and database operations run on Dispatchers.IO (Rule 11).
 * - Small leaf Mutex per key allowed ONLY around file mutation steps of promote and write.
 */
class ChapterRepository(
    private val chapterDao: ChapterDao,
    private val novelDao: NovelDao,
    val chapterStorage: ChapterStorage,
    val storageResolver: StorageResolver,
    private val settingsRepository: SettingsRepository,
    private val downloadQueueDao: DownloadQueueDao,
    private val notificationDao: NotificationDao? = null,
    val downloadCoordinator: DownloadCoordinator = DownloadCoordinator.getInstance(),
    val chapterCache: ChapterCache = ChapterCache(storageResolver.cacheDir),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Shared in-flight network fetch map for single-flight deduplication.
     * Shared between online reader fetches and download tasks for the same chapter.
     */
    @VisibleForTesting
    internal val inFlightNetworkFetches = ConcurrentHashMap<Pair<String, String>, Deferred<ChapterContent>>()

    @VisibleForTesting
    internal val inFlightFetches: ConcurrentHashMap<Pair<String, String>, Deferred<ChapterContent>>
        get() = inFlightNetworkFetches

    /**
     * Repository-level scope for background fetches and tasks.
     * Uses SupervisorJob + ioDispatcher so child failures or caller cancellations do not cancel the scope.
     */
    private val repositoryExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            android.util.Log.e("ChapterRepository", "Uncaught exception in repository scope", throwable)
        }
    }
    private val repositoryScope = CoroutineScope(SupervisorJob() + ioDispatcher + repositoryExceptionHandler)

    internal fun shutdownForTesting() {
        repositoryScope.cancel()
    }

    /**
     * Small leaf Mutex per (novelId, chapterId) key.
     * Allowed ONLY around leaf file mutation steps (write/delete file + Room tier update).
     * Nothing inside this lock may call another function that takes a lock or calls the network source.
     */
    internal val chapterLocks = ConcurrentHashMap<Pair<String, String>, Mutex>()

    /**
     * User-visible warning events emitted on storage errors (e.g. unwritable SAF fallback).
     */
    private val _storageWarnings = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val storageWarnings: SharedFlow<String> = _storageWarnings.asSharedFlow()

    private var lastStorageWarningTimestamp: Long = 0L
    private val storageWarningLock = Any()

    fun resetStorageWarningRateLimit() {
        synchronized(storageWarningLock) {
            lastStorageWarningTimestamp = 0L
        }
    }

    suspend fun emitStorageWarning(message: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val shouldEmit = synchronized(storageWarningLock) {
            if (force || (now - lastStorageWarningTimestamp >= 60 * 60 * 1000L)) {
                lastStorageWarningTimestamp = now
                true
            } else {
                false
            }
        }
        if (!shouldEmit) return

        _storageWarnings.emit(message)
        try {
            notificationDao?.insertAndTrim(
                NotificationEntity(
                    id = UUID.randomUUID().toString(),
                    title = "Download Storage Warning",
                    message = message,
                    type = NotificationTypes.DOWNLOAD,
                    timestamp = now
                )
            )
        } catch (_: Exception) {}
    }

    private suspend fun <T> withLeafLock(novelId: String, chapterId: String, action: suspend () -> T): T {
        val key = Pair(novelId, chapterId)
        val mutex = chapterLocks.computeIfAbsent(key) { Mutex() }
        return mutex.withLock {
            action()
        }
    }

    /**
     * Shared network fetch for single-flight deduplication.
     * Covers ONLY the network fetch (returns raw ChapterContent).
     */
    internal suspend fun fetchChapterContentFromNetwork(novel: Novel, chapterId: String): ChapterContent {
        val key = Pair(novel.id, chapterId)
        val candidate = repositoryScope.async(start = CoroutineStart.LAZY) {
            val source = SourceManager.getSourceById(novel.sourceId)
                ?: throw SourceUnavailable(novel.sourceId)
            source.getChapterContent(novel.id, chapterId)
        }
        candidate.invokeOnCompletion { inFlightNetworkFetches.remove(key, candidate) }
        val existing = inFlightNetworkFetches.putIfAbsent(key, candidate)
        val deferred = if (existing != null) {
            candidate.cancel()
            existing
        } else {
            candidate.start()
            candidate
        }
        return try {
            deferred.await()
        } finally {
            if (deferred.isCompleted) {
                inFlightNetworkFetches.remove(key, deferred)
            }
        }
    }

    /**
     * Reads chapter content according to storage rules:
     * 1. EPUB: reads from recorded location. Throws EpubFileMissing if missing.
     * 2. DOWNLOADED: reads from recorded location.
     *    If folder/permission is unreachable (StorageUnavailableException), throws it (tier stays DOWNLOADED).
     *    If missing in reachable folder, falls through to fetch (self-healing as DOWNLOADED).
     *    If read throws IOException on existing file, rethrows ChapterReadException (tier unchanged).
     * 3. Untracked cache: checks ChapterCache. Returns immediately on hit (no Room write).
     * 4. Network: fetches via shared single-flight map.
     *    - If missing DOWNLOADED: writes back to downloads and CAS sets DOWNLOADED in Room.
     *    - Otherwise (normal read): writes to ChapterCache. Makes NO Room write.
     */
    suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent = withContext(ioDispatcher) {
        val chapterRow = chapterDao.getChapter(novelId, chapterId)
            ?: throw ChapterRowMissing(novelId, chapterId)

        when (chapterRow.storageTier) {
            "EPUB" -> {
                val content = chapterStorage.read(
                    novelId = novelId,
                    chapterId = chapterId,
                    tier = "EPUB",
                    location = chapterRow.storageLocation
                )
                if (content != null) {
                    return@withContext content
                }
                throw EpubFileMissing(novelId, chapterId)
            }
            "DOWNLOADED" -> {
                val content = try {
                    chapterStorage.read(
                        novelId = novelId,
                        chapterId = chapterId,
                        tier = "DOWNLOADED",
                        location = chapterRow.storageLocation
                    )
                } catch (e: StorageUnavailableException) {
                    throw e // Unreachable folder: tier stays DOWNLOADED, nothing refetched
                }
                if (content != null) {
                    return@withContext content
                }
                // File is missing despite row recording it; fall through to miss handling
            }
        }

        // Untracked cache check
        val cached = chapterCache.read(novelId, chapterId)
        if (cached != null) {
            return@withContext cached
        }

        // Cache miss: fetch via shared single-flight map
        val isDownloadedMissing = (chapterRow.storageTier == "DOWNLOADED")
        val savedLocation = chapterRow.storageLocation
        val novel = (novelDao.getNovelById(novelId)
            ?: throw NovelRowMissing(novelId)).toModel()

        val rawContent = fetchChapterContentFromNetwork(novel, chapterId)
        val fileHash = storageResolver.sha256(chapterId)

        withLeafLock(novelId, chapterId) {
            if (isDownloadedMissing) {
                // Self-healing: write back as DOWNLOADED in original location
                val downloadedContent = rawContent.copy(isDownloaded = true)
                chapterStorage.writeToDownloads(downloadedContent, savedLocation)
                chapterDao.updateTierToDownloaded(
                    novelId = novelId,
                    id = chapterId,
                    storageLocation = savedLocation,
                    fileHash = fileHash
                )
                downloadedContent
            } else {
                // Untracked reader cache: write to ChapterCache, NO Room write
                val cleanContent = rawContent.copy(isDownloaded = false)
                chapterCache.write(cleanContent)

                val capMb = settingsRepository.downloadSettings.value.cacheSizeCapMb
                repositoryScope.launch {
                    try {
                        chapterCache.trim(capMb = capMb)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                }

                cleanContent
            }
        }
    }

    /**
     * Explicit download:
     * - Checks free space for INTERNAL (StatFs >= 20MB).
     * - Disk-first: if file exists at target location, updates tier and returns Success.
     * - If chapter is in cache, promote it by copying to download location (no network),
     *   CAS updating Room to DOWNLOADED, and deleting cache copy only if CAS succeeded.
     * - Otherwise fetches via shared single-flight map and writes straight to downloads.
     */
    suspend fun downloadChapter(novel: Novel, chapter: Chapter): DownloadResult = withContext(ioDispatcher) {
        val customUri = settingsRepository.downloadSettings.value.customDownloadLocationUri
        var targetLocation = if (!customUri.isNullOrBlank()) {
            val candidate = "SAF:$customUri"
            if (chapterStorage.isLocationWritable(candidate)) {
                candidate
            } else {
                emitStorageWarning("Custom download folder is unwritable. Falling back to internal storage.")
                "INTERNAL"
            }
        } else {
            "INTERNAL"
        }

        // Space check for INTERNAL storage (StatFs)
        if (targetLocation == "INTERNAL") {
            try {
                val statFs = android.os.StatFs(storageResolver.filesDir.path)
                val available = statFs.availableBytes
                val minRequired = 20L * 1024L * 1024L // 20MB
                if (available < minRequired) {
                    return@withContext DownloadResult.StorageFull(available, minRequired)
                }
            } catch (_: Exception) {}
        }

        // Disk-first & promote checks under leaf lock
        val preFetchResult: DownloadResult? = withLeafLock(novel.id, chapter.id) {
            val currentRow = chapterDao.getChapter(novel.id, chapter.id)
            if (currentRow?.storageTier == "DOWNLOADED") {
                return@withLeafLock DownloadResult.Success
            }

            // Disk-first check: if file already exists at target location
            val existingContent = try {
                chapterStorage.read(novel.id, chapter.id, "DOWNLOADED", targetLocation)
            } catch (_: Exception) {
                null
            }
            if (existingContent != null) {
                val fileHash = storageResolver.sha256(chapter.id)
                chapterDao.updateTierToDownloaded(
                    novelId = novel.id,
                    id = chapter.id,
                    storageLocation = targetLocation,
                    fileHash = fileHash
                )
                return@withLeafLock DownloadResult.Success
            }

            // Promote check: if chapter is in cache, copy to download location (no network)
            if (chapterCache.contains(novel.id, chapter.id)) {
                val cached = chapterCache.read(novel.id, chapter.id)
                if (cached != null) {
                    try {
                        chapterStorage.writeToDownloads(cached.copy(isDownloaded = true), targetLocation)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (targetLocation != "INTERNAL") {
                            emitStorageWarning("Failed to write to custom storage ($e). Falling back to internal storage.")
                            targetLocation = "INTERNAL"
                            chapterStorage.writeToDownloads(cached.copy(isDownloaded = true), "INTERNAL")
                        } else {
                            throw e
                        }
                    }
                    val fileHash = storageResolver.sha256(chapter.id)
                    val rowsUpdated = chapterDao.updateTierToDownloaded(
                        novelId = novel.id,
                        id = chapter.id,
                        storageLocation = targetLocation,
                        fileHash = fileHash
                    )
                    if (rowsUpdated > 0) {
                        chapterCache.delete(novel.id, chapter.id)
                    }
                    return@withLeafLock DownloadResult.Success
                }
            }

            null
        }

        if (preFetchResult != null) {
            return@withContext preFetchResult
        }

        // Network fetch via shared in-flight map
        val content = try {
            fetchChapterContentFromNetwork(novel, chapter.id)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val msg = e.message ?: "Unknown error"
            return@withContext when {
                msg.contains("429") || msg.contains("Rate limit", ignoreCase = true) || msg.contains("Too Many Requests", ignoreCase = true) ->
                    DownloadResult.RateLimited(msg)
                e is org.json.JSONException || msg.contains("parse", ignoreCase = true) ->
                    DownloadResult.Parse(msg, e)
                e is IOException ->
                    DownloadResult.Network(msg, e)
                else ->
                    DownloadResult.Network(msg, e)
            }
        }

        // Post-fetch write and CAS under leaf lock
        withLeafLock(novel.id, chapter.id) {
            val currentRow = chapterDao.getChapter(novel.id, chapter.id)
            if (currentRow?.storageTier == "DOWNLOADED") {
                return@withLeafLock DownloadResult.Success
            }

            try {
                chapterStorage.writeToDownloads(content.copy(isDownloaded = true), targetLocation)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val msg = e.message.orEmpty()
                if (msg.contains("ENOSPC", ignoreCase = true) || msg.contains("No space left", ignoreCase = true)) {
                    return@withLeafLock DownloadResult.StorageFull(0L, 0L)
                }
                if (targetLocation != "INTERNAL") {
                    emitStorageWarning("Failed to write to custom storage ($e). Falling back to internal storage.")
                    targetLocation = "INTERNAL"
                    try {
                        chapterStorage.writeToDownloads(content.copy(isDownloaded = true), "INTERNAL")
                    } catch (inner: Exception) {
                        if (inner is CancellationException) throw inner
                        val innerMsg = inner.message.orEmpty()
                        if (innerMsg.contains("ENOSPC", ignoreCase = true) || innerMsg.contains("No space left", ignoreCase = true)) {
                            return@withLeafLock DownloadResult.StorageFull(0L, 0L)
                        }
                        return@withLeafLock DownloadResult.StorageUnavailable("INTERNAL", innerMsg)
                    }
                } else {
                    return@withLeafLock DownloadResult.StorageUnavailable("INTERNAL", msg)
                }
            }

            val fileHash = storageResolver.sha256(chapter.id)
            val rowsUpdated = chapterDao.updateTierToDownloaded(
                novelId = novel.id,
                id = chapter.id,
                storageLocation = targetLocation,
                fileHash = fileHash
            )
            if (rowsUpdated > 0) {
                chapterCache.delete(novel.id, chapter.id)
            }
            DownloadResult.Success
        }
    }

    suspend fun downloadChapterBoolean(novel: Novel, chapter: Chapter): Boolean {
        return downloadChapter(novel, chapter).isSuccess
    }

    /**
     * Deletes downloads for a single novel.
     * Wrapped in NonCancellable + Dispatchers.IO.
     * Never holds coordinator lock while waiting for in-flight tasks.
     * Sets novelId in deletingNovelIds guard while files are being removed.
     * Deletes files at each row's recorded location (including SAF).
     * Resets DOWNLOADED rows to NONE; never touches EPUB or ChapterCache.
     */
    suspend fun deleteNovelDownloads(novelId: String) = withContext(NonCancellable + ioDispatcher) {
        val novel = novelDao.getNovelById(novelId)
        if (novel?.isEpub == true || novelId.startsWith("epub_")) {
            return@withContext
        }
        // 1. Under coordinator lock: set guard & delete queue rows
        downloadCoordinator.mutex.withLock {
            downloadCoordinator.deletingNovelIds.add(novelId)
            downloadQueueDao.deleteForNovel(novelId)
        }
        try {
            // 2. Outside lock: wait for in-flight tasks for this novel
            downloadCoordinator.awaitAllChapterJobsForNovel(novelId)

            // 3. Delete files & reset tiers
            val downloaded = chapterDao.getDownloadedChapters(novelId)
            val locations = downloaded.map { it.storageLocation }.toSet()
            chapterStorage.deleteDownloadsForNovel(novelId, locations)
            chapterDao.deleteNovelDownloads(novelId)
        } finally {
            // 4. Release deletion guard
            downloadCoordinator.deletingNovelIds.remove(novelId)
        }
    }

    /**
     * Deletes all downloads across all novels.
     * Wrapped in NonCancellable + Dispatchers.IO.
     * Never holds coordinator lock while waiting for in-flight tasks.
     * Deletes files at each row's recorded location (including SAF).
     * Resets DOWNLOADED rows to NONE; never touches EPUB or ChapterCache.
     */
    suspend fun deleteAllDownloads(): Int = withContext(NonCancellable + ioDispatcher) {
        // 1. Under coordinator lock: set isDeletingAll guard & clear queue
        downloadCoordinator.mutex.withLock {
            downloadCoordinator.isDeletingAll.incrementAndGet()
            downloadQueueDao.clearQueue()
        }
        try {
            // 2. Outside lock: wait for all in-flight tasks
            downloadCoordinator.awaitAllChapterJobs()

            // 3. Delete files & reset tiers
            val allDownloaded = chapterDao.getAllDownloadedChapters()
            val locations = allDownloaded.map { it.storageLocation }.toSet()
            chapterStorage.deleteAllDownloads(locations)
            chapterDao.deleteAllDownloads()
        } finally {
            // 4. Clear isDeletingAll guard
            downloadCoordinator.isDeletingAll.decrementAndGet()
        }
    }

    /**
     * Clears all cached chapters under cacheDir/chapters.
     * Wrapped in NonCancellable + Dispatchers.IO.
     */
    suspend fun clearChapterCache(): Long = withContext(NonCancellable + ioDispatcher) {
        chapterCache.clear()
    }

    /**
     * Completely removes a novel from Room and all its files on disk (including SAF and cacheDir).
     * Wrapped in NonCancellable + Dispatchers.IO.
     * Never holds coordinator lock while waiting for in-flight tasks.
     */
    suspend fun removeNovelCompletely(novelId: String) = withContext(NonCancellable + ioDispatcher) {
        val novel = novelDao.getNovelById(novelId)
        val isEpub = novel?.isEpub == true || novelId.startsWith("epub_")
        val chapters = chapterDao.getChapters(novelId)
        val locations = chapters.map { it.storageLocation }.toSet()

        downloadCoordinator.mutex.withLock {
            downloadCoordinator.deletingNovelIds.add(novelId)
            downloadQueueDao.deleteForNovel(novelId)
        }
        try {
            downloadCoordinator.awaitAllChapterJobsForNovel(novelId)
            novelDao.deleteById(novelId)
            chapterStorage.removeNovelCompletely(novelId, isEpub = isEpub, locations = locations)
            chapterCache.deleteNovel(novelId)
        } finally {
            downloadCoordinator.deletingNovelIds.remove(novelId)
        }
    }

    suspend fun getCacheSizeBytes(): Long = chapterCache.sizeBytes()

    suspend fun getDownloadsSizeBytes(): Long = chapterStorage.getDownloadsSizeBytes()

    suspend fun getEpubsSizeBytes(): Long = chapterStorage.getEpubsSizeBytes()

    suspend fun isDownloaded(novelId: String, chapterId: String): Boolean = withContext(ioDispatcher) {
        val tier = chapterDao.getChapter(novelId, chapterId)?.storageTier
        tier == "DOWNLOADED" || tier == "EPUB"
    }

    suspend fun isCached(novelId: String, chapterId: String): Boolean = withContext(ioDispatcher) {
        chapterCache.contains(novelId, chapterId)
    }
}
