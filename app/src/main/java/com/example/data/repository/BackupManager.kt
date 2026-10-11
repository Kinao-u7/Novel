package com.example.data.repository

import com.example.data.model.*
import com.example.data.source.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// TEMPORARY: Stage 6 replaces this file with the new archive-based backup engine
class BackupManager(
    private val repository: NovelRepository,
    private val preferencesManager: PreferencesManager
) {

    suspend fun createBackupJson(categories: BackupCategories): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("backupVersion", 1)
        root.put("app", "NovelReader")
        root.put("timestamp", System.currentTimeMillis())

        if (categories.library) {
            val libArr = JSONArray()
            for (novel in repository.libraryNovels.value) {
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
                libArr.put(obj)
            }
            root.put("library", libArr)
        }

        if (categories.history) {
            val histArr = JSONArray()
            for (novel in repository.libraryNovels.value.filter { it.lastReadTimestamp > 0L }) {
                val hObj = JSONObject().apply {
                    put("novelId", novel.id)
                    put("lastReadChapterId", novel.lastReadChapterId)
                    put("lastReadChapterTitle", novel.lastReadChapterTitle)
                    put("lastReadChapterIndex", novel.lastReadChapterIndex)
                    put("lastReadScrollPosition", novel.lastReadScrollPosition)
                    put("lastReadTimestamp", novel.lastReadTimestamp)
                }
                histArr.put(hObj)
            }
            root.put("history", histArr)
        }

        if (categories.readStatus) {
            val readObj = JSONObject()
            for ((novelId, chapterSet) in repository.readChapters.value) {
                readObj.put(novelId, JSONArray(chapterSet.toList()))
            }
            root.put("readStatus", readObj)
        }

        if (categories.categories) {
            val bmArr = JSONArray()
            for (b in repository.bookmarks.value) {
                bmArr.put(JSONObject().apply {
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
            root.put("bookmarks", bmArr)

            val rulesArr = JSONArray()
            for (r in preferencesManager.termReplacements.value) {
                rulesArr.put(JSONObject().apply {
                    put("id", r.id)
                    put("original", r.original)
                    put("replacement", r.replacement)
                    put("isEnabled", r.isEnabled)
                })
            }
            root.put("termReplacements", rulesArr)
        }

        if (categories.sources) {
            val sourcesArr = JSONArray()
            for (s in SourceManager.sources.value) {
                sourcesArr.put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("language", s.language)
                    put("version", s.version)
                    put("description", s.description)
                    put("isLocal", s.isLocal)
                })
            }
            root.put("sources", sourcesArr)
        }

        if (categories.appSettings) {
            val settingsObj = JSONObject().apply {
                val rs = preferencesManager.readerSettings.value
                val rsObj = JSONObject().apply {
                    put("theme", rs.theme.name)
                    put("fontSizeSp", rs.fontSizeSp)
                    put("fontWeight", rs.fontWeight.name)
                    put("lineSpacingMultiplier", rs.lineSpacingMultiplier.toDouble())
                    put("paragraphSpacingDp", rs.paragraphSpacingDp)
                    put("letterSpacingSp", rs.letterSpacingSp.toDouble())
                    put("textAlign", rs.textAlign.name)
                    put("horizontalMarginDp", rs.horizontalMarginDp)
                    put("verticalMarginDp", rs.verticalMarginDp)
                    put("contentWidthPercent", rs.contentWidthPercent)
                    put("paragraphIndentation", rs.paragraphIndentation)
                    put("marginType", rs.marginType.name)
                    put("fontType", rs.fontType.name)
                    put("readingMode", rs.readingMode.name)
                    put("autoScrollSpeed", rs.autoScrollSpeed)
                    put("isJustified", rs.isJustified)
                    put("toolbarPosition", rs.toolbarPosition.name)
                    put("showStatusBar", rs.showStatusBar)
                    put("enabledToolbarActions", JSONArray(rs.enabledToolbarActions))
                    put("globalTermReplacementsEnabled", rs.globalTermReplacementsEnabled)
                }
                put("readerSettings", rsObj)

                val ts = preferencesManager.themeSettings.value
                val tsObj = JSONObject().apply {
                    put("themeMode", ts.themeMode.name)
                    put("accentColorHex", ts.accentColorHex)
                    put("useDynamicColor", ts.useDynamicColor)
                }
                put("themeSettings", tsObj)

                val ds = preferencesManager.downloadSettings.value
                val dsObj = JSONObject().apply {
                    put("wifiOnly", ds.wifiOnly)
                    put("allowMobileData", ds.allowMobileData)
                    put("simultaneousDownloads", ds.simultaneousDownloads)
                    put("autoDownloadNext", ds.autoDownloadNext)
                    put("throttlingEnabled", ds.throttlingEnabled)
                    put("throttleBatchSize", ds.throttleBatchSize)
                    put("throttleDelaySeconds", ds.throttleDelaySeconds.toDouble())
                    put("throttleRandomJitter", ds.throttleRandomJitter)
                }
                put("downloadSettings", dsObj)
            }
            root.put("appSettings", settingsObj)
        }

        root.toString(2)
    }

    suspend fun restoreBackupFromJson(jsonString: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonString)

            if (root.has("library")) {
                val libArr = root.getJSONArray("library")
                val restoredList = mutableListOf<Novel>()
                for (i in 0 until libArr.length()) {
                    val obj = libArr.getJSONObject(i)
                    val genresList = mutableListOf<String>()
                    val gArr = obj.optJSONArray("genres")
                    if (gArr != null) {
                        for (g in 0 until gArr.length()) genresList.add(gArr.getString(g))
                    }
                    restoredList.add(
                        Novel(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            author = obj.optString("author", "Unknown"),
                            coverUrl = if (obj.has("coverUrl") && !obj.isNull("coverUrl")) obj.getString("coverUrl") else null,
                            description = obj.optString("description", ""),
                            genres = genresList,
                            status = obj.optString("status", "Ongoing"),
                            sourceId = obj.optString("sourceId", "unknown"),
                            sourceName = obj.optString("sourceName", "Unknown"),
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
                            locale = if (obj.has("locale") && !obj.isNull("locale")) obj.getString("locale") else null,
                            sourceUrl = if (obj.has("sourceUrl") && !obj.isNull("sourceUrl")) obj.getString("sourceUrl") else null
                        )
                    )
                }
                for (novel in restoredList) {
                    repository.addToLibrary(novel)
                }
            }

            if (root.has("history")) {
                val histArr = root.getJSONArray("history")
                for (i in 0 until histArr.length()) {
                    val hObj = histArr.getJSONObject(i)
                    val nId = hObj.getString("novelId")
                    val chId = if (hObj.has("lastReadChapterId") && !hObj.isNull("lastReadChapterId"))
                        hObj.getString("lastReadChapterId").takeIf { it.isNotBlank() && it != "null" } else null
                    val timestamp = hObj.optLong("lastReadTimestamp", 0L)
                    if (chId != null && timestamp > 0L) {
                        repository.updateReadingProgress(
                            novelId = nId,
                            chapterId = chId,
                            chapterTitle = hObj.optString("lastReadChapterTitle", ""),
                            chapterIndex = hObj.optInt("lastReadChapterIndex", 0),
                            scrollPosition = hObj.optInt("lastReadScrollPosition", 0)
                        )
                    }
                }
            }

            if (root.has("readStatus")) {
                val readObj = root.getJSONObject("readStatus")
                val keys = readObj.keys()
                while (keys.hasNext()) {
                    val nId = keys.next()
                    val arr = readObj.getJSONArray(nId)
                    for (i in 0 until arr.length()) {
                        val chId = arr.getString(i)
                        repository.markChapterAsRead(nId, chId)
                    }
                }
            }

            if (root.has("bookmarks")) {
                val bmArr = root.getJSONArray("bookmarks")
                for (i in 0 until bmArr.length()) {
                    val o = bmArr.getJSONObject(i)
                    repository.addBookmark(
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
            }

            if (root.has("termReplacements")) {
                val trArr = root.getJSONArray("termReplacements")
                val currentRules = preferencesManager.termReplacements.value.toMutableList()
                for (i in 0 until trArr.length()) {
                    val o = trArr.getJSONObject(i)
                    val rId = o.getString("id")
                    if (currentRules.none { it.id == rId }) {
                        preferencesManager.addTermReplacementRule(
                            TermReplacementRule(
                                id = rId,
                                original = o.getString("original"),
                                replacement = o.getString("replacement"),
                                isEnabled = o.optBoolean("isEnabled", true)
                            )
                        )
                    }
                }
            }

            if (root.has("appSettings")) {
                val setObj = root.getJSONObject("appSettings")
                if (setObj.has("readerSettings")) {
                    val rs = setObj.getJSONObject("readerSettings")
                    val currentRs = preferencesManager.readerSettings.value
                    val newRs = currentRs.copy(
                        theme = runCatching { ReaderTheme.valueOf(rs.getString("theme")) }.getOrDefault(currentRs.theme),
                        fontSizeSp = rs.optInt("fontSizeSp", currentRs.fontSizeSp),
                        lineSpacingMultiplier = rs.optDouble("lineSpacingMultiplier", currentRs.lineSpacingMultiplier.toDouble()).toFloat()
                    )
                    preferencesManager.updateReaderSettings(newRs)
                }
                if (setObj.has("themeSettings")) {
                    val ts = setObj.getJSONObject("themeSettings")
                    val currentTs = preferencesManager.themeSettings.value
                    val newTs = currentTs.copy(
                        themeMode = runCatching { AppThemeMode.valueOf(ts.getString("themeMode")) }.getOrDefault(currentTs.themeMode),
                        accentColorHex = ts.optString("accentColorHex", currentTs.accentColorHex),
                        useDynamicColor = ts.optBoolean("useDynamicColor", currentTs.useDynamicColor)
                    )
                    preferencesManager.updateThemeSettings(newTs)
                }
                if (setObj.has("downloadSettings")) {
                    val ds = setObj.getJSONObject("downloadSettings")
                    val currentDs = preferencesManager.downloadSettings.value
                    val newDs = currentDs.copy(
                        wifiOnly = ds.optBoolean("wifiOnly", currentDs.wifiOnly),
                        allowMobileData = ds.optBoolean("allowMobileData", currentDs.allowMobileData),
                        throttlingEnabled = ds.optBoolean("throttlingEnabled", currentDs.throttlingEnabled),
                        throttleBatchSize = ds.optInt("throttleBatchSize", currentDs.throttleBatchSize),
                        throttleDelaySeconds = ds.optDouble("throttleDelaySeconds", currentDs.throttleDelaySeconds.toDouble()).toFloat(),
                        throttleRandomJitter = ds.optBoolean("throttleRandomJitter", currentDs.throttleRandomJitter)
                    )
                    preferencesManager.updateDownloadSettings(newDs)
                }
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
