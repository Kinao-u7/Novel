package com.example.data.repository

import com.example.data.model.Bookmark
import com.example.data.model.Novel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class LibraryManager(private val preferencesManager: PreferencesManager) {

    private val persistScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            e.printStackTrace()
        }
    )

    private val _libraryNovels = MutableStateFlow<List<Novel>>(emptyList())
    val libraryNovels: StateFlow<List<Novel>> = _libraryNovels.asStateFlow()

    private val _readChapters = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val readChapters: StateFlow<Map<String, Set<String>>> = _readChapters.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    init {
        loadLibrary()
        loadReadChaptersIndex()
        loadBookmarks()
    }

    // --- LIBRARY NOVELS ---

    private fun loadLibrary() {
        val jsonString = preferencesManager.loadLibraryJson()
        if (!jsonString.isNullOrBlank()) {
            val array = try {
                JSONArray(jsonString)
            } catch (e: Exception) {
                preferencesManager.saveCorruptedLibraryBackup(jsonString)
                null
            }

            if (array != null) {
                val list = mutableListOf<Novel>()
                var anyRecordSkipped = false
                for (i in 0 until array.length()) {
                    try {
                        val obj = array.getJSONObject(i)
                        val id = obj.optString("id", "")
                        val title = obj.optString("title", "")
                        if (id.isBlank() || title.isBlank()) {
                            anyRecordSkipped = true
                            continue
                        }

                        val author = obj.optString("author", "Unknown Author")
                        val coverUrl = if (obj.has("coverUrl") && !obj.isNull("coverUrl")) obj.optString("coverUrl", null) else null
                        val description = obj.optString("description", "")
                        val genres = if (obj.has("genres")) {
                            val gArr = obj.optJSONArray("genres")
                            if (gArr != null) (0 until gArr.length()).map { gArr.getString(it) } else emptyList()
                        } else emptyList()
                        val status = obj.optString("status", "Ongoing")
                        val sourceId = obj.optString("sourceId", "unknown")
                        val sourceName = obj.optString("sourceName", "Source")

                        list.add(
                            Novel(
                                id = id,
                                title = title,
                                author = author,
                                coverUrl = coverUrl,
                                description = description,
                                genres = genres,
                                status = status,
                                sourceId = sourceId,
                                sourceName = sourceName,
                                inLibrary = true,
                                lastReadChapterId = if (obj.has("lastReadChapterId") && !obj.isNull("lastReadChapterId")) obj.optString("lastReadChapterId", null) else null,
                                lastReadChapterTitle = if (obj.has("lastReadChapterTitle") && !obj.isNull("lastReadChapterTitle")) obj.optString("lastReadChapterTitle", null) else null,
                                lastReadChapterIndex = obj.optInt("lastReadChapterIndex", 0),
                                lastReadScrollPosition = obj.optInt("lastReadScrollPosition", 0),
                                totalChapters = obj.optInt("totalChapters", 0),
                                downloadedChaptersCount = obj.optInt("downloadedChaptersCount", 0),
                                rating = obj.optDouble("rating", 4.8).toFloat(),
                                lastReadTimestamp = obj.optLong("lastReadTimestamp", 0L),
                                isEpub = obj.optBoolean("isEpub", false),
                                rawId = if (obj.has("rawId") && !obj.isNull("rawId")) obj.optString("rawId", null) else null,
                                locale = if (obj.has("locale") && !obj.isNull("locale")) obj.optString("locale", null) else null,
                                sourceUrl = if (obj.has("sourceUrl") && !obj.isNull("sourceUrl")) obj.optString("sourceUrl", null) else null
                            )
                        )
                    } catch (e: Exception) {
                        anyRecordSkipped = true
                    }
                }
                _libraryNovels.value = list

                // Take one backup of the library JSON to "library_novels_json_bak" right after a successful load,
                // and always if any record was skipped.
                preferencesManager.saveLibraryBackupJson(jsonString)
            }
        }
    }

    fun saveLibrary() {
        val array = JSONArray()
        for (novel in _libraryNovels.value) {
            val obj = JSONObject().apply {
                put("id", novel.id)
                put("title", novel.title)
                put("author", novel.author)
                put("coverUrl", novel.coverUrl)
                put("description", novel.description)
                put("genres", JSONArray(novel.genres))
                put("status", novel.status)
                put("sourceId", novel.sourceId)
                put("sourceName", novel.sourceName)
                put("lastReadChapterId", novel.lastReadChapterId)
                put("lastReadChapterTitle", novel.lastReadChapterTitle)
                put("lastReadChapterIndex", novel.lastReadChapterIndex)
                put("lastReadScrollPosition", novel.lastReadScrollPosition)
                put("totalChapters", novel.totalChapters)
                put("downloadedChaptersCount", novel.downloadedChaptersCount)
                put("rating", novel.rating.toDouble())
                put("lastReadTimestamp", novel.lastReadTimestamp)
                put("isEpub", novel.isEpub)
                put("rawId", novel.rawId)
                put("locale", novel.locale)
                put("sourceUrl", novel.sourceUrl)
            }
            array.put(obj)
        }
        preferencesManager.saveLibraryJson(array.toString())
    }

    fun isLibraryGridView(): Boolean = preferencesManager.isLibraryGridView()

    fun setLibraryGridView(isGrid: Boolean) = preferencesManager.setLibraryGridView(isGrid)

    fun addToLibrary(novel: Novel) {
        val current = _libraryNovels.value.toMutableList()
        val index = current.indexOfFirst { it.id == novel.id }
        val updatedNovel = novel.copy(inLibrary = true)
        if (index >= 0) {
            current[index] = updatedNovel
        } else {
            current.add(0, updatedNovel)
        }
        _libraryNovels.value = current
        saveLibrary()
    }

    fun removeFromLibrary(novelId: String) {
        _libraryNovels.value = _libraryNovels.value.filterNot { it.id == novelId }
        saveLibrary()
    }

    fun updateNovelDetails(
        novelId: String,
        newTitle: String,
        newAuthor: String,
        newDescription: String,
        newCoverUrl: String?
    ) {
        val current = _libraryNovels.value.toMutableList()
        val index = current.indexOfFirst { it.id == novelId }
        if (index >= 0) {
            current[index] = current[index].copy(
                title = newTitle.trim(),
                author = newAuthor.trim(),
                description = newDescription.trim(),
                coverUrl = newCoverUrl?.trim()?.ifEmpty { null }
            )
            _libraryNovels.value = current
            saveLibrary()
        }
    }

    fun clearNovelReadingHistory(novelId: String) {
        val current = _libraryNovels.value.toMutableList()
        val index = current.indexOfFirst { it.id == novelId }
        if (index >= 0) {
            current[index] = current[index].copy(lastReadTimestamp = 0L)
            _libraryNovels.value = current
            saveLibrary()
        }
    }

    fun isNovelInLibrary(novelId: String): Boolean {
        return _libraryNovels.value.any { it.id == novelId }
    }

    fun updateReadingProgress(
        novelId: String,
        chapterId: String,
        chapterTitle: String,
        chapterIndex: Int,
        scrollPosition: Int,
        wordsReadDelta: Int = 0
    ) {
        val current = _libraryNovels.value.toMutableList()
        val index = current.indexOfFirst { it.id == novelId }
        if (index >= 0) {
            val n = current[index]
            current[index] = n.copy(
                lastReadChapterId = chapterId,
                lastReadChapterTitle = chapterTitle,
                lastReadChapterIndex = chapterIndex,
                lastReadScrollPosition = scrollPosition,
                lastReadTimestamp = System.currentTimeMillis()
            )
            _libraryNovels.value = current
            saveLibrary()
        }

        markChapterAsRead(novelId, chapterId)

        if (wordsReadDelta > 0) {
            preferencesManager.recordReadingSession(minutesDelta = 1, wordsDelta = wordsReadDelta)
        }
    }

    fun persistReadingProgressAsync(
        novelId: String,
        chapterId: String,
        chapterTitle: String,
        chapterIndex: Int,
        scrollPosition: Int,
        wordsReadDelta: Int = 0
    ) {
        persistScope.launch {
            updateReadingProgress(novelId, chapterId, chapterTitle, chapterIndex, scrollPosition, wordsReadDelta)
        }
    }

    fun saveChapterReadingPositionAsync(
        novelId: String,
        chapterId: String,
        position: Int
    ) {
        persistScope.launch {
            preferencesManager.saveChapterReadingPosition(novelId, chapterId, position)
        }
    }

    fun updateDownloadedCount(novelId: String, count: Int) {
        val lib = _libraryNovels.value.toMutableList()
        val idx = lib.indexOfFirst { it.id == novelId }
        if (idx >= 0) {
            lib[idx] = lib[idx].copy(downloadedChaptersCount = count)
            _libraryNovels.value = lib
            saveLibrary()
        }
    }

    fun resetAllDownloadedCounts() {
        val updated = _libraryNovels.value.map { it.copy(downloadedChaptersCount = 0) }
        _libraryNovels.value = updated
        saveLibrary()
    }

    fun resetNonEpubDownloadedCounts(epubIds: Set<String>) {
        val updated = _libraryNovels.value.map { novel ->
            if (novel.isEpub || novel.sourceId == "local_epub" || epubIds.contains(novel.id)) {
                novel
            } else {
                novel.copy(downloadedChaptersCount = 0)
            }
        }
        _libraryNovels.value = updated
        saveLibrary()
    }

    fun updateNovelCoverUrl(novelId: String, newCoverUrl: String) {
        val current = _libraryNovels.value.toMutableList()
        val index = current.indexOfFirst { it.id == novelId }
        if (index >= 0) {
            current[index] = current[index].copy(coverUrl = newCoverUrl)
            _libraryNovels.value = current
            saveLibrary()
        }
    }

    fun setLibraryNovels(novels: List<Novel>) {
        _libraryNovels.value = novels
        saveLibrary()
    }

    // --- READ CHAPTERS TRACKING ---

    fun markChapterAsRead(novelId: String, chapterId: String) {
        val current = _readChapters.value.toMutableMap()
        val set = (current[novelId] ?: emptySet()).toMutableSet()
        set.add(chapterId)
        current[novelId] = set
        _readChapters.value = current

        val obj = JSONObject()
        for ((k, s) in current) {
            obj.put(k, JSONArray(s.toList()))
        }
        preferencesManager.saveReadChaptersJson(obj.toString())
    }

    fun setReadChapters(map: Map<String, Set<String>>) {
        _readChapters.value = map
        val obj = JSONObject()
        for ((k, s) in map) {
            obj.put(k, JSONArray(s.toList()))
        }
        preferencesManager.saveReadChaptersJson(obj.toString())
    }

    private fun loadReadChaptersIndex() {
        val str = preferencesManager.loadReadChaptersJson() ?: return
        try {
            val obj = JSONObject(str)
            val map = mutableMapOf<String, Set<String>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = obj.getJSONArray(k)
                map[k] = (0 until arr.length()).map { arr.getString(it) }.toSet()
            }
            _readChapters.value = map
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- BOOKMARKS ---

    fun addBookmark(bookmark: Bookmark) {
        val current = _bookmarks.value.toMutableList()
        current.add(0, bookmark)
        _bookmarks.value = current
        saveBookmarks()
    }

    fun removeBookmark(bookmarkId: String) {
        _bookmarks.value = _bookmarks.value.filterNot { it.id == bookmarkId }
        saveBookmarks()
    }

    fun getBookmarksForNovel(novelId: String): List<Bookmark> {
        return _bookmarks.value.filter { it.novelId == novelId }
    }

    fun setBookmarks(bookmarks: List<Bookmark>) {
        _bookmarks.value = bookmarks
        saveBookmarks()
    }

    private fun loadBookmarks() {
        val str = preferencesManager.loadBookmarksJson() ?: return
        try {
            val arr = JSONArray(str)
            val list = mutableListOf<Bookmark>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Bookmark(
                        id = o.getString("id"),
                        novelId = o.getString("novelId"),
                        chapterId = o.getString("chapterId"),
                        chapterTitle = o.getString("chapterTitle"),
                        paragraphIndex = o.getInt("paragraphIndex"),
                        excerpt = o.getString("excerpt"),
                        note = o.optString("note", ""),
                        timestamp = o.getLong("timestamp")
                    )
                )
            }
            _bookmarks.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveBookmarks() {
        val arr = JSONArray()
        for (b in _bookmarks.value) {
            arr.put(JSONObject().apply {
                put("id", b.id)
                put("novelId", b.novelId)
                put("chapterId", b.chapterId)
                put("chapterTitle", b.chapterTitle)
                put("paragraphIndex", b.paragraphIndex)
                put("excerpt", b.excerpt)
                put("note", b.note)
                put("timestamp", b.timestamp)
            })
        }
        preferencesManager.saveBookmarksJson(arr.toString())
    }

    fun getReadingHistory(): List<Novel> {
        return _libraryNovels.value
            .filter { it.lastReadTimestamp > 0L }
            .sortedByDescending { it.lastReadTimestamp }
    }
}
