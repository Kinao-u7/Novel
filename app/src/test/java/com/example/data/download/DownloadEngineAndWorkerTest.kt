package com.example.data.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.local.db.NovelDatabase
import com.example.data.local.db.NovelDatabaseHolder
import com.example.data.local.db.dao.ChapterDao
import com.example.data.local.db.dao.DownloadQueueDao
import com.example.data.local.db.dao.NotificationDao
import com.example.data.local.db.dao.NovelDao
import com.example.data.local.db.entity.ChapterEntity
import com.example.data.local.db.entity.DownloadQueueEntity
import com.example.data.local.db.entity.NovelEntity
import com.example.data.model.NotificationTypes
import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.local.db.toModel
import com.example.data.model.Novel
import com.example.data.repository.NovelRepository
import com.example.data.source.NovelSource
import com.example.data.source.SourceManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class TestWorkerSource(override val id: String = "test_source") : NovelSource {
    override val name: String = "Test Source"
    override val language: String = "en"
    override val version: String = "1.0.0"
    override val description: String = "Worker test source"
    override val isLocal: Boolean = false

    var failChapters = mutableSetOf<String>()
    val callTimes = mutableListOf<Long>()
    val currentConcurrent = AtomicInteger(0)
    var maxConcurrentCalls = 0
    val concurrencyLock = Any()

    override suspend fun getPopularNovels(page: Int): List<Novel> = emptyList()
    override suspend fun searchNovels(query: String, page: Int): List<Novel> = emptyList()
    override suspend fun getNovelDetails(novelId: String): Novel = Novel(
        id = novelId,
        title = "Novel $novelId",
        author = "Author",
        description = "Description for $novelId",
        sourceId = id,
        sourceName = name
    )
    override suspend fun getChapterList(novelId: String): List<Chapter> = emptyList()
    override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent {
        callTimes.add(System.currentTimeMillis())
        val active = currentConcurrent.incrementAndGet()
        synchronized(concurrencyLock) {
            if (active > maxConcurrentCalls) {
                maxConcurrentCalls = active
            }
        }
        try {
            if (failChapters.contains(chapterId)) {
                throw IOException("Simulated network failure for $chapterId")
            }
            delay(20)
            return ChapterContent(
                chapterId = chapterId,
                novelId = novelId,
                title = "Chapter $chapterId",
                paragraphs = listOf("Paragraph 1", "Paragraph 2"),
                isDownloaded = true
            )
        } finally {
            currentConcurrent.decrementAndGet()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class DownloadEngineAndWorkerTest {

    private lateinit var context: Context
    private lateinit var database: NovelDatabase
    private lateinit var novelDao: NovelDao
    private lateinit var chapterDao: ChapterDao
    private lateinit var downloadQueueDao: DownloadQueueDao
    private lateinit var notificationDao: NotificationDao
    private lateinit var repository: NovelRepository
    private lateinit var downloadEngine: DownloadEngine
    private lateinit var fakeSource: TestWorkerSource

    @Before
    fun setup() {
        DownloadCoordinator.resetInstanceForTesting()
        context = ApplicationProvider.getApplicationContext()

        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)

        database = Room.inMemoryDatabaseBuilder(context, NovelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        NovelDatabaseHolder.setInstanceForTesting(database)

        novelDao = database.novelDao()
        chapterDao = database.chapterDao()
        downloadQueueDao = database.downloadQueueDao()
        notificationDao = database.notificationDao()

        repository = NovelRepository(context, availableBytesProvider = { 1024L * 1024L * 1024L })
        NovelRepository.setInstanceForTesting(repository)
        DownloadEngineProvider.setRepositoryForTesting(repository)

        downloadEngine = repository.downloadEngine

        // Default: zero throttle delay for tests unless overridden specifically
        DownloadQueueWorker.throttleDelayOverrideMs = 0L
        DownloadQueueWorker.backoffBaseMsOverride = 20L

        fakeSource = TestWorkerSource("test_source")
        SourceManager.registerSource(fakeSource)
    }

    @After
    fun tearDown() {
        SourceManager.unregisterSource(fakeSource.id)
        DownloadQueueWorker.throttleDelayOverrideMs = null
        DownloadQueueWorker.maxWorkerDurationOverrideMs = null
        DownloadQueueWorker.backoffBaseMsOverride = null
        DownloadQueueWorker.maxPerSourceConcurrencyOverride = null
        DownloadEngineProvider.setRepositoryForTesting(null)
        repository.shutdownForTesting()
        NovelRepository.resetInstanceForTesting()
        NovelDatabaseHolder.clearInstanceForTesting()
        WorkManager.getInstance(context).cancelAllWork()
        database.close()
        DownloadCoordinator.resetInstanceForTesting()
    }

    private fun testNovelEntity(
        id: String = "nov_1",
        title: String = "Test Novel",
        author: String = "Author",
        description: String = "Desc",
        sourceId: String = "test_source",
        sourceName: String = "Test Source",
        status: String = "Ongoing",
        genresJson: String = "[]",
        inLibrary: Boolean = true,
        isEpub: Boolean = false,
        totalChapters: Int = 10,
        rating: Float? = null
    ): NovelEntity = NovelEntity(
        id = id,
        title = title,
        author = author,
        description = description,
        sourceId = sourceId,
        sourceName = sourceName,
        status = status,
        genresJson = genresJson,
        inLibrary = inLibrary,
        isEpub = isEpub,
        totalChapters = totalChapters,
        rating = rating
    )

    private fun testNovel(
        id: String = "nov_1",
        title: String = "Test Novel",
        author: String = "Author",
        coverUrl: String? = null,
        description: String = "Desc",
        genres: List<String> = emptyList(),
        status: String = "Ongoing",
        sourceId: String = "test_source",
        sourceName: String = "Test Source",
        inLibrary: Boolean = true
    ): Novel = Novel(
        id = id,
        title = title,
        author = author,
        coverUrl = coverUrl,
        description = description,
        genres = genres,
        status = status,
        sourceId = sourceId,
        sourceName = sourceName,
        inLibrary = inLibrary
    )

    private suspend fun insertTestNovelAndChapters(novelId: String = "nov_1", chapterCount: Int = 3) {
        val novel = NovelEntity(
            id = novelId,
            title = "Test Novel $novelId",
            author = "Author",
            description = "Desc",
            sourceId = fakeSource.id,
            sourceName = fakeSource.name,
            status = "Ongoing",
            inLibrary = true
        )
        novelDao.insertIgnore(novel)

        val chapters = (1..chapterCount).map { idx ->
            ChapterEntity(
                novelId = novelId,
                id = "ch_$idx",
                title = "Chapter $idx",
                chapterIndex = idx
            )
        }
        chapterDao.insertIgnore(chapters)
    }

    // --- 1. DAO TESTS ---

    @Test
    fun dao_insertIgnore_preventsDuplicateEntries() = runBlocking<Unit> {
        withTimeout(10_000) {
            insertTestNovelAndChapters("nov_dao", 2)

            val item1 = DownloadQueueEntity("nov_dao", "ch_1", queuedAt = 1000L)
            val rowId1 = downloadQueueDao.insertIgnore(item1)
            assertTrue(rowId1 > 0)

            val duplicate = DownloadQueueEntity("nov_dao", "ch_1", queuedAt = 2000L)
            val rowId2 = downloadQueueDao.insertIgnore(duplicate)
            assertEquals(-1L, rowId2)

            val fetched = downloadQueueDao.getItem("nov_dao", "ch_1")
            assertNotNull(fetched)
            assertEquals(1000L, fetched?.queuedAt)
        }
    }

    @Test
    fun dao_fifoOrdering_returnsEarliestQueuedFirst() = runBlocking<Unit> {
        withTimeout(10_000) {
            insertTestNovelAndChapters("nov_fifo", 3)

            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_fifo", "ch_3", queuedAt = 3000L))
            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_fifo", "ch_1", queuedAt = 1000L))
            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_fifo", "ch_2", queuedAt = 2000L))

            val batch = downloadQueueDao.getNextQueuedBatch(2)
            assertEquals(2, batch.size)
            assertEquals("ch_1", batch[0].chapterId)
            assertEquals("ch_2", batch[1].chapterId)

            val single = downloadQueueDao.getNextQueued()
            assertEquals("ch_1", single?.chapterId)
        }
    }

    @Test
    fun dao_resetDownloadingToQueued_recoversAbandonedWork() = runBlocking<Unit> {
        withTimeout(10_000) {
            insertTestNovelAndChapters("nov_recover", 2)

            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_recover", "ch_1", status = "DOWNLOADING"))
            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_recover", "ch_2", status = "QUEUED"))

            val affected = downloadQueueDao.resetDownloadingToQueued()
            assertEquals(1, affected)

            val item1 = downloadQueueDao.getItem("nov_recover", "ch_1")
            assertEquals("QUEUED", item1?.status)
        }
    }

    @Test
    fun dao_pauseAndResumeQueue_transitionsStatesCorrectly() = runBlocking<Unit> {
        withTimeout(10_000) {
            insertTestNovelAndChapters("nov_pause", 3)

            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_pause", "ch_1", status = "QUEUED"))
            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_pause", "ch_2", status = "DOWNLOADING"))
            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_pause", "ch_3", status = "COMPLETED"))

            val paused = downloadQueueDao.pauseQueue()
            assertEquals(2, paused)

            assertEquals("PAUSED", downloadQueueDao.getItem("nov_pause", "ch_1")?.status)
            assertEquals("PAUSED", downloadQueueDao.getItem("nov_pause", "ch_2")?.status)
            assertEquals("COMPLETED", downloadQueueDao.getItem("nov_pause", "ch_3")?.status)

            val resumed = downloadQueueDao.resumeQueue()
            assertEquals(2, resumed)
            assertEquals("QUEUED", downloadQueueDao.getItem("nov_pause", "ch_1")?.status)
            assertEquals("QUEUED", downloadQueueDao.getItem("nov_pause", "ch_2")?.status)
        }
    }

    @Test
    fun dao_retryFailed_resetsCountersAndStatus() = runBlocking<Unit> {
        withTimeout(10_000) {
            insertTestNovelAndChapters("nov_retry", 1)

            downloadQueueDao.insertIgnore(
                DownloadQueueEntity("nov_retry", "ch_1", status = "FAILED", retryCount = 3, errorMessage = "Error")
            )

            val affected = downloadQueueDao.retryFailed()
            assertEquals(1, affected)

            val item = downloadQueueDao.getItem("nov_retry", "ch_1")
            assertEquals("QUEUED", item?.status)
            assertEquals(0, item?.retryCount)
            assertNull(item?.errorMessage)
        }
    }

    @Test
    fun dao_clearCompleted_removesOnlyCompleted() = runBlocking<Unit> {
        withTimeout(10_000) {
            insertTestNovelAndChapters("nov_clear", 3)

            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_clear", "ch_1", status = "COMPLETED"))
            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_clear", "ch_2", status = "COMPLETED"))
            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_clear", "ch_3", status = "QUEUED"))

            val deleted = downloadQueueDao.clearCompleted()
            assertEquals(2, deleted)
            assertNull(downloadQueueDao.getItem("nov_clear", "ch_1"))
            assertNull(downloadQueueDao.getItem("nov_clear", "ch_2"))
            assertNotNull(downloadQueueDao.getItem("nov_clear", "ch_3"))
        }
    }

    // --- 2. DOWNLOAD ENGINE TESTS ---

    @Test
    fun engine_enqueueChapters_skipsWhenNovelMissing_andInsertsNoOrphanRows() = runBlocking<Unit> {
        withTimeout(10_000) {
            val result = downloadEngine.enqueueChapters("missing_novel", listOf("ch_1", "ch_2"))

            assertEquals(0, result.enqueuedCount)
            assertEquals(2, result.skippedCount)
            assertTrue(result.skippedReasons.containsKey("ch_1"))
            assertEquals(0, downloadQueueDao.getTotalCount())
        }
    }

    @Test
    fun engine_enqueueChapters_skipsMissingChapters_andEnqueuesExisting() = runBlocking<Unit> {
        withTimeout(10_000) {
            insertTestNovelAndChapters("nov_engine", 2)

            val result = downloadEngine.enqueueChapters("nov_engine", listOf("ch_1", "ch_missing", "ch_2"))

            assertEquals(2, result.enqueuedCount)
            assertEquals(1, result.skippedCount)
            assertEquals(listOf("ch_missing"), result.skippedChapterIds)

            assertEquals(2, downloadQueueDao.getTotalCount())
            assertNotNull(downloadQueueDao.getItem("nov_engine", "ch_1"))
            assertNotNull(downloadQueueDao.getItem("nov_engine", "ch_2"))
            assertNull(downloadQueueDao.getItem("nov_engine", "ch_missing"))
        }
    }

    @Test
    fun engine_queueSummaryFlow_accuratelyAggregatesStatuses() = runBlocking<Unit> {
        withTimeout(10_000) {
            insertTestNovelAndChapters("nov_summary", 5)

            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_summary", "ch_1", status = "QUEUED"))
            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_summary", "ch_2", status = "DOWNLOADING"))
            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_summary", "ch_3", status = "PAUSED"))
            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_summary", "ch_4", status = "FAILED"))
            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_summary", "ch_5", status = "COMPLETED"))

            val summary = downloadEngine.queueSummaryFlow.first()
            assertEquals(5, summary.totalCount)
            assertEquals(1, summary.queuedCount)
            assertEquals(1, summary.downloadingCount)
            assertEquals(1, summary.pausedCount)
            assertEquals(1, summary.failedCount)
            assertEquals(1, summary.completedCount)
        }
    }

    @Test
    fun engine_autoDownloadNext_enqueuesNextEligibleChapters() = runBlocking<Unit> {
        withTimeout(10_000) {
            insertTestNovelAndChapters("nov_auto", 5)

            // Starting after chapter index 2, requested count 2 -> chapters 3 and 4
            val result = downloadEngine.autoDownloadNext(novelId = "nov_auto", fromChapterIndex = 2, count = 2)

            assertEquals(2, result.enqueuedCount)
            assertEquals(listOf("ch_3", "ch_4"), result.enqueuedChapterIds)
            assertEquals(0, result.skippedCount)
        }
    }

    @Test
    fun engine_autoDownloadNext_skipsAlreadyDownloadedOrQueuedChapters() = runBlocking<Unit> {
        withTimeout(10_000) {
            insertTestNovelAndChapters("nov_auto_skip", 5)

            // Mark chapter 3 as DOWNLOADED
            chapterDao.updateStorageTier(
                novelId = "nov_auto_skip",
                id = "ch_3",
                storageTier = "DOWNLOADED",
                storageLocation = "INTERNAL",
                fileHash = "hash3"
            )

            // Pre-enqueue chapter 4 into queue
            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_auto_skip", "ch_4", status = "QUEUED"))

            // After index 2, request count 2 -> ch_3 is DOWNLOADED, ch_4 is QUEUED, so only ch_5 is eligible
            val result = downloadEngine.autoDownloadNext(novelId = "nov_auto_skip", fromChapterIndex = 2, count = 2)

            assertEquals(1, result.enqueuedCount)
            assertEquals(listOf("ch_5"), result.enqueuedChapterIds)
        }
    }

    // --- 3. DOWNLOAD QUEUE WORKER TESTS ---

    @Test
    fun worker_processesQueue_andMarksCompleted() = runBlocking<Unit> {
        withTimeout(10_000) {
            insertTestNovelAndChapters("nov_worker_1", 2)
            downloadEngine.enqueueChapters("nov_worker_1", listOf("ch_1", "ch_2"))

            val worker = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
            val result = worker.doWork()

            assertTrue(result is ListenableWorker.Result.Success)

            assertNull(downloadQueueDao.getItem("nov_worker_1", "ch_1"))
            assertNull(downloadQueueDao.getItem("nov_worker_1", "ch_2"))

            // Verify storage tier in Room
            val ch1 = chapterDao.getChapter("nov_worker_1", "ch_1")
            val ch2 = chapterDao.getChapter("nov_worker_1", "ch_2")
            assertEquals("DOWNLOADED", ch1?.storageTier)
            assertEquals("DOWNLOADED", ch2?.storageTier)
        }
    }

    @Test
    fun worker_onFailure_retriesWithExponentialBackoff_andMarksFailedOnMaxRetries() = runBlocking<Unit> {
        withTimeout(10_000) {
            insertTestNovelAndChapters("nov_fail", 1)
            fakeSource.failChapters.add("ch_1")
            downloadEngine.enqueueChapters("nov_fail", listOf("ch_1"))

            val worker = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
            worker.doWork()

            // 3 consecutive failures inside the worker loop transition to FAILED
            val item = downloadQueueDao.getItem("nov_fail", "ch_1")
            assertEquals("FAILED", item?.status)
            assertEquals(3, item?.retryCount)
            assertTrue(item?.errorMessage?.contains("Max retries") == true)
        }
    }

    @Test
    fun worker_stopsImmediately_whenQueueIsPaused() = runBlocking<Unit> {
        withTimeout(10_000) {
            insertTestNovelAndChapters("nov_worker_pause", 2)
            downloadEngine.enqueueChapters("nov_worker_pause", listOf("ch_1", "ch_2"))
            downloadEngine.pauseQueue()

            val worker = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
            val result = worker.doWork()

            assertTrue(result is ListenableWorker.Result.Success)

            // Must remain PAUSED, not processed or failed
            val item1 = downloadQueueDao.getItem("nov_worker_pause", "ch_1")
            val item2 = downloadQueueDao.getItem("nov_worker_pause", "ch_2")
            assertEquals("PAUSED", item1?.status)
            assertEquals("PAUSED", item2?.status)
            assertEquals(0, item1?.retryCount)
        }
    }

    // --- 4. STORAGE WARNING RATE-LIMIT TEST ---

    @Test
    fun rateLimit_storageWarning_producesExactlyOneWarningAndNotificationFor50Chapters() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_unwritable_50"
            insertTestNovelAndChapters(novelId, 50)

            // Set an unwritable custom location (in test environment, mock SAF URI is unwritable)
            repository.settingsRepository.updateDownloadSettings {
                it.copy(customDownloadLocationUri = "content://unwritable/tree")
            }

            val warnings = mutableListOf<String>()
            val collectJob = launch(Dispatchers.Unconfined) {
                repository.chapterRepository.storageWarnings.collect {
                    warnings.add(it)
                }
            }

            val chapters = chapterDao.getChapters(novelId)
            val novel = novelDao.getNovelById(novelId)!!.toModel()

            // Download all 50 chapters
            for (ch in chapters) {
                val success = repository.chapterRepository.downloadChapter(novel, ch.toModel())
                assertTrue(success.isSuccess)
            }

            collectJob.cancel()

            // Exactly 1 warning event emitted across 50 chapters
            assertEquals(1, warnings.size)

            // Exactly 1 notification record persisted in database
            val notifications = notificationDao.getAllNotifications()
            val downloadNotifications = notifications.filter { it.type == NotificationTypes.DOWNLOAD }
            assertEquals(1, downloadNotifications.size)
        }
    }

    // --- 5. REPOSITORY INTERFACE TEST ---

    @Test
    fun repository_exposesDownloadEngineFunctionsDirectly() = runBlocking<Unit> {
        withTimeout(10_000) {
            insertTestNovelAndChapters("nov_repo_expose", 3)

            val enqueueResult = repository.enqueueChapters("nov_repo_expose", listOf("ch_1", "ch_2"))
            assertEquals(2, enqueueResult.enqueuedCount)

            val queueList = repository.downloadQueueFlow.first()
            assertEquals(2, queueList.size)

            val summary = repository.downloadQueueSummaryFlow.first()
            assertEquals(2, summary.totalCount)

            repository.pauseDownloadQueue()
            assertEquals("PAUSED", downloadQueueDao.getItem("nov_repo_expose", "ch_1")?.status)

            repository.resumeDownloadQueue()
            assertEquals("QUEUED", downloadQueueDao.getItem("nov_repo_expose", "ch_1")?.status)

            repository.cancelDownloadChapter("nov_repo_expose", "ch_1")
            assertNull(downloadQueueDao.getItem("nov_repo_expose", "ch_1"))

            repository.cancelNovelDownloads("nov_repo_expose")
            assertEquals(0, downloadQueueDao.getTotalCount())
        }
    }

    // --- 6. ADDITIONAL ENGINE & WORKER TESTS ---

    @Test
    fun concurrency_neverExceedsSimultaneousDownloads() = runBlocking<Unit> {
        withTimeout(10_000) {
            val source1 = TestWorkerSource("source_c1")
            val source2 = TestWorkerSource("source_c2")
            SourceManager.registerSource(source1)
            SourceManager.registerSource(source2)
            try {
                val novel1 = NovelEntity(
                    id = "nov_c1",
                    title = "Novel 1",
                    author = "Author",
                    description = "Desc",
                    sourceId = source1.id,
                    sourceName = source1.name,
                    status = "Ongoing",
                    inLibrary = true
                )
                val novel2 = NovelEntity(
                    id = "nov_c2",
                    title = "Novel 2",
                    author = "Author",
                    description = "Desc",
                    sourceId = source2.id,
                    sourceName = source2.name,
                    status = "Ongoing",
                    inLibrary = true
                )
                novelDao.insertIgnore(novel1)
                novelDao.insertIgnore(novel2)

                chapterDao.insertIgnore((1..5).map { idx ->
                    ChapterEntity(novelId = "nov_c1", id = "ch_c1_$idx", title = "Chapter $idx", chapterIndex = idx)
                })
                chapterDao.insertIgnore((1..5).map { idx ->
                    ChapterEntity(novelId = "nov_c2", id = "ch_c2_$idx", title = "Chapter $idx", chapterIndex = idx)
                })

                repository.settingsRepository.updateDownloadSettings {
                    it.copy(simultaneousDownloads = 2)
                }

                downloadEngine.enqueueChapters("nov_c1", (1..5).map { "ch_c1_$it" })
                downloadEngine.enqueueChapters("nov_c2", (1..5).map { "ch_c2_$it" })

                val worker = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
                val result = worker.doWork()

                assertTrue(result is ListenableWorker.Result.Success)
                // With per-source limit = 1, each source's concurrent calls is at most 1
                assertTrue("Source 1 max concurrent calls was ${source1.maxConcurrentCalls}, expected <= 1", source1.maxConcurrentCalls <= 1)
                assertTrue("Source 2 max concurrent calls was ${source2.maxConcurrentCalls}, expected <= 1", source2.maxConcurrentCalls <= 1)
                assertEquals(10, chapterDao.getAllDownloadedChapters().size)
            } finally {
                SourceManager.unregisterSource(source1.id)
                SourceManager.unregisterSource(source2.id)
            }
        }
    }

    @Test
    fun cancelChapter_andCancelAll_removeRowsAndStopWork() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_cancel_flow"
            insertTestNovelAndChapters(novelId, 3)

            downloadEngine.enqueueChapters(novelId, listOf("ch_1", "ch_2", "ch_3"))
            assertEquals(3, downloadQueueDao.getTotalCount())

            // cancelChapter removes single chapter row
            downloadEngine.cancelChapter(novelId, "ch_1")
            assertNull(downloadQueueDao.getItem(novelId, "ch_1"))
            assertNotNull(downloadQueueDao.getItem(novelId, "ch_2"))
            assertNotNull(downloadQueueDao.getItem(novelId, "ch_3"))
            assertEquals(2, downloadQueueDao.getTotalCount())

            // cancelAll removes all rows and cancels work in WorkManager
            downloadEngine.cancelAll()
            assertEquals(0, downloadQueueDao.getTotalCount())

            val workInfos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(DownloadEngine.WORK_NAME).get()
            assertTrue(workInfos.all { it.state.isFinished })
        }
    }

    @Test
    fun wifiOnly_true_usesUnmeteredConstraint_andFalse_usesConnected() = runBlocking<Unit> {
        withTimeout(10_000) {
            // Case 1: wifiOnly = true -> UNMETERED constraint
            repository.settingsRepository.updateDownloadSettings { it.copy(wifiOnly = true) }
            downloadEngine.restartWorker()

            val workInfosTrue = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(DownloadEngine.WORK_NAME).get()
            val workInfoTrue = workInfosTrue.firstOrNull()
            assertNotNull("Work request should be scheduled", workInfoTrue)
            assertEquals(
                NetworkType.UNMETERED,
                workInfoTrue!!.constraints.requiredNetworkType
            )

            // Case 2: wifiOnly = false -> CONNECTED constraint
            repository.settingsRepository.updateDownloadSettings { it.copy(wifiOnly = false) }
            downloadEngine.restartWorker()

            val workInfosFalse = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(DownloadEngine.WORK_NAME).get()
            val workInfoFalse = workInfosFalse.firstOrNull()
            assertNotNull("Work request should be scheduled", workInfoFalse)
            assertEquals(
                NetworkType.CONNECTED,
                workInfoFalse!!.constraints.requiredNetworkType
            )
        }
    }

    @Test
    fun worker_startedWithDownloadingRows_recoversThemToQueuedAndCompletes() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_worker_recover"
            insertTestNovelAndChapters(novelId, 2)

            // Pre-insert rows in DOWNLOADING state simulating an interrupted worker run
            downloadQueueDao.insertIgnore(DownloadQueueEntity(novelId, "ch_1", status = "DOWNLOADING"))
            downloadQueueDao.insertIgnore(DownloadQueueEntity(novelId, "ch_2", status = "DOWNLOADING"))

            assertEquals("DOWNLOADING", downloadQueueDao.getItem(novelId, "ch_1")?.status)
            assertEquals("DOWNLOADING", downloadQueueDao.getItem(novelId, "ch_2")?.status)

            val worker = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
            val result = worker.doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertNull(downloadQueueDao.getItem(novelId, "ch_1"))
            assertNull(downloadQueueDao.getItem(novelId, "ch_2"))

            val ch1 = chapterDao.getChapter(novelId, "ch_1")
            val ch2 = chapterDao.getChapter(novelId, "ch_2")
            assertEquals("DOWNLOADED", ch1?.storageTier)
            assertEquals("DOWNLOADED", ch2?.storageTier)
        }
    }

    @Test
    fun throttling_insertsDelayBetweenRequests() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_throttle_delay"
            insertTestNovelAndChapters(novelId, 2)

            // Inject 600ms throttle delay
            DownloadQueueWorker.throttleDelayOverrideMs = 600L

            repository.settingsRepository.updateDownloadSettings {
                it.copy(
                    throttlingEnabled = true,
                    throttleBatchSize = 1,
                    simultaneousDownloads = 1
                )
            }

            fakeSource.callTimes.clear()
            downloadEngine.enqueueChapters(novelId, listOf("ch_1", "ch_2"))

            val worker = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
            val result = worker.doWork()
            val workerEndTime = System.currentTimeMillis()

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals(2, fakeSource.callTimes.size)

            val timeReq1 = fakeSource.callTimes[0]
            val timeReq2 = fakeSource.callTimes[1]

            val intervalBetweenRequests = timeReq2 - timeReq1
            val intervalAfterLastRequest = workerEndTime - timeReq2

            // Delay was invoked between request 1 and request 2
            assertTrue(
                "Expected delay >= 500ms between requests, got $intervalBetweenRequests ms",
                intervalBetweenRequests >= 500L
            )
            // Time after last request is worker cleanup, not throttle delay (< 1000ms)
            assertTrue(
                "Expected time after last request < 1000ms, got $intervalAfterLastRequest ms",
                intervalAfterLastRequest < 1000L
            )

            assertNull(downloadQueueDao.getItem(novelId, "ch_1"))
            assertNull(downloadQueueDao.getItem(novelId, "ch_2"))
            assertEquals("DOWNLOADED", chapterDao.getChapter(novelId, "ch_1")?.storageTier)
            assertEquals("DOWNLOADED", chapterDao.getChapter(novelId, "ch_2")?.storageTier)
        }
    }

    @Test
    fun failedChapter_doesNotStopOthers() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_fail_other_3"
            insertTestNovelAndChapters(novelId, 3)

            fakeSource.failChapters.add("ch_2")
            downloadEngine.enqueueChapters(novelId, listOf("ch_1", "ch_2", "ch_3"))

            val worker = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
            val result = worker.doWork()

            assertTrue(result is ListenableWorker.Result.Success)

            // Chapter 1 and Chapter 3 complete successfully (tier is DOWNLOADED, queue rows cleared)
            assertNull(downloadQueueDao.getItem(novelId, "ch_1"))
            assertNull(downloadQueueDao.getItem(novelId, "ch_3"))
            assertEquals("DOWNLOADED", chapterDao.getChapter(novelId, "ch_1")?.storageTier)
            assertEquals("DOWNLOADED", chapterDao.getChapter(novelId, "ch_3")?.storageTier)

            // Chapter 2 fails 3 times and is marked FAILED with an error message
            val item2 = downloadQueueDao.getItem(novelId, "ch_2")
            assertEquals("FAILED", item2?.status)
            assertEquals(3, item2?.retryCount)
            assertNotNull(item2?.errorMessage)
            assertTrue(item2?.errorMessage?.contains("Max retries") == true)
        }
    }

    @Test
    fun worker_skipsAlreadyDownloadedChapters_marksCompletedImmediately() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_skip_downloaded"
            insertTestNovelAndChapters(novelId, 2)

            // Mark chapter 1 as already DOWNLOADED in database
            chapterDao.updateStorageTier(
                novelId = novelId,
                id = "ch_1",
                storageTier = "DOWNLOADED",
                storageLocation = "INTERNAL",
                fileHash = "hash1"
            )

            downloadEngine.enqueueChapters(novelId, listOf("ch_1", "ch_2"))
            fakeSource.callTimes.clear()

            val worker = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
            val result = worker.doWork()

            assertTrue(result is ListenableWorker.Result.Success)

            assertNull(downloadQueueDao.getItem(novelId, "ch_1"))
            assertNull(downloadQueueDao.getItem(novelId, "ch_2"))
            assertEquals("DOWNLOADED", chapterDao.getChapter(novelId, "ch_1")?.storageTier)
            assertEquals("DOWNLOADED", chapterDao.getChapter(novelId, "ch_2")?.storageTier)

            // Only chapter 2 was actually downloaded from source
            assertEquals(1, fakeSource.callTimes.size)
        }
    }

    @Test
    fun engine_enqueueChapters_requeuesExistingFailedOrCompletedRows() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_requeue_test"
            insertTestNovelAndChapters(novelId, 3)

            // Insert initial queue rows with FAILED and COMPLETED states
            downloadQueueDao.insertIgnore(
                DownloadQueueEntity(
                    novelId = novelId,
                    chapterId = "ch_1",
                    status = "FAILED",
                    retryCount = 3,
                    errorMessage = "Previous failure"
                )
            )
            downloadQueueDao.insertIgnore(
                DownloadQueueEntity(
                    novelId = novelId,
                    chapterId = "ch_2",
                    status = "COMPLETED"
                )
            )

            // Enqueue chapters 1, 2, and 3
            downloadEngine.enqueueChapters(novelId, listOf("ch_1", "ch_2", "ch_3"))

            val item1 = downloadQueueDao.getItem(novelId, "ch_1")
            val item2 = downloadQueueDao.getItem(novelId, "ch_2")
            val item3 = downloadQueueDao.getItem(novelId, "ch_3")

            assertEquals("QUEUED", item1?.status)
            assertEquals(0, item1?.retryCount)
            assertNull(item1?.errorMessage)

            assertEquals("QUEUED", item2?.status)
            assertEquals(0, item2?.retryCount)

            assertEquals("QUEUED", item3?.status)
        }
    }

    @Test
    fun chapterRepository_deleteNovelDownloads_cleansDownloadQueue() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_del_queue_clean"
            insertTestNovelAndChapters(novelId, 2)

            downloadEngine.enqueueChapters(novelId, listOf("ch_1", "ch_2"))
            assertEquals(2, downloadQueueDao.getTotalCount())

            repository.chapterRepository.deleteNovelDownloads(novelId)

            assertEquals(0, downloadQueueDao.getTotalCount())
        }
    }

    @Test
    fun worker_timeLimitReached_leavesFollowUpEnqueuedUnderUniqueWorkName() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_time_limit"
            insertTestNovelAndChapters(novelId, 2)

            downloadQueueDao.insertIgnore(DownloadQueueEntity(novelId, "ch_1", status = "QUEUED"))
            downloadQueueDao.insertIgnore(DownloadQueueEntity(novelId, "ch_2", status = "QUEUED"))

            DownloadQueueWorker.maxWorkerDurationOverrideMs = 0L
            try {
                // Pre-enqueue existing unique work under WORK_NAME so KEEP would drop the follow-up
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
                val initialRequest = OneTimeWorkRequestBuilder<DownloadQueueWorker>()
                    .setConstraints(constraints)
                    .addTag(DownloadEngine.TAG_DOWNLOAD)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    DownloadEngine.WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    initialRequest
                )

                val worker = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
                val result = worker.doWork()

                assertTrue(result is ListenableWorker.Result.Success)

                val workInfos = WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(DownloadEngine.WORK_NAME)
                    .get()

                // With APPEND_OR_REPLACE, the follow-up work is chained/appended.
                // With KEEP, WorkManager would retain only initialRequest and drop the follow-up.
                assertTrue("Expected follow-up work to be added (total work infos >= 2)", workInfos.size >= 2)
                val followUp = workInfos.firstOrNull { it.id != initialRequest.id }
                assertNotNull("Chained follow-up work request must exist", followUp)
                val followUpState = followUp!!.state
                assertTrue(
                    "Follow-up work should be ENQUEUED or BLOCKED, was $followUpState",
                    followUpState == WorkInfo.State.ENQUEUED || followUpState == WorkInfo.State.BLOCKED
                )
            } finally {
                DownloadQueueWorker.maxWorkerDurationOverrideMs = null
            }
        }
    }

    @Test
    fun pausedRow_isNotOverwrittenByGuardedFailureUpdate() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_paused_guard"
            insertTestNovelAndChapters(novelId, 1)

            downloadQueueDao.insertIgnore(
                DownloadQueueEntity(
                    novelId = novelId,
                    chapterId = "ch_1",
                    status = "PAUSED",
                    retryCount = 0
                )
            )

            // Attempt failure update with guard (WHERE status = 'DOWNLOADING')
            val updatedRows = downloadQueueDao.updateStatusAndRetryIfDownloading(
                novelId = novelId,
                chapterId = "ch_1",
                status = "FAILED",
                retryCount = 3,
                errorMessage = "Attempted overwrite"
            )

            assertEquals(0, updatedRows)
            val item = downloadQueueDao.getItem(novelId, "ch_1")
            assertEquals("PAUSED", item?.status)
            assertEquals(0, item?.retryCount)
            assertNull(item?.errorMessage)
        }
    }

    @Test
    fun enqueueNovel_requeuesFailedChapter_andDoesNotQueueDownloadedOrEpub() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_enqueue_novel_filter"

            val novel = testNovelEntity(
                id = novelId,
                title = "Filter Test Novel",
                author = "Author",
                genresJson = "[]",
                sourceId = "test_source",
                inLibrary = true
            )
            database.novelDao().insertIgnore(novel)

            val ch1 = ChapterEntity(novelId, "ch_1", "Ch 1", 1, storageTier = "DOWNLOADED")
            val ch2 = ChapterEntity(novelId, "ch_2", "Ch 2", 2, storageTier = "EPUB")
            val ch3 = ChapterEntity(novelId, "ch_3", "Ch 3", 3, storageTier = "NONE")
            val ch4 = ChapterEntity(novelId, "ch_4", "Ch 4", 4, storageTier = "NONE")
            database.chapterDao().insertIgnore(listOf(ch1, ch2, ch3, ch4))

            // Pre-seed ch_3 as FAILED in download_queue
            downloadQueueDao.insertIgnore(
                DownloadQueueEntity(
                    novelId = novelId,
                    chapterId = "ch_3",
                    status = "FAILED",
                    retryCount = 3,
                    errorMessage = "Failed previously"
                )
            )

            val result = downloadEngine.enqueueNovel(novelId)

            assertEquals(listOf("ch_3", "ch_4"), result.enqueuedChapterIds)
            assertEquals(2, result.enqueuedCount)

            // Verify ch_3 was requeued to QUEUED with reset retries and error
            val item3 = downloadQueueDao.getItem(novelId, "ch_3")
            assertEquals("QUEUED", item3?.status)
            assertEquals(0, item3?.retryCount)
            assertNull(item3?.errorMessage)

            // Verify ch_4 was newly queued
            val item4 = downloadQueueDao.getItem(novelId, "ch_4")
            assertEquals("QUEUED", item4?.status)

            // Verify ch_1 and ch_2 were never inserted into queue
            assertNull(downloadQueueDao.getItem(novelId, "ch_1"))
            assertNull(downloadQueueDao.getItem(novelId, "ch_2"))
        }
    }

    // --- 7. RACE CONDITIONS & COORDINATOR TESTS ---

    @Test
    fun promoteFromCache_copiesToDownloads_andCasSetsDownloaded() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_evict_promote"
            insertTestNovelAndChapters(novelId, 1)

            // Read chapter into untracked ChapterCache
            fakeSource.callTimes.clear()
            val readContent = repository.chapterRepository.getChapterContent(novelId, "ch_1")
            assertNotNull(readContent)
            assertEquals(1, fakeSource.callTimes.size)
            assertTrue(repository.chapterCache.contains(novelId, "ch_1"))

            // Room row remains NONE
            val rowBefore = chapterDao.getChapter(novelId, "ch_1")
            assertEquals("NONE", rowBefore?.storageTier)

            val novel = novelDao.getNovelById(novelId)!!.toModel()
            val chapter = chapterDao.getChapterById(novelId, "ch_1")!!.toModel()

            // Promote chapter to DOWNLOADED (copies cache file to downloads, CAS to DOWNLOADED, no network)
            fakeSource.callTimes.clear()
            val dlResult = repository.chapterRepository.downloadChapter(novel, chapter)
            assertTrue(dlResult.isSuccess)
            assertEquals(0, fakeSource.callTimes.size) // Promoted from cache, zero network calls!
            assertEquals("DOWNLOADED", chapterDao.getChapter(novelId, "ch_1")?.storageTier)
            assertTrue(repository.storageResolver.getDownloadChapterFile(novelId, "ch_1").exists())
            // Cache copy is removed after promote
            assertFalse(repository.chapterCache.contains(novelId, "ch_1"))

            // Cache trim cannot touch DOWNLOADED files
            repository.chapterCache.trimToBytes(maxBytes = 0L)
            val finalRow = chapterDao.getChapter(novelId, "ch_1")
            assertEquals("DOWNLOADED", finalRow?.storageTier)
            assertTrue(repository.storageResolver.getDownloadChapterFile(novelId, "ch_1").exists())
        }
    }

    @Test
    fun downloadVsReaderFetch_leafLockEnsuresConsistentDownloadedTier() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_dl_reader"
            insertTestNovelAndChapters(novelId, 1)

            val novel = novelDao.getNovelById(novelId)!!.toModel()
            val chapter = chapterDao.getChapterById(novelId, "ch_1")!!.toModel()

            coroutineScope {
                val readDeferred = async(Dispatchers.IO) {
                    repository.chapterRepository.getChapterContent(novelId, "ch_1")
                }
                val downloadDeferred = async(Dispatchers.IO) {
                    repository.chapterRepository.downloadChapter(novel, chapter)
                }

                val content = readDeferred.await()
                val dlResult = downloadDeferred.await()

                assertNotNull(content)
                assertTrue(dlResult.isSuccess)

                // Chapter row must have storageTier DOWNLOADED
                val finalRow = chapterDao.getChapter(novelId, "ch_1")
                assertEquals("DOWNLOADED", finalRow?.storageTier)
            }
        }
    }

    @Test
    fun enqueueChapters_rejectsNovelsBeingDeleted() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_deleting_guard"
            insertTestNovelAndChapters(novelId, 2)

            val coordinator = repository.chapterRepository.downloadCoordinator
            coordinator.deletingNovelIds.add(novelId)
            try {
                val result = downloadEngine.enqueueChapters(novelId, listOf("ch_1", "ch_2"))
                assertEquals(0, result.enqueuedCount)
                assertEquals(2, result.skippedCount)
                assertTrue(result.skippedReasons["ch_1"]?.contains("being deleted") == true)
                assertEquals(0, downloadQueueDao.getTotalCount())
            } finally {
                coordinator.deletingNovelIds.remove(novelId)
            }
        }
    }

    @Test
    fun deleteNovelDownloads_clearsQueueAndGuardsDeletion() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_delete_flow"
            insertTestNovelAndChapters(novelId, 2)
            downloadEngine.enqueueChapters(novelId, listOf("ch_1", "ch_2"))
            assertEquals(2, downloadQueueDao.getTotalCount())

            repository.chapterRepository.deleteNovelDownloads(novelId)

            assertEquals(0, downloadQueueDao.getQueuedChapterIds(novelId).size)
            assertFalse(repository.chapterRepository.downloadCoordinator.deletingNovelIds.contains(novelId))
        }
    }

    @Test
    fun removeNovelCompletely_usesOnlyDeletingNovelIds_andDoesNotSetIsDeletingAll() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_remove_completely"
            insertTestNovelAndChapters(novelId, 2)
            insertTestNovelAndChapters("nov_other", 2)

            assertEquals(0, downloadEngine.downloadCoordinator.isDeletingAll.get())
            repository.chapterRepository.removeNovelCompletely(novelId)
            assertEquals(0, downloadEngine.downloadCoordinator.isDeletingAll.get())
            assertFalse(downloadEngine.downloadCoordinator.deletingNovelIds.contains(novelId))

            // Other novel is unaffected and enqueues normally
            val result = downloadEngine.enqueueChapters("nov_other", listOf("ch_1", "ch_2"))
            assertEquals(2, result.enqueuedCount)
        }
    }

    @Test
    fun missingDownloaded_selfHealsBackToDownloadedInOriginalLocation() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_self_heal"
            insertTestNovelAndChapters(novelId, 1)

            // Mark as DOWNLOADED in Room, but don't create file
            chapterDao.updateTierToDownloaded(
                novelId = novelId,
                id = "ch_1",
                storageLocation = "INTERNAL",
                fileHash = "hash1"
            )

            val downloadFile = repository.storageResolver.getDownloadChapterFile(novelId, "ch_1")
            assertFalse("File must not exist initially", downloadFile.exists())

            // Read miss triggers refetch & self-healing write back as DOWNLOADED
            val content = repository.chapterRepository.getChapterContent(novelId, "ch_1")
            assertNotNull(content)
            assertTrue("Content returned isDownloaded", content.isDownloaded)

            // Verify file written to downloads location
            assertTrue("File was written back to downloads", downloadFile.exists())

            // Verify Room tier remains DOWNLOADED
            val row = chapterDao.getChapter(novelId, "ch_1")
            assertEquals("DOWNLOADED", row?.storageTier)
            assertEquals("INTERNAL", row?.storageLocation)
        }
    }

    @Test
    fun diskFirstCheck_skipsNetworkIfFileExists() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_disk_first"
            insertTestNovelAndChapters(novelId, 1)

            val novel = novelDao.getNovelById(novelId)!!.toModel()
            val chapter = chapterDao.getChapterById(novelId, "ch_1")!!.toModel()

            // Pre-create valid file on disk in downloads/ without setting tier in Room
            repository.chapterStorage.writeToDownloads(
                ChapterContent("ch_1", novelId, "Pre-existing Title", listOf("P1", "P2"), isDownloaded = true),
                "INTERNAL"
            )

            val initialFetchCount = fakeSource.callTimes.size

            val result = repository.chapterRepository.downloadChapter(novel, chapter)
            assertTrue(result.isSuccess)

            // Network was NOT called because file was found on disk
            assertEquals(initialFetchCount, fakeSource.callTimes.size)

            val row = chapterDao.getChapter(novelId, "ch_1")
            assertEquals("DOWNLOADED", row?.storageTier)
        }
    }

    @Test
    fun enqueue_rejectedDuringDeleteAll() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_del_all_reject"
            insertTestNovelAndChapters(novelId, 2)

            downloadEngine.downloadCoordinator.isDeletingAll.incrementAndGet()
            try {
                val result = downloadEngine.enqueueChapters(novelId, listOf("ch_1", "ch_2"))
                assertEquals(0, result.enqueuedCount)
                assertEquals(2, result.skippedCount)
                assertTrue(result.skippedReasons["ch_1"]!!.contains("All downloads are currently being deleted"))
            } finally {
                downloadEngine.downloadCoordinator.isDeletingAll.decrementAndGet()
            }
        }
    }

    @Test
    fun enqueue_usesUnderLockDeletionCheck() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_lock_deletion_check"
            insertTestNovelAndChapters(novelId, 2)

            downloadEngine.downloadCoordinator.deletingNovelIds.add(novelId)
            try {
                val result = downloadEngine.enqueueChapters(novelId, listOf("ch_1", "ch_2"))
                assertEquals(0, result.enqueuedCount)
                assertEquals(2, result.skippedCount)
                assertTrue(result.skippedReasons["ch_1"]!!.contains("currently being deleted"))
            } finally {
                downloadEngine.downloadCoordinator.deletingNovelIds.remove(novelId)
            }
        }
    }

    @Test
    fun failureBeforeTryBlock_doesNotHangAwaitWorkerStopped() = runBlocking<Unit> {
        withTimeout(5_000) {
            downloadEngine.downloadCoordinator.awaitWorkerStopped(timeoutMs = 1_000L)
        }
    }

    @Test
    fun restartWorker_waitsForWorkerToStop() = runBlocking<Unit> {
        withTimeout(10_000) {
            val runId = "test_run_restart"
            downloadEngine.downloadCoordinator.onWorkerStarted(runId)

            val restartJob = launch(Dispatchers.IO) {
                downloadEngine.restartWorker(timeoutMs = 5_000L)
            }

            delay(50)
            assertFalse("restartJob should still be waiting for worker to stop", restartJob.isCompleted)

            downloadEngine.downloadCoordinator.onWorkerStopped(runId)
            restartJob.join()
            assertTrue("restartWorker completed after worker stopped", restartJob.isCompleted)
        }
    }

    @Test
    fun perSourceInFlight_neverExceedsLimit() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_per_source_limit"
            insertTestNovelAndChapters(novelId, 4)

            // Allow up to 3 simultaneous downloads overall
            repository.settingsRepository.updateDownloadSettings {
                it.copy(simultaneousDownloads = 3)
            }

            fakeSource.maxConcurrentCalls = 0
            downloadEngine.enqueueChapters(novelId, listOf("ch_1", "ch_2", "ch_3", "ch_4"))

            val worker = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
            val result = worker.doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            // Even though simultaneousDownloads is 3, all chapters share 1 source, so max in-flight per source is 1
            assertEquals(1, fakeSource.maxConcurrentCalls)
            assertEquals(4, chapterDao.getDownloadedCount(novelId))
            assertEquals(0, downloadQueueDao.countActiveRemaining())
        }
    }

    @Test
    fun backoff_doesNotOccupySlot() = runBlocking<Unit> {
        withTimeout(10_000) {
            val source2 = TestWorkerSource("test_source_2")
            SourceManager.registerSource(source2)
            try {
                // Novel 1 uses fakeSource ("test_source")
                val novel1 = testNovelEntity(
                    id = "nov_slot_1",
                    title = "Novel 1",
                    author = "Author",
                    description = "Desc",
                    sourceId = fakeSource.id,
                    sourceName = fakeSource.name,
                    status = "Ongoing"
                )
                novelDao.insertIgnore(novel1)
                chapterDao.insertIgnore(ChapterEntity("nov_slot_1", "ch_1", "Chapter 1", 1))

                // Novel 2 uses source2 ("test_source_2")
                val novel2 = testNovelEntity(
                    id = "nov_slot_2",
                    title = "Novel 2",
                    author = "Author",
                    description = "Desc",
                    sourceId = source2.id,
                    sourceName = source2.name,
                    status = "Ongoing"
                )
                novelDao.insertIgnore(novel2)
                chapterDao.insertIgnore(ChapterEntity("nov_slot_2", "ch_2", "Chapter 2", 1))

                // Make novel 1 fail so it enters backoff
                fakeSource.failChapters.add("ch_1")

                // Settings: 2 simultaneous slots
                repository.settingsRepository.updateDownloadSettings {
                    it.copy(simultaneousDownloads = 2)
                }

                downloadEngine.enqueueChapters("nov_slot_1", listOf("ch_1"))
                downloadEngine.enqueueChapters("nov_slot_2", listOf("ch_2"))

                val worker = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
                val result = worker.doWork()

                assertTrue(result is ListenableWorker.Result.Success)
                // Novel 2's chapter 2 succeeded without waiting for Novel 1's retries to block slots
                assertNull(downloadQueueDao.getItem("nov_slot_2", "ch_2"))
                assertEquals("DOWNLOADED", chapterDao.getChapter("nov_slot_2", "ch_2")?.storageTier)
                assertEquals("FAILED", downloadQueueDao.getItem("nov_slot_1", "ch_1")?.status)
            } finally {
                SourceManager.unregisterSource(source2.id)
            }
        }
    }

    @Test
    fun rowsWaitingForNotBefore_keepWorkerAlive() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_wait_alive"

            var fetchCount = 0
            val dynamicSource = object : NovelSource {
                override val id = "dynamic_source"
                override val name = "Dynamic Source"
                override val language = "en"
                override val version = "1.0.0"
                override val description = "Dynamic"
                override val isLocal = false
                override suspend fun getPopularNovels(page: Int): List<Novel> = emptyList()
                override suspend fun searchNovels(query: String, page: Int): List<Novel> = emptyList()
                override suspend fun getNovelDetails(novelId: String): Novel = testNovel(
                    id = novelId,
                    title = "Title",
                    author = "Author",
                    description = "Desc",
                    sourceId = id,
                    sourceName = name,
                    status = "Ongoing"
                )
                override suspend fun getChapterList(novelId: String): List<Chapter> = emptyList()
                override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent {
                    fetchCount++
                    if (fetchCount == 1) {
                        throw IOException("Temporary network error on attempt 1")
                    }
                    return ChapterContent(chapterId, novelId, "Title", listOf("Success text"), isDownloaded = true)
                }
            }
            SourceManager.registerSource(dynamicSource)
            try {
                val novel = testNovelEntity(
                    id = novelId,
                    title = "Dynamic Novel",
                    author = "Author",
                    description = "Desc",
                    sourceId = dynamicSource.id,
                    sourceName = dynamicSource.name,
                    status = "Ongoing"
                )
                novelDao.insertIgnore(novel)
                chapterDao.insertIgnore(ChapterEntity(novelId, "ch_1", "Chapter 1", 1))

                downloadEngine.enqueueChapters(novelId, listOf("ch_1"))

                val worker = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
                val result = worker.doWork()

                assertTrue(result is ListenableWorker.Result.Success)
                assertEquals(2, fetchCount)
                assertEquals("DOWNLOADED", chapterDao.getChapter(novelId, "ch_1")?.storageTier)
                assertNull(downloadQueueDao.getItem(novelId, "ch_1"))
            } finally {
                SourceManager.unregisterSource(dynamicSource.id)
            }
        }
    }

    @Test
    fun unpickableQueuedRow_markedFailed_andDoesNotKeepWorkerAlive() = runBlocking<Unit> {
        withTimeout(10_000) {
            // Novel and chapter exist, but source is not registered
            val unregisteredNovel = testNovelEntity(
                id = "nov_unreg_src",
                title = "Novel Unregistered",
                author = "Author",
                description = "Desc",
                sourceId = "non_existent_source_xyz",
                sourceName = "Unregistered",
                status = "Ongoing"
            )
            novelDao.insertIgnore(unregisteredNovel)
            chapterDao.insertIgnore(listOf(ChapterEntity(novelId = "nov_unreg_src", id = "ch_unreg_1", title = "Ch 1", chapterIndex = 1)))
            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_unreg_src", "ch_unreg_1", status = "QUEUED", queuedAt = 3000L))

            val worker = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
            val result = worker.doWork()

            assertTrue(result is ListenableWorker.Result.Success)

            val item = downloadQueueDao.getItem("nov_unreg_src", "ch_unreg_1")
            assertEquals("FAILED", item?.status)
            assertTrue(item?.errorMessage?.contains("Source not registered") == true)

            assertEquals(0, downloadQueueDao.countActiveRemaining())
        }
    }

    @Test
    fun cancelChapter_reallyCancelsAndWritesNothing() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_cancel_real"
            val fetchStarted = CompletableDeferred<Unit>()
            val allowFetchFinish = CompletableDeferred<Unit>()

            val slowSource = object : NovelSource {
                override val id = "slow_cancel_source"
                override val name = "Slow Source"
                override val language = "en"
                override val version = "1.0"
                override val description = "Slow source for cancel test"
                override val isLocal = false
                override suspend fun getPopularNovels(page: Int): List<Novel> = emptyList()
                override suspend fun searchNovels(query: String, page: Int): List<Novel> = emptyList()
                override suspend fun getNovelDetails(novelId: String): Novel = testNovel(
                    id = novelId,
                    title = "Title",
                    author = "Author",
                    description = "Desc",
                    sourceId = id,
                    sourceName = name
                )
                override suspend fun getChapterList(novelId: String): List<Chapter> = emptyList()
                override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent {
                    fetchStarted.complete(Unit)
                    allowFetchFinish.await()
                    return ChapterContent(chapterId, novelId, "Title", listOf("Text"))
                }
            }
            SourceManager.registerSource(slowSource)
            try {
                val novel = NovelEntity(
                    id = novelId,
                    title = "Cancel Novel",
                    author = "Author",
                    description = "Desc",
                    sourceId = slowSource.id,
                    sourceName = slowSource.name,
                    status = "Ongoing",
                    inLibrary = true
                )
                novelDao.insertIgnore(novel)
                chapterDao.insertIgnore(listOf(
                    ChapterEntity(novelId = novelId, id = "ch_1", title = "Ch 1", chapterIndex = 1),
                    ChapterEntity(novelId = novelId, id = "ch_2", title = "Ch 2", chapterIndex = 2)
                ))

                downloadEngine.enqueueChapters(novelId, listOf("ch_1"))

                // Launch worker in background
                val workerJob = launch(Dispatchers.IO) {
                    val worker = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
                    worker.doWork()
                }

                // Wait until download has started fetching
                fetchStarted.await()

                // Cancel chapter while it's in-flight
                val cancelJob = launch(Dispatchers.IO) {
                    downloadEngine.cancelChapter(novelId, "ch_1")
                }

                cancelJob.join()

                // Now unblock the source fetch
                allowFetchFinish.complete(Unit)

                workerJob.join()

                // Verify chapter row in Room was never set to DOWNLOADED
                val chapterEntity = chapterDao.getChapterById(novelId, "ch_1")
                assertEquals("NONE", chapterEntity?.storageTier)

                // Verify queue item was deleted
                assertNull(downloadQueueDao.getItem(novelId, "ch_1"))

                // Verify no download file exists
                val content = repository.chapterStorage.read(novelId, "ch_1", "DOWNLOADED", "INTERNAL")
                assertNull(content)
            } finally {
                SourceManager.unregisterSource(slowSource.id)
            }
        }
    }

    @Test
    fun cancellingOneWaiter_doesNotCancelSharedNetworkFetch() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_singleflight_cancel"
            val fetchStarted = CompletableDeferred<Unit>()
            val allowFetchFinish = CompletableDeferred<Unit>()
            val sourceCalls = AtomicInteger(0)

            val slowSource = object : NovelSource {
                override val id = "slow_waiter_source"
                override val name = "Slow Waiter Source"
                override val language = "en"
                override val version = "1.0"
                override val description = "Slow source"
                override val isLocal = false
                override suspend fun getPopularNovels(page: Int): List<Novel> = emptyList()
                override suspend fun searchNovels(query: String, page: Int): List<Novel> = emptyList()
                override suspend fun getNovelDetails(novelId: String): Novel = testNovel(
                    id = novelId,
                    title = "Title",
                    author = "Author",
                    description = "Desc",
                    sourceId = id,
                    sourceName = name
                )
                override suspend fun getChapterList(novelId: String): List<Chapter> = emptyList()
                override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent {
                    sourceCalls.incrementAndGet()
                    fetchStarted.complete(Unit)
                    allowFetchFinish.await()
                    return ChapterContent(chapterId, novelId, "Title", listOf("Single flight text"))
                }
            }
            SourceManager.registerSource(slowSource)
            try {
                val novel = NovelEntity(
                    id = novelId,
                    title = "Shared Fetch Novel",
                    author = "Author",
                    description = "Desc",
                    sourceId = slowSource.id,
                    sourceName = slowSource.name,
                    status = "Ongoing",
                    inLibrary = true
                )
                novelDao.insertIgnore(novel)
                chapterDao.insertIgnore(listOf(
                    ChapterEntity(novelId = novelId, id = "ch_1", title = "Ch 1", chapterIndex = 1)
                ))

                // Waiter 1
                val waiter1 = async(Dispatchers.IO) {
                    repository.chapterRepository.getChapterContent(novelId, "ch_1")
                }

                fetchStarted.await()

                // Waiter 2 attaches to the same in-flight fetch
                val waiter2 = async(Dispatchers.IO) {
                    repository.chapterRepository.getChapterContent(novelId, "ch_1")
                }

                delay(300)

                // Cancel Waiter 1
                waiter1.cancel()

                // Unblock network fetch
                allowFetchFinish.complete(Unit)

                // Waiter 2 must succeed
                val result2 = waiter2.await()
                assertEquals("Single flight text", result2.paragraphs.first())
                assertEquals(1, sourceCalls.get())
            } finally {
                SourceManager.unregisterSource(slowSource.id)
            }
        }
    }

    @Test
    fun followUpRuns_reportBatchCompletedCount() = runBlocking<Unit> {
        withTimeout(10_000) {
            val novelId = "nov_batch_runs"
            insertTestNovelAndChapters(novelId, 4)

            repository.settingsRepository.updateNotificationSettings {
                it.copy(notifyOnDownload = true)
            }

            // Enqueue all 4 chapters
            downloadEngine.enqueueChapters(novelId, (1..4).map { "ch_$it" })

            val coordinator = repository.chapterRepository.downloadCoordinator
            assertEquals(0, coordinator.getBatchCompletedCount())

            // Simulate run 1: downloads 2 chapters, but is interrupted (e.g. maxWorkerDuration)
            DownloadQueueWorker.maxWorkerDurationOverrideMs = 150L
            val worker1 = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
            worker1.doWork()

            val run1Completed = chapterDao.getDownloadedCount(novelId)
            assertTrue("Expected some chapters completed in run 1", run1Completed > 0)
            assertEquals(run1Completed, coordinator.getBatchCompletedCount())

            // Simulate run 2: completes remaining chapters
            DownloadQueueWorker.maxWorkerDurationOverrideMs = null
            val worker2 = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
            worker2.doWork()

            assertEquals(4, chapterDao.getDownloadedCount(novelId))
            assertEquals(0, downloadQueueDao.countActiveRemaining())

            // Verify notification received total batch count (4)
            val notifications = notificationDao.getNotificationsFlow().first()
            val downloadNotif = notifications.firstOrNull { it.type == NotificationTypes.DOWNLOAD }
            assertNotNull(downloadNotif)
            assertTrue("Notification should report 4 chapters: ${downloadNotif?.message}", downloadNotif?.message?.contains("4 chapters") == true)

            // Verify coordinator batch counter is reset to 0 once queue is completely empty
            assertEquals(0, downloadQueueDao.countActiveRemaining())
            assertEquals(0, coordinator.getBatchCompletedCount())
        }
    }
}
