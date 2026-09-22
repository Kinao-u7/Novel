package com.example.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.data.epub.EpubParser
import com.example.data.epub.ParsedEpub
import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class StorageManager(private val context: Context) {

    // Fast in-memory caches
    private val localChapterCache = mutableMapOf<String, ChapterContent>()
    private val localNovelChapters = mutableMapOf<String, List<Chapter>>()

    fun getCachedChapters(novelId: String): List<Chapter>? {
        return localNovelChapters[novelId]
    }

    fun putCachedChapters(novelId: String, chapters: List<Chapter>) {
        localNovelChapters[novelId] = chapters
    }

    fun getCachedChapterContent(chapterId: String): ChapterContent? {
        return localChapterCache[chapterId]
    }

    fun putCachedChapterContent(chapterId: String, content: ChapterContent) {
        localChapterCache[chapterId] = content
    }

    private fun getSafeNovelId(novelId: String): String {
        return novelId.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
    }

    private fun getChaptersDir(novelId: String): File {
        val safeId = getSafeNovelId(novelId)
        val dir = File(context.filesDir, "chapters/$safeId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getChapterFile(novelId: String, chapterId: String): File {
        val safeChapterId = chapterId.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
        return File(getChaptersDir(novelId), "$safeChapterId.json")
    }

    private fun getNovelChaptersFile(novelId: String): File {
        val safeId = getSafeNovelId(novelId)
        return File(context.filesDir, "novel_chapters_$safeId.json")
    }

    fun isChapterFilePresent(novelId: String, chapterId: String, customUriStr: String?): Boolean {
        // Fast path: internal storage check
        if (getChapterFile(novelId, chapterId).exists()) return true
        // Legacy internal check without safe ID
        val legacyFile = File(context.filesDir, "chapters/$novelId/${chapterId.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")}.json")
        if (legacyFile.exists()) return true
        return false
    }

    fun saveChapterToDisk(content: ChapterContent, customUriStr: String?) {
        val jsonString = try {
            val json = JSONObject().apply {
                put("chapterId", content.chapterId)
                put("novelId", content.novelId)
                put("title", content.title)
                put("paragraphs", JSONArray(content.paragraphs))
            }
            json.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        // 1. Always save to internal app storage (guaranteed reliable fallback)
        try {
            val file = getChapterFile(content.novelId, content.chapterId)
            file.writeText(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. If custom SAF location is configured, also attempt to mirror to DocumentFile
        if (!customUriStr.isNullOrBlank()) {
            try {
                val rootTreeUri = Uri.parse(customUriStr)
                val rootDoc = DocumentFile.fromTreeUri(context, rootTreeUri)
                if (rootDoc != null && rootDoc.canWrite()) {
                    var chaptersDoc = rootDoc.findFile("chapters")
                    if (chaptersDoc == null || !chaptersDoc.isDirectory) {
                        chaptersDoc = rootDoc.createDirectory("chapters")
                    }
                    if (chaptersDoc != null) {
                        var novelDoc = chaptersDoc.findFile(content.novelId)
                        if (novelDoc == null || !novelDoc.isDirectory) {
                            novelDoc = chaptersDoc.createDirectory(content.novelId)
                        }
                        if (novelDoc != null) {
                            val safeChapterId = content.chapterId.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
                            val fileName = "$safeChapterId.json"
                            var targetDoc = novelDoc.findFile(fileName)
                            if (targetDoc == null) {
                                targetDoc = novelDoc.createFile("application/json", fileName)
                            }
                            if (targetDoc != null) {
                                context.contentResolver.openOutputStream(targetDoc.uri)?.use { os ->
                                    os.write(jsonString.toByteArray(Charsets.UTF_8))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Non-fatal: internal storage already has the file
            }
        }
    }

    fun readChapterFromDisk(novelId: String, chapterId: String, customUriStr: String?): ChapterContent? {
        // 1. Fast check: internal storage
        val file = getChapterFile(novelId, chapterId)
        if (file.exists()) {
            try {
                val json = JSONObject(file.readText())
                val pArr = json.getJSONArray("paragraphs")
                val paragraphs = (0 until pArr.length()).map { pArr.getString(it) }
                return ChapterContent(
                    chapterId = json.getString("chapterId"),
                    novelId = json.getString("novelId"),
                    title = json.getString("title"),
                    paragraphs = paragraphs,
                    isDownloaded = true
                )
            } catch (e: Exception) {
                // Corrupted internal file, fall through to SAF check
            }
        }

        // 2. SAF custom directory fallback
        if (!customUriStr.isNullOrBlank()) {
            try {
                val rootTreeUri = Uri.parse(customUriStr)
                val rootDoc = DocumentFile.fromTreeUri(context, rootTreeUri)
                val chaptersDoc = rootDoc?.findFile("chapters")
                val novelDoc = chaptersDoc?.findFile(novelId)
                val safeChapterId = chapterId.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
                val doc = novelDoc?.findFile("$safeChapterId.json")
                if (doc != null && doc.exists()) {
                    context.contentResolver.openInputStream(doc.uri)?.use { inputStream ->
                        val text = inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(text)
                        val pArr = json.getJSONArray("paragraphs")
                        val paragraphs = (0 until pArr.length()).map { pArr.getString(it) }

                        // Cache back to internal storage for instant future loads
                        try {
                            file.writeText(text)
                        } catch (e: Exception) {}

                        return ChapterContent(
                            chapterId = json.getString("chapterId"),
                            novelId = json.getString("novelId"),
                            title = json.getString("title"),
                            paragraphs = paragraphs,
                            isDownloaded = true
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return null
    }

    // Facade alias methods for clean API
    fun saveChapter(content: ChapterContent, customUriStr: String? = null) {
        saveChapterToDisk(content, customUriStr)
    }

    fun getChapter(novelId: String, chapterId: String, customUriStr: String? = null): ChapterContent? {
        return readChapterFromDisk(novelId, chapterId, customUriStr)
    }

    fun saveChapterIndex(novelId: String, chapters: List<Chapter>, customUriStr: String? = null) {
        saveNovelChaptersToDisk(novelId, chapters, customUriStr)
    }

    fun getChapterIndex(novelId: String, customUriStr: String? = null): List<Chapter> {
        return loadNovelChaptersFromDisk(novelId, customUriStr)
    }

    fun saveNovelChaptersToDisk(novelId: String, chapters: List<Chapter>, customUriStr: String?) {
        val jsonStr = try {
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
            array.toString()
        } catch (e: Exception) {
            return
        }

        // 1. Always save to internal filesDir in multiple robust locations:
        // a. Inside novel's chapters directory (same folder structure as chapter content)
        try {
            val chaptersDir = getChaptersDir(novelId)
            File(chaptersDir, "chapters_meta.json").writeText(jsonStr)
            File(chaptersDir, "chapters.json").writeText(jsonStr)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // b. In filesDir root with safe novel ID
        try {
            val file = getNovelChaptersFile(novelId)
            file.writeText(jsonStr)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // c. In filesDir root with exact novel ID if different
        if (getSafeNovelId(novelId) != novelId) {
            try {
                File(context.filesDir, "novel_chapters_$novelId.json").writeText(jsonStr)
            } catch (e: Exception) {}
        }

        // 2. Also save to custom SAF storage if configured
        if (!customUriStr.isNullOrBlank()) {
            try {
                val rootTreeUri = Uri.parse(customUriStr)
                val rootDoc = DocumentFile.fromTreeUri(context, rootTreeUri)
                if (rootDoc != null && rootDoc.canWrite()) {
                    var chaptersDoc = rootDoc.findFile("chapters")
                    if (chaptersDoc == null || !chaptersDoc.isDirectory) {
                        chaptersDoc = rootDoc.createDirectory("chapters")
                    }
                    if (chaptersDoc != null) {
                        val safeNovelId = getSafeNovelId(novelId)
                        var novelDoc = chaptersDoc.findFile(safeNovelId)
                            ?: chaptersDoc.findFile(novelId)
                        if (novelDoc == null || !novelDoc.isDirectory) {
                            novelDoc = chaptersDoc.createDirectory(safeNovelId)
                        }
                        if (novelDoc != null) {
                            var metaDoc = novelDoc.findFile("chapters_meta.json")
                            if (metaDoc == null) {
                                metaDoc = novelDoc.createFile("application/json", "chapters_meta.json")
                            }
                            if (metaDoc != null) {
                                context.contentResolver.openOutputStream(metaDoc.uri)?.use { os ->
                                    os.write(jsonStr.toByteArray(Charsets.UTF_8))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore SAF permission or IO issues; internal storage is already saved
            }
        }
    }

    private fun parseChaptersJsonArray(array: JSONArray, fallbackNovelId: String): List<Chapter> {
        val list = mutableListOf<Chapter>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").ifBlank { "${fallbackNovelId}_ch_${i + 1}" }
            val nId = obj.optString("novelId", fallbackNovelId).ifBlank { fallbackNovelId }
            val rawTitle = obj.optString("title", "Chapter ${i + 1}").ifBlank { "Chapter ${i + 1}" }
            val title = com.example.data.source.webscrape.WtrLabExtractor.cleanWtrLabTokens(rawTitle)
            val chapterIndex = obj.optInt("chapterIndex", i + 1)
            val releaseDate = obj.optString("releaseDate", "Web")
            val wordCount = obj.optInt("wordCount", 0)

            list.add(
                Chapter(
                    id = id,
                    novelId = nId,
                    title = title,
                    chapterIndex = chapterIndex,
                    releaseDate = releaseDate,
                    wordCount = wordCount
                )
            )
        }
        return list
    }

    fun loadNovelChaptersFromDisk(novelId: String, customUriStr: String?): List<Chapter> {
        // Fix for chapter list re-fetching:
        // Always check internal filesDir first across all candidate file locations.
        val candidateFiles = listOf(
            File(getChaptersDir(novelId), "chapters_meta.json"),
            File(getChaptersDir(novelId), "chapters.json"),
            getNovelChaptersFile(novelId),
            File(context.filesDir, "novel_chapters_$novelId.json")
        )

        for (candidate in candidateFiles) {
            if (candidate.exists() && candidate.length() > 0) {
                try {
                    val text = candidate.readText()
                    val array = JSONArray(text)
                    val list = parseChaptersJsonArray(array, novelId)
                    if (list.isNotEmpty()) return list
                } catch (e: Exception) {
                    // Try next candidate
                }
            }
        }

        // Secondary fallback: SAF custom storage
        if (!customUriStr.isNullOrBlank()) {
            try {
                val rootTreeUri = Uri.parse(customUriStr)
                val rootDoc = DocumentFile.fromTreeUri(context, rootTreeUri)
                val chaptersDoc = rootDoc?.findFile("chapters")
                val safeNovelId = getSafeNovelId(novelId)
                val novelDoc = chaptersDoc?.findFile(safeNovelId)
                    ?: chaptersDoc?.findFile(novelId)
                val metaDoc = novelDoc?.findFile("chapters_meta.json")
                    ?: novelDoc?.findFile("novel_chapters_$novelId.json")
                    ?: novelDoc?.findFile("novel_chapters_$safeNovelId.json")
                if (metaDoc != null && metaDoc.exists()) {
                    context.contentResolver.openInputStream(metaDoc.uri)?.use { inputStream ->
                        val text = inputStream.bufferedReader().readText()
                        val array = JSONArray(text)
                        val list = parseChaptersJsonArray(array, novelId)
                        if (list.isNotEmpty()) {
                            // Backfill internal cache for instant future loads
                            try {
                                getNovelChaptersFile(novelId).writeText(text)
                                File(getChaptersDir(novelId), "chapters_meta.json").writeText(text)
                            } catch (e: Exception) {}
                            return list
                        }
                    }
                }
            } catch (e: Exception) {
                // Non-fatal fallback
            }
        }

        return emptyList()
    }

    fun parseEpub(uri: Uri, fileName: String): ParsedEpub {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open stream for URI")
        return EpubParser.parseEpub(context, inputStream, fileName)
    }

    fun deleteNovelChapters(novelId: String) {
        val dir = getChaptersDir(novelId)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        val metaFile = getNovelChaptersFile(novelId)
        if (metaFile.exists()) {
            metaFile.delete()
        }
    }

    fun deleteAllDownloadedContent(): Int {
        val chaptersDir = File(context.filesDir, "chapters")
        var count = 0
        if (chaptersDir.exists()) {
            count = chaptersDir.listFiles()?.size ?: 0
            chaptersDir.deleteRecursively()
            chaptersDir.mkdirs()
        }
        // Also remove novel_chapters metadata files from filesDir
        context.filesDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("novel_chapters_") && file.name.endsWith(".json")) {
                file.delete()
            }
        }
        return count
    }

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

    private fun getFolderSize(dir: File): Long {
        var size: Long = 0
        dir.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return size
    }
}
