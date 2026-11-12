package com.example.data.source.plugin

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.Novel
import com.example.data.source.NovelSource
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Android [NovelSource] adapter wrapping LNReader JavaScript plugins.
 */
class LnPluginSource(
    private val pluginScript: String,
    private val pluginLanguage: String = "AR",
    private val context: Context? = null,
    private val customName: String? = null,
    private val customVersion: String? = null,
    private val customId: String? = null,
    private val allowLoopback: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : NovelSource, AutoCloseable {

    private val callMutex = Mutex()
    private var host: PluginBridgeHost? = null

    // Read synchronously or lazily from plugin header / script
    private val lazyMeta: JSONObject by lazy {
        val idRegex = Regex("""(?:id\s*:\s*["']([^"']+)["']|this\.id\s*=\s*["']([^"']+)["'])""")
        val nameRegex = Regex("""(?:name\s*:\s*["']([^"']+)["']|this\.name\s*=\s*["']([^"']+)["']|sourceName\s*:\s*["']([^"']+)["'])""")
        val versionRegex = Regex("""(?:version\s*:\s*["']([^"']+)["']|this\.version\s*=\s*["']([^"']+)["'])""")
        val siteRegex = Regex("""(?:site\s*:\s*["']([^"']+)["']|this\.site\s*=\s*["']([^"']+)["']|sourceSite\s*:\s*["']([^"']+)["'])""")

        val foundId = idRegex.find(pluginScript)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: "unknown"
        val foundName = nameRegex.find(pluginScript)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: foundId
        val foundVersion = versionRegex.find(pluginScript)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: "1.0.0"
        val foundSite = siteRegex.find(pluginScript)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: ""

        JSONObject().apply {
            put("id", foundId)
            put("name", foundName)
            put("version", foundVersion)
            put("site", foundSite)
        }
    }

    val rawPluginId: String get() = customId ?: LnPluginMapping.optStringOrNull(lazyMeta, "id") ?: "unknown"
    val pluginId: String get() = rawPluginId.replace(Regex("""[^A-Za-z0-9_.-]"""), "_")

    override val id: String get() = "ln_$pluginId"
    override val name: String get() = customName ?: LnPluginMapping.optStringOrNull(lazyMeta, "name") ?: pluginId
    override val language: String get() = pluginLanguage.uppercase()
    override val version: String get() = customVersion ?: LnPluginMapping.optStringOrNull(lazyMeta, "version") ?: "1.0.0"
    override val description: String get() = "LNReader plugin: $name"
    override val isLocal: Boolean get() = false

    // latestSupported is ASSUMED via source script check
    override val latestSupported: Boolean get() = pluginScript.contains("showLatestNovels")
    override val filtersSupported: Boolean get() = false

    internal fun getBridgeHost(): PluginBridgeHost? = host

    private suspend fun ensureHostInitialized(): PluginBridgeHost {
        host?.let { return it }
        val startMs = android.os.SystemClock.elapsedRealtime()
        val newHost = PluginBridgeHost(
            httpClient = PluginHttpClientImpl(allowLoopback = allowLoopback)
        )
        val polyfillsJs = loadAssetOrFile("js/polyfills.js")
        val libsJs = loadAssetOrFile("js/libs.js")
        val harnessJs = loadAssetOrFile("js/harness.js")

        newHost.initHarness(polyfillsJs, libsJs, harnessJs)
        newHost.loadPlugin(pluginId, pluginScript)
        host = newHost
        val elapsedMs = android.os.SystemClock.elapsedRealtime() - startMs
        Log.i("LnPluginSource", "Initialized plugin [$pluginId] in ${elapsedMs}ms")
        return newHost
    }

    private fun loadAssetOrFile(relativePath: String): String {
        context?.let { ctx ->
            try {
                return ctx.assets.open(relativePath).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.w("LnPluginSource", "Failed to load asset '$relativePath' via Context for plugin [$pluginId]", e)
            }
        }
        val fileInApp = File("app/src/main/assets/$relativePath")
        if (fileInApp.exists()) return fileInApp.readText()
        val fileDirect = File("src/main/assets/$relativePath")
        if (fileDirect.exists()) return fileDirect.readText()
        val err = IOException("Required asset '$relativePath' missing for plugin [$pluginId]")
        Log.w("LnPluginSource", err.message, err)
        throw err
    }

    fun isPathLengthSafe(path: String): Boolean {
        // len("ln_" + pluginId + "_") + ceil(4 * n / 3) + 5 <= 255
        val prefixLen = 3 + pluginId.length + 1 // "ln_" + pluginId + "_"
        val base64Len = Math.ceil(4.0 * path.toByteArray(Charsets.UTF_8).size / 3.0).toInt()
        val totalLen = prefixLen + base64Len + 5
        return totalLen <= 255
    }

    fun encodeNovelId(path: String): String {
        val encoded = Base64.encodeToString(
            path.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "ln_${pluginId}_$encoded"
    }

    private val cachedEntry = AtomicReference<CachedNovelEntry?>(null)

    companion object {
        const val PREFIX = "ln_"
        private val BASE64_URL_SAFE_REGEX = Regex("^[A-Za-z0-9_-]+$")
    }

    fun decodeNovelPath(novelId: String): String {
        val prefix = "ln_${pluginId}_"
        if (!novelId.startsWith(prefix)) {
            throw IllegalArgumentException("Plugin [$pluginId] cannot decode novelId not starting with '$prefix': $novelId")
        }
        val encoded = novelId.substring(prefix.length)
        if (encoded.isEmpty() || !BASE64_URL_SAFE_REGEX.matches(encoded)) {
            throw IllegalArgumentException("Plugin [$pluginId] malformed novelId base64 (invalid chars or empty): $novelId")
        }
        val bytes = try {
            Base64.decode(encoded, Base64.URL_SAFE)
        } catch (e: Exception) {
            throw IllegalArgumentException("Plugin [$pluginId] malformed novelId base64: $novelId", e)
        }
        if (bytes == null || bytes.isEmpty()) {
            throw IllegalArgumentException("Plugin [$pluginId] decoded empty novel path from: $novelId")
        }
        val reencoded = Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        if (reencoded != encoded) {
            throw IllegalArgumentException("Plugin [$pluginId] novelId failed base64 round-trip verification: $novelId")
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val path = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            throw IllegalArgumentException("Plugin [$pluginId] decoded bytes are not valid UTF-8: $novelId", e)
        }
        if (path.isEmpty()) {
            throw IllegalArgumentException("Plugin [$pluginId] decoded empty novel path from: $novelId")
        }
        return path
    }

    private fun decodeAndValidatePath(novelId: String): String {
        val path = decodeNovelPath(novelId)
        if (!isPathLengthSafe(path)) {
            throw IllegalArgumentException("Novel path exceeds safe length limit: $path (plugin=$pluginId)")
        }
        return path
    }

    private suspend inline fun <T> runPluginCall(method: String, block: suspend (PluginBridgeHost) -> T): T =
        callMutex.withLock {
            try {
                val bridge = ensureHostInitialized()
                block(bridge)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (e is IllegalArgumentException) throw e
                throw IOException("Plugin [$pluginId] failed during $method: ${e.message}", e)
            }
        }

    override suspend fun getPopularNovels(page: Int): List<Novel> = withContext(ioDispatcher) {
        runPluginCall("popularNovels") { bridge ->
            val jsonStr = bridge.callPluginWithOptions(pluginId, "popularNovels", listOf(page))
            LnPluginMapping.parseNovelsArray(jsonStr, id, name, ::isPathLengthSafe, ::encodeNovelId)
        }
    }

    override suspend fun getLatestNovels(page: Int): List<Novel> = withContext(ioDispatcher) {
        if (!latestSupported) return@withContext emptyList()
        runPluginCall("latestNovels") { bridge ->
            val extra = JSONObject().apply { put("showLatestNovels", true) }
            val jsonStr = bridge.callPluginWithOptions(pluginId, "popularNovels", listOf(page), extra)
            LnPluginMapping.parseNovelsArray(jsonStr, id, name, ::isPathLengthSafe, ::encodeNovelId)
        }
    }

    override suspend fun searchNovels(query: String, page: Int): List<Novel> = withContext(ioDispatcher) {
        runPluginCall("searchNovels") { bridge ->
            val jsonStr = bridge.callPluginWithOptions(pluginId, "searchNovels", listOf(query, page))
            LnPluginMapping.parseNovelsArray(jsonStr, id, name, ::isPathLengthSafe, ::encodeNovelId)
        }
    }

    private suspend fun fetchChapters(
        bridge: PluginBridgeHost,
        path: String,
        novelObj: JSONObject
    ): List<RawPluginChapter> {
        val allChapters = LnPluginMapping.parseRawChapters(novelObj.optJSONArray("chapters")).toMutableList()
        val totalPages = novelObj.optInt("totalPages", 1)
        if (totalPages > 1) {
            val maxPages = minOf(totalPages, 200)
            if (totalPages > 200) {
                Log.w("LnPluginSource", "totalPages ($totalPages) exceeded 200 limit for novel $path (plugin=$pluginId)")
            }
            for (p in 2..maxPages) {
                val pageJson = bridge.callPlugin(pluginId, "parsePage", listOf(path, p))
                if (pageJson != "null" && pageJson.isNotBlank()) {
                    val pageArr = try { JSONArray(pageJson) } catch (_: Exception) { null }
                    if (pageArr != null) allChapters.addAll(LnPluginMapping.parseRawChapters(pageArr))
                }
            }
        }
        return allChapters
    }

    override suspend fun getNovelDetails(novelId: String): Novel = withContext(ioDispatcher) {
        val path = decodeAndValidatePath(novelId)
        runPluginCall("parseNovel") { bridge ->
            val jsonStr = bridge.callPlugin(pluginId, "__parseNovelDetails", listOf(path))
            val detailsJson = JSONObject(jsonStr)
            val novelObj = detailsJson.optJSONObject("novel") ?: detailsJson
            val authorStr = novelObj.optString("author", "")
            val summaryStr = if (novelObj.has("summary")) novelObj.optString("summary", "") else novelObj.optString("description", "")
            Log.d("LnPluginSource", "parseNovel [$pluginId] keys: ${novelObj.keys().asSequence().toList()}, author len: ${authorStr.length}, summary len: ${summaryStr.length}")
            val allRawChapters = fetchChapters(bridge, path, novelObj)
            val (baseNovel, _) = LnPluginMapping.parseNovelAndChapters(novelId, pluginId, name, path, detailsJson)
            val chapters = LnPluginMapping.orderAndMapChapters(novelId, allRawChapters)
            val novel = baseNovel.copy(totalChapters = chapters.size)
            cachedEntry.set(CachedNovelEntry(novelId, novel, chapters, System.currentTimeMillis()))
            novel
        }
    }

    override suspend fun getChapterList(novelId: String): List<Chapter> = withContext(ioDispatcher) {
        val path = decodeAndValidatePath(novelId)
        val cached = cachedEntry.get()
        val now = System.currentTimeMillis()
        if (cached != null && cached.novelId == novelId && (now - cached.timestampMs) <= 10_000L) {
            if (cachedEntry.compareAndSet(cached, null)) {
                return@withContext cached.chapters
            }
        }

        runPluginCall("parseNovel") { bridge ->
            val jsonStr = bridge.callPlugin(pluginId, "__parseNovelDetails", listOf(path))
            val detailsJson = JSONObject(jsonStr)
            val novelObj = detailsJson.optJSONObject("novel") ?: detailsJson
            val allRawChapters = fetchChapters(bridge, path, novelObj)
            LnPluginMapping.orderAndMapChapters(novelId, allRawChapters)
        }
    }

    override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent = withContext(ioDispatcher) {
        decodeAndValidatePath(novelId)
        if (chapterId.isBlank()) throw IllegalArgumentException("chapterId cannot be blank")
        if (!isPathLengthSafe(chapterId)) throw IllegalArgumentException("Chapter path exceeds safe length limit: $chapterId (plugin=$pluginId)")

        val bestTitle = cachedEntry.get()?.takeIf { it.novelId == novelId }?.chapters?.find { it.id == chapterId }?.title
            ?: chapterId

        runPluginCall("parseChapter") { bridge ->
            val jsonStr = bridge.callPlugin(pluginId, "__parseChapterContent", listOf(chapterId))
            val arr = JSONArray(jsonStr)
            val paragraphs = (0 until arr.length()).mapNotNull { i -> LnPluginMapping.optStringOrNull(arr, i) }
            ChapterContent(chapterId = chapterId, novelId = novelId, title = bestTitle, paragraphs = paragraphs)
        }
    }

    override fun close() {
        cachedEntry.set(null)
        host?.close()
        host = null
    }
}
