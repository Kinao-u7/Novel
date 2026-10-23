package com.example.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.local.db.dao.ChapterDao
import com.example.data.local.db.dao.DownloadQueueDao
import com.example.data.local.db.dao.NotificationDao
import com.example.data.local.db.dao.NovelDao
import com.example.data.local.db.entity.DownloadQueueEntity
import com.example.data.repository.ChapterRepository
import com.example.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class EnqueueResult(
    val enqueuedChapterIds: List<String>,
    val skippedChapterIds: List<String>,
    val skippedReasons: Map<String, String> = emptyMap()
) {
    val enqueuedCount: Int get() = enqueuedChapterIds.size
    val skippedCount: Int get() = skippedChapterIds.size
}

data class DownloadQueueSummary(
    val totalCount: Int = 0,
    val queuedCount: Int = 0,
    val downloadingCount: Int = 0,
    val pausedCount: Int = 0,
    val failedCount: Int = 0,
    val completedCount: Int = 0
)

class DownloadEngine(
    private val context: Context,
    private val downloadQueueDao: DownloadQueueDao,
    private val chapterDao: ChapterDao,
    private val novelDao: NovelDao,
    private val chapterRepository: ChapterRepository,
    private val settingsRepository: SettingsRepository,
    private val notificationDao: NotificationDao,
    val downloadCoordinator: DownloadCoordinator = DownloadCoordinator.getInstance()
) {

    companion object {
        const val WORK_NAME = "novel_reader_download_queue"
        const val TAG_DOWNLOAD = "novel_download_worker"
    }

    val queueFlow: Flow<List<DownloadQueueEntity>> = downloadQueueDao.getQueueFlow().distinctUntilChanged()

    val queueSummaryFlow: Flow<DownloadQueueSummary> = queueFlow.map { list ->
        var queued = 0
        var downloading = 0
        var paused = 0
        var failed = 0
        var completed = 0
        for (item in list) {
            when (item.status) {
                "QUEUED" -> queued++
                "DOWNLOADING" -> downloading++
                "PAUSED" -> paused++
                "FAILED" -> failed++
                "COMPLETED" -> completed++
            }
        }
        DownloadQueueSummary(
            totalCount = list.size,
            queuedCount = queued,
            downloadingCount = downloading,
            pausedCount = paused,
            failedCount = failed,
            completedCount = completed
        )
    }.distinctUntilChanged()

    /**
     * Enqueues chapters into Room download_queue table.
     * Verifies that the novel and every chapter row exists in Room first;
     * if missing, returns a structured result reporting which were skipped rather than inserting orphaned rows.
     */
    suspend fun enqueueChapters(novelId: String, chapterIds: List<String>): EnqueueResult = withContext(Dispatchers.IO) {
        if (downloadCoordinator.isDeletingAll.get() > 0) {
            val reasons = chapterIds.associateWith { "All downloads are currently being deleted" }
            return@withContext EnqueueResult(
                enqueuedChapterIds = emptyList(),
                skippedChapterIds = chapterIds,
                skippedReasons = reasons
            )
        }

        if (downloadCoordinator.deletingNovelIds.contains(novelId)) {
            val reasons = chapterIds.associateWith { "Novel is currently being deleted: $novelId" }
            return@withContext EnqueueResult(
                enqueuedChapterIds = emptyList(),
                skippedChapterIds = chapterIds,
                skippedReasons = reasons
            )
        }

        val novel = novelDao.getNovelById(novelId)
        if (novel == null) {
            val reasons = chapterIds.associateWith { "Novel not found in database: $novelId" }
            return@withContext EnqueueResult(
                enqueuedChapterIds = emptyList(),
                skippedChapterIds = chapterIds,
                skippedReasons = reasons
            )
        }

        val enqueued = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val reasons = mutableMapOf<String, String>()

        val activeQueuedIds = downloadQueueDao.getQueuedChapterIds(novelId).toSet()
        val toQueue = mutableListOf<String>()

        for (chapterId in chapterIds) {
            val chapter = chapterDao.getChapterById(novelId, chapterId)
            when {
                chapter == null -> {
                    skipped.add(chapterId)
                    reasons[chapterId] = "Chapter not found in database: $chapterId"
                }
                chapter.storageTier == "DOWNLOADED" || chapter.storageTier == "EPUB" -> {
                    skipped.add(chapterId)
                    reasons[chapterId] = "Chapter already stored as ${chapter.storageTier}: $chapterId"
                }
                activeQueuedIds.contains(chapterId) -> {
                    skipped.add(chapterId)
                    reasons[chapterId] = "Chapter is already in download queue: $chapterId"
                }
                else -> {
                    toQueue.add(chapterId)
                    enqueued.add(chapterId)
                }
            }
        }

        if (toQueue.isNotEmpty()) {
            val lockedResult = downloadCoordinator.mutex.withLock {
                if (downloadCoordinator.isDeletingAll.get() > 0) {
                    val deleteReasons = chapterIds.associateWith { "All downloads are currently being deleted" }
                    return@withLock EnqueueResult(
                        enqueuedChapterIds = emptyList(),
                        skippedChapterIds = chapterIds,
                        skippedReasons = deleteReasons
                    )
                }
                if (downloadCoordinator.deletingNovelIds.contains(novelId)) {
                    val deleteReasons = chapterIds.associateWith { "Novel is currently being deleted: $novelId" }
                    return@withLock EnqueueResult(
                        enqueuedChapterIds = emptyList(),
                        skippedChapterIds = chapterIds,
                        skippedReasons = deleteReasons
                    )
                }
                val now = System.currentTimeMillis()
                val entities = toQueue.map { id ->
                    DownloadQueueEntity(
                        novelId = novelId,
                        chapterId = id,
                        status = "QUEUED",
                        queuedAt = now
                    )
                }
                entities.chunked(500).forEach { chunk ->
                    downloadQueueDao.insertIgnoreAll(chunk)
                }
                downloadQueueDao.bulkRequeue(novelId, toQueue, now)

                scheduleWorker(appendOrReplace = true)
                null
            }
            if (lockedResult != null) {
                return@withContext lockedResult
            }
        }

        EnqueueResult(
            enqueuedChapterIds = enqueued,
            skippedChapterIds = skipped,
            skippedReasons = reasons
        )
    }

    /**
     * Enqueues all un-downloaded chapters for a novel (skips already DOWNLOADED or EPUB).
     */
    suspend fun enqueueNovel(novelId: String): EnqueueResult = withContext(Dispatchers.IO) {
        val novel = novelDao.getNovelById(novelId) ?: return@withContext EnqueueResult(
            enqueuedChapterIds = emptyList(),
            skippedChapterIds = emptyList(),
            skippedReasons = mapOf(novelId to "Novel not found: $novelId")
        )
        val allChapters = chapterDao.getChapters(novelId)
        val queuedIds = downloadQueueDao.getQueuedChapterIds(novelId).toSet()
        val eligible = allChapters.filter { chapter ->
            chapter.storageTier != "DOWNLOADED" &&
            chapter.storageTier != "EPUB" &&
            !queuedIds.contains(chapter.id)
        }
        enqueueChapters(novelId, eligible.map { it.id })
    }

    /**
     * Enqueues the next [count] chapters after [fromChapterIndex] that are not already DOWNLOADED or EPUB,
     * and not already in the download queue.
     */
    suspend fun autoDownloadNext(novelId: String, fromChapterIndex: Int, count: Int): EnqueueResult = withContext(Dispatchers.IO) {
        if (count <= 0) {
            return@withContext EnqueueResult(emptyList(), emptyList())
        }
        val novel = novelDao.getNovelById(novelId) ?: return@withContext EnqueueResult(
            enqueuedChapterIds = emptyList(),
            skippedChapterIds = emptyList(),
            skippedReasons = mapOf(novelId to "Novel not found: $novelId")
        )
        val allChapters = chapterDao.getChapters(novelId)
        val queuedIds = downloadQueueDao.getQueuedChapterIds(novelId).toSet()

        val eligible = allChapters.filter { chapter ->
            chapter.chapterIndex > fromChapterIndex &&
            chapter.storageTier != "DOWNLOADED" &&
            chapter.storageTier != "EPUB" &&
            !queuedIds.contains(chapter.id)
        }.take(count)

        if (eligible.isEmpty()) {
            return@withContext EnqueueResult(emptyList(), emptyList())
        }
        enqueueChapters(novelId, eligible.map { it.id })
    }

    suspend fun pauseQueue() = withContext(Dispatchers.IO) {
        downloadCoordinator.mutex.withLock {
            downloadQueueDao.pauseQueue()
        }
        cancelWorker()
        downloadCoordinator.awaitWorkerStopped()
    }

    suspend fun resumeQueue() = withContext(Dispatchers.IO) {
        downloadCoordinator.awaitWorkerStopped()
        downloadCoordinator.mutex.withLock {
            downloadQueueDao.resumeQueue()
            scheduleWorker()
        }
    }

    suspend fun cancelChapter(novelId: String, chapterId: String) = withContext(Dispatchers.IO) {
        downloadCoordinator.mutex.withLock {
            downloadQueueDao.delete(novelId, chapterId)
        }
        downloadCoordinator.awaitChapterJobs(listOf(Pair(novelId, chapterId)))
    }

    suspend fun cancelNovel(novelId: String) = withContext(Dispatchers.IO) {
        downloadCoordinator.mutex.withLock {
            downloadQueueDao.deleteForNovel(novelId)
        }
        downloadCoordinator.awaitAllChapterJobsForNovel(novelId)
    }

    suspend fun cancelAll() = withContext(Dispatchers.IO) {
        downloadCoordinator.mutex.withLock {
            downloadQueueDao.clearQueue()
        }
        cancelWorker()
        downloadCoordinator.awaitWorkerStopped()
        downloadCoordinator.awaitAllChapterJobs()
    }

    suspend fun retryFailed() = withContext(Dispatchers.IO) {
        downloadCoordinator.mutex.withLock {
            downloadQueueDao.retryFailed()
            scheduleWorker(appendOrReplace = true)
        }
    }

    suspend fun restartWorker(timeoutMs: Long = 10_000L) = withContext(Dispatchers.IO) {
        cancelWorker()
        downloadCoordinator.awaitWorkerStopped(timeoutMs)
        downloadCoordinator.mutex.withLock {
            scheduleWorker(appendOrReplace = false)
        }
    }

    suspend fun clearCompleted() = withContext(Dispatchers.IO) {
        downloadQueueDao.clearCompleted()
    }

    fun scheduleWorker(appendOrReplace: Boolean = false) {
        val wifiOnly = settingsRepository.downloadSettings.value.wifiOnly
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadQueueWorker>()
            .setConstraints(constraints)
            .addTag(TAG_DOWNLOAD)
            .build()

        val policy = if (appendOrReplace) {
            ExistingWorkPolicy.APPEND_OR_REPLACE
        } else {
            ExistingWorkPolicy.KEEP
        }
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.e("DownloadEngine", "Failed to schedule WorkManager", e)
        }
    }

    fun cancelWorker() {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.e("DownloadEngine", "Failed to cancel worker", e)
        }
    }
}
