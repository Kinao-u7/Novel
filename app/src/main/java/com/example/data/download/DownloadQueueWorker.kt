package com.example.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.data.local.db.entity.NotificationEntity
import com.example.data.model.NotificationTypes
import com.example.data.local.db.toModel
import com.example.data.source.webscrape.WebChapterScraper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class DownloadQueueWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = DownloadNotificationHelper.CHANNEL_ID
        const val NOTIFICATION_ID = DownloadNotificationHelper.NOTIFICATION_ID
        const val COMPLETION_NOTIFICATION_ID = DownloadNotificationHelper.COMPLETION_NOTIFICATION_ID
        const val MAX_WORKER_DURATION_MS = 8 * 60 * 1000L // 8 minutes
        const val MAX_RETRY_COUNT = 3

        @androidx.annotation.VisibleForTesting
        @Volatile
        var throttleDelayOverrideMs: Long? = null

        @androidx.annotation.VisibleForTesting
        @Volatile
        var maxWorkerDurationOverrideMs: Long? = null
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repository = DownloadEngineProvider.getRepository(applicationContext)
        val downloadQueueDao = repository.downloadQueueDao
        val novelDao = repository.novelDao
        val chapterDao = repository.chapterDao
        val chapterRepository = repository.chapterRepository
        val settingsRepository = repository.settingsRepository
        val notificationDao = repository.notificationDao
        val coordinator = chapterRepository.downloadCoordinator

        val runId = id.toString()
        val downloadedCountThisSession = AtomicInteger(0)
        var followUpScheduled = false
        var startQueuedCount = 0
        var startCompletedCount = 0
        var startFailedCount = 0

        try {
            coordinator.onWorkerStarted(runId)

            // Reset rate-limit per worker run / batch so at most 1 warning and 1 notification per batch
            chapterRepository.resetStorageWarningRateLimit()

            // Invariant: turn every DOWNLOADING row back to QUEUED on worker start so a killed worker never abandons work
            downloadQueueDao.resetDownloadingToQueued()

            val startTime = System.currentTimeMillis()

            startQueuedCount = downloadQueueDao.countByStatus("QUEUED")
            startCompletedCount = downloadQueueDao.countByStatus("COMPLETED")
            startFailedCount = downloadQueueDao.countByStatus("FAILED")
            val initialRemaining = downloadQueueDao.countActiveRemaining()
            val initialTotal = startCompletedCount + initialRemaining
            if (initialRemaining == 0) {
                if (downloadQueueDao.countActiveOrPaused() == 0) {
                    downloadQueueDao.clearCompleted()
                }
                return@withContext Result.success()
            }

            trySetForeground(progress = startCompletedCount, total = initialTotal)

            val settings = settingsRepository.downloadSettings.value
            val maxConcurrent = settings.simultaneousDownloads.coerceIn(1, 5)
            WebChapterScraper.configurePoolCapacity(maxConcurrent)

            val sourceNextAllowedAt = ConcurrentHashMap<String, Long>()
            val semaphore = Semaphore(maxConcurrent)
            val activeJobs = java.util.Collections.synchronizedList(mutableListOf<Job>())

            coroutineScope {
                while (!isStopped) {
                    // Time limit: Stop after ~8 minutes of work and re-enqueue if queue still has QUEUED rows
                    val maxDuration = maxWorkerDurationOverrideMs ?: MAX_WORKER_DURATION_MS
                    if (System.currentTimeMillis() - startTime >= maxDuration) {
                        val remainingQueued = downloadQueueDao.countByStatus("QUEUED")
                        if (remainingQueued > 0) {
                            followUpScheduled = true
                            repository.downloadEngine.scheduleWorker(appendOrReplace = true)
                        }
                        break
                    }

                    // Pause check: pause pauses the whole queue: do not download, stop the worker
                    val pausedCount = downloadQueueDao.countByStatus("PAUSED")
                    if (pausedCount > 0) {
                        break
                    }

                    semaphore.acquire()

                    if (isStopped) {
                        semaphore.release()
                        break
                    }

                    val now = System.currentTimeMillis()
                    val candidates = downloadQueueDao.getNextQueuedBatch(10)

                    var chosenItem: com.example.data.local.db.entity.DownloadQueueEntity? = null
                    var chosenNovel: com.example.data.local.db.entity.NovelEntity? = null
                    var chosenSourceId: String? = null
                    var minThrottleWaitMs: Long? = null

                    for (candidate in candidates) {
                        val novelEntity = novelDao.getNovelById(candidate.novelId)
                        if (novelEntity == null) {
                            downloadQueueDao.delete(candidate.novelId, candidate.chapterId)
                            continue
                        }
                        val sourceId = novelEntity.sourceId
                        val nextAllowed = sourceNextAllowedAt[sourceId] ?: 0L
                        if (nextAllowed > now) {
                            val waitMs = nextAllowed - now
                            if (minThrottleWaitMs == null || waitMs < minThrottleWaitMs) {
                                minThrottleWaitMs = waitMs
                            }
                            continue
                        }

                        // Guarded transition to DOWNLOADING
                        val updated = downloadQueueDao.updateStatusIfExpected(
                            novelId = candidate.novelId,
                            chapterId = candidate.chapterId,
                            status = "DOWNLOADING",
                            expectedStatus = "QUEUED"
                        )
                        if (updated > 0) {
                            chosenItem = candidate
                            chosenNovel = novelEntity
                            chosenSourceId = sourceId
                            break
                        }
                    }

                    if (chosenItem == null) {
                        semaphore.release()
                        if (minThrottleWaitMs != null) {
                            delay(minThrottleWaitMs.coerceIn(50L, 500L))
                            continue
                        }

                        // No candidates: check under coordinator lock if queue is truly empty
                        val shouldExit = coordinator.mutex.withLock {
                            val remainingQueued = downloadQueueDao.countByStatus("QUEUED")
                            remainingQueued == 0
                        }
                        if (shouldExit) {
                            break
                        }
                        delay(100L)
                        continue
                    }

                    val item = chosenItem
                    val novelEntity = chosenNovel!!
                    val sourceId = chosenSourceId

                    val job = launch {
                        try {
                            if (isStopped) return@launch

                            val chapterEntity = chapterDao.getChapterById(item.novelId, item.chapterId)
                            if (chapterEntity == null) {
                                downloadQueueDao.delete(item.novelId, item.chapterId)
                                return@launch
                            }

                            if (chapterEntity.storageTier == "DOWNLOADED") {
                                downloadQueueDao.updateStatusIfExpected(item.novelId, item.chapterId, "COMPLETED", "DOWNLOADING")
                                return@launch
                            }

                            val result: com.example.data.download.DownloadResult = try {
                                chapterRepository.downloadChapter(novelEntity.toModel(), chapterEntity.toModel())
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Log.e("DownloadQueueWorker", "Download failed for chapter ${item.chapterId}", e)
                                com.example.data.download.DownloadResult.Network(e.message ?: "Download exception", e)
                            }

                            when (result) {
                                is com.example.data.download.DownloadResult.Success -> {
                                    downloadQueueDao.updateStatusIfExpected(item.novelId, item.chapterId, "COMPLETED", "DOWNLOADING")
                                    downloadedCountThisSession.incrementAndGet()

                                    // Apply per-source throttle delay after success if enabled
                                    if (settings.throttlingEnabled && sourceId != null) {
                                        val delayOverride = throttleDelayOverrideMs
                                        val computed = delayOverride ?: run {
                                            var ms = (settings.throttleDelaySeconds * 1000).toLong()
                                            if (settings.throttleRandomJitter) {
                                                val jitterFactor = Random.nextDouble(0.5, 1.5)
                                                ms = (ms * jitterFactor).toLong().coerceAtLeast(200L)
                                            }
                                            ms
                                        }
                                        if (computed > 0L) {
                                            sourceNextAllowedAt[sourceId] = System.currentTimeMillis() + computed
                                        }
                                    }
                                }
                                is com.example.data.download.DownloadResult.RateLimited -> {
                                    val retryAfter = result.retryAfterMs ?: run {
                                        val nextRetry = item.retryCount + 1
                                        (1000L * (1 shl nextRetry)).coerceAtMost(16_000L)
                                    }
                                    if (sourceId != null) {
                                        sourceNextAllowedAt[sourceId] = System.currentTimeMillis() + retryAfter
                                    }
                                    val nextRetry = item.retryCount + 1
                                    if (nextRetry < MAX_RETRY_COUNT) {
                                        delay(retryAfter.coerceAtMost(2000L))
                                        downloadQueueDao.updateStatusAndRetryIfDownloading(
                                            novelId = item.novelId,
                                            chapterId = item.chapterId,
                                            status = "QUEUED",
                                            retryCount = nextRetry,
                                            errorMessage = result.message
                                        )
                                    } else {
                                        downloadQueueDao.updateStatusAndRetryIfDownloading(
                                            novelId = item.novelId,
                                            chapterId = item.chapterId,
                                            status = "FAILED",
                                            retryCount = nextRetry,
                                            errorMessage = "Max retries ($MAX_RETRY_COUNT) reached: ${result.message}"
                                        )
                                    }
                                }
                                is com.example.data.download.DownloadResult.Network -> {
                                    val nextRetry = item.retryCount + 1
                                    if (nextRetry < MAX_RETRY_COUNT) {
                                        val backoffMs = (1000L * (1 shl nextRetry)).coerceAtMost(16_000L)
                                        delay(backoffMs.coerceAtMost(2000L))
                                        downloadQueueDao.updateStatusAndRetryIfDownloading(
                                            novelId = item.novelId,
                                            chapterId = item.chapterId,
                                            status = "QUEUED",
                                            retryCount = nextRetry,
                                            errorMessage = result.message
                                        )
                                    } else {
                                        downloadQueueDao.updateStatusAndRetryIfDownloading(
                                            novelId = item.novelId,
                                            chapterId = item.chapterId,
                                            status = "FAILED",
                                            retryCount = nextRetry,
                                            errorMessage = "Max retries ($MAX_RETRY_COUNT) reached: ${result.message}"
                                        )
                                    }
                                }
                                is com.example.data.download.DownloadResult.Parse -> {
                                    downloadQueueDao.updateStatusAndRetryIfDownloading(
                                        novelId = item.novelId,
                                        chapterId = item.chapterId,
                                        status = "FAILED",
                                        retryCount = item.retryCount,
                                        errorMessage = "Parse error: ${result.message}"
                                    )
                                }
                                is com.example.data.download.DownloadResult.SourceUnavailable -> {
                                    downloadQueueDao.updateStatusAndRetryIfDownloading(
                                        novelId = item.novelId,
                                        chapterId = item.chapterId,
                                        status = "FAILED",
                                        retryCount = item.retryCount,
                                        errorMessage = "Source unavailable: ${result.sourceId}"
                                    )
                                }
                                is com.example.data.download.DownloadResult.StorageFull -> {
                                    downloadQueueDao.updateStatusAndRetryIfDownloading(
                                        novelId = item.novelId,
                                        chapterId = item.chapterId,
                                        status = "FAILED",
                                        retryCount = item.retryCount,
                                        errorMessage = "Storage full: ${result.availableBytes} bytes available"
                                    )
                                }
                                is com.example.data.download.DownloadResult.StorageUnavailable -> {
                                    downloadQueueDao.updateStatusAndRetryIfDownloading(
                                        novelId = item.novelId,
                                        chapterId = item.chapterId,
                                        status = "FAILED",
                                        retryCount = item.retryCount,
                                        errorMessage = "Storage unavailable: ${result.location} - ${result.message}"
                                    )
                                }
                            }

                            val completedCount = downloadQueueDao.countByStatus("COMPLETED")
                            val remaining = downloadQueueDao.countActiveRemaining()
                            val totalForRun = completedCount + remaining
                            trySetForeground(progress = completedCount, total = totalForRun, novelTitle = novelEntity.title)
                        } finally {
                            semaphore.release()
                        }
                    }

                    coordinator.registerChapterJob(item.novelId, item.chapterId, job)
                    activeJobs.add(job)
                    activeJobs.removeAll { it.isCompleted }
                }

                // Wait for all in-flight jobs launched in this session to finish
                for (j in activeJobs.toList()) {
                    try { j.join() } catch (_: Exception) {}
                }
            }
        } finally {
            withContext(NonCancellable) {
                coordinator.onWorkerStopped(runId)

                if (!isStopped && !followUpScheduled) {
                    coordinator.mutex.withLock {
                        val currentQueued = downloadQueueDao.countByStatus("QUEUED")
                        val currentCompleted = downloadQueueDao.countByStatus("COMPLETED")
                        val currentFailed = downloadQueueDao.countByStatus("FAILED")
                        val pausedCount = downloadQueueDao.countByStatus("PAUSED")

                        val changed = (currentCompleted != startCompletedCount) ||
                                      (currentFailed != startFailedCount) ||
                                      (currentQueued != startQueuedCount)

                        if (currentQueued > 0 && pausedCount == 0 && changed) {
                            followUpScheduled = true
                            repository.downloadEngine.scheduleWorker(appendOrReplace = true)
                        } else if (currentQueued == 0 && pausedCount == 0 && downloadQueueDao.countActiveRemaining() == 0) {
                            downloadQueueDao.clearCompleted()
                        }
                    }
                }

                // Lifecycle: release pooled WebViews when queue is finished or worker stops
                try {
                    WebChapterScraper.releaseWebViews()
                } catch (e: Exception) {
                    Log.w("DownloadQueueWorker", "Error releasing WebViews", e)
                }

                // Cancel ongoing progress notification
                val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                try {
                    notificationManager?.cancel(NOTIFICATION_ID)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }

                // Post completion notification only if notifyOnDownload is enabled, worker was not stopped, and follow-up was not scheduled
                val notifyOnDownload = settingsRepository.notificationSettings.value.notifyOnDownload
                val completed = downloadedCountThisSession.get()
                if (!isStopped && !followUpScheduled && notifyOnDownload && completed > 0) {
                    DownloadNotificationHelper.postCompletionNotification(applicationContext, completed, notifyOnDownload = true)
                    try {
                        notificationDao.insertAndTrim(
                            NotificationEntity(
                                id = UUID.randomUUID().toString(),
                                type = NotificationTypes.DOWNLOAD,
                                title = "Downloads Complete",
                                message = "Successfully downloaded $completed chapter${if (completed > 1) "s" else ""}.",
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w("DownloadQueueWorker", "Error inserting notification", e)
                    }
                }
            }
        }

        Result.success()
    }

    private suspend fun trySetForeground(progress: Int, total: Int, novelTitle: String? = null) {
        try {
            setForeground(createForegroundInfo(progress, total, novelTitle))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("DownloadQueueWorker", "Could not start foreground service, continuing in background", e)
        }
    }

    private fun createForegroundInfo(progress: Int, total: Int, novelTitle: String? = null): ForegroundInfo {
        DownloadNotificationHelper.createNotificationChannel(applicationContext)

        val pauseIntent = Intent(applicationContext, DownloadNotificationReceiver::class.java).apply {
            action = ACTION_PAUSE_DOWNLOADS
        }
        val pausePendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            1,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(applicationContext, DownloadNotificationReceiver::class.java).apply {
            action = ACTION_CANCEL_DOWNLOADS
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            2,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (!novelTitle.isNullOrBlank()) "Downloading $novelTitle" else "Downloading chapters"
        val contentText = if (total > 0) "$progress / $total completed" else "Preparing downloads..."

        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "Pause", pausePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)

        if (total > 0) {
            builder.setProgress(total, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }

        val notification = builder.build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
