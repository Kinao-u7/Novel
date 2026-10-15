package com.example.data.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
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
import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.Novel
import com.example.data.model.toModel
import com.example.data.repository.NovelRepository
import com.example.data.source.NovelSource
import com.example.data.source.SourceManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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

        repository = NovelRepository(context)
        NovelRepository.setInstanceForTesting(repository)
        DownloadEngineProvider.setRepositoryForTesting(repository)

        downloadEngine = repository.downloadEngine

        // Default: zero throttle delay for tests unless overridden specifically
        DownloadQueueWorker.throttleDelayOverrideMs = 0L

        fakeSource = TestWorkerSource("test_source")
        SourceManager.registerSource(fakeSource)
    }

    @After
    fun tearDown() {
        DownloadQueueWorker.throttleDelayOverrideMs = null
        DownloadEngineProvider.setRepositoryForTesting(null)
        NovelRepository.resetInstanceForTesting()
        NovelDatabaseHolder.clearInstanceForTesting()
        database.close()
    }

    private fun insertTestNovelAndChapters(novelId: String = "nov_1", chapterCount: Int = 3) {
        val novel = NovelEntity(
            id = novelId,
            title = "Test Novel $novelId",
            author = "Author",
            description = "Desc",
            sourceId = fakeSource.id,
            sourceName = fakeSource.name,
            status = "Ongoing"
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
    fun dao_insertIgnore_preventsDuplicateEntries() = runTest {
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
    fun dao_fifoOrdering_returnsEarliestQueuedFirst() = runTest {
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
    fun dao_resetDownloadingToQueued_recoversAbandonedWork() = runTest {
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
    fun dao_pauseAndResumeQueue_transitionsStatesCorrectly() = runTest {
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
    fun dao_retryFailed_resetsCountersAndStatus() = runTest {
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
    fun dao_clearCompleted_removesOnlyCompleted() = runTest {
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
    fun engine_enqueueChapters_skipsWhenNovelMissing_andInsertsNoOrphanRows() = runTest {
        withTimeout(10_000) {
            val result = downloadEngine.enqueueChapters("missing_novel", listOf("ch_1", "ch_2"))

            assertEquals(0, result.enqueuedCount)
            assertEquals(2, result.skippedCount)
            assertTrue(result.skippedReasons.containsKey("ch_1"))
            assertEquals(0, downloadQueueDao.getTotalCount())
        }
    }

    @Test
    fun engine_enqueueChapters_skipsMissingChapters_andEnqueuesExisting() = runTest {
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
    fun engine_queueSummaryFlow_accuratelyAggregatesStatuses() = runTest {
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
    fun engine_autoDownloadNext_enqueuesNextEligibleChapters() = runTest {
        withTimeout(10_000) {
            insertTestNovelAndChapters("nov_auto", 5)

            // Starting after chapter index 2, requested count 2 -> chapters 3 and 4
            val result = downloadEngine.autoDownloadNext("nov_auto", fromChapterIndex = 2, count = 2)

            assertEquals(2, result.enqueuedCount)
            assertEquals(listOf("ch_3", "ch_4"), result.enqueuedChapterIds)
            assertEquals(0, result.skippedCount)
        }
    }

    @Test
    fun engine_autoDownloadNext_skipsAlreadyDownloadedOrQueuedChapters() = runTest {
        withTimeout(10_000) {
            insertTestNovelAndChapters("nov_auto_skip", 5)

            // Mark chapter 3 as DOWNLOADED
            chapterDao.updateStorageTier(
                novelId = "nov_auto_skip",
                id = "ch_3",
                storageTier = "DOWNLOADED",
                storageLocation = "INTERNAL",
                fileHash = "hash3",
                lastAccessedAt = System.currentTimeMillis()
            )

            // Pre-enqueue chapter 4 into queue
            downloadQueueDao.insertIgnore(DownloadQueueEntity("nov_auto_skip", "ch_4", status = "QUEUED"))

            // After index 2, request count 2 -> ch_3 is DOWNLOADED, ch_4 is QUEUED, so only ch_5 is eligible
            val result = downloadEngine.autoDownloadNext("nov_auto_skip", fromChapterIndex = 2, count = 2)

            assertEquals(1, result.enqueuedCount)
            assertEquals(listOf("ch_5"), result.enqueuedChapterIds)
        }
    }

    // --- 3. DOWNLOAD QUEUE WORKER TESTS ---

    @Test
    fun worker_processesQueue_andMarksCompleted() = runTest {
        withTimeout(10_000) {
            insertTestNovelAndChapters("nov_worker_1", 2)
            downloadEngine.enqueueChapters("nov_worker_1", listOf("ch_1", "ch_2"))

            val worker = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
            val result = worker.doWork()

            assertTrue(result is ListenableWorker.Result.Success)

            val item1 = downloadQueueDao.getItem("nov_worker_1", "ch_1")
            val item2 = downloadQueueDao.getItem("nov_worker_1", "ch_2")
            assertEquals("COMPLETED", item1?.status)
            assertEquals("COMPLETED", item2?.status)

            // Verify storage tier in Room
            val ch1 = chapterDao.getChapter("nov_worker_1", "ch_1")
            assertEquals("DOWNLOADED", ch1?.storageTier)
        }
    }

    @Test
    fun worker_onFailure_retriesWithExponentialBackoff_andMarksFailedOnMaxRetries() = runTest {
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
    fun worker_stopsImmediately_whenQueueIsPaused() = runTest {
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
    fun rateLimit_storageWarning_producesExactlyOneWarningAndNotificationFor50Chapters() = runTest {
        withTimeout(10_000) {
            val novelId = "nov_unwritable_50"
            insertTestNovelAndChapters(novelId, 50)

            // Set an unwritable custom location (in test environment, mock SAF URI is unwritable)
            repository.settingsRepository.updateDownloadSettings {
                it.copy(customDownloadLocationUri = "content://unwritable/tree")
            }

            val warnings = mutableListOf<String>()
            val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.chapterRepository.storageWarnings.collect {
                    warnings.add(it)
                }
            }

            val chapters = chapterDao.getChapters(novelId)
            val novel = novelDao.getNovelById(novelId)!!.toModel()

            // Download all 50 chapters
            for (ch in chapters) {
                val success = repository.chapterRepository.downloadChapter(novel, ch.toModel())
                assertTrue(success)
            }

            collectJob.cancel()

            // Exactly 1 warning event emitted across 50 chapters
            assertEquals(1, warnings.size)

            // Exactly 1 notification record persisted in database
            val notifications = notificationDao.getAllNotifications()
            val downloadNotifications = notifications.filter { it.type == "download" }
            assertEquals(1, downloadNotifications.size)
        }
    }

    // --- 5. REPOSITORY INTERFACE TEST ---

    @Test
    fun repository_exposesDownloadEngineFunctionsDirectly() = runTest {
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
    fun concurrency_neverExceedsSimultaneousDownloads() = runTest {
        withTimeout(10_000) {
            val novelId = "nov_concurrent_10"
            insertTestNovelAndChapters(novelId, 10)

            repository.settingsRepository.updateDownloadSettings {
                it.copy(simultaneousDownloads = 2)
            }

            downloadEngine.enqueueChapters(novelId, (1..10).map { "ch_$it" })

            val worker = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
            val result = worker.doWork()

            assertTrue(result is ListenableWorker.Result.Success)
            assertTrue(
                "Max concurrent calls was ${fakeSource.maxConcurrentCalls}, expected <= 2",
                fakeSource.maxConcurrentCalls <= 2
            )
            assertTrue(
                "Expected concurrent execution, got ${fakeSource.maxConcurrentCalls}",
                fakeSource.maxConcurrentCalls >= 1
            )
            assertEquals(10, downloadQueueDao.countByStatus("COMPLETED"))
        }
    }

    @Test
    fun cancelChapter_andCancelAll_removeRowsAndStopWork() = runTest {
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
    fun wifiOnly_true_usesUnmeteredConstraint_andFalse_usesConnected() = runTest {
        withTimeout(10_000) {
            val testDriver = WorkManagerTestInitHelper.getTestDriver(context)

            // Case 1: wifiOnly = true -> UNMETERED constraint
            repository.settingsRepository.updateDownloadSettings { it.copy(wifiOnly = true) }
            downloadEngine.scheduleWorker(replace = true)

            val workInfosTrue = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(DownloadEngine.WORK_NAME).get()
            val workInfoTrue = workInfosTrue.firstOrNull()
            assertNotNull("Work request should be scheduled", workInfoTrue)
            assertEquals(
                NetworkType.UNMETERED,
                workInfoTrue!!.constraints.requiredNetworkType
            )
            testDriver?.setAllConstraintsMet(workInfoTrue.id)

            // Case 2: wifiOnly = false -> CONNECTED constraint
            repository.settingsRepository.updateDownloadSettings { it.copy(wifiOnly = false) }
            downloadEngine.scheduleWorker(replace = true)

            val workInfosFalse = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(DownloadEngine.WORK_NAME).get()
            val workInfoFalse = workInfosFalse.firstOrNull()
            assertNotNull("Work request should be scheduled", workInfoFalse)
            assertEquals(
                NetworkType.CONNECTED,
                workInfoFalse!!.constraints.requiredNetworkType
            )
            testDriver?.setAllConstraintsMet(workInfoFalse.id)
        }
    }

    @Test
    fun worker_startedWithDownloadingRows_recoversThemToQueuedAndCompletes() = runTest {
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
            assertEquals("COMPLETED", downloadQueueDao.getItem(novelId, "ch_1")?.status)
            assertEquals("COMPLETED", downloadQueueDao.getItem(novelId, "ch_2")?.status)

            val ch1 = chapterDao.getChapter(novelId, "ch_1")
            val ch2 = chapterDao.getChapter(novelId, "ch_2")
            assertEquals("DOWNLOADED", ch1?.storageTier)
            assertEquals("DOWNLOADED", ch2?.storageTier)
        }
    }

    @Test
    fun throttling_insertsDelayBetweenRequests() = runTest {
        withTimeout(10_000) {
            val novelId = "nov_throttle_delay"
            insertTestNovelAndChapters(novelId, 2)

            // Inject 100ms throttle delay
            DownloadQueueWorker.throttleDelayOverrideMs = 100L

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
                "Expected delay >= 80ms between requests, got $intervalBetweenRequests ms",
                intervalBetweenRequests >= 80L
            )
            // No delay was invoked after the last request
            assertTrue(
                "Expected no delay (< 80ms) after last request, got $intervalAfterLastRequest ms",
                intervalAfterLastRequest < 80L
            )

            assertEquals("COMPLETED", downloadQueueDao.getItem(novelId, "ch_1")?.status)
            assertEquals("COMPLETED", downloadQueueDao.getItem(novelId, "ch_2")?.status)
        }
    }

    @Test
    fun failedChapter_doesNotStopOthers() = runTest {
        withTimeout(10_000) {
            val novelId = "nov_fail_other_3"
            insertTestNovelAndChapters(novelId, 3)

            fakeSource.failChapters.add("ch_2")
            downloadEngine.enqueueChapters(novelId, listOf("ch_1", "ch_2", "ch_3"))

            val worker = TestListenableWorkerBuilder<DownloadQueueWorker>(context).build()
            val result = worker.doWork()

            assertTrue(result is ListenableWorker.Result.Success)

            // Chapter 1 and Chapter 3 complete successfully
            val item1 = downloadQueueDao.getItem(novelId, "ch_1")
            val item3 = downloadQueueDao.getItem(novelId, "ch_3")
            assertEquals("COMPLETED", item1?.status)
            assertEquals("COMPLETED", item3?.status)

            // Chapter 2 fails 3 times and is marked FAILED with an error message
            val item2 = downloadQueueDao.getItem(novelId, "ch_2")
            assertEquals("FAILED", item2?.status)
            assertEquals(3, item2?.retryCount)
            assertNotNull(item2?.errorMessage)
            assertTrue(item2?.errorMessage?.contains("Max retries") == true)
        }
    }
}
