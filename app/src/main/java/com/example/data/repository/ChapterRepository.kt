package com.example.data.repository

import com.example.data.local.db.dao.ChapterDao
import com.example.data.local.db.dao.NovelDao
import com.example.data.local.db.entity.ChapterEntity
import com.example.data.local.storage.ChapterStorage
import com.example.data.local.storage.StorageResolver
import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.Novel
import com.example.data.source.SourceManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Modern repository managing chapter reading, caching, eviction, and offline downloads.
 *
 * Adheres to Architectural Invariants:
 * - Room is single source of truth for chapter storage tier and location (Section 8).
 * - Reading sets CACHE tier only; never sets DOWNLOADED (Rule 2).
 * - LRU cache eviction when cache folder exceeds DownloadSettings.cacheSizeCapMb (Section 8).
 * - Explicit download promotes cached files (no re-fetch) or writes straight to downloads/ (Section 8).
 * - Deletes reset DOWNLOADED and CACHE rows to NONE; never touches EPUB rows or files (Rule 1).
 * - All disk and database operations run on Dispatchers.IO (Rule 11).
 * - Delete operations are atomic and wrapped in NonCancellable (Rule 5).
 */
class ChapterRepository(
    private val chapterDao: ChapterDao,
    private val novelDao: NovelDao,
    val chapterStorage: ChapterStorage,
    val storageResolver: StorageResolver,
    private val settingsRepository: SettingsRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Reads chapter content according to the 2-tier storage rules:
     * - EPUB or DOWNLOADED: reads from the recorded location.
     * - CACHE: reads from cache and updates last_accessed_at in Room.
     * - On a miss (or when row says file exists but file is missing/corrupted):
     *   resets tier to NONE, fetches from source, writes to cache, sets tier CACHE and
     *   last_accessed_at, then runs LRU eviction.
     * Reading NEVER sets DOWNLOADED.
     */
    suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent = withContext(ioDispatcher) {
        val chapterRow = chapterDao.getChapter(novelId, chapterId)

        if (chapterRow != null) {
            when (chapterRow.storageTier) {
                "EPUB", "DOWNLOADED" -> {
                    val content = chapterStorage.read(novelId, chapterId, chapterRow.storageTier)
                    if (content != null) {
                        return@withContext content
                    }
                    // File is missing despite row recording it; fall through to miss handling
                }
                "CACHE" -> {
                    val content = chapterStorage.read(novelId, chapterId, "CACHE")
                    if (content != null) {
                        chapterDao.updateLastAccessed(novelId, chapterId, System.currentTimeMillis())
                        return@withContext content.copy(isDownloaded = false)
                    }
                    // Cache file is missing; fall through to miss handling
                }
            }
        }

        // On miss (file absent or corrupted or tier NONE):
        // 1. Reset tier to NONE if previously recorded as having storage
        if (chapterRow != null && chapterRow.storageTier != "NONE") {
            chapterDao.updateStorageTier(
                novelId = novelId,
                id = chapterId,
                storageTier = "NONE",
                storageLocation = "INTERNAL",
                fileHash = "",
                lastAccessedAt = 0L
            )
        }

        // 2. Fetch from source
        val novel = novelDao.getNovelById(novelId)
        val source = if (novel != null) {
            SourceManager.getSourceById(novel.sourceId) ?: SourceManager.getActiveSource()
        } else {
            SourceManager.getActiveSource()
        }

        val rawContent = source.getChapterContent(novelId, chapterId)
        val cleanContent = rawContent.copy(isDownloaded = false)

        // 3. Write to cache
        chapterStorage.writeToCache(cleanContent)

        // 4. Set tier CACHE and last_accessed_at in Room
        val now = System.currentTimeMillis()
        val fileHash = storageResolver.sha256(chapterId)
        if (chapterRow != null) {
            chapterDao.updateStorageTier(
                novelId = novelId,
                id = chapterId,
                storageTier = "CACHE",
                storageLocation = "INTERNAL",
                fileHash = fileHash,
                lastAccessedAt = now
            )
        } else {
            chapterDao.insertIgnore(
                ChapterEntity(
                    novelId = novelId,
                    id = chapterId,
                    title = cleanContent.title,
                    chapterIndex = 1,
                    storageTier = "CACHE",
                    storageLocation = "INTERNAL",
                    fileHash = fileHash,
                    lastAccessedAt = now
                )
            )
        }

        // 5. Run LRU eviction
        runEviction()

        // Reading NEVER sets DOWNLOADED
        cleanContent
    }

    /**
     * LRU eviction: when cache folder exceeds DownloadSettings.cacheSizeCapMb,
     * deletes CACHE chapters ordered by last_accessed_at ascending (tie-break by chapter_index)
     * and sets their tier to NONE. EPUB and DOWNLOADED rows are never selected.
     */
    suspend fun runEviction() = withContext(ioDispatcher) {
        val capMb = settingsRepository.downloadSettings.value.cacheSizeCapMb
        val capBytes = capMb.toLong() * 1024L * 1024L
        var currentBytes = chapterStorage.getCacheSizeBytes()

        if (currentBytes <= capBytes) return@withContext

        val candidates = chapterDao.getCacheChaptersLru()
        for (candidate in candidates) {
            if (currentBytes <= capBytes) break
            chapterStorage.deleteCacheChapter(candidate.novelId, candidate.id)
            chapterDao.updateStorageTier(
                novelId = candidate.novelId,
                id = candidate.id,
                storageTier = "NONE",
                storageLocation = "INTERNAL",
                fileHash = "",
                lastAccessedAt = candidate.lastAccessedAt
            )
            currentBytes = chapterStorage.getCacheSizeBytes()
        }
    }

    /**
     * Explicit download:
     * - If chapter is already cached, promote it by moving the file (no network).
     * - Otherwise fetch and write straight to downloads/.
     * Sets storageTier DOWNLOADED and storageLocation INTERNAL in Room.
     */
    suspend fun downloadChapter(novel: Novel, chapter: Chapter): Boolean = withContext(ioDispatcher) {
        val isPromoted = chapterStorage.promoteCacheToDownload(novel.id, chapter.id)
        if (isPromoted) {
            val now = System.currentTimeMillis()
            val fileHash = storageResolver.sha256(chapter.id)
            chapterDao.updateStorageTier(
                novelId = novel.id,
                id = chapter.id,
                storageTier = "DOWNLOADED",
                storageLocation = "INTERNAL",
                fileHash = fileHash,
                lastAccessedAt = now
            )
            return@withContext true
        }

        // Not cached: fetch from remote source and write straight to downloads/
        val source = SourceManager.getSourceById(novel.sourceId) ?: SourceManager.getActiveSource()
        val content = try {
            source.getChapterContent(novel.id, chapter.id)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }

        chapterStorage.writeToDownloads(content)
        val now = System.currentTimeMillis()
        val fileHash = storageResolver.sha256(chapter.id)
        chapterDao.updateStorageTier(
            novelId = novel.id,
            id = chapter.id,
            storageTier = "DOWNLOADED",
            storageLocation = "INTERNAL",
            fileHash = fileHash,
            lastAccessedAt = now
        )
        true
    }

    /**
     * Deletes downloads for a single novel.
     * Wrapped in NonCancellable + Dispatchers.IO.
     * Resets DOWNLOADED and CACHE rows to NONE; never touches EPUB.
     */
    suspend fun deleteNovelDownloads(novelId: String) = withContext(NonCancellable + ioDispatcher) {
        val novel = novelDao.getNovelById(novelId)
        if (novel?.isEpub == true || novelId.startsWith("epub_")) {
            return@withContext
        }
        chapterStorage.deleteDownloadsForNovel(novelId)
        chapterStorage.deleteCacheForNovel(novelId)
        chapterDao.deleteNovelDownloads(novelId)
    }

    /**
     * Deletes all downloads across all novels.
     * Wrapped in NonCancellable + Dispatchers.IO.
     * Resets DOWNLOADED and CACHE rows to NONE; never touches EPUB.
     */
    suspend fun deleteAllDownloads(): Int = withContext(NonCancellable + ioDispatcher) {
        chapterStorage.deleteAllDownloads()
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
     * Completely removes a novel from Room and all its files on disk.
     * Wrapped in NonCancellable + Dispatchers.IO.
     */
    suspend fun removeNovelCompletely(novelId: String) = withContext(NonCancellable + ioDispatcher) {
        novelDao.deleteById(novelId)
        chapterStorage.removeNovelCompletely(novelId)
    }

    suspend fun getCacheSizeBytes(): Long = chapterStorage.getCacheSizeBytes()

    suspend fun getDownloadsSizeBytes(): Long = chapterStorage.getDownloadsSizeBytes()

    suspend fun getEpubsSizeBytes(): Long = chapterStorage.getEpubsSizeBytes()
}
