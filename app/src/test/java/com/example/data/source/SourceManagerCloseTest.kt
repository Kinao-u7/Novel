package com.example.data.source

import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.Novel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class SourceManagerCloseTest {

    private class NonCloseableFakeSource(
        override val id: String = "fake_non_closeable",
        override val name: String = "Non Closeable",
        override val language: String = "EN",
        override val version: String = "1.0",
        override val description: String = "Fake",
        override val isLocal: Boolean = false,
        override val latestSupported: Boolean = false,
        override val filtersSupported: Boolean = false
    ) : NovelSource {
        override suspend fun getPopularNovels(page: Int): List<Novel> = emptyList()
        override suspend fun getLatestNovels(page: Int): List<Novel> = emptyList()
        override suspend fun searchNovels(query: String, page: Int): List<Novel> = emptyList()
        override suspend fun getNovelDetails(novelId: String): Novel = error("Not implemented")
        override suspend fun getChapterList(novelId: String): List<Chapter> = emptyList()
        override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent = error("Not implemented")
    }

    private class CloseableFakeSource(
        override val id: String = "fake_closeable",
        override val name: String = "Closeable",
        override val language: String = "EN",
        override val version: String = "1.0",
        override val description: String = "Fake",
        override val isLocal: Boolean = false,
        override val latestSupported: Boolean = false,
        override val filtersSupported: Boolean = false
    ) : NovelSource, AutoCloseable {
        val isClosed = AtomicBoolean(false)
        override fun close() {
            isClosed.set(true)
        }
        override suspend fun getPopularNovels(page: Int): List<Novel> = emptyList()
        override suspend fun getLatestNovels(page: Int): List<Novel> = emptyList()
        override suspend fun searchNovels(query: String, page: Int): List<Novel> = emptyList()
        override suspend fun getNovelDetails(novelId: String): Novel = error("Not implemented")
        override suspend fun getChapterList(novelId: String): List<Chapter> = emptyList()
        override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent = error("Not implemented")
    }

    @Test
    fun unregisterSource_closesAutoCloseableAndIgnoresNonCloseable() {
        val closeableSource = CloseableFakeSource()
        val nonCloseableSource = NonCloseableFakeSource()

        SourceManager.registerSource(closeableSource)
        SourceManager.registerSource(nonCloseableSource)

        assertFalse("Closeable source should not be closed yet", closeableSource.isClosed.get())

        // Unregister non-closeable source: should succeed and not crash
        SourceManager.unregisterSource(nonCloseableSource.id)
        assertNull("Non-closeable source should be removed", SourceManager.getSourceById(nonCloseableSource.id))

        // Unregister closeable source: should remove and invoke close()
        SourceManager.unregisterSource(closeableSource.id)
        assertNull("Closeable source should be removed", SourceManager.getSourceById(closeableSource.id))
        assertTrue("Closeable source must have close() called on unregister", closeableSource.isClosed.get())
    }
}
