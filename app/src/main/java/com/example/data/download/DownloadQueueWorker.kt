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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.UUID
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

        // Reset rate-limit per worker run / batch so at most 1 warning and 1 notification per batch
        chapterRepository.resetStorageWarningRateLimit()

        // Clear completed items from previous runs at start of each worker run
        downloadQueueDao.clearCompleted()

        // Invariant: turn every DOWNLOADING row back to QUEUED on worker start so a killed worker never abandons work
        downloadQueueDao.resetDownloadingToQueued()

        val startTime = System.currentTimeMillis()
        val downloadedCountThisSession = AtomicInteger(0)
        val batchCounter = AtomicInteger(0)
        var followUpScheduled = false

        val initialQueuedCount = downloadQueueDao.countByStatus("QUEUED")
        if (initialQueuedCount == 0) {
            return@withContext Result.success()
        }

        trySetForeground(progress = 0, total = initialQueuedCount)

        try {
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

                val settings = settingsRepository.downloadSettings.value
                val maxConcurrent = settings.simultaneousDownloads.coerceIn(1, 5)
                WebChapterScraper.configurePoolCapacity(maxConcurrent)

                val batch = downloadQueueDao.getNextQueuedBatch(maxConcurrent)
                if (batch.isEmpty()) {
                    break
                }

                // Transition selected batch to DOWNLOADING
                for (item in batch) {
                    downloadQueueDao.updateStatus(item.novelId, item.chapterId, "DOWNLOADING")
                }

                val semaphore = Semaphore(maxConcurrent)
                coroutineScope {
                    batch.map { item ->
                        async {
                            semaphore.withPermit {
                                if (isStopped) return@withPermit

                                val novelEntity = novelDao.getNovelById(item.novelId)
                                val chapterEntity = chapterDao.getChapterById(item.novelId, item.chapterId)

                                if (novelEntity == null || chapterEntity == null) {
                                    downloadQueueDao.delete(item.novelId, item.chapterId)
                                    return@withPermit
                                }

                                if (chapterEntity.storageTier == "DOWNLOADED") {
                                    downloadQueueDao.updateStatusIfDownloading(item.novelId, item.chapterId, "COMPLETED")
                                    return@withPermit
                                }

                                // Apply request throttling with jitter at call site if enabled
                                val throttleBatchSize = settings.throttleBatchSize.coerceAtLeast(1)
                                val currentBatch = batchCounter.getAndIncrement()
                                if (settings.throttlingEnabled && currentBatch > 0 && currentBatch % throttleBatchSize == 0) {
                                    val delayMs = throttleDelayOverrideMs ?: run {
                                        var computed = (settings.throttleDelaySeconds * 1000).toLong()
                                        if (settings.throttleRandomJitter) {
                                            val jitterFactor = Random.nextDouble(0.5, 1.5)
                                            computed = (computed * jitterFactor).toLong().coerceAtLeast(200L)
                                        }
                                        computed
                                    }
                                    if (delayMs > 0L) {
                                        delay(delayMs)
                                    }
                                }

                                val success = try {
                                    chapterRepository.downloadChapter(novelEntity.toModel(), chapterEntity.toModel())
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    Log.e("DownloadQueueWorker", "Download failed for chapter ${item.chapterId}", e)
                                    false
                                }

                                if (success) {
                                    downloadQueueDao.updateStatusIfDownloading(item.novelId, item.chapterId, "COMPLETED")
                                    downloadedCountThisSession.incrementAndGet()
                                } else {
                                    val nextRetry = item.retryCount + 1
                                    if (nextRetry < MAX_RETRY_COUNT) {
                                        // Exponential backoff
                                        val backoffMs = (1000L * (1 shl nextRetry)).coerceAtMost(16_000L)
                                        delay(backoffMs)
                                        downloadQueueDao.updateStatusAndRetryIfDownloading(
                                            novelId = item.novelId,
                                            chapterId = item.chapterId,
                                            status = "QUEUED",
                                            retryCount = nextRetry,
                                            errorMessage = "Download attempt $nextRetry failed"
                                        )
                                    } else {
                                        downloadQueueDao.updateStatusAndRetryIfDownloading(
                                            novelId = item.novelId,
                                            chapterId = item.chapterId,
                                            status = "FAILED",
                                            retryCount = nextRetry,
                                            errorMessage = "Max retries ($MAX_RETRY_COUNT) reached"
                                        )
                                    }
                                }

                                val completedThisRun = downloadedCountThisSession.get()
                                val remaining = downloadQueueDao.countActiveRemaining()
                                val totalForRun = completedThisRun + remaining
                                trySetForeground(progress = completedThisRun, total = totalForRun, novelTitle = novelEntity.title)
                            }
                        }
                    }.awaitAll()
                }
            }
        } finally {
            withContext(NonCancellable) {
                // Lifecycle: release pooled WebViews when queue is finished or worker stops
                try {
                    WebChapterScraper.releaseWebViews()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
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
