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

    val rawPluginId: String get() = lazyMeta.optString("id", "unknown")
    val pluginId: String get() = rawPluginId.replace(Regex("""[^A-Za-z0-9_.-]"""), "_")

    override val id: String get() = "ln_$pluginId"
    override val name: String get() = lazyMeta.optString("name", pluginId)
    override val language: String get() = pluginLanguage.uppercase()
    override val version: String get() = lazyMeta.optString("version", "1.0.0")
    override val description: String get() = "LNReader plugin: $name"
    override val isLocal: Boolean get() = false

    // latestSupported is ASSUMED via source script check
    override val latestSupported: Boolean get() = pluginScript.contains("showLatestNovels")
    override val filtersSupported: Boolean get() = false

    internal fun getBridgeHost(): PluginBridgeHost? = host

    private suspend fun ensureHostInitialized(): PluginBridgeHost {
        host?.let { return it }
        val newHost = PluginBridgeHost(
            httpClient = PluginHttpClientImpl(allowLoopback = allowLoopback),
            jsDispatcher = ioDispatcher
        )
        val polyfillsJs = loadAssetOrFile("js/polyfills.js")
        val libsJs = loadAssetOrFile("js/libs.js")
        val harnessJs = loadAssetOrFile("js/harness.js")

        newHost.initHarness(polyfillsJs, libsJs, harnessJs)
        newHost.loadPlugin(pluginId, pluginScript)
        host = newHost
        return newHost
    }

    private fun loadAssetOrFile(relativePath: String): String {
        context?.let { ctx ->
            try {
                return ctx.assets.open(relativePath).bufferedReader().use { it.readText() }
            } catch (_: Exception) {}
        }
        val fileInApp = File("app/src/main/assets/$relativePath")
        if (fileInApp.exists()) return fileInApp.readText()
        val fileDirect = File("src/main/assets/$relativePath")
        if (fileDirect.exists()) return fileDirect.readText()
        throw IOException("Failed to load asset: $relativePath")
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

    fun decodeNovelPath(novelId: String): String {
        val prefix = "ln_${pluginId}_"
        if (!novelId.startsWith(prefix)) {
            throw IllegalArgumentException("Plugin [$pluginId] cannot decode novelId not starting with '$prefix': $novelId")
        }
        val encoded = novelId.substring(prefix.length)
        val bytes = Base64.decode(encoded, Base64.URL_SAFE)
        return String(bytes, Charsets.UTF_8)
    }

    private inline fun <T> runPluginCall(methodName: String, block: (PluginBridgeHost) -> T): T {
        try {
            val bridge = kotlinx.coroutines.runBlocking(ioDispatcher) { ensureHostInitialized() }
            return block(bridge)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            throw IOException("Plugin [$pluginId] failed during $methodName: ${e.message}", e)
        }
    }

    override suspend fun getPopularNovels(page: Int): List<Novel> = withContext(ioDispatcher) {
        callMutex.withLock {
            runPluginCall("popularNovels") { bridge ->
                val jsonStr = bridge.callPluginWithOptions(pluginId, "popularNovels", listOf(page))
                parseNovelsArray(jsonStr)
            }
        }
    }

    override suspend fun getLatestNovels(page: Int): List<Novel> = withContext(ioDispatcher) {
        if (!latestSupported) return@withContext emptyList()
        callMutex.withLock {
            runPluginCall("latestNovels") { bridge ->
                val extra = JSONObject().apply { put("showLatestNovels", true) }
                val jsonStr = bridge.callPluginWithOptions(pluginId, "popularNovels", listOf(page), extra)
                parseNovelsArray(jsonStr)
            }
        }
    }

    override suspend fun searchNovels(query: String, page: Int): List<Novel> = withContext(ioDispatcher) {
        callMutex.withLock {
            runPluginCall("searchNovels") { bridge ->
                val jsonStr = bridge.callPluginWithOptions(pluginId, "searchNovels", listOf(query, page))
                parseNovelsArray(jsonStr)
            }
        }
    }


    private fun parseNovelsArray(jsonStr: String): List<Novel> {
        val array = JSONArray(jsonStr)
        val results = ArrayList<Novel>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val rawPath = obj.optString("path", "")
            val title = obj.optString("name", "")
            val cover = obj.optString("cover", "").takeIf { it.isNotBlank() }

            if (rawPath.isBlank()) continue
            if (!isPathLengthSafe(rawPath)) {
                Log.w("LnPluginSource", "Skipping novel with over-long path: $rawPath (plugin=$pluginId)")
                continue
            }

            val novelId = encodeNovelId(rawPath)
            results.add(
                Novel(
                    id = novelId,
                    title = title,
                    author = "",
                    coverUrl = cover,
                    description = "",
                    status = "Ongoing",
                    sourceId = id,
                    sourceName = name,
                    rawId = rawPath,
                    sourceUrl = null
                )
            )
        }
        return results
    }

    override suspend fun getNovelDetails(novelId: String): Novel {
        throw UnsupportedOperationException("getNovelDetails is not implemented in C1")
    }

    override suspend fun getChapterList(novelId: String): List<Chapter> {
        throw UnsupportedOperationException("getChapterList is not implemented in C1")
    }

    override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent {
        throw UnsupportedOperationException("getChapterContent is not implemented in C1")
    }

    override fun close() {
        host?.close()
        host = null
    }
}
