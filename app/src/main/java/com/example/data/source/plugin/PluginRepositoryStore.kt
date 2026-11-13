package com.example.data.source.plugin

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.URI
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Repository(val url: String, val enabled: Boolean = true)

sealed interface AddRepositoryResult {
    data class Added(val repository: Repository) : AddRepositoryResult
    data object Duplicate : AddRepositoryResult
    data object NotSupported : AddRepositoryResult
}

class PluginRepositoryStore(
    private val pluginsDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(context: Context) : this(File(context.filesDir, "plugins"))

    private val mutex = Mutex()
    private val file = File(pluginsDir, FILE_NAME)

    suspend fun getAll(): List<Repository> = withContext(ioDispatcher) {
        mutex.withLock { readOrSeedLocked() }
    }

    suspend fun add(url: String): AddRepositoryResult = withContext(ioDispatcher) {
        val normalized = normalizeUrl(url) ?: return@withContext AddRepositoryResult.NotSupported
        if (normalized != OFFICIAL_REPOSITORY_URL) return@withContext AddRepositoryResult.NotSupported
        mutex.withLock {
            val current = readOrSeedLocked()
            if (current.any { it.url == normalized }) return@withLock AddRepositoryResult.Duplicate
            val added = Repository(url = normalized, enabled = true)
            writeLocked(listOf(added))
            AddRepositoryResult.Added(added)
        }
    }

    suspend fun remove(url: String): Unit = withContext(ioDispatcher) {
        val target = normalizeUrl(url) ?: url.trim()
        mutex.withLock {
            val current = readOrSeedLocked()
            val updated = current.filterNot { it.url == target }
            if (updated.size != current.size) writeLocked(updated)
        }
    }

    suspend fun setEnabled(url: String, enabled: Boolean): Unit = withContext(ioDispatcher) {
        val target = normalizeUrl(url) ?: url.trim()
        mutex.withLock {
            val current = readOrSeedLocked()
            val updated = current.map { if (it.url == target) it.copy(enabled = enabled) else it }
            if (updated != current) writeLocked(updated)
        }
    }

    private fun readOrSeedLocked(): List<Repository> {
        val defaultSeed = listOf(Repository(OFFICIAL_REPOSITORY_URL, enabled = true))
        if (!file.exists()) {
            writeLocked(defaultSeed)
            return defaultSeed
        }
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) {
                Log.w(TAG, "$FILE_NAME was blank; falling back to default repository")
                return defaultSeed
            }
            val arr = JSONArray(text)
            (0 until minOf(arr.length(), 1)).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = obj.optString("url", "").trim()
                if (url.isEmpty()) null else Repository(url, obj.optBoolean("enabled", true))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Corrupt or unreadable $FILE_NAME; falling back to default repository", e)
            defaultSeed
        }
    }

    private fun writeLocked(list: List<Repository>) {
        pluginsDir.mkdirs()
        val arr = JSONArray().apply { list.take(1).forEach { put(JSONObject().put("url", it.url).put("enabled", it.enabled)) } }
        val tmp = File(pluginsDir, "$FILE_NAME.tmp")
        try {
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) throw IOException("Failed to rename ${tmp.name}")
        } finally {
            tmp.delete()
        }
    }

    companion object {
        const val OFFICIAL_REPOSITORY_URL =
            "https://raw.githubusercontent.com/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json"
        private const val FILE_NAME = "repositories.json"
        private const val TAG = "PluginRepositoryStore"

        internal fun normalizeUrl(raw: String): String? {
            val noFragment = raw.trim().substringBefore('#')
            if (noFragment.isEmpty()) return null
            val uri = try { URI(noFragment) } catch (_: Exception) { return null }
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (uri.rawUserInfo != null) return null
            val port = if (uri.port != -1) ":${uri.port}" else ""
            val path = (uri.rawPath ?: "").trimEnd('/')
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return "$scheme://$host$port$path$query"
        }
    }
}
