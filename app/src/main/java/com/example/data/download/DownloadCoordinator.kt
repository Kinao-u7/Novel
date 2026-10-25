package com.example.data.download

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex

/**
 * App-scoped coordinator synchronizing download queue operations, worker termination,
 * in-flight chapter execution, and deletion guards.
 *
 * Invariant: Never wait for workers or in-flight downloads while holding [mutex].
 */
class DownloadCoordinator {

    val mutex = Mutex()

    /**
     * Set of novel IDs currently undergoing file deletion.
     * enqueueChapters checks and rejects operations for novels in this set.
     */
    val deletingNovelIds: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    /**
     * Counter indicating whether all downloads are currently undergoing deletion.
     * enqueueChapters checks and rejects operations while this is > 0.
     */
    val isDeletingAll = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Map of active in-flight chapter jobs: (novelId, chapterId) -> Job.
     */
    val activeChapterJobs = ConcurrentHashMap<Pair<String, String>, Job>()

    /**
     * Map of active worker runs: runId -> CompletableDeferred<Unit>.
     */
    private val activeWorkerRuns = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    fun onWorkerStarted(runId: String = java.util.UUID.randomUUID().toString()): CompletableDeferred<Unit> {
        val deferred = CompletableDeferred<Unit>()
        activeWorkerRuns[runId] = deferred
        return deferred
    }

    fun onWorkerStopped(runId: String? = null) {
        if (runId != null) {
            activeWorkerRuns.remove(runId)?.complete(Unit)
        } else {
            // If no specific runId provided, complete all active runs
            val runs = activeWorkerRuns.keys.toList()
            for (id in runs) {
                activeWorkerRuns.remove(id)?.complete(Unit)
            }
        }
    }

    suspend fun awaitWorkerStopped(timeoutMs: Long = 10_000L) {
        val activeRuns = activeWorkerRuns.entries.toList()
        for ((runId, deferred) in activeRuns) {
            try {
                kotlinx.coroutines.withTimeout(timeoutMs) {
                    deferred.await()
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                android.util.Log.w("DownloadCoordinator", "Timed out waiting for worker run $runId to stop after ${timeoutMs}ms")
            }
        }
    }

    /**
     * Counter tracking successful chapter downloads across follow-up worker runs in a batch.
     * Reset to 0 when the download queue empties.
     */
    val batchCompletedCount = java.util.concurrent.atomic.AtomicInteger(0)

    fun incrementBatchCompleted() {
        batchCompletedCount.incrementAndGet()
    }

    fun getBatchCompletedCount(): Int {
        return batchCompletedCount.get()
    }

    fun resetBatchCompletedCount() {
        batchCompletedCount.set(0)
    }

    fun registerChapterJob(novelId: String, chapterId: String, job: Job) {
        val key = Pair(novelId, chapterId)
        activeChapterJobs[key] = job
        job.invokeOnCompletion {
            activeChapterJobs.remove(key, job)
        }
    }

    suspend fun awaitChapterJobs(chapterKeys: Collection<Pair<String, String>>) {
        val jobs = chapterKeys.mapNotNull { activeChapterJobs[it] }
        for (job in jobs) {
            try {
                job.join()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    suspend fun awaitAllChapterJobsForNovel(novelId: String) {
        val jobs = activeChapterJobs.entries
            .filter { it.key.first == novelId }
            .map { it.value }
        for (job in jobs) {
            try {
                job.join()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    suspend fun awaitAllChapterJobs() {
        val jobs = activeChapterJobs.values.toList()
        for (job in jobs) {
            try {
                job.join()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    /**
     * Cancels active chapter job and joins it.
     * Rethrows CancellationException if the caller coroutine was cancelled.
     */
    suspend fun cancelChapterJob(novelId: String, chapterId: String) {
        val job = activeChapterJobs[Pair(novelId, chapterId)] ?: return
        try {
            job.cancel()
            job.join()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    /**
     * Cancels active chapter jobs for specified keys and joins them.
     * Rethrows CancellationException if the caller coroutine was cancelled.
     */
    suspend fun cancelChapterJobs(chapterKeys: Collection<Pair<String, String>>) {
        val jobs = chapterKeys.mapNotNull { activeChapterJobs[it] }
        for (job in jobs) {
            try {
                job.cancel()
                job.join()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    /**
     * Cancels active chapter jobs for a specific novel and joins them.
     * Rethrows CancellationException if the caller coroutine was cancelled.
     */
    suspend fun cancelChapterJobsForNovel(novelId: String) {
        val jobs = activeChapterJobs.entries
            .filter { it.key.first == novelId }
            .map { it.value }
        for (job in jobs) {
            try {
                job.cancel()
                job.join()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    /**
     * Cancels all active chapter jobs and joins them.
     * Rethrows CancellationException if the caller coroutine was cancelled.
     */
    suspend fun cancelAllChapterJobs() {
        val jobs = activeChapterJobs.values.toList()
        for (job in jobs) {
            try {
                job.cancel()
                job.join()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    companion object {
        @Volatile
        private var defaultInstance: DownloadCoordinator? = null

        fun getInstance(): DownloadCoordinator {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: DownloadCoordinator().also { defaultInstance = it }
            }
        }

        @androidx.annotation.VisibleForTesting
        fun resetInstanceForTesting(): DownloadCoordinator {
            val fresh = DownloadCoordinator()
            defaultInstance = fresh
            return fresh
        }
    }
}
