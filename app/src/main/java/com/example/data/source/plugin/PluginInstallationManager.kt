package com.example.data.source.plugin

import android.content.Context
import com.example.data.source.NetworkDefaults
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class InstalledPlugin(
    val id: String,
    val name: String,
    val language: String,
    val version: String,
    val site: String,
    val iconUrl: String? = null
)

class PluginInstallationManager(
    private val pluginsDir: File,
    private val allowHttp: Boolean = false,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    constructor(context: Context) : this(File(context.filesDir, "plugins"))

    private val mutex = Mutex()
    private val manifestFile = File(pluginsDir, INSTALLED_FILE_NAME)

    suspend fun fetchCatalogue(url: String): List<PluginCatalogueEntry> = withContext(ioDispatcher) {
        validateUrl(url)
        val body = httpClient.newCall(buildRequest(url)).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code} fetching catalogue")
            res.body?.string() ?: throw IOException("Empty catalogue response")
        }
        PluginCatalogueParser.parseCatalogueJson(body)
    }

    suspend fun install(entry: PluginCatalogueEntry): InstalledPlugin = installOrUpdate(entry)

    suspend fun update(entry: PluginCatalogueEntry): InstalledPlugin = installOrUpdate(entry)

    private suspend fun installOrUpdate(entry: PluginCatalogueEntry): InstalledPlugin = withContext(ioDispatcher) {
        validateId(entry.id)
        validateUrl(entry.url)
        mutex.withLock {
            pluginsDir.mkdirs()
            val tmpFile = File(pluginsDir, "${entry.id}.js.tmp")
            val targetFile = File(pluginsDir, "${entry.id}.js")
            try {
                httpClient.newCall(buildRequest(entry.url)).execute().use { res ->
                    if (!res.isSuccessful) throw IOException("HTTP ${res.code} downloading ${entry.id}")
                    val stream = res.body?.byteStream() ?: throw IOException("Empty script response for ${entry.id}")
                    stream.use { input -> tmpFile.outputStream().use { output -> input.copyTo(output) } }
                }
                if (tmpFile.length() == 0L) throw IOException("Downloaded script is empty for ${entry.id}")
                if (!tmpFile.renameTo(targetFile)) throw IOException("Failed to rename ${tmpFile.name}")
                val record = InstalledPlugin(entry.id, entry.name, entry.lang, entry.version, entry.site, entry.iconUrl)
                writeInstalledLocked(readInstalledLocked().filterNot { it.id == entry.id } + record)
                record
            } finally {
                tmpFile.delete()
            }
        }
    }

    suspend fun uninstall(id: String): Unit = withContext(ioDispatcher) {
        validateId(id)
        mutex.withLock {
            File(pluginsDir, "$id.js").delete()
            File(pluginsDir, "$id.js.tmp").delete()
            if (manifestFile.exists()) writeInstalledLocked(readInstalledLocked().filterNot { it.id == id })
        }
    }

    suspend fun installedPlugins(): List<InstalledPlugin> = withContext(ioDispatcher) {
        mutex.withLock { readInstalledLocked() }
    }

    private fun readInstalledLocked(): List<InstalledPlugin> {
        if (!manifestFile.exists()) return emptyList()
        val text = manifestFile.readText(Charsets.UTF_8)
        if (text.isBlank()) return emptyList()
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            InstalledPlugin(
                id = obj.getString("id"), name = obj.getString("name"),
                language = obj.getString("language"), version = obj.getString("version"),
                site = obj.optString("site", ""),
                iconUrl = obj.optString("iconUrl", "").takeIf { it.isNotEmpty() && it != "null" }
            )
        }
    }

    private fun writeInstalledLocked(list: List<InstalledPlugin>) {
        pluginsDir.mkdirs()
        val arr = JSONArray().apply {
            list.forEach { item ->
                put(JSONObject().put("id", item.id).put("name", item.name).put("language", item.language)
                    .put("version", item.version).put("site", item.site)
                    .apply { if (item.iconUrl != null) put("iconUrl", item.iconUrl) })
            }
        }
        val tmpManifest = File(pluginsDir, "$INSTALLED_FILE_NAME.tmp")
        try {
            tmpManifest.writeText(arr.toString(), Charsets.UTF_8)
            if (!tmpManifest.renameTo(manifestFile)) throw IOException("Failed to rename $INSTALLED_FILE_NAME.tmp")
        } finally {
            tmpManifest.delete()
        }
    }

    private fun buildRequest(url: String): Request =
        Request.Builder().url(url.trim()).header("User-Agent", NetworkDefaults.DEFAULT_USER_AGENT).build()

    private fun validateId(id: String) {
        if (!VALID_ID_REGEX.matches(id)) throw IllegalArgumentException("Invalid plugin id: $id")
    }

    private fun validateUrl(url: String) {
        val t = url.trim()
        val ok = t.startsWith("https://", true) || (allowHttp && t.startsWith("http://", true))
        if (!ok) throw IllegalArgumentException("Only HTTPS URLs are allowed: $url")
    }

    companion object {
        private const val INSTALLED_FILE_NAME = "installed_plugins.json"
        private val VALID_ID_REGEX = Regex("""^[A-Za-z0-9][A-Za-z0-9_.-]*$""")
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .followSslRedirects(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }
}
