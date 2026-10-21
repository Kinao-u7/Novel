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
     * Map of active in-flight chapter jobs: (novelId, chapterId) -> Job.
     */
    val activeChapterJobs = ConcurrentHashMap<Pair<String, String>, Job>()

    @Volatile
    private var activeWorkerDeferred: CompletableDeferred<Unit>? = null

    fun onWorkerStarted(): CompletableDeferred<Unit> {
        val deferred = CompletableDeferred<Unit>()
        activeWorkerDeferred = deferred
        return deferred
    }

    fun onWorkerStopped() {
        activeWorkerDeferred?.complete(Unit)
        activeWorkerDeferred = null
    }

    suspend fun awaitWorkerStopped() {
        activeWorkerDeferred?.await()
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
            } catch (_: Exception) {}
        }
    }

    suspend fun awaitAllChapterJobs() {
        val jobs = activeChapterJobs.values.toList()
        for (job in jobs) {
            try {
                job.join()
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
