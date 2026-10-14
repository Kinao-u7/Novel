package com.example.data.local.storage

import android.content.Context
import com.example.data.model.ChapterContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Storage facade for chapter content files.
 * Delegates all underlying file I/O to a [ChapterFileStore] (internal disk or SAF).
 *
 * Implements:
 * - Atomic writes via temp file and rename (Rule 5)
 * - File operations strictly off the main thread on Dispatchers.IO (Rule 11)
 * - Promotion of cached files to downloads (no re-downloading)
 * - Size calculation for cache, downloads, and epubs
 * - Multi-location support (INTERNAL and SAF)
 */
class ChapterStorage(
    val resolver: StorageResolver,
    val fileStore: ChapterFileStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    constructor(
        resolver: StorageResolver,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        resolver = resolver,
        fileStore = InternalChapterFileStore(resolver, ioDispatcher),
        ioDispatcher = ioDispatcher
    )

    constructor(context: Context) : this(
        resolver = StorageResolver(context),
        fileStore = DefaultChapterFileStore(
            internalStore = InternalChapterFileStore(StorageResolver(context)),
            safStore = SafChapterFileStore(context, StorageResolver(context))
        )
    )

    constructor(resolver: StorageResolver, context: Context) : this(
        resolver = resolver,
        fileStore = DefaultChapterFileStore(
            internalStore = InternalChapterFileStore(resolver),
            safStore = SafChapterFileStore(context, resolver)
        )
    )

    suspend fun read(
        novelId: String,
        chapterId: String,
        tier: String,
        location: String = "INTERNAL"
    ): ChapterContent? = withContext(ioDispatcher) {
        fileStore.read(novelId, chapterId, tier, location)
    }

    suspend fun isCached(novelId: String, chapterId: String): Boolean = withContext(ioDispatcher) {
        val file = resolver.getCacheChapterFile(novelId, chapterId)
        file.exists() && file.isFile && file.length() > 0L
    }

    suspend fun isDownloaded(novelId: String, chapterId: String): Boolean = withContext(ioDispatcher) {
        val file = resolver.getDownloadChapterFile(novelId, chapterId)
        file.exists() && file.isFile && file.length() > 0L
    }

    suspend fun writeToCache(content: ChapterContent): File = withContext(ioDispatcher) {
        fileStore.writeCache(content) ?: resolver.getCacheChapterFile(content.novelId, content.chapterId)
    }

    suspend fun writeToDownloads(content: ChapterContent, location: String = "INTERNAL"): File = withContext(ioDispatcher) {
        fileStore.writeDownload(content, location)
        resolver.getDownloadChapterFile(content.novelId, content.chapterId)
    }

    suspend fun writeToEpub(content: ChapterContent): File = withContext(ioDispatcher) {
        fileStore.writeEpub(content) ?: resolver.getEpubChapterFile(content.novelId, content.chapterId)
    }

    suspend fun promoteCacheToDownload(
        novelId: String,
        chapterId: String,
        targetLocation: String = "INTERNAL"
    ): Boolean = withContext(ioDispatcher) {
        fileStore.promoteCacheToDownload(novelId, chapterId, targetLocation)
    }

    suspend fun deleteCacheChapter(novelId: String, chapterId: String): Boolean = withContext(ioDispatcher) {
        fileStore.deleteChapter(novelId, chapterId, "CACHE", "INTERNAL")
    }

    suspend fun deleteDownloadChapter(
        novelId: String,
        chapterId: String,
        location: String = "INTERNAL"
    ): Boolean = withContext(ioDispatcher) {
        fileStore.deleteChapter(novelId, chapterId, "DOWNLOADED", location)
    }

    suspend fun deleteDownloadsForNovel(
        novelId: String,
        locations: Set<String> = setOf("INTERNAL")
    ): Boolean = withContext(ioDispatcher) {
        fileStore.deleteNovelDownloads(novelId, locations)
    }

    suspend fun deleteCacheForNovel(novelId: String): Boolean = withContext(ioDispatcher) {
        fileStore.deleteNovelCache(novelId)
    }

    suspend fun deleteAllDownloads(
        locations: Set<String> = setOf("INTERNAL")
    ): Boolean = withContext(ioDispatcher) {
        fileStore.deleteAllDownloads(locations)
    }

    suspend fun clearChapterCache(): Long = withContext(ioDispatcher) {
        fileStore.clearCache()
    }

    suspend fun removeNovelCompletely(
        novelId: String,
        isEpub: Boolean = false,
        locations: Set<String> = setOf("INTERNAL")
    ) = withContext(ioDispatcher) {
        fileStore.removeNovelCompletely(novelId, isEpub, locations)
    }

    suspend fun getCacheSizeBytes(): Long = withContext(ioDispatcher) {
        fileStore.getCacheSizeBytes()
    }

    suspend fun getDownloadsSizeBytes(): Long = withContext(ioDispatcher) {
        fileStore.getDownloadsSizeBytes()
    }

    suspend fun getEpubsSizeBytes(): Long = withContext(ioDispatcher) {
        fileStore.getEpubsSizeBytes()
    }

    fun isLocationReachable(location: String): Boolean = fileStore.isLocationReachable(location)

    fun isLocationWritable(location: String): Boolean = fileStore.isLocationWritable(location)
}
