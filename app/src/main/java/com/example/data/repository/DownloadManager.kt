package com.example.data.repository

import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.Novel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

class DownloadManager(
    private val storageManager: StorageManager,
    private val preferencesManager: PreferencesManager,
    private val libraryManager: LibraryManager
) {

    private val _downloadedChapters = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val downloadedChapters: StateFlow<Map<String, Set<String>>> = _downloadedChapters.asStateFlow()

    init {
        loadDownloadedChaptersIndex()
    }

    private fun loadDownloadedChaptersIndex() {
        val str = preferencesManager.loadDownloadedIndexJson() ?: return
        try {
            val obj = JSONObject(str)
            val map = mutableMapOf<String, Set<String>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = obj.getJSONArray(k)
                val set = (0 until arr.length()).map { arr.getString(it) }.toSet()
                map[k] = set
            }
            _downloadedChapters.value = map
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveDownloadedChaptersIndex() {
        val obj = JSONObject()
        for ((k, set) in _downloadedChapters.value) {
            obj.put(k, JSONArray(set.toList()))
        }
        preferencesManager.saveDownloadedIndexJson(obj.toString())
    }

    fun markChapterDownloaded(novelId: String, chapterId: String) {
        val current = _downloadedChapters.value.toMutableMap()
        val set = (current[novelId] ?: emptySet()).toMutableSet()
        set.add(chapterId)
        current[novelId] = set
        _downloadedChapters.value = current
        saveDownloadedChaptersIndex()

        libraryManager.updateDownloadedCount(novelId, set.size)
    }

    fun markChaptersDownloaded(novelId: String, chapterIds: Set<String>) {
        val current = _downloadedChapters.value.toMutableMap()
        val set = (current[novelId] ?: emptySet()).toMutableSet()
        set.addAll(chapterIds)
        current[novelId] = set
        _downloadedChapters.value = current
        saveDownloadedChaptersIndex()

        libraryManager.updateDownloadedCount(novelId, set.size)
    }

    suspend fun downloadChapter(
        novel: Novel,
        chapter: Chapter,
        fetchContent: suspend () -> ChapterContent
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val content = fetchContent()
            val customUri = preferencesManager.downloadSettings.value.customDownloadLocationUri
            storageManager.saveChapterToDisk(content, customUri)
            markChapterDownloaded(novel.id, chapter.id)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun downloadChaptersBatch(
        novel: Novel,
        chaptersToDownload: List<Chapter>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        fetchContent: suspend (Chapter) -> ChapterContent
    ) = withContext(Dispatchers.IO) {
        var completed = 0
        val settings = preferencesManager.downloadSettings.value

        for ((index, chapter) in chaptersToDownload.withIndex()) {
            if (settings.throttlingEnabled && index > 0 && index % settings.throttleBatchSize == 0) {
                var delayMs = (settings.throttleDelaySeconds * 1000).toLong()
                if (settings.throttleRandomJitter) {
                    val jitterFactor = Random.nextDouble(0.5, 1.5)
                    delayMs = (delayMs * jitterFactor).toLong().coerceAtLeast(200L)
                }
                delay(delayMs)
            }

            downloadChapter(novel, chapter) {
                fetchContent(chapter)
            }
            completed++
            onProgress(completed, chaptersToDownload.size)
        }
    }

    fun deleteDownloadedNovelChapters(novelId: String) {
        storageManager.deleteNovelChapters(novelId)

        val current = _downloadedChapters.value.toMutableMap()
        current.remove(novelId)
        _downloadedChapters.value = current
        saveDownloadedChaptersIndex()

        libraryManager.updateDownloadedCount(novelId, 0)
    }

    fun deleteAllDownloadedContent(): Int {
        val count = storageManager.deleteAllDownloadedContent()
        _downloadedChapters.value = emptyMap()
        saveDownloadedChaptersIndex()
        libraryManager.resetAllDownloadedCounts()
        return count
    }

    fun deleteNovelDownloads(novelId: String) {
        deleteDownloadedNovelChapters(novelId)
    }

    fun setDownloadedChapters(map: Map<String, Set<String>>) {
        _downloadedChapters.value = map
        saveDownloadedChaptersIndex()
    }
}
