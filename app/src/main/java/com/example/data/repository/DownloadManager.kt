package com.example.data.repository

import com.example.data.model.Chapter
import com.example.data.model.Novel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Temporary download orchestrator managing batch downloads and request throttling.
 *
 * All file operations route through ChapterStorage and all tier updates route through Room
 * via ChapterRepository. Stage 5 replaces this with WorkManager DownloadEngine.
 */
class DownloadManager(
    private val chapterRepository: ChapterRepository,
    private val settingsRepository: SettingsRepository
) {

    suspend fun downloadChapter(
        novel: Novel,
        chapter: Chapter
    ): Boolean = withContext(Dispatchers.IO) {
        chapterRepository.downloadChapter(novel, chapter)
    }

    suspend fun downloadChaptersBatch(
        novel: Novel,
        chaptersToDownload: List<Chapter>,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        var completed = 0
        val settings = settingsRepository.downloadSettings.value

        for ((index, chapter) in chaptersToDownload.withIndex()) {
            if (settings.throttlingEnabled && index > 0 && index % settings.throttleBatchSize == 0) {
                var delayMs = (settings.throttleDelaySeconds * 1000).toLong()
                if (settings.throttleRandomJitter) {
                    val jitterFactor = Random.nextDouble(0.5, 1.5)
                    delayMs = (delayMs * jitterFactor).toLong().coerceAtLeast(200L)
                }
                delay(delayMs)
            }

            downloadChapter(novel, chapter)
            completed++
            onProgress(completed, chaptersToDownload.size)
        }
    }

    suspend fun deleteDownloadedNovelChapters(novelId: String) = withContext(Dispatchers.IO) {
        chapterRepository.deleteNovelDownloads(novelId)
    }

    suspend fun deleteAllDownloadedContent(): Int = withContext(Dispatchers.IO) {
        chapterRepository.deleteAllDownloads()
    }

    suspend fun deleteNovelDownloads(novelId: String) {
        deleteDownloadedNovelChapters(novelId)
    }
}
