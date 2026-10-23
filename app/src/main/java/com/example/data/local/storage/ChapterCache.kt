package com.example.data.local.storage

import com.example.data.model.ChapterContent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Untracked disk cache for chapters read online.
 *
 * File layout: cacheDir/chapters/{novelId}/{sha256(chapterId)}.json
 *
 * Rules:
 * - Room does NOT record cached chapters.
 * - Atomic writes via a ".tmp" temp file in the same directory, then rename.
 * - Temp files are ignored by size accounting and trim; stale temp files (> 10m) are deleted on trim.
 * - Reads refresh the file's lastModified timestamp.
 * - Missing or corrupt (JSONException/EOF) cache file returns null (miss) and corrupt files are deleted.
 * - Keeps a running size estimate (initialized once by scanning the folder).
 * - Rescans and trims only when the running size exceeds cacheSizeCapMb.
 * - One Mutex serializes trimming and size recalculations.
 */
class ChapterCache(
    val cacheDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val trimMutex = Mutex()
    private val runningSizeEstimate = AtomicLong(-1L)

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun getChaptersDir(): File = File(cacheDir, "chapters")

    fun getNovelDir(novelId: String): File = File(getChaptersDir(), novelId)

    fun getCacheFile(novelId: String, chapterId: String): File =
        File(getNovelDir(novelId), "${sha256(chapterId)}.json")

    /**
     * Initializes running size estimate by scanning all .json files under cacheDir/chapters.
     */
    private fun initSizeIfNeeded(): Long {
        val current = runningSizeEstimate.get()
        if (current >= 0L) return current

        val dir = getChaptersDir()
        var total = 0L
        if (dir.exists()) {
            dir.walkTopDown().forEach { file ->
                if (file.isFile && file.name.endsWith(".json")) {
                    total += file.length()
                }
            }
        }
        runningSizeEstimate.compareAndSet(-1L, total)
        return runningSizeEstimate.get()
    }

    suspend fun sizeBytes(): Long = withContext(ioDispatcher) {
        initSizeIfNeeded()
    }

    suspend fun contains(novelId: String, chapterId: String): Boolean = withContext(ioDispatcher) {
        getCacheFile(novelId, chapterId).exists()
    }

    /**
     * Reads and parses chapter content.
     * Refreshes lastModified on success.
     * Returns null if missing or corrupt (deleting corrupt file).
     */
    suspend fun read(novelId: String, chapterId: String): ChapterContent? = withContext(ioDispatcher) {
        val file = getCacheFile(novelId, chapterId)
        if (!file.exists()) return@withContext null

        try {
            val text = file.readText()
            val json = JSONObject(text)
            val id = json.getString("id")
            val nId = json.getString("novelId")
            val title = json.getString("title")
            val pArray = json.getJSONArray("paragraphs")
            val paragraphs = ArrayList<String>(pArray.length())
            for (i in 0 until pArray.length()) {
                paragraphs.add(pArray.getString(i))
            }
            // Refresh lastModified time on read
            file.setLastModified(System.currentTimeMillis())
            ChapterContent(
                id = id,
                novelId = nId,
                title = title,
                paragraphs = paragraphs,
                isDownloaded = false
            )
        } catch (_: FileNotFoundException) {
            // Disappeared during read -> miss, not an error
            null
        } catch (e: Exception) {
            if (e is JSONException || e is IOException) {
                // Corrupt file -> delete and report miss
                val len = file.length()
                if (file.delete()) {
                    runningSizeEstimate.addAndGet(-len)
                }
                null
            } else {
                throw e
            }
        }
    }

    /**
     * Writes chapter content to a temporary file (.tmp) in the same directory, then renames.
     * Updates running size estimate.
     */
    suspend fun write(content: ChapterContent): Unit = withContext(ioDispatcher) {
        initSizeIfNeeded()
        val targetFile = getCacheFile(content.novelId, content.chapterId)
        val parent = targetFile.parentFile ?: getNovelDir(content.novelId)
        if (!parent.exists()) {
            parent.mkdirs()
        }

        val json = JSONObject().apply {
            put("id", content.id)
            put("novelId", content.novelId)
            put("title", content.title)
            val arr = JSONArray()
            content.paragraphs.forEach { arr.put(it) }
            put("paragraphs", arr)
        }

        val tempFile = File(parent, "${targetFile.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                val bytes = json.toString().toByteArray(Charsets.UTF_8)
                fos.write(bytes)
                fos.flush()
                try {
                    fos.fd.sync()
                } catch (_: Exception) {}
            }

            val prevLen = if (targetFile.exists()) targetFile.length() else 0L
            if (!tempFile.renameTo(targetFile)) {
                // If rename fails, fallback to copy + delete
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            val newLen = targetFile.length()
            targetFile.setLastModified(System.currentTimeMillis())
            runningSizeEstimate.addAndGet(newLen - prevLen)
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

    /**
     * Deletes a single chapter file from the cache.
     */
    suspend fun delete(novelId: String, chapterId: String): Boolean = withContext(ioDispatcher) {
        val file = getCacheFile(novelId, chapterId)
        if (file.exists()) {
            val len = file.length()
            val deleted = file.delete()
            if (deleted) {
                runningSizeEstimate.addAndGet(-len)
            }
            deleted
        } else {
            false
        }
    }

    /**
     * Deletes the entire novel cache directory: cacheDir/chapters/{novelId}.
     */
    suspend fun deleteNovel(novelId: String): Boolean = withContext(ioDispatcher) {
        val dir = getNovelDir(novelId)
        if (!dir.exists()) return@withContext false

        var freed = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile && file.name.endsWith(".json")) {
                freed += file.length()
            }
        }
        val deleted = dir.deleteRecursively()
        if (deleted) {
            runningSizeEstimate.addAndGet(-freed)
        }
        deleted
    }

    /**
     * Clears all cached chapters under cacheDir/chapters.
     */
    suspend fun clear(): Long = withContext(ioDispatcher) {
        trimMutex.withLock {
            val dir = getChaptersDir()
            if (!dir.exists()) {
                runningSizeEstimate.set(0L)
                return@withLock 0L
            }
            var freed = 0L
            dir.walkTopDown().forEach { file ->
                if (file.isFile && file.name.endsWith(".json")) {
                    freed += file.length()
                }
            }
            dir.deleteRecursively()
            dir.mkdirs()
            runningSizeEstimate.set(0L)
            freed
        }
    }

    /**
     * Trims the cache if running size estimate exceeds capMb.
     * Deletes stale .tmp files (> 10m).
     * Evicts oldest modified .json files until total size is <= capMb.
     */
    suspend fun trim(capMb: Int): Long = withContext(ioDispatcher) {
        val capBytes = capMb.toLong() * 1024L * 1024L
        val currentEstimate = initSizeIfNeeded()
        if (currentEstimate <= capBytes) {
            return@withContext 0L
        }

        trimMutex.withLock {
            val dir = getChaptersDir()
            if (!dir.exists()) {
                runningSizeEstimate.set(0L)
                return@withLock 0L
            }

            val now = System.currentTimeMillis()
            val tenMinutesAgo = now - 10 * 60 * 1000L

            val validFiles = mutableListOf<File>()
            var totalBytes = 0L

            dir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    if (file.name.endsWith(".tmp")) {
                        // Clean up stale temp files
                        if (file.lastModified() < tenMinutesAgo) {
                            file.delete()
                        }
                    } else if (file.name.endsWith(".json")) {
                        validFiles.add(file)
                        totalBytes += file.length()
                    }
                }
            }

            if (totalBytes <= capBytes) {
                runningSizeEstimate.set(totalBytes)
                return@withLock 0L
            }

            // Sort files by lastModified ascending (oldest first)
            validFiles.sortBy { it.lastModified() }

            var bytesFreed = 0L
            val iterator = validFiles.iterator()
            while (iterator.hasNext() && totalBytes > capBytes) {
                val file = iterator.next()
                val len = file.length()
                if (file.delete()) {
                    bytesFreed += len
                    totalBytes -= len
                }
            }

            runningSizeEstimate.set(totalBytes)
            bytesFreed
        }
    }
}
