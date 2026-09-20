package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.example.data.epub.EpubParser
import com.example.data.model.*
import com.example.data.source.SourceManager
import com.example.data.source.webscrape.ScrapedNovelData
import com.example.data.source.webscrape.WebScrapeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class NovelRepository(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE)

    // Library novels state
    private val _libraryNovels = MutableStateFlow<List<Novel>>(emptyList())
    val libraryNovels: StateFlow<List<Novel>> = _libraryNovels.asStateFlow()

    // Downloaded chapters map: novelId -> set of chapterIds
    private val _downloadedChapters = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val downloadedChapters: StateFlow<Map<String, Set<String>>> = _downloadedChapters.asStateFlow()

    // Read chapters map: novelId -> set of chapterIds
    private val _readChapters = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val readChapters: StateFlow<Map<String, Set<String>>> = _readChapters.asStateFlow()

    // Bookmarks state
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    // Reader Settings state
    private val _readerSettings = MutableStateFlow(loadReaderSettings())
    val readerSettings: StateFlow<ReaderSettings> = _readerSettings.asStateFlow()

    // Term Replacement Rules state
    private val _termReplacements = MutableStateFlow<List<TermReplacementRule>>(emptyList())
    val termReplacements: StateFlow<List<TermReplacementRule>> = _termReplacements.asStateFlow()

    // App Theme Settings state
    private val _themeSettings = MutableStateFlow(loadThemeSettings())
    val themeSettings: StateFlow<ThemeSettings> = _themeSettings.asStateFlow()

    // Download Settings state
    private val _downloadSettings = MutableStateFlow(loadDownloadSettings())
    val downloadSettings: StateFlow<DownloadSettings> = _downloadSettings.asStateFlow()

    // Notification Settings state
    private val _notificationSettings = MutableStateFlow(loadNotificationSettings())
    val notificationSettings: StateFlow<NotificationSettings> = _notificationSettings.asStateFlow()

    // Notifications history state
    private val _notifications = MutableStateFlow<List<AppNotificationItem>>(loadNotifications())
    val notifications: StateFlow<List<AppNotificationItem>> = _notifications.asStateFlow()

    // Reading Stats state
    private val _readingStats = MutableStateFlow(loadReadingStats())
    val readingStats: StateFlow<ReadingStats> = _readingStats.asStateFlow()

    // In-memory cache for EPUB chapters and custom chapters
    private val localChapterCache = mutableMapOf<String, ChapterContent>()
    private val localNovelChapters = mutableMapOf<String, List<Chapter>>()

    init {
        SourceManager.registerSource(WebScrapeSource(context))
        loadLibrary()
        loadDownloadedChaptersIndex()
        loadReadChaptersIndex()
        loadBookmarks()
        loadTermReplacements()
    }

    // --- LIBRARY METHODS ---

    private fun loadLibrary() {
        val jsonString = prefs.getString("library_novels_json", null)
        if (!jsonString.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonString)
                val list = mutableListOf<Novel>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        Novel(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            author = obj.getString("author"),
                            coverUrl = if (obj.has("coverUrl") && !obj.isNull("coverUrl")) obj.getString("coverUrl") else null,
                            description = obj.getString("description"),
                            genres = if (obj.has("genres")) {
                                val gArr = obj.getJSONArray("genres")
                                (0 until gArr.length()).map { gArr.getString(it) }
                            } else emptyList(),
                            status = obj.optString("status", "Ongoing"),
                            sourceId = obj.getString("sourceId"),
                            sourceName = obj.optString("sourceName", "Source"),
                            inLibrary = true,
                            lastReadChapterId = if (obj.has("lastReadChapterId") && !obj.isNull("lastReadChapterId")) obj.getString("lastReadChapterId") else null,
                            lastReadChapterTitle = if (obj.has("lastReadChapterTitle") && !obj.isNull("lastReadChapterTitle")) obj.getString("lastReadChapterTitle") else null,
                            lastReadChapterIndex = obj.optInt("lastReadChapterIndex", 0),
                            lastReadScrollPosition = obj.optInt("lastReadScrollPosition", 0),
                            totalChapters = obj.optInt("totalChapters", 0),
                            downloadedChaptersCount = obj.optInt("downloadedChaptersCount", 0),
                            rating = obj.optDouble("rating", 4.8).toFloat(),
                            lastReadTimestamp = obj.optLong("lastReadTimestamp", 0L),
                            isEpub = obj.optBoolean("isEpub", false),
                            rawId = if (obj.has("rawId") && !obj.isNull("rawId")) obj.getString("rawId") else null,
                            locale = if (obj.has("locale") && !obj.isNull("locale")) obj.getString("locale") else null
                        )
                    )
                }
                _libraryNovels.value = list
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveLibrary() {
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
            }
            array.put(obj)
        }
        prefs.edit().putString("library_novels_json", array.toString()).apply()
    }

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
        // Update in library
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

        // Mark chapter as read
        markChapterAsRead(novelId, chapterId)

        // Update stats
        if (wordsReadDelta > 0) {
            recordReadingSession(minutesDelta = 1, wordsDelta = wordsReadDelta)
        }
    }

    // --- CHAPTERS & OFFLINE STORAGE ---

    suspend fun getChapterList(novel: Novel): List<Chapter> = withContext(Dispatchers.IO) {
        if (novel.isEpub && localNovelChapters.containsKey(novel.id)) {
            val chapters = localNovelChapters[novel.id] ?: emptyList()
            val readSet = _readChapters.value[novel.id] ?: emptySet()
            return@withContext chapters.map {
                it.copy(
                    isDownloaded = true,
                    isRead = readSet.contains(it.id)
                )
            }
        }

        if (novel.sourceId == WebScrapeSource.SOURCE_ID || localNovelChapters.containsKey(novel.id)) {
            var chapters = localNovelChapters[novel.id]
            if (chapters == null) {
                chapters = loadNovelChaptersFromDisk(novel.id)
                if (chapters.isNotEmpty()) {
                    localNovelChapters[novel.id] = chapters
                }
            }
            if (!chapters.isNullOrEmpty()) {
                val downloadedSet = _downloadedChapters.value[novel.id] ?: emptySet()
                val readSet = _readChapters.value[novel.id] ?: emptySet()
                return@withContext chapters.map { ch ->
                    ch.copy(
                        isDownloaded = downloadedSet.contains(ch.id) || isChapterFilePresent(novel.id, ch.id),
                        isRead = readSet.contains(ch.id)
                    )
                }
            }
        }

        val source = SourceManager.getSourceById(novel.sourceId) ?: SourceManager.getActiveSource()
        val rawChapters = source.getChapterList(novel.id)
        val downloadedSet = _downloadedChapters.value[novel.id] ?: emptySet()
        val readSet = _readChapters.value[novel.id] ?: emptySet()

        rawChapters.map { ch ->
            ch.copy(
                isDownloaded = downloadedSet.contains(ch.id) || isChapterFilePresent(novel.id, ch.id),
                isRead = readSet.contains(ch.id)
            )
        }
    }

    suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent = withContext(Dispatchers.IO) {
        // 1. Check local cache (memory)
        localChapterCache[chapterId]?.let { return@withContext it.copy(isDownloaded = true) }

        // 2. Check local disk file
        val offlineContent = readChapterFromDisk(novelId, chapterId)
        if (offlineContent != null) {
            return@withContext offlineContent.copy(isDownloaded = true)
        }

        // 3. Fetch from Source
        val novel = _libraryNovels.value.firstOrNull { it.id == novelId }
        val source = if (novel != null) {
            SourceManager.getSourceById(novel.sourceId) ?: SourceManager.getActiveSource()
        } else {
            SourceManager.getActiveSource()
        }

        val content = source.getChapterContent(novelId, chapterId)
        // Automatically save fetched chapter to disk for instant offline access next time!
        saveChapterToDisk(content)
        markChapterDownloaded(novelId, chapterId)
        content
    }

    private fun getChaptersDir(novelId: String): File {
        val dir = File(context.filesDir, "chapters/$novelId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getChapterFile(novelId: String, chapterId: String): File {
        val safeChapterId = chapterId.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
        return File(getChaptersDir(novelId), "$safeChapterId.json")
    }

    private fun isChapterFilePresent(novelId: String, chapterId: String): Boolean {
        return getChapterFile(novelId, chapterId).exists()
    }

    private fun saveChapterToDisk(content: ChapterContent) {
        try {
            val file = getChapterFile(content.novelId, content.chapterId)
            val json = JSONObject().apply {
                put("chapterId", content.chapterId)
                put("novelId", content.novelId)
                put("title", content.title)
                put("paragraphs", JSONArray(content.paragraphs))
            }
            file.writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun readChapterFromDisk(novelId: String, chapterId: String): ChapterContent? {
        val file = getChapterFile(novelId, chapterId)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val pArr = json.getJSONArray("paragraphs")
            val paragraphs = (0 until pArr.length()).map { pArr.getString(it) }
            ChapterContent(
                chapterId = json.getString("chapterId"),
                novelId = json.getString("novelId"),
                title = json.getString("title"),
                paragraphs = paragraphs,
                isDownloaded = true
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun downloadChapter(novel: Novel, chapter: Chapter): Boolean = withContext(Dispatchers.IO) {
        try {
            val content = getChapterContent(novel.id, chapter.id)
            saveChapterToDisk(content)
            markChapterDownloaded(novel.id, chapter.id)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun downloadChaptersBatch(novel: Novel, chaptersToDownload: List<Chapter>, onProgress: (Int, Int) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        var completed = 0
        for (chapter in chaptersToDownload) {
            downloadChapter(novel, chapter)
            completed++
            onProgress(completed, chaptersToDownload.size)
        }
    }

    fun deleteDownloadedNovelChapters(novelId: String) {
        val dir = getChaptersDir(novelId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        val current = _downloadedChapters.value.toMutableMap()
        current.remove(novelId)
        _downloadedChapters.value = current
        saveDownloadedChaptersIndex()

        // Update novel entity
        val lib = _libraryNovels.value.toMutableList()
        val idx = lib.indexOfFirst { it.id == novelId }
        if (idx >= 0) {
            lib[idx] = lib[idx].copy(downloadedChaptersCount = 0)
            _libraryNovels.value = lib
            saveLibrary()
        }
    }

    private fun markChapterDownloaded(novelId: String, chapterId: String) {
        val current = _downloadedChapters.value.toMutableMap()
        val set = (current[novelId] ?: emptySet()).toMutableSet()
        set.add(chapterId)
        current[novelId] = set
        _downloadedChapters.value = current
        saveDownloadedChaptersIndex()

        // Update download count on novel
        val lib = _libraryNovels.value.toMutableList()
        val idx = lib.indexOfFirst { it.id == novelId }
        if (idx >= 0) {
            lib[idx] = lib[idx].copy(downloadedChaptersCount = set.size)
            _libraryNovels.value = lib
            saveLibrary()
        }
    }

    private fun loadDownloadedChaptersIndex() {
        val str = prefs.getString("downloaded_index_json", null) ?: return
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
        prefs.edit().putString("downloaded_index_json", obj.toString()).apply()
    }

    // --- READ CHAPTERS TRACKING ---

    fun markChapterAsRead(novelId: String, chapterId: String) {
        val current = _readChapters.value.toMutableMap()
        val set = (current[novelId] ?: emptySet()).toMutableSet()
        set.add(chapterId)
        current[novelId] = set
        _readChapters.value = current

        // Save
        val obj = JSONObject()
        for ((k, s) in current) {
            obj.put(k, JSONArray(s.toList()))
        }
        prefs.edit().putString("read_chapters_json", obj.toString()).apply()
    }

    private fun loadReadChaptersIndex() {
        val str = prefs.getString("read_chapters_json", null) ?: return
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

    // --- EPUB IMPORT ---

    suspend fun importEpub(uri: Uri, fileName: String): Novel = withContext(Dispatchers.IO) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open stream for URI")
        
        val parsed = EpubParser.parseEpub(context, inputStream, fileName)

        // Store chapters in memory and save to disk
        localNovelChapters[parsed.novel.id] = parsed.chapters
        for ((chId, content) in parsed.chapterContents) {
            localChapterCache[chId] = content
            saveChapterToDisk(content)
        }

        // Add novel to library
        addToLibrary(parsed.novel)

        // Mark all chapters downloaded
        val current = _downloadedChapters.value.toMutableMap()
        current[parsed.novel.id] = parsed.chapters.map { it.id }.toSet()
        _downloadedChapters.value = current
        saveDownloadedChaptersIndex()

        parsed.novel
    }

    // --- WEB SCRAPED NOVELS ---

    fun saveWebScrapedNovel(
        scrapedData: ScrapedNovelData,
        customTitle: String,
        customAuthor: String,
        customSynopsis: String,
        customTags: List<String>,
        customCoverUrl: String?
    ): Novel {
        val novelId = "web_" + java.util.UUID.randomUUID().toString().take(8)
        val host = try {
            java.net.URI(scrapedData.sourceUrl).host?.removePrefix("www.") ?: "Web"
        } catch (e: Exception) {
            "Web"
        }

        val chapters = scrapedData.chapters.mapIndexed { index, ch ->
            Chapter(
                id = ch.url,
                novelId = novelId,
                title = ch.title,
                chapterIndex = index + 1,
                releaseDate = "Web",
                isDownloaded = false,
                isRead = false,
                wordCount = 0
            )
        }

        // Save chapters to memory and disk
        localNovelChapters[novelId] = chapters
        saveNovelChaptersToDisk(novelId, chapters)

        val novel = Novel(
            id = novelId,
            title = customTitle.ifBlank { "Untitled Web Novel" },
            author = customAuthor.ifBlank { "Unknown Author" },
            coverUrl = customCoverUrl?.takeIf { it.isNotBlank() },
            description = customSynopsis.ifBlank { "Added from $host" },
            genres = customTags.ifEmpty { listOf("Web Novel") },
            status = "Web",
            sourceId = WebScrapeSource.SOURCE_ID,
            sourceName = "Web ($host)",
            inLibrary = true,
            totalChapters = chapters.size,
            downloadedChaptersCount = 0,
            rating = 5.0f,
            lastReadTimestamp = System.currentTimeMillis(),
            rawId = scrapedData.rawId,
            locale = scrapedData.locale
        )

        addToLibrary(novel)
        return novel
    }

    private fun saveNovelChaptersToDisk(novelId: String, chapters: List<Chapter>) {
        try {
            val file = File(context.filesDir, "novel_chapters_$novelId.json")
            val array = JSONArray()
            chapters.forEach { ch ->
                val obj = JSONObject().apply {
                    put("id", ch.id)
                    put("novelId", ch.novelId)
                    put("title", ch.title)
                    put("chapterIndex", ch.chapterIndex)
                    put("releaseDate", ch.releaseDate)
                    put("wordCount", ch.wordCount)
                }
                array.put(obj)
            }
            file.writeText(array.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadNovelChaptersFromDisk(novelId: String): List<Chapter> {
        val file = File(context.filesDir, "novel_chapters_$novelId.json")
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            val list = mutableListOf<Chapter>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    Chapter(
                        id = obj.getString("id"),
                        novelId = obj.getString("novelId"),
                        title = obj.getString("title"),
                        chapterIndex = obj.optInt("chapterIndex", i + 1),
                        releaseDate = obj.optString("releaseDate", "Web"),
                        wordCount = obj.optInt("wordCount", 0)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
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

    private fun loadBookmarks() {
        val str = prefs.getString("bookmarks_json", null) ?: return
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
        prefs.edit().putString("bookmarks_json", arr.toString()).apply()
    }

    // --- READER SETTINGS ---

    fun updateReaderSettings(settings: ReaderSettings) {
        _readerSettings.value = settings
        prefs.edit().apply {
            putString("reader_theme", settings.theme.name)
            putInt("reader_font_size", settings.fontSizeSp)
            putString("reader_font_weight", settings.fontWeight.name)
            putFloat("reader_line_spacing", settings.lineSpacingMultiplier)
            putInt("reader_para_spacing", settings.paragraphSpacingDp)
            putFloat("reader_letter_spacing", settings.letterSpacingSp)
            putString("reader_text_align", settings.textAlign.name)
            putInt("reader_horizontal_margin", settings.horizontalMarginDp)
            putInt("reader_vertical_margin", settings.verticalMarginDp)
            putInt("reader_content_width_pct", settings.contentWidthPercent)
            putBoolean("reader_para_indent", settings.paragraphIndentation)
            putString("reader_margin", settings.marginType.name)
            putString("reader_font", settings.fontType.name)
            putString("reader_mode", settings.readingMode.name)
            putInt("reader_auto_scroll_speed", settings.autoScrollSpeed)
            putBoolean("reader_justified", settings.isJustified)
            putString("reader_toolbar_pos", settings.toolbarPosition.name)
            putBoolean("reader_show_status_bar", settings.showStatusBar)
            putString("reader_toolbar_actions", settings.enabledToolbarActions.joinToString(","))
            putBoolean("reader_global_replacements", settings.globalTermReplacementsEnabled)
            putFloat("reader_tts_speed", settings.ttsSpeechRate)
            putFloat("reader_tts_pitch", settings.ttsPitch)
            putString("reader_tts_voice", settings.ttsVoice)
            putBoolean("reader_save_pos", settings.saveReadingPosition)
            apply()
        }
    }

    private fun loadReaderSettings(): ReaderSettings {
        val themeName = prefs.getString("reader_theme", ReaderTheme.SEPIA.name) ?: ReaderTheme.SEPIA.name
        val theme = runCatching { ReaderTheme.valueOf(themeName) }.getOrDefault(ReaderTheme.SEPIA)

        val fontName = prefs.getString("reader_font", FontType.SERIF.name) ?: FontType.SERIF.name
        val font = runCatching { FontType.valueOf(fontName) }.getOrDefault(FontType.SERIF)

        val weightName = prefs.getString("reader_font_weight", ReaderFontWeight.NORMAL.name) ?: ReaderFontWeight.NORMAL.name
        val weight = runCatching { ReaderFontWeight.valueOf(weightName) }.getOrDefault(ReaderFontWeight.NORMAL)

        val alignName = prefs.getString("reader_text_align", ReaderTextAlign.JUSTIFIED.name) ?: ReaderTextAlign.JUSTIFIED.name
        val textAlign = runCatching { ReaderTextAlign.valueOf(alignName) }.getOrDefault(ReaderTextAlign.JUSTIFIED)

        val marginName = prefs.getString("reader_margin", MarginType.NORMAL.name) ?: MarginType.NORMAL.name
        val margin = runCatching { MarginType.valueOf(marginName) }.getOrDefault(MarginType.NORMAL)

        val modeName = prefs.getString("reader_mode", ReadingMode.CONTINUOUS.name) ?: ReadingMode.CONTINUOUS.name
        val mode = runCatching { ReadingMode.valueOf(modeName) }.getOrDefault(ReadingMode.CONTINUOUS)

        val toolbarPosName = prefs.getString("reader_toolbar_pos", ToolbarPosition.BOTTOM.name) ?: ToolbarPosition.BOTTOM.name
        val toolbarPos = runCatching { ToolbarPosition.valueOf(toolbarPosName) }.getOrDefault(ToolbarPosition.BOTTOM)

        val showStatusBar = prefs.getBoolean("reader_show_status_bar", true)

        val defaultActions = listOf("prev_chapter", "next_chapter", "toc", "tts", "bookmark", "display")
        val savedActionsStr = prefs.getString("reader_toolbar_actions", null)
        val actions = if (!savedActionsStr.isNullOrBlank()) {
            savedActionsStr.split(",").filter { it.isNotBlank() }
        } else {
            defaultActions
        }

        return ReaderSettings(
            theme = theme,
            fontSizeSp = prefs.getInt("reader_font_size", 18),
            fontWeight = weight,
            lineSpacingMultiplier = prefs.getFloat("reader_line_spacing", 1.6f),
            paragraphSpacingDp = prefs.getInt("reader_para_spacing", 12),
            letterSpacingSp = prefs.getFloat("reader_letter_spacing", 0f),
            textAlign = textAlign,
            horizontalMarginDp = prefs.getInt("reader_horizontal_margin", 20),
            verticalMarginDp = prefs.getInt("reader_vertical_margin", 20),
            contentWidthPercent = prefs.getInt("reader_content_width_pct", 100),
            paragraphIndentation = prefs.getBoolean("reader_para_indent", false),
            marginType = margin,
            fontType = font,
            readingMode = mode,
            autoScrollSpeed = prefs.getInt("reader_auto_scroll_speed", 0),
            isJustified = prefs.getBoolean("reader_justified", true),
            toolbarPosition = toolbarPos,
            showStatusBar = showStatusBar,
            enabledToolbarActions = actions,
            globalTermReplacementsEnabled = prefs.getBoolean("reader_global_replacements", true),
            ttsSpeechRate = prefs.getFloat("reader_tts_speed", 1.0f),
            ttsPitch = prefs.getFloat("reader_tts_pitch", 1.0f),
            ttsVoice = prefs.getString("reader_tts_voice", null),
            saveReadingPosition = prefs.getBoolean("reader_save_pos", true)
        )
    }

    // --- CHAPTER READING POSITION PERSISTENCE ---

    fun getChapterReadingPosition(novelId: String, chapterId: String): Int {
        val safeChapterId = chapterId.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
        return prefs.getInt("pos_${novelId}_$safeChapterId", 0)
    }

    fun saveChapterReadingPosition(novelId: String, chapterId: String, position: Int) {
        val safeChapterId = chapterId.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
        prefs.edit().putInt("pos_${novelId}_$safeChapterId", position).apply()
    }

    // --- TERM REPLACEMENT RULES ---

    fun addTermReplacementRule(rule: TermReplacementRule) {
        val current = _termReplacements.value.toMutableList()
        current.add(rule)
        _termReplacements.value = current
        saveTermReplacements()
    }

    fun updateTermReplacementRule(rule: TermReplacementRule) {
        val current = _termReplacements.value.toMutableList()
        val idx = current.indexOfFirst { it.id == rule.id }
        if (idx >= 0) {
            current[idx] = rule
            _termReplacements.value = current
            saveTermReplacements()
        }
    }

    fun deleteTermReplacementRule(id: String) {
        val current = _termReplacements.value.filterNot { it.id == id }
        _termReplacements.value = current
        saveTermReplacements()
    }

    fun toggleTermReplacementRule(id: String, isEnabled: Boolean) {
        val current = _termReplacements.value.toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) {
            current[idx] = current[idx].copy(isEnabled = isEnabled)
            _termReplacements.value = current
            saveTermReplacements()
        }
    }

    private fun loadTermReplacements() {
        val str = prefs.getString("term_replacements_json", null) ?: return
        try {
            val arr = JSONArray(str)
            val list = mutableListOf<TermReplacementRule>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    TermReplacementRule(
                        id = o.getString("id"),
                        original = o.getString("original"),
                        replacement = o.getString("replacement"),
                        isEnabled = o.optBoolean("isEnabled", true)
                    )
                )
            }
            _termReplacements.value = list
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveTermReplacements() {
        val arr = JSONArray()
        for (r in _termReplacements.value) {
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("original", r.original)
                put("replacement", r.replacement)
                put("isEnabled", r.isEnabled)
            })
        }
        prefs.edit().putString("term_replacements_json", arr.toString()).apply()
    }

    // --- APP THEME & ACCENT SETTINGS ---

    fun updateThemeSettings(settings: ThemeSettings) {
        _themeSettings.value = settings
        prefs.edit().apply {
            putString("app_theme_mode", settings.themeMode.name)
            putString("app_accent_hex", settings.accentColorHex)
            putBoolean("app_use_dynamic_color", settings.useDynamicColor)
            apply()
        }
    }

    private fun loadThemeSettings(): ThemeSettings {
        val modeName = prefs.getString("app_theme_mode", AppThemeMode.DARK.name) ?: AppThemeMode.DARK.name
        val mode = runCatching { AppThemeMode.valueOf(modeName) }.getOrDefault(AppThemeMode.DARK)
        val accentHex = prefs.getString("app_accent_hex", "#6366F1") ?: "#6366F1"
        val useDynamic = prefs.getBoolean("app_use_dynamic_color", false)
        return ThemeSettings(
            themeMode = mode,
            accentColorHex = accentHex,
            useDynamicColor = useDynamic
        )
    }

    // --- STATS ---

    fun recordReadingSession(minutesDelta: Int, wordsDelta: Int) {
        val current = _readingStats.value
        val updated = current.copy(
            todayMinutesRead = current.todayMinutesRead + minutesDelta,
            totalWordsRead = current.totalWordsRead + wordsDelta,
            totalChaptersRead = current.totalChaptersRead + if (wordsDelta > 1000) 1 else 0,
            lastSessionTimestamp = System.currentTimeMillis()
        )
        _readingStats.value = updated
        saveReadingStats(updated)
    }

    private fun loadReadingStats(): ReadingStats {
        val minutes = prefs.getInt("stats_today_minutes", 48)
        val streak = prefs.getInt("stats_streak_days", 7)
        val chapters = prefs.getInt("stats_total_chapters", 128)
        val words = prefs.getLong("stats_total_words", 345200L)
        return ReadingStats(
            todayMinutesRead = minutes,
            currentStreakDays = streak,
            totalChaptersRead = chapters,
            totalWordsRead = words
        )
    }

    private fun saveReadingStats(stats: ReadingStats) {
        prefs.edit().apply {
            putInt("stats_today_minutes", stats.todayMinutesRead)
            putInt("stats_streak_days", stats.currentStreakDays)
            putInt("stats_total_chapters", stats.totalChaptersRead)
            putLong("stats_total_words", stats.totalWordsRead)
            apply()
        }
    }

    // --- DOWNLOAD SETTINGS ---

    fun updateDownloadSettings(settings: DownloadSettings) {
        _downloadSettings.value = settings
        prefs.edit().apply {
            putBoolean("dl_wifi_only", settings.wifiOnly)
            putBoolean("dl_allow_mobile", settings.allowMobileData)
            putInt("dl_simultaneous", settings.simultaneousDownloads)
            putBoolean("dl_auto_next", settings.autoDownloadNext)
            apply()
        }
    }

    private fun loadDownloadSettings(): DownloadSettings {
        return DownloadSettings(
            wifiOnly = prefs.getBoolean("dl_wifi_only", true),
            allowMobileData = prefs.getBoolean("dl_allow_mobile", false),
            simultaneousDownloads = prefs.getInt("dl_simultaneous", 3),
            autoDownloadNext = prefs.getBoolean("dl_auto_next", false)
        )
    }

    // --- NOTIFICATIONS ---

    fun updateNotificationSettings(settings: NotificationSettings) {
        _notificationSettings.value = settings
        prefs.edit().apply {
            putBoolean("notif_download", settings.notifyOnDownload)
            putBoolean("notif_update", settings.notifyOnNovelUpdate)
            apply()
        }
    }

    private fun loadNotificationSettings(): NotificationSettings {
        return NotificationSettings(
            notifyOnDownload = prefs.getBoolean("notif_download", true),
            notifyOnNovelUpdate = prefs.getBoolean("notif_update", true)
        )
    }

    private fun loadNotifications(): List<AppNotificationItem> {
        val json = prefs.getString("notifications_json", null) ?: return listOf(
            AppNotificationItem(
                title = "Welcome to Novel Reader",
                message = "Explore millions of web novels and import your local EPUB files.",
                timestamp = System.currentTimeMillis() - 3600_000 * 2,
                type = "system"
            )
        )
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<AppNotificationItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    AppNotificationItem(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        title = obj.getString("title"),
                        message = obj.getString("message"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        type = obj.optString("type", "info")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveNotifications(list: List<AppNotificationItem>) {
        val arr = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("message", item.message)
                put("timestamp", item.timestamp)
                put("type", item.type)
            }
            arr.put(obj)
        }
        prefs.edit().putString("notifications_json", arr.toString()).apply()
    }

    fun addNotification(title: String, message: String, type: String = "info") {
        val newItem = AppNotificationItem(title = title, message = message, type = type)
        val updated = listOf(newItem) + _notifications.value
        _notifications.value = updated
        saveNotifications(updated)
    }

    fun clearNotifications() {
        _notifications.value = emptyList()
        prefs.edit().remove("notifications_json").apply()
    }

    // --- READING HISTORY ---

    fun getReadingHistory(): List<Novel> {
        return _libraryNovels.value
            .filter { it.lastReadTimestamp > 0L }
            .sortedByDescending { it.lastReadTimestamp }
    }

    // --- STORAGE & CACHE MANAGEMENT ---

    fun getStorageUsageBytes(): Long {
        val chaptersDir = File(context.filesDir, "chapters")
        if (!chaptersDir.exists()) return 0L
        return getFolderSize(chaptersDir)
    }

    fun getCacheSizeBytes(): Long {
        return getFolderSize(context.cacheDir)
    }

    fun clearCache(): Long {
        val before = getCacheSizeBytes()
        context.cacheDir.deleteRecursively()
        context.cacheDir.mkdirs()
        return before
    }

    fun deleteAllDownloadedContent(): Int {
        val chaptersDir = File(context.filesDir, "chapters")
        var count = 0
        if (chaptersDir.exists()) {
            count = chaptersDir.listFiles()?.size ?: 0
            chaptersDir.deleteRecursively()
            chaptersDir.mkdirs()
        }
        _downloadedChapters.value = emptyMap()
        saveDownloadedChaptersIndex()

        // Also update local download count for library novels
        val updated = _libraryNovels.value.map { it.copy(downloadedChaptersCount = 0) }
        _libraryNovels.value = updated
        saveLibrary()
        return count
    }

    fun deleteNovelDownloads(novelId: String) {
        val chaptersDir = File(context.filesDir, "chapters")
        if (chaptersDir.exists()) {
            chaptersDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("${novelId}_")) {
                    file.delete()
                }
            }
        }
        val currentMap = _downloadedChapters.value.toMutableMap()
        currentMap.remove(novelId)
        _downloadedChapters.value = currentMap
        saveDownloadedChaptersIndex()

        val updated = _libraryNovels.value.map {
            if (it.id == novelId) it.copy(downloadedChaptersCount = 0) else it
        }
        _libraryNovels.value = updated
        saveLibrary()
    }

    fun resetAppPreferences() {
        _readerSettings.value = ReaderSettings()
        updateReaderSettings(ReaderSettings())

        _themeSettings.value = ThemeSettings()
        updateThemeSettings(ThemeSettings())

        _downloadSettings.value = DownloadSettings()
        updateDownloadSettings(DownloadSettings())

        _notificationSettings.value = NotificationSettings()
        updateNotificationSettings(NotificationSettings())
    }

    private fun getFolderSize(dir: File): Long {
        var size: Long = 0
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return size
    }
}
