package com.example.data.source.plugin

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class PluginBridgeHost(
    val httpClient: PluginHttpClient = PluginHttpClientImpl(),
    private val jsExecutor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "quickjs-runtime") }
) : AutoCloseable {

    val jsDispatcher: CoroutineDispatcher = jsExecutor.asCoroutineDispatcher()
    val scope = CoroutineScope(jsDispatcher + Job())
    val quickJs: QuickJs = QuickJs.create(jobDispatcher = jsDispatcher)

    private val activeTimers = ConcurrentHashMap<Int, Job>()

    companion object {
        const val MAX_DELAY_MS = 15_000L // 15 seconds max delay
    }

    init {
        registerBridge()
    }

    private fun registerBridge() {
        // 1. __host_fetch(requestJson) -> JSON String or throws "Name: message"
        quickJs.asyncFunction("__host_fetch") { args: Array<Any?> ->
            val jsonStr = args[0] as String
            try {
                val json = JSONObject(jsonStr)
                val url = json.getString("url")
                val method = json.optString("method", "GET")
                val redirect = json.optString("redirect", "follow")
                val timeoutMs = json.optLong("timeoutMs", 30_000L)
                val bodyText = if (json.has("bodyText") && !json.isNull("bodyText")) json.getString("bodyText") else null
                val encoding = if (json.has("encoding") && !json.isNull("encoding")) json.getString("encoding") else null

                val headersList = mutableListOf<Pair<String, String>>()
                if (json.has("headers")) {
                    val headersArr = json.getJSONArray("headers")
                    for (i in 0 until headersArr.length()) {
                        val pair = headersArr.getJSONArray(i)
                        headersList.add(pair.getString(0) to pair.getString(1))
                    }
                }

                val request = PluginHttpRequest(
                    url = url,
                    method = method,
                    headers = headersList,
                    bodyText = bodyText,
                    redirect = redirect,
                    timeoutMs = timeoutMs
                )

                val response = httpClient.execute(0, request)
                val charset = when {
                    !encoding.isNullOrEmpty() -> safeCharset(encoding)
                    else -> extractCharsetFromContentType(response.headers)
                }
                val decodedBody = String(response.bodyBytes, charset)

                val resultJson = JSONObject().apply {
                    put("status", response.status)
                    put("ok", response.ok)
                    put("statusText", response.statusText)
                    put("url", response.url)
                    val headersArr = JSONArray()
                    for ((k, v) in response.headers) {
                        headersArr.put(JSONArray().apply {
                            put(k)
                            put(v)
                        })
                    }
                    put("headers", headersArr)
                    put("body", decodedBody)
                }
                resultJson.toString()
            } catch (e: Throwable) {
                val errorName = when (e) {
                    is PluginAbortException -> "AbortError"
                    is PayloadTooLargeException -> "PayloadTooLarge"
                    is java.io.IOException -> "TypeError"
                    else -> if (e::class.java.simpleName.isNotEmpty()) e::class.java.simpleName else "Error"
                }
                throw RuntimeException("$errorName: ${e.message ?: ""}")
            }
        }

        // 2. __host_setTimeout(callbackId, delayMs) -> sync host function, NEVER throws
        quickJs.function("__host_setTimeout") { args: Array<Any?> ->
            try {
                val callbackId = (args[0] as Number).toInt()
                val rawDelay = (args[1] as Number).toLong()
                val delayMs = rawDelay.coerceIn(0L, MAX_DELAY_MS)

                val job = scope.launch(jsDispatcher) {
                    try {
                        delay(delayMs)
                        activeTimers.remove(callbackId)
                        quickJs.evaluate<Any?>("__dispatchTimeout($callbackId)")
                    } catch (_: Throwable) {
                        // Cancelled
                    }
                }
                activeTimers[callbackId] = job
                "ok"
            } catch (_: Throwable) {
                null
            }
        }

        // 3. __host_clearTimeout(callbackId) -> sync host function, NEVER throws
        quickJs.function("__host_clearTimeout") { args: Array<Any?> ->
            try {
                val callbackId = (args[0] as Number).toInt()
                val job = activeTimers.remove(callbackId)
                job?.cancel()
                "ok"
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun safeCharset(name: String): Charset {
        return try {
            Charset.forName(name)
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }

    private fun extractCharsetFromContentType(headers: List<Pair<String, String>>): Charset {
        val contentType = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second ?: return Charsets.UTF_8
        val match = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(contentType) ?: return Charsets.UTF_8
        val charsetName = match.groupValues[1].trim().trim('"', '\'')
        return safeCharset(charsetName)
    }

    suspend fun initHarness(polyfillsJs: String, libsJs: String, harnessJs: String) {
        if (polyfillsJs.isNotEmpty()) {
            quickJs.evaluate<Any?>(polyfillsJs)
        }
        quickJs.evaluate<Any?>(libsJs)
        quickJs.evaluate<Any?>(harnessJs)
    }

    suspend fun initHarness(libsJs: String, harnessJs: String) {
        initHarness("", libsJs, harnessJs)
    }

    suspend fun loadPlugin(id: String, sourceCode: String): JSONObject {
        quickJs.function("__getPluginSrc") { sourceCode }
        val metaJson = quickJs.evaluate<String>("JSON.stringify(__loadPlugin('$id', __getPluginSrc()))")
        return JSONObject(metaJson)
    }

    suspend fun callPlugin(id: String, method: String, args: List<Any?> = emptyList()): String {
        val argsJson = JSONArray(args).toString()
        quickJs.function("__getArgsJson") { argsJson }
        return quickJs.evaluate<String>("await __callPlugin('$id', '$method', __getArgsJson())")
    }

    override fun close() {
        scope.cancel()
        httpClient.abortAll()
        activeTimers.values.forEach { it.cancel() }
        activeTimers.clear()
        quickJs.close()
        jsExecutor.shutdown()
    }
}
