package com.example.data.local.storage

import com.example.data.local.db.dao.ChapterDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Startup reconciler for cleaning orphan cache/staging files and resetting rows for missing internal files.
 *
 * Invariants (Spec 7):
 * - Runs once per app start in the background after the library flow emits.
 * - Deletes unregistered files ONLY inside cache/ and staging/ and ONLY if older than 10 minutes.
 * - Resets CACHE or DOWNLOADED rows whose INTERNAL file is missing to NONE.
 * - NEVER touches downloads/, epubs/, original_epubs/, covers/ or SAF files that have no row.
 */
class CacheReconciler(
    private val chapterDao: ChapterDao,
    private val storageResolver: StorageResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val hasRun = AtomicBoolean(false)

    suspend fun reconcile(force: Boolean = false) = withContext(ioDispatcher) {
        if (!force && !hasRun.compareAndSet(false, true)) {
            return@withContext
        }

        val now = System.currentTimeMillis()
        val tenMinutesAgo = now - 10 * 60 * 1000L

        // 1. Delete unregistered files only inside cache/chapters/ older than 10 minutes
        val cacheDir = storageResolver.getCacheChaptersDir()
        if (cacheDir.exists() && cacheDir.isDirectory) {
            val registeredCacheKeys = chapterDao.getCacheChaptersLru()
                .map { "${it.novelId}/${it.fileHash}.json" }
                .toSet()

            cacheDir.listFiles()?.forEach { novelDir ->
                if (novelDir.isDirectory) {
                    val novelId = novelDir.name
                    novelDir.listFiles()?.forEach { file ->
                        if (file.isFile && file.lastModified() < tenMinutesAgo) {
                            val relKey = "$novelId/${file.name}"
                            if (!registeredCacheKeys.contains(relKey)) {
                                file.delete()
                            }
                        }
                    }
                    if (novelDir.listFiles()?.isEmpty() == true) {
                        novelDir.delete()
                    }
                }
            }
        }

        // 2. Delete files only inside staging/ older than 10 minutes
        val stagingDir = storageResolver.getStagingDir()
        if (stagingDir.exists() && stagingDir.isDirectory) {
            stagingDir.listFiles()?.forEach { file ->
                if (file.lastModified() < tenMinutesAgo) {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
            }
        }

        // 3. Reset CACHE rows whose INTERNAL file is missing
        val cacheChapters = chapterDao.getCacheChaptersLru()
        for (chapter in cacheChapters) {
            if (chapter.storageLocation == "INTERNAL") {
                val file = storageResolver.getCacheChapterFile(chapter.novelId, chapter.id)
                if (!file.exists() || !file.isFile || file.length() == 0L) {
                    chapterDao.updateStorageTier(
                        novelId = chapter.novelId,
                        id = chapter.id,
                        storageTier = "NONE",
                        storageLocation = "INTERNAL",
                        fileHash = "",
                        lastAccessedAt = 0L
                    )
                }
            }
        }

        // 4. Reset DOWNLOADED rows whose INTERNAL file is missing
        val downloadedChapters = chapterDao.getAllDownloadedChapters()
        for (chapter in downloadedChapters) {
            if (chapter.storageLocation == "INTERNAL") {
                val file = storageResolver.getDownloadChapterFile(chapter.novelId, chapter.id)
                if (!file.exists() || !file.isFile || file.length() == 0L) {
                    chapterDao.updateStorageTier(
                        novelId = chapter.novelId,
                        id = chapter.id,
                        storageTier = "NONE",
                        storageLocation = "INTERNAL",
                        fileHash = "",
                        lastAccessedAt = 0L
                    )
                }
            }
        }

        // downloads/, epubs/, original_epubs/, covers/, and SAF rows/files are NEVER touched.
    }
}
