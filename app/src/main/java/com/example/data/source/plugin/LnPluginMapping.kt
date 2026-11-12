package com.example.data.source.plugin

import com.example.data.model.Chapter
import com.example.data.model.Novel
import org.json.JSONArray
import org.json.JSONObject

data class RawPluginChapter(
    val path: String,
    val name: String,
    val releaseTime: String?,
    val chapterNumber: Double?
)

object LnPluginMapping {
    private const val DEFAULT_COVER_MARKER = "coverNotAvailable.jpg"

    fun normalizeNovelStatus(raw: String?): String = when (raw?.trim()) {
        "Completed", "Publishing Finished" -> "Completed"
        "Ongoing", "On Hiatus", "Cancelled" -> raw.trim()
        else -> "Unknown"
    }

    fun parseGenres(raw: Any?): List<String> = when (raw) {
        is String -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it, "").trim().takeIf { s -> s.isNotEmpty() } }
        else -> emptyList()
    }

    fun parseRating(obj: JSONObject): Float? =
        obj.takeIf { it.has("rating") && !it.isNull("rating") }?.optDouble("rating")?.takeUnless { it.isNaN() }?.toFloat()

    fun cleanCoverUrl(cover: String?): String? =
        cover?.trim()?.takeUnless { it.isEmpty() || it.contains(DEFAULT_COVER_MARKER, ignoreCase = true) }

    fun parseRawChapters(arr: JSONArray?): List<RawPluginChapter> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val path = obj.optString("path", "").trim()
            if (path.isEmpty()) return@mapNotNull null
            val name = obj.optString("name", "").trim()
            val time = obj.optString("releaseTime", "").trim().takeIf { it.isNotEmpty() }
            val num = if (obj.has("chapterNumber") && !obj.isNull("chapterNumber")) {
                obj.optDouble("chapterNumber").takeUnless { it.isNaN() }
            } else null
            RawPluginChapter(path, name, time, num)
        }
    }

    fun orderAndMapChapters(novelId: String, rawChapters: List<RawPluginChapter>): List<Chapter> {
        val sorted = if (rawChapters.isNotEmpty() && rawChapters.all { it.chapterNumber != null }) {
            rawChapters.sortedBy { it.chapterNumber }
        } else rawChapters
        return sorted.mapIndexed { idx, raw ->
            Chapter(
                id = raw.path,
                novelId = novelId,
                title = raw.name.ifEmpty { raw.path },
                chapterIndex = idx + 1,
                releaseDate = raw.releaseTime ?: "Recent"
            )
        }
    }

    fun parseNovelAndChapters(
        novelId: String,
        pluginId: String,
        pluginName: String,
        path: String,
        detailsJson: JSONObject
    ): Pair<Novel, List<RawPluginChapter>> {
        val novelObj = detailsJson.optJSONObject("novel") ?: detailsJson
        val sourceUrl = detailsJson.optString("sourceUrl", "").takeIf { it.isNotBlank() }
        val rawChapters = parseRawChapters(novelObj.optJSONArray("chapters"))

        val novel = Novel(
            id = novelId,
            title = novelObj.optString("name", "").trim(),
            author = novelObj.optString("author", "").trim(),
            coverUrl = cleanCoverUrl(novelObj.optString("cover", "")),
            description = novelObj.optString("summary", "").trim(),
            genres = parseGenres(if (novelObj.has("genres")) novelObj.get("genres") else null),
            status = normalizeNovelStatus(novelObj.optString("status", "").takeIf { it.isNotBlank() }),
            sourceId = "ln_$pluginId",
            sourceName = pluginName,
            rawId = path,
            sourceUrl = sourceUrl,
            rating = parseRating(novelObj),
            totalChapters = rawChapters.size
        )
        return Pair(novel, rawChapters)
    }

    fun parseNovelsArray(
        jsonStr: String,
        sourceId: String,
        sourceName: String,
        isSafe: (String) -> Boolean,
        encodeId: (String) -> String
    ): List<Novel> {
        val arr = JSONArray(jsonStr)
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val path = obj.optString("path", "")
            if (path.isBlank() || !isSafe(path)) return@mapNotNull null
            Novel(
                id = encodeId(path),
                title = obj.optString("name", ""),
                author = "",
                coverUrl = obj.optString("cover", "").takeIf { it.isNotBlank() },
                description = "",
                status = "Ongoing",
                sourceId = sourceId,
                sourceName = sourceName,
                rawId = path
            )
        }
    }
}
