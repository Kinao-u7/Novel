package com.example.ui.screens.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.NovelDatabase
import com.example.data.local.db.NovelDatabaseHolder
import com.example.data.local.db.entity.ChapterEntity
import com.example.data.local.db.entity.NovelEntity
import com.example.data.model.ChapterContent
import com.example.data.model.ReaderTheme
import com.example.data.repository.NovelRepository
import com.example.data.repository.SettingsRepository
import com.example.di.ChapterFinishedListener
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReaderViewModelTest {

    private lateinit var mainExecutor: ExecutorService
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private var viewModel: ReaderViewModel? = null

    private lateinit var context: Context
    private lateinit var database: NovelDatabase
    private lateinit var repository: NovelRepository
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setup() {
        mainExecutor = Executors.newSingleThreadExecutor()
        mainDispatcher = mainExecutor.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)

        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NovelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        NovelDatabaseHolder.setInstanceForTesting(database)
        repository = NovelRepository(context)
        settingsRepository = SettingsRepository.getInstance(context)
    }

    @After
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        repository.shutdownForTesting()
        NovelDatabaseHolder.clearInstanceForTesting()
        database.close()
        Dispatchers.resetMain()
        mainDispatcher.close()
        com.example.data.download.DownloadCoordinator.resetInstanceForTesting()
        com.example.data.source.SourceManager.setCustomSources(emptyList())
    }

    private suspend fun collectState(
        vm: ReaderViewModel,
        timeoutMs: Long = 5000L,
        predicate: (ReaderUiState) -> Boolean
    ): ReaderUiState {
        var lastState: ReaderUiState = vm.uiState.value
        return try {
            withTimeout(timeoutMs) {
                vm.uiState.first { state ->
                    lastState = state
                    predicate(state)
                }
            }
        } catch (e: TimeoutCancellationException) {
            System.err.println("Timeout waiting for ReaderUiState condition. Last state was: $lastState")
            throw AssertionError("Timeout waiting for ReaderUiState condition. Last state was: $lastState", e)
        }
    }

    private suspend fun setupNovelAndChapters(novelId: String = "novel_1"): Pair<NovelEntity, List<ChapterEntity>> {
        val novel = NovelEntity(
            id = novelId,
            title = "Test Novel",
            author = "Test Author",
            description = "A test novel description",
            genresJson = "[\"Fantasy\"]",
            status = "Ongoing",
            sourceId = "test_src",
            sourceName = "Test Source",
            inLibrary = true,
            totalChapters = 2
        )
        database.novelDao().insertIgnore(novel)

        val ch1 = ChapterEntity(
            novelId = novelId,
            id = "ch_1",
            title = "Chapter 1",
            chapterIndex = 1,
            isRead = false,
            readParagraphIndex = 0
        )
        val ch2 = ChapterEntity(
            novelId = novelId,
            id = "ch_2",
            title = "Chapter 2",
            chapterIndex = 2,
            isRead = false,
            readParagraphIndex = 0
        )
        database.chapterDao().insertIgnore(listOf(ch1, ch2))

        // Pre-populate chapterCache so getChapterContent succeeds offline
        repository.chapterRepository.chapterCache.write(
            ChapterContent(
                chapterId = "ch_1",
                novelId = novelId,
                title = "Chapter 1",
                paragraphs = listOf("Paragraph 1 of chapter 1", "Paragraph 2 of chapter 1")
            )
        )
        repository.chapterRepository.chapterCache.write(
            ChapterContent(
                chapterId = "ch_2",
                novelId = novelId,
                title = "Chapter 2",
                paragraphs = listOf("Paragraph 1 of chapter 2", "Paragraph 2 of chapter 2")
            )
        )

        return Pair(novel, listOf(ch1, ch2))
    }

    @Test
    fun testLoadAndContent() = runBlocking {
        setupNovelAndChapters("novel_1")

        val savedStateHandle = SavedStateHandle().apply {
            set("novelId", "novel_1")
            set("chapterIndex", 1)
        }
        val vm = ReaderViewModel(
            novelRepository = repository,
            settingsRepository = settingsRepository,
            savedStateHandle = savedStateHandle
        )
        viewModel = vm

        val loaded = collectState(vm) { !it.isLoading && it.chapter != null }
        assertEquals("Test Novel", loaded.novelTitle)
        val chapter = loaded.chapter!!
        assertEquals("Chapter 1", chapter.title)
        assertEquals(1, chapter.index)
        assertEquals(2, chapter.paragraphs.size)
        assertEquals("Paragraph 1 of chapter 1", chapter.paragraphs[0])
        assertFalse(chapter.hasPrevious)
        assertTrue(chapter.hasNext)
        assertEquals(2, loaded.chapterCount)
        assertEquals(2, loaded.contents.size)
        assertTrue(loaded.contents[0].isCurrent)
        assertFalse(loaded.contents[1].isCurrent)
    }

    @Test
    fun testStartParagraph_fromSavedPosition() = runBlocking {
        setupNovelAndChapters("novel_saved_pos")
        // Update chapter 1 in Room with saved reading position = 5
        database.chapterDao().updatePosition("novel_saved_pos", "ch_1", 5, 0)

        // 1. When saveReadingPosition is true (default)
        val handle1 = SavedStateHandle().apply {
            set("novelId", "novel_saved_pos")
            set("chapterIndex", 1)
        }
        val vm1 = ReaderViewModel(
            novelRepository = repository,
            settingsRepository = settingsRepository,
            savedStateHandle = handle1
        )
        viewModel = vm1
        val state1 = collectState(vm1) { !it.isLoading && it.chapter != null }
        assertEquals(5, state1.startParagraph)

        // 2. When saveReadingPosition is false
        settingsRepository.updateReaderSettings { it.copy(saveReadingPosition = false) }
        val handle2 = SavedStateHandle().apply {
            set("novelId", "novel_saved_pos")
            set("chapterIndex", 1)
        }
        val vm2 = ReaderViewModel(
            novelRepository = repository,
            settingsRepository = settingsRepository,
            savedStateHandle = handle2
        )
        viewModel = vm2
        val state2 = collectState(vm2) { !it.isLoading && it.chapter != null }
        assertEquals(0, state2.startParagraph)
    }

    @Test
    fun testPositionSavedAndFlushedOnChapterChange() = runBlocking {
        setupNovelAndChapters("novel_flush")

        val savedStateHandle = SavedStateHandle().apply {
            set("novelId", "novel_flush")
            set("chapterIndex", 1)
        }
        val vm = ReaderViewModel(
            novelRepository = repository,
            settingsRepository = settingsRepository,
            savedStateHandle = savedStateHandle
        )
        viewModel = vm

        collectState(vm) { !it.isLoading && it.chapter?.index == 1 }

        // User reads to paragraph 7
        vm.onVisibleParagraph(7)

        // Navigate to chapter 2 -> should flush reading position for chapter 1 to Room
        vm.onNextChapter()

        collectState(vm) { !it.isLoading && it.chapter?.index == 2 }

        val ch1InDb = database.chapterDao().getChapter("novel_flush", "ch_1")
        assertNotNull(ch1InDb)
        assertEquals(7, ch1InDb!!.readParagraphIndex)

        // Novel last read updated
        val novelInDb = database.novelDao().getNovelById("novel_flush")
        assertEquals("ch_1", novelInDb?.lastReadChapterId)
    }

    @Test
    fun testOnReachedEnd_marksReadAndNotifiesListener() = runBlocking {
        setupNovelAndChapters("novel_listener")

        val finishedCalls = mutableListOf<Triple<String, Int, Boolean>>()
        val listener = ChapterFinishedListener { novelId, chapterIndex, wasUnread ->
            finishedCalls.add(Triple(novelId, chapterIndex, wasUnread))
        }

        val savedStateHandle = SavedStateHandle().apply {
            set("novelId", "novel_listener")
            set("chapterIndex", 1)
        }
        val vm = ReaderViewModel(
            novelRepository = repository,
            settingsRepository = settingsRepository,
            chapterFinishedListener = listener,
            savedStateHandle = savedStateHandle
        )
        viewModel = vm

        collectState(vm) { !it.isLoading && it.chapter?.index == 1 }

        // 1. First time reaching end on unread chapter
        vm.onReachedEnd()

        withTimeout(5000L) {
            while (finishedCalls.isEmpty()) {
                kotlinx.coroutines.delay(20)
            }
        }
        assertEquals(1, finishedCalls.size)
        assertEquals(Triple("novel_listener", 1, true), finishedCalls[0])

        // Verify chapter is now marked read in Room
        val ch1 = database.chapterDao().getChapter("novel_listener", "ch_1")
        assertTrue(ch1!!.isRead)

        // 2. Calling onReachedEnd again on the same loaded chapter is ignored (once per loaded chapter)
        vm.onReachedEnd()
        assertEquals(1, finishedCalls.size)

        // 3. Switch to chapter 2 then back to chapter 1 (reloaded chapter that is already read)
        vm.onChapterSelected(2)
        collectState(vm) { it.chapter?.index == 2 }

        vm.onChapterSelected(1)
        collectState(vm) { it.chapter?.index == 1 }

        vm.onReachedEnd()
        withTimeout(5000L) {
            while (finishedCalls.size < 2) {
                kotlinx.coroutines.delay(20)
            }
        }
        assertEquals(2, finishedCalls.size)
        assertEquals(Triple("novel_listener", 1, false), finishedCalls[1])
    }

    @Test
    fun testChapterNavigation() = runBlocking {
        setupNovelAndChapters("novel_nav")

        val savedStateHandle = SavedStateHandle().apply {
            set("novelId", "novel_nav")
            set("chapterIndex", 1)
        }
        val vm = ReaderViewModel(
            novelRepository = repository,
            settingsRepository = settingsRepository,
            savedStateHandle = savedStateHandle
        )
        viewModel = vm

        collectState(vm) { !it.isLoading && it.chapter?.index == 1 }

        // Next chapter
        vm.onNextChapter()
        val ch2State = collectState(vm) { it.chapter?.index == 2 }
        assertEquals(2, ch2State.chapter?.index)
        assertTrue(ch2State.chapter?.hasPrevious == true)
        assertFalse(ch2State.chapter?.hasNext == true)

        // Previous chapter
        vm.onPreviousChapter()
        val ch1State = collectState(vm) { it.chapter?.index == 1 }
        assertEquals(1, ch1State.chapter?.index)

        // Select chapter directly
        vm.onChapterSelected(2)
        val selectedState = collectState(vm) { it.chapter?.index == 2 }
        assertEquals(2, selectedState.chapter?.index)
    }

    @Test
    fun testDisplayChangePersistsToSettings() = runBlocking {
        setupNovelAndChapters("novel_disp")

        val savedStateHandle = SavedStateHandle().apply {
            set("novelId", "novel_disp")
            set("chapterIndex", 1)
        }
        val vm = ReaderViewModel(
            novelRepository = repository,
            settingsRepository = settingsRepository,
            savedStateHandle = savedStateHandle
        )
        viewModel = vm

        val initial = collectState(vm) { !it.isLoading && it.chapter != null }

        val newDisplay = initial.display.copy(
            fontSizeSp = 25,
            theme = ReaderTheme.AMOLED,
            justified = false
        )
        vm.onDisplayChange(newDisplay)

        val updated = collectState(vm) { it.display.fontSizeSp == 25 && it.display.theme == ReaderTheme.AMOLED }
        assertEquals(25, updated.display.fontSizeSp)
        assertEquals(ReaderTheme.AMOLED, updated.display.theme)
        assertFalse(updated.display.justified)

        assertEquals(25, settingsRepository.settings.value.reader.fontSizeSp)
        assertEquals(ReaderTheme.AMOLED, settingsRepository.settings.value.reader.theme)
    }

    @Test
    fun testErrorMapping() = runBlocking {
        // Novel exists, but has no chapters in database
        val novel = NovelEntity(
            id = "empty_novel",
            title = "Empty Novel",
            author = "Unknown",
            description = "",
            status = "Ongoing",
            genresJson = "[]",
            sourceId = "test_src",
            sourceName = "Test"
        )
        database.novelDao().insertIgnore(novel)

        val savedStateHandle = SavedStateHandle().apply {
            set("novelId", "empty_novel")
            set("chapterIndex", 1)
        }
        val vm = ReaderViewModel(
            novelRepository = repository,
            settingsRepository = settingsRepository,
            savedStateHandle = savedStateHandle
        )
        viewModel = vm

        val state = collectState(vm) { !it.isLoading && it.error != null }
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("No chapters found"))
    }
}
