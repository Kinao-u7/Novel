package com.example.data.local.storage

import android.content.Context
import com.example.data.model.ChapterContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Low-level storage engine for chapter content files.
 *
 * Implements:
 * - Atomic writes via temp file and rename (Rule 5)
 * - File operations strictly off the main thread on Dispatchers.IO (Rule 11)
 * - Promotion of cached files to downloads (no re-downloading)
 * - Size calculation for cache, downloads, and epubs
 */
class ChapterStorage(
    val resolver: StorageResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    constructor(context: Context) : this(StorageResolver(context))

    suspend fun read(novelId: String, chapterId: String, tier: String): ChapterContent? = withContext(ioDispatcher) {
        val file = when (tier) {
            "CACHE" -> resolver.getCacheChapterFile(novelId, chapterId)
            "DOWNLOADED" -> resolver.getDownloadChapterFile(novelId, chapterId)
            "EPUB" -> resolver.getEpubChapterFile(novelId, chapterId)
            else -> return@withContext null
        }
        if (!file.exists() || !file.isFile || file.length() == 0L) {
            return@withContext null
        }
        try {
            val text = file.readText(Charsets.UTF_8)
            val json = JSONObject(text)
            val pArr = json.getJSONArray("paragraphs")
            val paragraphs = ArrayList<String>(pArr.length())
            for (i in 0 until pArr.length()) {
                paragraphs.add(pArr.getString(i))
            }
            ChapterContent(
                chapterId = json.getString("chapterId"),
                novelId = json.getString("novelId"),
                title = json.getString("title"),
                paragraphs = paragraphs,
                isDownloaded = (tier == "DOWNLOADED" || tier == "EPUB")
            )
        } catch (e: Exception) {
            // Corrupt file - remove it so next access can fetch cleanly
            file.delete()
            null
        }
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
        val file = resolver.getCacheChapterFile(content.novelId, content.chapterId)
        atomicWrite(file, serialize(content))
        file
    }

    suspend fun writeToDownloads(content: ChapterContent): File = withContext(ioDispatcher) {
        val file = resolver.getDownloadChapterFile(content.novelId, content.chapterId)
        atomicWrite(file, serialize(content.copy(isDownloaded = true)))
        file
    }

    suspend fun writeToEpub(content: ChapterContent): File = withContext(ioDispatcher) {
        val file = resolver.getEpubChapterFile(content.novelId, content.chapterId)
        atomicWrite(file, serialize(content.copy(isDownloaded = true)))
        file
    }

    /**
     * Promotes a cached chapter file to download location by moving the file (no network).
     * Returns true if file was present and moved, false otherwise.
     */
    suspend fun promoteCacheToDownload(novelId: String, chapterId: String): Boolean = withContext(ioDispatcher) {
        val cacheFile = resolver.getCacheChapterFile(novelId, chapterId)
        if (!cacheFile.exists() || !cacheFile.isFile) {
            return@withContext false
        }
        val downloadFile = resolver.getDownloadChapterFile(novelId, chapterId)
        val parent = downloadFile.parentFile ?: throw IOException("Missing parent dir for $downloadFile")
        if (!parent.exists()) {
            parent.mkdirs()
        }
        val moved = cacheFile.renameTo(downloadFile)
        if (!moved) {
            cacheFile.copyTo(downloadFile, overwrite = true)
            cacheFile.delete()
        }
        // Cleanup novel cache directory if empty
        val novelCacheDir = resolver.getNovelCacheDir(novelId)
        if (novelCacheDir.exists() && novelCacheDir.list()?.isEmpty() == true) {
            novelCacheDir.delete()
        }
        true
    }

    suspend fun deleteCacheChapter(novelId: String, chapterId: String): Boolean = withContext(ioDispatcher) {
        val file = resolver.getCacheChapterFile(novelId, chapterId)
        if (file.exists()) file.delete() else false
    }

    suspend fun deleteDownloadChapter(novelId: String, chapterId: String): Boolean = withContext(ioDispatcher) {
        val file = resolver.getDownloadChapterFile(novelId, chapterId)
        if (file.exists()) file.delete() else false
    }

    suspend fun deleteDownloadsForNovel(novelId: String): Boolean = withContext(ioDispatcher) {
        val dir = resolver.getNovelDownloadsDir(novelId)
        if (dir.exists()) dir.deleteRecursively() else false
    }

    suspend fun deleteCacheForNovel(novelId: String): Boolean = withContext(ioDispatcher) {
        val dir = resolver.getNovelCacheDir(novelId)
        if (dir.exists()) dir.deleteRecursively() else false
    }

    suspend fun deleteAllDownloads(): Boolean = withContext(ioDispatcher) {
        val dir = resolver.getDownloadsDir()
        if (dir.exists()) dir.deleteRecursively() else false
    }

    suspend fun clearChapterCache(): Long = withContext(ioDispatcher) {
        val dir = resolver.getCacheChaptersDir()
        val before = getFolderSize(dir)
        if (dir.exists()) {
            dir.deleteRecursively()
            dir.mkdirs()
        }
        before
    }

    suspend fun removeNovelCompletely(novelId: String) = withContext(ioDispatcher) {
        deleteDownloadsForNovel(novelId)
        deleteCacheForNovel(novelId)
        val epubDir = resolver.getNovelEpubDir(novelId)
        if (epubDir.exists()) epubDir.deleteRecursively()
        val coverFile = resolver.getCoverFile(novelId)
        if (coverFile.exists()) coverFile.delete()
        val originalEpub = resolver.getOriginalEpubFile(novelId)
        if (originalEpub.exists()) originalEpub.delete()
    }

    suspend fun getCacheSizeBytes(): Long = withContext(ioDispatcher) {
        getFolderSize(resolver.getCacheChaptersDir())
    }

    suspend fun getDownloadsSizeBytes(): Long = withContext(ioDispatcher) {
        getFolderSize(resolver.getDownloadsDir())
    }

    suspend fun getEpubsSizeBytes(): Long = withContext(ioDispatcher) {
        getFolderSize(resolver.getEpubsDir()) + getFolderSize(resolver.getOriginalEpubsDir())
    }

    private fun serialize(content: ChapterContent): String {
        val json = JSONObject().apply {
            put("chapterId", content.chapterId)
            put("novelId", content.novelId)
            put("title", content.title)
            put("paragraphs", JSONArray(content.paragraphs))
        }
        return json.toString()
    }

    private fun atomicWrite(targetFile: File, contentString: String) {
        val parent = targetFile.parentFile ?: throw IOException("Missing parent dir for $targetFile")
        if (!parent.exists()) {
            parent.mkdirs()
        }
        val tempFile = File(
            parent,
            "${targetFile.name}.${System.currentTimeMillis()}.${UUID.randomUUID().toString().take(6)}.tmp"
        )
        try {
            tempFile.writeText(contentString, Charsets.UTF_8)
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun getFolderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.listFiles()?.forEach { file ->
            total += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return total
    }
}
