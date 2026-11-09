package com.example.data.source.plugin

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalEncodingApi::class)
class PluginBridgeHost(
    private val httpClient: PluginHttpClient = PluginHttpClientImpl(),
    val byteStore: ResponseByteStore = ResponseByteStore(),
    private val jsExecutor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "quickjs-runtime") }
) : AutoCloseable {

    val jsDispatcher: CoroutineDispatcher = jsExecutor.asCoroutineDispatcher()
    val scope = CoroutineScope(jsDispatcher + Job())
    val quickJs: QuickJs = QuickJs.create(jobDispatcher = jsDispatcher)

    private val metaStore = ConcurrentHashMap<Long, PluginHttpResponse>()
    private val activeTimers = ConcurrentHashMap<Int, Job>()
    private val timerCount = AtomicInteger(0)

    val logs = mutableListOf<String>()

    companion object {
        const val MAX_PENDING_TIMERS = 50
        const val MAX_DELAY_MS = 60_000L // 60 seconds max delay
        const val MAX_DECODE_BYTES = 10 * 1024 * 1024L // 10 MB cap for __host_decodeText
        const val MAX_LOGS = 200
    }

    init {
        registerBridge()
    }

    private fun registerBridge() {
        // 1. __host_fetch(requestId, requestJson) -> responseId
        quickJs.asyncFunction("__host_fetch") { args: Array<Any?> ->
            val requestId = (args[0] as Number).toInt()
            val jsonStr = args[1] as String
            val json = JSONObject(jsonStr)

            val url = json.getString("url")
            val method = json.optString("method", "GET")
            val redirect = json.optString("redirect", "follow")
            val timeoutMs = json.optLong("timeoutMs", 30_000L)
            val bodyText = if (json.has("bodyText") && !json.isNull("bodyText")) json.getString("bodyText") else null
            val bodyBase64 = if (json.has("bodyBase64") && !json.isNull("bodyBase64")) json.getString("bodyBase64") else null

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
                bodyBase64 = bodyBase64,
                redirect = redirect,
                timeoutMs = timeoutMs
            )

            val response = httpClient.execute(requestId, request)
            val responseId = byteStore.store(response.bodyBytes)
            metaStore[responseId] = response
            responseId
        }

        // 2. __host_abort(requestId)
        quickJs.function("__host_abort") { args: Array<Any?> ->
            val requestId = (args[0] as Number).toInt()
            httpClient.abort(requestId)
        }

        // 3. __host_response_meta(responseId) -> JSON metadata
        quickJs.function("__host_response_meta") { args: Array<Any?> ->
            val responseId = (args[0] as Number).toLong()
            val meta = metaStore[responseId] ?: throw NoSuchElementException("No response metadata for id=$responseId")

            val json = JSONObject()
            json.put("status", meta.status)
            json.put("ok", meta.ok)
            json.put("statusText", meta.statusText)
            json.put("url", meta.url)

            val headersArr = JSONArray()
            for ((k, v) in meta.headers) {
                val pair = JSONArray().apply {
                    put(k)
                    put(v)
                }
                headersArr.put(pair)
            }
            json.put("headers", headersArr)
            json.toString()
        }

        // 4. __host_response_text(responseId, encoding?) -> decoded string up to 10 MB (synchronous)
        quickJs.function("__host_response_text") { args: Array<Any?> ->
            val responseId = (args[0] as Number).toLong()
            val overrideEncoding = if (args.size > 1 && args[1] is String) args[1] as String else null

            val meta = metaStore[responseId]
            val bytes = byteStore.remove(responseId) ?: throw NoSuchElementException("No stored bytes for id=$responseId")

            val charset = when {
                !overrideEncoding.isNullOrEmpty() -> safeCharset(overrideEncoding)
                meta != null -> extractCharsetFromContentType(meta.headers)
                else -> Charsets.UTF_8
            }

            val decoded = String(bytes, charset)
            decoded
        }

        // 5. __host_response_bytes(responseId) -> ByteArray (QuickJS maps to Int8Array, synchronous)
        quickJs.function("__host_response_bytes") { args: Array<Any?> ->
            val responseId = (args[0] as Number).toLong()
            val bytes = byteStore.getBinary(responseId)
            byteStore.remove(responseId)
            bytes
        }

        // 6. __host_response_close(responseId)
        quickJs.function("__host_response_close") { args: Array<Any?> ->
            val responseId = (args[0] as Number).toLong()
            byteStore.remove(responseId)
            metaStore.remove(responseId)
        }

        // 7. __host_decodeText(bytesBase64, encoding) -> String (enforces 10 MB cap)
        quickJs.function("__host_decodeText") { args: Array<Any?> ->
            val b64 = args[0] as String
            val encoding = if (args.size > 1 && args[1] is String) args[1] as String else "utf-8"

            val bytes = Base64.decode(b64)
            if (bytes.size > MAX_DECODE_BYTES) {
                throw PayloadTooLargeException("Text decode input (${bytes.size} bytes) exceeds ${MAX_DECODE_BYTES / (1024 * 1024)} MB limit")
            }

            val charset = safeCharset(encoding)
            String(bytes, charset)
        }

        // 8. __host_setTimeout(callbackId, delayMs)
        quickJs.function("__host_setTimeout") { args: Array<Any?> ->
            val callbackId = (args[0] as Number).toInt()
            val rawDelay = (args[1] as Number).toLong()

            if (timerCount.get() >= MAX_PENDING_TIMERS) {
                throw IllegalStateException("Timer limit exceeded: max $MAX_PENDING_TIMERS concurrent pending timers allowed")
            }

            // Clamp delay to MAX_DELAY_MS (60 seconds)
            val delayMs = rawDelay.coerceIn(0L, MAX_DELAY_MS)
            timerCount.incrementAndGet()

            val job = scope.launch(jsDispatcher) {
                try {
                    delay(delayMs)
                    activeTimers.remove(callbackId)
                    timerCount.decrementAndGet()
                    quickJs.evaluate<Any?>("__dispatchTimeout($callbackId)")
                } catch (e: Throwable) {
                    // Cancelled
                }
            }
            activeTimers[callbackId] = job
        }

        // 9. __host_clearTimeout(callbackId)
        quickJs.function("__host_clearTimeout") { args: Array<Any?> ->
            val callbackId = (args[0] as Number).toInt()
            val job = activeTimers.remove(callbackId)
            if (job != null) {
                timerCount.decrementAndGet()
                job.cancel()
            }
        }

        // 10. __host_log(level, message)
        quickJs.function("__host_log") { args: Array<Any?> ->
            val level = args[0] as String
            val msg = args[1] as String
            synchronized(logs) {
                if (logs.size >= MAX_LOGS) {
                    logs.removeAt(0)
                }
                logs.add("[$level] $msg")
            }
        }
    }

    private fun safeCharset(name: String): Charset {
        return try {
            Charset.forName(name)
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }

    private fun extractCharsetFromContentType(headers: List<Pair<String, String>>): Charset {
        val contentType = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second ?: return Charsets.UTF_8
        val match = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(contentType) ?: return Charsets.UTF_8
        val charsetName = match.groupValues[1].trim().trim('"', '\'')
        return safeCharset(charsetName)
    }

    suspend fun <T> executePluginCall(block: suspend () -> T): T {
        try {
            return block()
        } finally {
            byteStore.clear()
            metaStore.clear()
        }
    }

    override fun close() {
        scope.cancel()
        httpClient.abortAll()
        activeTimers.values.forEach { it.cancel() }
        activeTimers.clear()
        byteStore.clear()
        metaStore.clear()
        quickJs.close()
        jsExecutor.shutdown()
    }
}
