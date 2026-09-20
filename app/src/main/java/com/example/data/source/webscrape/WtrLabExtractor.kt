package com.example.data.source.webscrape

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class WtrLabMetadata(
    val title: String,
    val author: String,
    val synopsis: String,
    val rawId: String,
    val chapterCount: Int,
    val genres: List<String>,
    val coverUrl: String?,
    val locale: String,
    val serieSlug: String,
    val sourceUrl: String
)

object WtrLabExtractor {
    private const val TAG = "WtrLabExtractor"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Checks if the given URL or host belongs to WTR-Lab.
     */
    fun isWtrLabUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.contains("wtr-lab.com", ignoreCase = true)
    }

    /**
     * Extracts novel metadata from Next.js __NEXT_DATA__ JSON string according to WTR-Lab's exact schema:
     * - Title: props.pageProps.serie.serie_data.data.title
     * - Author: props.pageProps.serie.serie_data.data.author (or top-level serie_data.author as fallback)
     * - Synopsis: props.pageProps.serie.serie_data.data.description
     * - raw_id: props.pageProps.serie.serie_data.raw_id
     * - chapter_count: props.pageProps.serie.serie_data.chapter_count
     * - Genre/tag IDs: props.pageProps.serie.serie_data.genres and props.pageProps.serie.serie_data.tags
     * - Tag name lookup: props.pageProps.tags cross-referenced by id
     */
    fun extractMetadataFromNextData(nextDataJsonStr: String, pageUrl: String): WtrLabMetadata? {
        return try {
            val root = JSONObject(nextDataJsonStr)
            val props = root.optJSONObject("props") ?: JSONObject()
            val pageProps = props.optJSONObject("pageProps") ?: JSONObject()
            val serie = pageProps.optJSONObject("serie") ?: JSONObject()
            val serieData = serie.optJSONObject("serie_data") ?: JSONObject()
            val data = serieData.optJSONObject("data") ?: JSONObject()

            // 1. Title: props.pageProps.serie.serie_data.data.title
            var title = data.optString("title").trim()
            if (title.isBlank()) {
                title = serieData.optString("title").trim()
            }
            if (title.isBlank()) {
                title = serie.optString("title").trim()
            }

            // 2. Author: props.pageProps.serie.serie_data.data.author (fallback: serie_data.author)
            var author = data.optString("author").trim()
            if (author.isBlank()) {
                val authorObj = data.optJSONObject("author")
                if (authorObj != null) {
                    author = authorObj.optString("name").trim()
                }
            }
            if (author.isBlank()) {
                author = serieData.optString("author").trim()
            }
            if (author.isBlank()) {
                val sAuthorObj = serieData.optJSONObject("author")
                if (sAuthorObj != null) {
                    author = sAuthorObj.optString("name").trim()
                }
            }
            if (author.isBlank()) {
                author = serie.optString("author").trim()
            }
            if (author.isBlank()) {
                author = "Unknown Author"
            }

            // 3. Synopsis: props.pageProps.serie.serie_data.data.description
            var synopsis = data.optString("description").trim()
            if (synopsis.isBlank()) {
                synopsis = serieData.optString("description").trim()
            }
            if (synopsis.isBlank()) {
                synopsis = serie.optString("description").trim()
            }
            // Strip HTML tags and clean whitespace
            synopsis = synopsis.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

            // 4. raw_id: props.pageProps.serie.serie_data.raw_id
            var rawId = serieData.optString("raw_id").trim()
            if (rawId.isBlank()) {
                val rawIdNum = serieData.optLong("raw_id", -1L)
                if (rawIdNum > 0) rawId = rawIdNum.toString()
            }
            if (rawId.isBlank()) {
                rawId = serie.optString("raw_id").trim()
                if (rawId.isBlank()) {
                    val sRawIdNum = serie.optLong("raw_id", -1L)
                    if (sRawIdNum > 0) rawId = sRawIdNum.toString()
                }
            }

            // 5. chapter_count: props.pageProps.serie.serie_data.chapter_count
            var chapterCount = serieData.optInt("chapter_count", -1)
            if (chapterCount <= 0) {
                chapterCount = serie.optInt("chapter_count", 0)
            }

            // 6. Tag lookup map from props.pageProps.tags: [{id, title, category_id, category_name}, ...]
            val tagMap = mutableMapOf<String, String>()
            val tagsArr = pageProps.optJSONArray("tags")
            if (tagsArr != null) {
                for (i in 0 until tagsArr.length()) {
                    val tObj = tagsArr.optJSONObject(i) ?: continue
                    val tId = tObj.opt("id")?.toString()?.trim() ?: continue
                    val tTitle = tObj.optString("title").ifBlank { tObj.optString("name") }.trim()
                    if (tTitle.isNotBlank()) {
                        tagMap[tId] = tTitle
                    }
                }
            }

            // 7. Genre & tag IDs lookup
            val genresList = mutableListOf<String>()
            val genresArr = serieData.optJSONArray("genres")
            if (genresArr != null) {
                for (i in 0 until genresArr.length()) {
                    val gId = genresArr.opt(i)?.toString()?.trim() ?: continue
                    val resolvedName = tagMap[gId]
                    if (!resolvedName.isNullOrBlank() && !genresList.contains(resolvedName)) {
                        genresList.add(resolvedName)
                    }
                }
            }
            val tagsIdArr = serieData.optJSONArray("tags")
            if (tagsIdArr != null) {
                for (i in 0 until tagsIdArr.length()) {
                    val tId = tagsIdArr.opt(i)?.toString()?.trim() ?: continue
                    val resolvedName = tagMap[tId]
                    if (!resolvedName.isNullOrBlank() && !genresList.contains(resolvedName)) {
                        genresList.add(resolvedName)
                    }
                }
            }

            // 8. Cover image
            var coverUrl: String? = data.optString("image").ifBlank { null }
                ?: serieData.optString("image").ifBlank { null }
                ?: serie.optString("image").ifBlank { null }
                ?: serie.optString("cover").ifBlank { null }
                ?: serie.optString("cover_url").ifBlank { null }

            if (coverUrl != null && !coverUrl.startsWith("http")) {
                coverUrl = "https://wtr-lab.com" + if (coverUrl.startsWith("/")) coverUrl else "/$coverUrl"
            }

            // 9. Query & slug resolution: query.locale & query.serie_slug
            val queryObj = root.optJSONObject("query")
                ?: pageProps.optJSONObject("query")
                ?: JSONObject()
            var locale = queryObj.optString("locale").trim()
            if (locale.isBlank()) {
                locale = pageProps.optString("locale").trim()
            }
            var serieSlug = queryObj.optString("serie_slug").trim()
            if (serieSlug.isBlank()) {
                serieSlug = serie.optString("slug").trim()
            }
            if (serieSlug.isBlank()) {
                serieSlug = serieData.optString("slug").trim()
            }

            // Fallback locale / serie_slug from pageUrl
            val uriSegments = pageUrl.removePrefix("https://").removePrefix("http://")
                .substringAfter("/")
                .split("/")
                .filter { it.isNotBlank() }

            if (locale.isBlank()) {
                if (uriSegments.isNotEmpty() && uriSegments[0].matches(Regex("^[a-z]{2}(-[a-z]{2})?$", RegexOption.IGNORE_CASE))) {
                    locale = uriSegments[0]
                } else {
                    locale = "en"
                }
            }

            if (serieSlug.isBlank()) {
                // Check uriSegments, e.g. ["en", "serie-98790", "tycoon-if-you-keep-imagining-things-im-going-to-become-a-god"]
                for (seg in uriSegments.reversed()) {
                    val cleanSeg = seg.substringBefore("?").substringBefore("#")
                    if (!cleanSeg.equals(locale, ignoreCase = true) && !cleanSeg.startsWith("serie-") && cleanSeg != "serie") {
                        serieSlug = cleanSeg
                        break
                    }
                }
                if (serieSlug.isBlank() && rawId.isNotBlank()) {
                    serieSlug = "serie-$rawId"
                }
            }

            WtrLabMetadata(
                title = title,
                author = author,
                synopsis = synopsis,
                rawId = rawId,
                chapterCount = chapterCount,
                genres = genresList,
                coverUrl = coverUrl,
                locale = locale.ifBlank { "en" },
                serieSlug = serieSlug,
                sourceUrl = pageUrl
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Next.js __NEXT_DATA__ for WTR-Lab: ${e.message}", e)
            null
        }
    }

    /**
     * Parses the JSON response from https://wtr-lab.com/api/chapters/{raw_id}?start=1&end={chapter_count}
     * Structure: {"chapters": [{"id", "order", "title", "name", "updated_at"}, ...]}
     * Maps "title" to the chapter title (English/translated) and "order" to chapter number.
     * Chapter URL: https://wtr-lab.com/{locale}/novel/{raw_id}/{serie_slug}/chapter-{order}
     */
    fun parseChaptersResponse(
        jsonStr: String,
        rawId: String = "",
        locale: String = "en",
        serieSlug: String = ""
    ): List<ScrapedChapter> {
        val list = mutableListOf<ScrapedChapter>()
        try {
            val root = JSONObject(jsonStr)
            val chaptersArr = root.optJSONArray("chapters") ?: JSONArray()
            val effectiveLocale = locale.ifBlank { "en" }

            for (i in 0 until chaptersArr.length()) {
                val item = chaptersArr.optJSONObject(i) ?: continue
                val order = item.optInt("order", i + 1)
                val title = item.optString("title").trim()
                    .ifBlank { item.optString("name").trim() }
                    .ifBlank { "Chapter $order" }

                val chapterUrl = if (rawId.isNotBlank() && serieSlug.isNotBlank()) {
                    "https://wtr-lab.com/$effectiveLocale/novel/$rawId/$serieSlug/chapter-$order"
                } else if (rawId.isNotBlank()) {
                    "https://wtr-lab.com/$effectiveLocale/novel/$rawId/chapter-$order"
                } else if (serieSlug.isNotBlank()) {
                    "https://wtr-lab.com/$effectiveLocale/$serieSlug/chapter-$order"
                } else {
                    "https://wtr-lab.com/$effectiveLocale/chapter-$order"
                }

                list.add(
                    ScrapedChapter(
                        title = title,
                        url = chapterUrl,
                        order = order
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing WTR-Lab chapters API response: ${e.message}", e)
        }
        return list
    }

    /**
     * Fetches chapters from WTR-Lab API using direct HTTP GET:
     * https://wtr-lab.com/api/chapters/{raw_id}?start=1&end={chapter_count}
     * (end is set to chapterCount + 50 as confirmed safe by user).
     */
    fun fetchChaptersFromApi(
        rawId: String,
        chapterCount: Int,
        locale: String = "en",
        serieSlug: String = "",
        userAgent: String? = null,
        cookies: String? = null,
        referer: String? = null
    ): List<ScrapedChapter> {
        if (rawId.isBlank()) return emptyList()

        val endParam = if (chapterCount > 0) chapterCount + 50 else 200
        val url = "https://wtr-lab.com/api/chapters/$rawId?start=1&end=$endParam"
        Log.d(TAG, "Executing direct HTTP GET to WTR-Lab chapters API: $url")

        return try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/plain, */*")

            if (!userAgent.isNullOrBlank()) {
                reqBuilder.header("User-Agent", userAgent)
            }
            if (!cookies.isNullOrBlank()) {
                reqBuilder.header("Cookie", cookies)
            }
            if (!referer.isNullOrBlank()) {
                reqBuilder.header("Referer", referer)
            }

            val request = reqBuilder.build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "WTR-Lab API request failed with code ${response.code}: ${response.message}")
                return emptyList()
            }

            val body = response.body?.string() ?: ""
            if (body.isBlank()) {
                Log.w(TAG, "WTR-Lab API returned empty body.")
                return emptyList()
            }

            val chapters = parseChaptersResponse(body, rawId = rawId, locale = locale, serieSlug = serieSlug)
            Log.d(TAG, "Successfully fetched and parsed ${chapters.size} chapters from WTR-Lab API.")
            chapters
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch chapters from WTR-Lab API: ${e.message}", e)
            emptyList()
        }
    }
}
