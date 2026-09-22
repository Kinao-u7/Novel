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

    private fun getChaptersDir(novelId: String): File {
        val dir = File(context.filesDir, "chapters/$novelId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getChapterFile(novelId: String, chapterId: String): File {
        val safeChapterId = chapterId.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
        return File(getChaptersDir(novelId), "$safeChapterId.json")
    }

    private fun getNovelChaptersFile(novelId: String): File {
        return File(context.filesDir, "novel_chapters_$novelId.json")
    }

    fun isChapterFilePresent(novelId: String, chapterId: String, customUriStr: String?): Boolean {
        // Fast path: internal storage check
        if (getChapterFile(novelId, chapterId).exists()) return true

        // Secondary path: custom SAF storage if configured
        if (!customUriStr.isNullOrBlank()) {
            try {
                val rootTreeUri = Uri.parse(customUriStr)
                val rootDoc = DocumentFile.fromTreeUri(context, rootTreeUri)
                val chaptersDoc = rootDoc?.findFile("chapters")
                val novelDoc = chaptersDoc?.findFile(novelId)
                val safeChapterId = chapterId.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
                val doc = novelDoc?.findFile("$safeChapterId.json")
                if (doc != null && doc.exists()) return true
            } catch (e: Exception) {
                // Ignore SAF permission or resolution errors
            }
        }
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

        // 1. Always save to internal filesDir
        try {
            val file = getNovelChaptersFile(novelId)
            file.writeText(jsonStr)
        } catch (e: Exception) {
            e.printStackTrace()
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
                        var novelDoc = chaptersDoc.findFile(novelId)
                        if (novelDoc == null || !novelDoc.isDirectory) {
                            novelDoc = chaptersDoc.createDirectory(novelId)
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

    fun loadNovelChaptersFromDisk(novelId: String, customUriStr: String?): List<Chapter> {
        // Fix for SAF re-fetch bug:
        // Always check internal filesDir first. If present and valid, return immediately
        // without waiting for SAF permission resolution or tree traversal.
        val file = getNovelChaptersFile(novelId)
        if (file.exists()) {
            try {
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
                if (list.isNotEmpty()) return list
            } catch (e: Exception) {
                // Corrupted file, try SAF fallback
            }
        }

        // Secondary fallback: SAF custom storage
        if (!customUriStr.isNullOrBlank()) {
            try {
                val rootTreeUri = Uri.parse(customUriStr)
                val rootDoc = DocumentFile.fromTreeUri(context, rootTreeUri)
                val chaptersDoc = rootDoc?.findFile("chapters")
                val novelDoc = chaptersDoc?.findFile(novelId)
                val metaDoc = novelDoc?.findFile("chapters_meta.json")
                    ?: novelDoc?.findFile("novel_chapters_$novelId.json")
                if (metaDoc != null && metaDoc.exists()) {
                    context.contentResolver.openInputStream(metaDoc.uri)?.use { inputStream ->
                        val text = inputStream.bufferedReader().readText()
                        val array = JSONArray(text)
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
                        if (list.isNotEmpty()) {
                            // Backfill internal cache for instant future loads
                            try {
                                file.writeText(text)
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
