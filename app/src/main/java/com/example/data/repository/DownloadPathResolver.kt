package com.example.data.repository

import com.example.data.local.db.dao.ChapterDao
import com.example.data.local.db.dao.NovelDao
import com.example.data.local.storage.DownloadNaming
import com.example.data.model.Chapter
import com.example.data.model.Novel
import com.example.data.source.SourceManager
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class NovelDownloadFolders(
    val sourceFolder: String,
    val novelFolder: String
)

/**
 * Allocates and persists fixed readable folder and file names for downloaded novels and chapters.
 */
class DownloadPathResolver(
    private val novelDao: NovelDao,
    private val chapterDao: ChapterDao,
    private val sourceNameProvider: (String) -> String? = { SourceManager.getSourceById(it)?.name },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val allocationMutex = Mutex()

    suspend fun resolveNovelFolders(novel: Novel): NovelDownloadFolders = withContext(ioDispatcher) {
        val fastRow = novelDao.getNovelById(novel.id)
        if (fastRow?.downloadSourceFolder != null && fastRow.downloadNovelFolder != null) {
            return@withContext NovelDownloadFolders(
                sourceFolder = fastRow.downloadSourceFolder,
                novelFolder = fastRow.downloadNovelFolder
            )
        }

        allocationMutex.withLock {
            val current = novelDao.getNovelById(novel.id)
            if (current?.downloadSourceFolder != null && current.downloadNovelFolder != null) {
                return@withLock NovelDownloadFolders(
                    sourceFolder = current.downloadSourceFolder,
                    novelFolder = current.downloadNovelFolder
                )
            }

            val sourceFolder = current?.downloadSourceFolder
                ?: novelDao.getDownloadSourceFolderForSource(novel.sourceId)
                ?: run {
                    val rawSourceName = novel.sourceName.ifBlank {
                        sourceNameProvider(novel.sourceId) ?: novel.sourceId
                    }
                    val usedSources = novelDao.getOtherSourcesDownloadFolders(novel.sourceId)
                        .map { it.lowercase(Locale.ROOT) }
                        .toSet()
                    DownloadNaming.resolveUniqueFolderName(rawSourceName, novel.sourceId, usedSources)
                }

            val novelFolder = current?.downloadNovelFolder
                ?: run {
                    val usedNovels = novelDao.getOtherNovelsDownloadFolders(sourceFolder, novel.id)
                        .map { it.lowercase(Locale.ROOT) }
                        .toSet()
                    DownloadNaming.resolveUniqueFolderName(novel.title, novel.id, usedNovels)
                }

            if (current != null) {
                novelDao.setDownloadFoldersIfNull(novel.id, sourceFolder, novelFolder)
            }
            NovelDownloadFolders(sourceFolder = sourceFolder, novelFolder = novelFolder)
        }
    }

    suspend fun resolveChapterFileName(chapter: Chapter): String = withContext(ioDispatcher) {
        val fastRow = chapterDao.getChapter(chapter.novelId, chapter.id)
        fastRow?.downloadFileName?.let { return@withContext it }

        allocationMutex.withLock {
            val current = chapterDao.getChapter(chapter.novelId, chapter.id)
            current?.downloadFileName?.let { return@withLock it }

            val usedNames = chapterDao.getOtherChapterDownloadFileNames(chapter.novelId, chapter.id)
                .map { it.lowercase(Locale.ROOT) }
                .toSet()
            val fileName = DownloadNaming.formatChapterFileName(
                chapterIndex = chapter.chapterIndex,
                rawTitle = chapter.title,
                chapterId = chapter.id,
                existingFileNamesLower = usedNames
            )
            if (current != null) {
                chapterDao.setDownloadFileNameIfNull(chapter.novelId, chapter.id, fileName)
            }
            fileName
        }
    }
}
