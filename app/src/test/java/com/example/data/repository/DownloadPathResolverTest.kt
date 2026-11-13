package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.db.NovelDatabase
import com.example.data.local.db.entity.ChapterEntity
import com.example.data.local.db.entity.NovelEntity
import com.example.data.local.storage.DownloadNaming
import com.example.data.model.Chapter
import com.example.data.model.Novel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadPathResolverTest {

    private lateinit var context: Context
    private lateinit var database: NovelDatabase
    private lateinit var resolver: DownloadPathResolver

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NovelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        resolver = DownloadPathResolver(
            novelDao = database.novelDao(),
            chapterDao = database.chapterDao(),
            sourceNameProvider = { null },
            ioDispatcher = Dispatchers.IO
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun parallelFirstDownloadsOfSameNovel_allocateIdenticalFolders() = runBlocking<Unit> {
        database.novelDao().insertIgnore(
            NovelEntity(
                id = "n_parallel",
                title = "Shadow Slave",
                author = "Guiltythree",
                description = "Desc",
                sourceId = "ln_fansmtl",
                sourceName = "Fans MTL",
                status = "Ongoing",
                inLibrary = true
            )
        )
        val novel = Novel(
            id = "n_parallel",
            title = "Shadow Slave",
            author = "Guiltythree",
            description = "Desc",
            sourceId = "ln_fansmtl",
            sourceName = "Fans MTL"
        )

        val results = coroutineScope {
            (1..8).map {
                async(Dispatchers.IO) { resolver.resolveNovelFolders(novel) }
            }.awaitAll()
        }

        val distinct = results.toSet()
        assertEquals(1, distinct.size)
        val allocated = distinct.first()
        assertEquals("Fans MTL", allocated.sourceFolder)
        assertEquals("Shadow Slave", allocated.novelFolder)

        val row = database.novelDao().getNovelById("n_parallel")!!
        assertEquals("Fans MTL", row.downloadSourceFolder)
        assertEquals("Shadow Slave", row.downloadNovelFolder)
    }

    @Test
    fun sameSourceReusesSourceFolder_andDuplicateSourceNamesDisambiguate() = runBlocking<Unit> {
        database.novelDao().insertIgnore(
            NovelEntity(
                id = "n_1",
                title = "Book One",
                author = "A",
                description = "D",
                sourceId = "ln_src_a",
                sourceName = "Shared Site",
                status = "Ongoing"
            )
        )
        database.novelDao().insertIgnore(
            NovelEntity(
                id = "n_2",
                title = "Book Two",
                author = "A",
                description = "D",
                sourceId = "ln_src_a",
                sourceName = "Renamed Shared Site",
                status = "Ongoing"
            )
        )
        database.novelDao().insertIgnore(
            NovelEntity(
                id = "n_3",
                title = "Book Three",
                author = "A",
                description = "D",
                sourceId = "ln_src_b",
                sourceName = "Shared Site",
                status = "Ongoing"
            )
        )

        val f1 = resolver.resolveNovelFolders(Novel(id = "n_1", title = "Book One", author = "A", description = "D", sourceId = "ln_src_a", sourceName = "Shared Site"))
        val f2 = resolver.resolveNovelFolders(Novel(id = "n_2", title = "Book Two", author = "A", description = "D", sourceId = "ln_src_a", sourceName = "Renamed Shared Site"))
        val f3 = resolver.resolveNovelFolders(Novel(id = "n_3", title = "Book Three", author = "A", description = "D", sourceId = "ln_src_b", sourceName = "Shared Site"))

        assertEquals("Shared Site", f1.sourceFolder)
        assertEquals(f1.sourceFolder, f2.sourceFolder)
        assertNotEquals(f1.sourceFolder, f3.sourceFolder)
        assertEquals("Shared Site_${DownloadNaming.shortHash6("ln_src_b")}", f3.sourceFolder)
    }

    @Test
    fun parallelAndCollidingChapters_allocateStableUniqueFileNames() = runBlocking<Unit> {
        database.novelDao().insertIgnore(
            NovelEntity(
                id = "n_ch",
                title = "Novel",
                author = "A",
                description = "D",
                sourceId = "src",
                sourceName = "Src",
                status = "Ongoing"
            )
        )
        database.chapterDao().insertIgnore(
            listOf(
                ChapterEntity(novelId = "n_ch", id = "ch_a", title = "Intro", chapterIndex = 1),
                ChapterEntity(novelId = "n_ch", id = "ch_b", title = "Intro", chapterIndex = 1)
            )
        )

        val nameA = resolver.resolveChapterFileName(Chapter("ch_a", "n_ch", "Intro", 1))
        val nameB = resolver.resolveChapterFileName(Chapter("ch_b", "n_ch", "Intro", 1))

        assertEquals("0001 - Intro.json", nameA)
        assertEquals("0001 - Intro_${DownloadNaming.shortHash6("ch_b")}.json", nameB)
        assertEquals(nameA, database.chapterDao().getChapter("n_ch", "ch_a")!!.downloadFileName)
        assertEquals(nameB, database.chapterDao().getChapter("n_ch", "ch_b")!!.downloadFileName)
    }
}
