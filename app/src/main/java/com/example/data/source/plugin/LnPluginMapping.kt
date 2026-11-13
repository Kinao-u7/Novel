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

internal data class CachedNovelEntry(
    val novelId: String,
    val novel: Novel,
    val chapters: List<Chapter>,
    val timestampMs: Long
)

object LnPluginMapping {
    private const val DEFAULT_COVER_MARKER = "coverNotAvailable.jpg"

    fun optStringOrNull(json: JSONObject?, key: String): String? {
        if (json == null || !json.has(key) || json.isNull(key)) return null
        val v = json.opt(key)
        if (v == null || v == JSONObject.NULL) return null
        val s = json.optString(key, "").trim()
        return if (s.isEmpty() || s.equals("null", ignoreCase = true)) null else s
    }

    fun optStringOrNull(arr: JSONArray?, index: Int): String? {
        if (arr == null || index < 0 || index >= arr.length() || arr.isNull(index)) return null
        val v = arr.opt(index)
        if (v == null || v == JSONObject.NULL) return null
        val s = arr.optString(index, "").trim()
        return if (s.isEmpty() || s.equals("null", ignoreCase = true)) null else s
    }

    fun normalizeNovelStatus(raw: String?): String {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty() || trimmed.equals("null", ignoreCase = true)) return "Unknown"
        return when (trimmed) {
            "Completed", "Publishing Finished" -> "Completed"
            "Ongoing", "On Hiatus", "Cancelled" -> trimmed
            else -> "Unknown"
        }
    }

    fun parseGenres(raw: Any?): List<String> = when (raw) {
        null, JSONObject.NULL -> emptyList()
        is String -> {
            val s = raw.trim()
            if (s.isEmpty() || s.equals("null", ignoreCase = true)) emptyList()
            else s.split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        }
        is JSONArray -> (0 until raw.length()).mapNotNull { i ->
            optStringOrNull(raw, i)
        }
        else -> emptyList()
    }

    fun parseRating(obj: JSONObject): Float? {
        if (!obj.has("rating") || obj.isNull("rating")) return null
        val v = obj.opt("rating")
        if (v == null || v == JSONObject.NULL || v == "null") return null
        return obj.optDouble("rating").takeUnless { it.isNaN() }?.toFloat()
    }

    fun cleanCoverUrl(cover: String?): String? =
        cover?.trim()?.takeUnless {
            it.isEmpty() || it.equals("null", ignoreCase = true) || it.contains(DEFAULT_COVER_MARKER, ignoreCase = true)
        }

    fun parseRawChapters(arr: JSONArray?): List<RawPluginChapter> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val path = optStringOrNull(obj, "path") ?: return@mapNotNull null
            val name = optStringOrNull(obj, "name") ?: ""
            val time = optStringOrNull(obj, "releaseTime")
            val num = if (obj.has("chapterNumber") && !obj.isNull("chapterNumber")) {
                val v = obj.opt("chapterNumber")
                if (v == null || v == JSONObject.NULL || v == "null") null
                else obj.optDouble("chapterNumber").takeUnless { it.isNaN() }
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
        val sourceUrl = optStringOrNull(detailsJson, "sourceUrl")
        val rawChapters = parseRawChapters(novelObj.optJSONArray("chapters"))

        val novel = Novel(
            id = novelId,
            title = optStringOrNull(novelObj, "name") ?: "",
            author = optStringOrNull(novelObj, "author") ?: "",
            coverUrl = cleanCoverUrl(optStringOrNull(novelObj, "cover")),
            description = optStringOrNull(novelObj, "summary")
                ?: optStringOrNull(novelObj, "description")
                ?: "",
            genres = parseGenres(if (novelObj.has("genres") && !novelObj.isNull("genres")) novelObj.get("genres") else null),
            status = normalizeNovelStatus(optStringOrNull(novelObj, "status")),
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
            val path = optStringOrNull(obj, "path")
            if (path.isNullOrBlank() || !isSafe(path)) return@mapNotNull null
            Novel(
                id = encodeId(path),
                title = optStringOrNull(obj, "name") ?: "",
                author = optStringOrNull(obj, "author") ?: "",
                coverUrl = cleanCoverUrl(optStringOrNull(obj, "cover")),
                description = optStringOrNull(obj, "summary") ?: optStringOrNull(obj, "description") ?: "",
                status = normalizeNovelStatus(optStringOrNull(obj, "status")),
                sourceId = sourceId,
                sourceName = sourceName,
                rawId = path
            )
        }
    }

    fun extractMetaFromScript(script: String): JSONObject {
        val idRegex = Regex("""(?:id\s*:\s*["']([^"']+)["']|this\.id\s*=\s*["']([^"']+)["'])""")
        val nameRegex = Regex("""(?:name\s*:\s*["']([^"']+)["']|this\.name\s*=\s*["']([^"']+)["']|sourceName\s*:\s*["']([^"']+)["'])""")
        val versionRegex = Regex("""(?:version\s*:\s*["']([^"']+)["']|this\.version\s*=\s*["']([^"']+)["'])""")
        val siteRegex = Regex("""(?:site\s*:\s*["']([^"']+)["']|this\.site\s*=\s*["']([^"']+)["']|sourceSite\s*:\s*["']([^"']+)["'])""")
        val foundId = idRegex.find(script)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: "unknown"
        val foundName = nameRegex.find(script)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: foundId
        val foundVersion = versionRegex.find(script)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: "1.0.0"
        val foundSite = siteRegex.find(script)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: ""
        return JSONObject().put("id", foundId).put("name", foundName).put("version", foundVersion).put("site", foundSite)
    }
}
