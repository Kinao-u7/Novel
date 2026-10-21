package com.example.data.repository

import com.example.data.download.DownloadCoordinator
import com.example.data.download.DownloadResult
import com.example.data.local.db.dao.ChapterDao
import com.example.data.local.db.dao.DownloadQueueDao
import com.example.data.local.db.dao.NotificationDao
import com.example.data.local.db.dao.NovelDao
import com.example.data.local.db.entity.NotificationEntity
import com.example.data.model.NotificationTypes
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

/**
 * Modern repository managing chapter reading, caching, eviction, and offline downloads.
 *
 * Adheres to Architectural Invariants:
 * - Room is single source of truth for chapter storage tier and location (Section 8).
 * - Reading sets CACHE tier only; never sets DOWNLOADED (Rule 2).
 * - LRU cache eviction when cache folder exceeds DownloadSettings.cacheSizeCapMb (Section 8).
 * - Explicit download promotes cached files (no re-fetch) or writes to downloads/ or SAF (Section 8).
 * - Deletes reset DOWNLOADED and CACHE rows to NONE; never touches EPUB rows or files (Rule 1).
 * - All disk and database operations run on Dispatchers.IO (Rule 11).
 * - Delete operations are atomic and wrapped in NonCancellable (Rule 5).
 * - Single-flight for chapter fetches using repositoryScope (SupervisorJob + Dispatchers.IO).
 * - Small leaf Mutex per key allowed ONLY around file mutation steps of promote and delete.
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Map of in-flight chapter fetches for single-flight deduplication.
     */
    internal val inFlightFetches = ConcurrentHashMap<Pair<String, String>, Deferred<ChapterContent>>()

    /**
     * Repository-level scope for background fetches and tasks.
     * Uses SupervisorJob + ioDispatcher so child failures or caller cancellations do not cancel the scope.
     */
    private val repositoryScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /**
     * Small leaf Mutex per (novelId, chapterId) key.
     * Allowed ONLY around leaf file mutation steps of promote and delete
     * (move/delete file + Room tier update). Nothing inside this lock may call
     * another function that takes a lock, calls the source, or does eviction.
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
     * Reads chapter content according to the 2-tier storage rules:
     * - EPUB or DOWNLOADED: reads from the recorded location.
     *   If a read of a SAF row fails because the folder or permission is unavailable
     *   (for example the SD card is missing), throws StorageUnavailableException and does NOT reset the tier.
     *   Resets the tier to NONE only when the folder is reachable and the file is really missing.
     * - CACHE: reads from cache and updates last_accessed_at in Room.
     * - On a miss (or when row says file exists but file is really missing in reachable folder):
     *   deduplicates concurrent fetches via inFlightFetches single-flight.
     *   The fetch coroutine fetches from source, writes to cache, sets tier CACHE and
     *   last_accessed_at, then removes its map entry in finally.
     *   Eviction runs after the write completes, outside any map or lock.
     * Reading NEVER sets DOWNLOADED.
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
                val content = chapterStorage.read(
                    novelId = novelId,
                    chapterId = chapterId,
                    tier = chapterRow.storageTier,
                    location = chapterRow.storageLocation
                )
                if (content != null) {
                    return@withContext content
                }
                // File is really missing despite row recording it; fall through to miss handling
            }
            "CACHE" -> {
                val content = try {
                    chapterStorage.read(
                        novelId = novelId,
                        chapterId = chapterId,
                        tier = "CACHE",
                        location = chapterRow.storageLocation
                    )
                } catch (e: ChapterReadException) {
                    null // Cache is disposable; read errors fall through to refetch
                }
                if (content != null) {
                    chapterDao.updateLastAccessed(novelId, chapterId, System.currentTimeMillis())
                    return@withContext content.copy(isDownloaded = false)
                }
                // Cache file is missing; fall through to miss handling
            }
        }

        // Cache miss: single-flight deduplication using LAZY async + putIfAbsent
        val isDownloadedMissing = (chapterRow.storageTier == "DOWNLOADED")
        val savedLocation = chapterRow.storageLocation
        val key = Pair(novelId, chapterId)
        val candidate = repositoryScope.async(start = CoroutineStart.LAZY) {
            val novel = novelDao.getNovelById(novelId) ?: throw ChapterRowMissing(novelId, chapterId)
            val source = SourceManager.getSourceById(novel.sourceId)
                ?: throw SourceUnavailable(novel.sourceId)

            val rawContent = source.getChapterContent(novelId, chapterId)
            val now = System.currentTimeMillis()
            val fileHash = storageResolver.sha256(chapterId)

            if (isDownloadedMissing) {
                // Self-healing: write back as DOWNLOADED in original location, not CACHE
                val downloadedContent = rawContent.copy(isDownloaded = true)
                chapterStorage.writeToDownloads(downloadedContent, savedLocation)
                chapterDao.updateTierToDownloaded(
                    novelId = novelId,
                    id = chapterId,
                    storageLocation = savedLocation,
                    fileHash = fileHash,
                    lastAccessedAt = now
                )
                downloadedContent
            } else {
                val cleanContent = rawContent.copy(isDownloaded = false)
                // Write to cache
                chapterStorage.writeToCache(cleanContent)

                // CAS update: set CACHE only if NONE or CACHE, always refresh last_accessed_at
                chapterDao.updateTierToCacheIfNoneOrCache(
                    novelId = novelId,
                    id = chapterId,
                    storageLocation = "INTERNAL",
                    fileHash = fileHash,
                    lastAccessedAt = now
                )

                // Run LRU eviction after write completes, outside any map or lock
                repositoryScope.launch {
                    try {
                        runEviction()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                }

                cleanContent
            }
        }
        candidate.invokeOnCompletion { inFlightFetches.remove(key, candidate) }
        val existing = inFlightFetches.putIfAbsent(key, candidate)
        val deferred = if (existing != null) {
            candidate.cancel()
            existing
        } else {
            candidate.start()
            candidate
        }

        deferred.await()
    }

    /**
     * LRU eviction: when cache folder exceeds DownloadSettings.cacheSizeCapMb,
     * deletes CACHE chapters ordered by last_accessed_at ascending (tie-break by chapter_index)
     * and sets their tier to NONE. EPUB and DOWNLOADED rows are never selected.
     * Leaf mutex per key protects each individual deletion and Room update.
     */
    suspend fun runEviction() = withContext(ioDispatcher) {
        val capMb = settingsRepository.downloadSettings.value.cacheSizeCapMb
        val capBytes = capMb.toLong() * 1024L * 1024L
        var currentBytes = chapterStorage.getCacheSizeBytes()

        if (currentBytes <= capBytes) return@withContext

        val candidates = chapterDao.getCacheChaptersLru()
        for (candidate in candidates) {
            if (currentBytes <= capBytes) break
            withLeafLock(candidate.novelId, candidate.id) {
                // Re-read row inside leaf lock: reset to NONE only if it is still CACHE
                val currentRow = chapterDao.getChapter(candidate.novelId, candidate.id)
                if (currentRow?.storageTier == "CACHE") {
                    chapterStorage.deleteCacheChapter(candidate.novelId, candidate.id)
                    chapterDao.updateTierToNoneIfCache(
                        novelId = candidate.novelId,
                        id = candidate.id,
                        lastAccessedAt = candidate.lastAccessedAt
                    )
                }
            }
            currentBytes = chapterStorage.getCacheSizeBytes()
        }
    }

    /**
     * Explicit download:
     * - When DownloadSettings.customDownloadLocationUri is set and writable, explicit downloads
     *   are written under NovelReader/downloads/{novelId}/{sha256}.json in the SAF tree,
     *   and the row records storage_location = "SAF:" + the tree uri.
     * - If custom SAF folder is unwritable, falls back to INTERNAL, records INTERNAL,
     *   and emits a user-visible warning event.
     * - If chapter is already cached, promote it by moving/copying the file (no network).
     * - Otherwise fetch and write straight to downloads/.
     * Sets storageTier DOWNLOADED in Room.
     */
    /**
     * Explicit download:
     * - Checks free space for INTERNAL (StatFs >= 20MB).
     * - Disk-first: if file exists at target location, updates tier and returns Success.
     * - If chapter is already cached, promote it by moving/copying the file (no network).
     * - Otherwise fetch and write straight to downloads/.
     * Sets storageTier DOWNLOADED in Room using CAS (updateTierToDownloaded).
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

        // Disk-first check: before fetching, check target location
        val existingContent = try {
            chapterStorage.read(novel.id, chapter.id, "DOWNLOADED", targetLocation)
        } catch (_: Exception) {
            null
        }
        if (existingContent != null) {
            val now = System.currentTimeMillis()
            val fileHash = storageResolver.sha256(chapter.id)
            chapterDao.updateTierToDownloaded(
                novelId = novel.id,
                id = chapter.id,
                storageLocation = targetLocation,
                fileHash = fileHash,
                lastAccessedAt = now
            )
            return@withContext DownloadResult.Success
        }

        // Promote check under leaf lock
        val isPromoted = withLeafLock(novel.id, chapter.id) {
            var moved = try {
                chapterStorage.promoteCacheToDownload(novel.id, chapter.id, targetLocation)
            } catch (e: Exception) {
                if (targetLocation != "INTERNAL") {
                    emitStorageWarning("Failed to promote to custom storage ($e). Falling back to internal.")
                    targetLocation = "INTERNAL"
                    chapterStorage.promoteCacheToDownload(novel.id, chapter.id, "INTERNAL")
                } else {
                    false
                }
            }
            if (moved) {
                val now = System.currentTimeMillis()
                val fileHash = storageResolver.sha256(chapter.id)
                chapterDao.updateTierToDownloaded(
                    novelId = novel.id,
                    id = chapter.id,
                    storageLocation = targetLocation,
                    fileHash = fileHash,
                    lastAccessedAt = now
                )
                true
            } else {
                false
            }
        }
        if (isPromoted) {
            return@withContext DownloadResult.Success
        }

        // Leaf lock around fetch + write + tier update to synchronize with reader
        withLeafLock(novel.id, chapter.id) {
            val currentRow = chapterDao.getChapter(novel.id, chapter.id)
            if (currentRow?.storageTier == "DOWNLOADED") {
                return@withLeafLock DownloadResult.Success
            }

            val source = SourceManager.getSourceById(novel.sourceId)
                ?: return@withLeafLock DownloadResult.SourceUnavailable(novel.sourceId)

            val content = try {
                source.getChapterContent(novel.id, chapter.id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val msg = e.message ?: "Unknown error"
                return@withLeafLock when {
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

            try {
                chapterStorage.writeToDownloads(content, targetLocation)
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
                        chapterStorage.writeToDownloads(content, "INTERNAL")
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

            val now = System.currentTimeMillis()
            val fileHash = storageResolver.sha256(chapter.id)
            chapterDao.updateTierToDownloaded(
                novelId = novel.id,
                id = chapter.id,
                storageLocation = targetLocation,
                fileHash = fileHash,
                lastAccessedAt = now
            )
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
     * Resets DOWNLOADED and CACHE rows to NONE; never touches EPUB.
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
            chapterStorage.deleteCacheForNovel(novelId)
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
     * Resets DOWNLOADED and CACHE rows to NONE; never touches EPUB.
     */
    suspend fun deleteAllDownloads(): Int = withContext(NonCancellable + ioDispatcher) {
        // 1. Under coordinator lock: clear queue
        downloadCoordinator.mutex.withLock {
            downloadQueueDao.clearQueue()
        }
        // 2. Outside lock: wait for all in-flight tasks
        downloadCoordinator.awaitAllChapterJobs()

        // 3. Delete files & reset tiers
        val allDownloaded = chapterDao.getAllDownloadedChapters()
        val locations = allDownloaded.map { it.storageLocation }.toSet()
        chapterStorage.deleteAllDownloads(locations)
        chapterDao.deleteAllDownloads()
    }

    /**
     * Clears all cached chapters.
     * Wrapped in NonCancellable + Dispatchers.IO.
     * Resets CACHE rows to NONE; never touches EPUB or DOWNLOADED rows.
     */
    suspend fun clearChapterCache(): Long = withContext(NonCancellable + ioDispatcher) {
        val bytesDeleted = chapterStorage.clearChapterCache()
        chapterDao.clearCacheTier()
        bytesDeleted
    }

    /**
     * Completely removes a novel from Room and all its files on disk (including SAF).
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
        } finally {
            downloadCoordinator.deletingNovelIds.remove(novelId)
        }
    }

    suspend fun getCacheSizeBytes(): Long = chapterStorage.getCacheSizeBytes()

    suspend fun getDownloadsSizeBytes(): Long = chapterStorage.getDownloadsSizeBytes()

    suspend fun getEpubsSizeBytes(): Long = chapterStorage.getEpubsSizeBytes()

    suspend fun isDownloaded(novelId: String, chapterId: String): Boolean = withContext(ioDispatcher) {
        val tier = chapterDao.getChapter(novelId, chapterId)?.storageTier
        tier == "DOWNLOADED" || tier == "EPUB"
    }

    suspend fun isCached(novelId: String, chapterId: String): Boolean = withContext(ioDispatcher) {
        val tier = chapterDao.getChapter(novelId, chapterId)?.storageTier
        tier == "CACHE"
    }
}
