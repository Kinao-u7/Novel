package com.example.data.source

import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.Novel

/**
 * Modular interface for novel and light novel content sources / plugins.
 * Any developer can create a plugin implementing this interface without
 * altering the core reader application.
 */
interface NovelSource {
    val id: String
    val name: String
    val language: String
    val version: String
    val description: String
    val isLocal: Boolean

    suspend fun getPopularNovels(page: Int = 1): List<Novel>
    suspend fun searchNovels(query: String, page: Int = 1): List<Novel>
    suspend fun getNovelDetails(novelId: String): Novel
    suspend fun getChapterList(novelId: String): List<Chapter>
    suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent
}
