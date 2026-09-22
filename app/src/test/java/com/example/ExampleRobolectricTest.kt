package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Novel Reader", appName)
  }

  @Test
  fun `launch main activity`() {
    val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
    org.junit.Assert.assertNotNull(controller.get())
  }

  @Test
  fun `repository library operations, download chapter, and reopening novel without re-fetch`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo1 = com.example.data.repository.NovelRepository(context)

    // 1. Add novel to library
    val novel = com.example.data.model.Novel(
      id = "test_novel_1",
      title = "Test Novel",
      author = "Author Name",
      description = "A great novel",
      genres = listOf("Fantasy"),
      sourceId = "test_source",
      sourceName = "Test Source",
      inLibrary = true,
      totalChapters = 2
    )
    repo1.addToLibrary(novel)
    org.junit.Assert.assertTrue(repo1.isNovelInLibrary("test_novel_1"))
    org.junit.Assert.assertEquals(1, repo1.libraryNovels.value.size)

    // 2. Save chapters to disk
    val chapters = listOf(
      com.example.data.model.Chapter(
        id = "ch_1",
        novelId = "test_novel_1",
        title = "Chapter 1",
        chapterIndex = 1
      ),
      com.example.data.model.Chapter(
        id = "ch_2",
        novelId = "test_novel_1",
        title = "Chapter 2",
        chapterIndex = 2
      )
    )
    repo1.storageManager.saveNovelChaptersToDisk("test_novel_1", chapters, null)

    val content1 = com.example.data.model.ChapterContent(
      chapterId = "ch_1",
      novelId = "test_novel_1",
      title = "Chapter 1",
      paragraphs = listOf("Line 1", "Line 2")
    )
    repo1.storageManager.saveChapterToDisk(content1, null)

    // 3. Open novel and get chapter list
    val loadedChapters = repo1.getChapterList(novel)
    org.junit.Assert.assertEquals(2, loadedChapters.size)
    org.junit.Assert.assertEquals("Chapter 1", loadedChapters[0].title)

    // 4. Download chapter 1
    val downloadSuccess = repo1.downloadChapter(novel, chapters[0])
    org.junit.Assert.assertTrue(downloadSuccess)
    org.junit.Assert.assertTrue(repo1.downloadedChapters.value["test_novel_1"]?.contains("ch_1") == true)

    // 5. Re-instantiate repository (simulating app relaunch/reopening previously-added novel)
    val repo2 = com.example.data.repository.NovelRepository(context)
    org.junit.Assert.assertTrue(repo2.isNovelInLibrary("test_novel_1"))

    // Verify local disk storage resolves chapters immediately without re-fetch
    val reloadedChapters = repo2.getChapterList(novel)
    org.junit.Assert.assertEquals(2, reloadedChapters.size)
    org.junit.Assert.assertTrue("Chapter 1 should be marked downloaded", reloadedChapters[0].isDownloaded)
    org.junit.Assert.assertFalse("Chapter 2 should not be marked downloaded", reloadedChapters[1].isDownloaded)

    // Verify chapter content is readable from disk
    val readContent = repo2.getChapterContent("test_novel_1", "ch_1")
    org.junit.Assert.assertEquals("Chapter 1", readContent.title)
    org.junit.Assert.assertEquals(listOf("Line 1", "Line 2"), readContent.paragraphs)
    org.junit.Assert.assertTrue(readContent.isDownloaded)
  }

  @Test
  fun `backup and restore flow via repository facade`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = com.example.data.repository.NovelRepository(context)

    repo.updateReaderSettings(repo.readerSettings.value.copy(fontSizeSp = 24))
    val backupJson = repo.createBackupJson(
      com.example.data.model.BackupCategories(appSettings = true, library = true)
    )
    org.junit.Assert.assertTrue(backupJson.contains("backupVersion"))

    repo.updateReaderSettings(repo.readerSettings.value.copy(fontSizeSp = 16))
    org.junit.Assert.assertEquals(16, repo.readerSettings.value.fontSizeSp)

    val restored = repo.restoreBackupFromJson(backupJson)
    org.junit.Assert.assertTrue(restored)
    org.junit.Assert.assertEquals(24, repo.readerSettings.value.fontSizeSp)
  }
}
