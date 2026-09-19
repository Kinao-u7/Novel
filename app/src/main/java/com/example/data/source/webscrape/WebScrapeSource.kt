package com.example.data.source.webscrape

import android.content.Context
import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.Novel
import com.example.data.source.NovelSource

class WebScrapeSource(private val context: Context) : NovelSource {

    companion object {
        const val SOURCE_ID = "custom_web"
        const val SOURCE_NAME = "Web Novel Scraper"
    }

    override val id: String = SOURCE_ID
    override val name: String = SOURCE_NAME
    override val language: String = "ALL"
    override val version: String = "1.0.0"
    override val description: String = "Generic scraping source for web novels added via in-app browser."
    override val isLocal: Boolean = false

    override suspend fun getPopularNovels(page: Int): List<Novel> = emptyList()

    override suspend fun searchNovels(query: String, page: Int): List<Novel> = emptyList()

    override suspend fun getNovelDetails(novelId: String): Novel {
        throw UnsupportedOperationException("Web novels are loaded directly from local database.")
    }

    override suspend fun getChapterList(novelId: String): List<Chapter> {
        return emptyList()
    }

    override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent {
        return WebChapterScraper.extractChapterText(
            context = context,
            url = chapterId,
            novelId = novelId,
            chapterId = chapterId
        )
    }
}
