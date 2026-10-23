package com.example.data.local.storage

import android.content.Context
import com.example.data.model.ChapterContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class ChapterReadException(val novelId: String, val chapterId: String, cause: Throwable) :
    IOException("Failed to read chapter file for novel $novelId, chapter $chapterId", cause)

/**
 * Storage facade for chapter content files.
 * Delegates all underlying file I/O to a [ChapterFileStore] (internal disk or SAF)
 * and untracked reader caching to [ChapterCache].
 *
 * Implements:
 * - Atomic writes via temp file and rename (Rule 5)
 * - File operations strictly off the main thread on Dispatchers.IO (Rule 11)
 * - Size calculation for cache, downloads, and epubs
 * - Multi-location support (INTERNAL and SAF)
 */
class ChapterStorage(
    val resolver: StorageResolver,
    val fileStore: ChapterFileStore,
    val chapterCache: ChapterCache = ChapterCache(resolver.cacheDir),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    constructor(
        resolver: StorageResolver,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        resolver = resolver,
        fileStore = InternalChapterFileStore(resolver, ioDispatcher),
        chapterCache = ChapterCache(resolver.cacheDir, ioDispatcher),
        ioDispatcher = ioDispatcher
    )

    constructor(context: Context) : this(
        resolver = StorageResolver(context),
        fileStore = DefaultChapterFileStore(
            internalStore = InternalChapterFileStore(StorageResolver(context)),
            safStore = SafChapterFileStore(context, StorageResolver(context))
        ),
        chapterCache = ChapterCache(context.cacheDir)
    )

    constructor(resolver: StorageResolver, context: Context) : this(
        resolver = resolver,
        fileStore = DefaultChapterFileStore(
            internalStore = InternalChapterFileStore(resolver),
            safStore = SafChapterFileStore(context, resolver)
        ),
        chapterCache = ChapterCache(resolver.cacheDir)
    )

    constructor(
        resolver: StorageResolver,
        context: Context,
        chapterCache: ChapterCache
    ) : this(
        resolver = resolver,
        fileStore = DefaultChapterFileStore(
            internalStore = InternalChapterFileStore(resolver),
            safStore = SafChapterFileStore(context, resolver)
        ),
        chapterCache = chapterCache
    )

    constructor(
        resolver: StorageResolver,
        fileStore: ChapterFileStore,
        chapterCache: ChapterCache
    ) : this(
        resolver = resolver,
        fileStore = fileStore,
        chapterCache = chapterCache,
        ioDispatcher = Dispatchers.IO
    )

    val isCalculatingDownloadsSize: Boolean
        get() = (fileStore as? DefaultChapterFileStore)?.safStore?.isCalculatingDownloadsSize ?: false

    suspend fun read(
        novelId: String,
        chapterId: String,
        tier: String,
        location: String = "INTERNAL"
    ): ChapterContent? = withContext(ioDispatcher) {
        fileStore.read(novelId, chapterId, tier, location)
    }

    suspend fun writeToDownloads(content: ChapterContent, location: String = "INTERNAL"): File? = withContext(ioDispatcher) {
        fileStore.writeDownload(content, location)
        if (location.startsWith("SAF:")) {
            null
        } else {
            resolver.getDownloadChapterFile(content.novelId, content.chapterId)
        }
    }

    suspend fun writeToEpub(content: ChapterContent): File = withContext(ioDispatcher) {
        fileStore.writeEpub(content) ?: resolver.getEpubChapterFile(content.novelId, content.chapterId)
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

    suspend fun deleteAllDownloads(
        locations: Set<String> = setOf("INTERNAL")
    ): Boolean = withContext(ioDispatcher) {
        fileStore.deleteAllDownloads(locations)
    }

    suspend fun clearChapterCache(): Long = chapterCache.clear()

    suspend fun removeNovelCompletely(
        novelId: String,
        isEpub: Boolean = false,
        locations: Set<String> = setOf("INTERNAL")
    ): Unit {
        withContext(ioDispatcher) {
            fileStore.removeNovelCompletely(novelId, isEpub, locations)
        }
    }

    suspend fun getCacheSizeBytes(): Long = chapterCache.sizeBytes()

    suspend fun getDownloadsSizeBytes(): Long = withContext(ioDispatcher) {
        fileStore.getDownloadsSizeBytes()
    }

    suspend fun getEpubsSizeBytes(): Long = withContext(ioDispatcher) {
        fileStore.getEpubsSizeBytes()
    }

    fun isLocationReachable(location: String): Boolean = fileStore.isLocationReachable(location)

    fun isLocationWritable(location: String): Boolean = fileStore.isLocationWritable(location)
}
