package com.example.data.source.plugin

import com.example.data.source.NetworkDefaults
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PayloadTooLargeException(message: String) : RuntimeException("PayloadTooLarge: $message")
class StoreCapacityExceededException(message: String) : RuntimeException("StoreCapacityExceeded: $message")
class PluginAbortException(message: String = "The user aborted a request.") : RuntimeException("AbortError: $message")

data class PluginHttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: List<Pair<String, String>> = emptyList(),
    val bodyText: String? = null,
    val bodyBase64: String? = null,
    val redirect: String = "follow", // "follow", "manual", "error"
    val timeoutMs: Long = 30_000L
)

data class PluginHttpResponse(
    val status: Int,
    val ok: Boolean,
    val statusText: String,
    val url: String,
    val headers: List<Pair<String, String>>,
    val bodyBytes: ByteArray
)

interface PluginHttpClient {
    suspend fun execute(requestId: Int, request: PluginHttpRequest): PluginHttpResponse
    fun abort(requestId: Int)
    fun abortAll()
}

@OptIn(ExperimentalEncodingApi::class)
class PluginHttpClientImpl(
    private val baseClient: OkHttpClient = OkHttpClient(),
    private val allowLoopback: Boolean = false
) : PluginHttpClient {

    val activeCalls = ConcurrentHashMap<Int, Call>()

    companion object {
        const val MAX_BODY_BYTES = 10 * 1024 * 1024L // 10 MB
        const val MAX_PRE_ABORTED_IDS = 500
        private const val STREAM_BUFFER_SIZE = 8192
    }

    val preAbortedIds: MutableSet<Int> = java.util.Collections.synchronizedSet(
        object : LinkedHashSet<Int>() {
            override fun add(element: Int): Boolean {
                if (size >= MAX_PRE_ABORTED_IDS) {
                    val it = iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
                return super.add(element)
            }
        }
    )

    override suspend fun execute(requestId: Int, request: PluginHttpRequest): PluginHttpResponse = withContext(Dispatchers.IO) {
        if (preAbortedIds.remove(requestId)) {
            throw PluginAbortException()
        }

        // 1. Host-side header sanitization (strip Accept-Encoding, Connection, Host)
        val sanitizedHeaders = request.headers.filterNot { (k, _) ->
            k.equals("Accept-Encoding", ignoreCase = true) ||
            k.equals("Connection", ignoreCase = true) ||
            k.equals("Host", ignoreCase = true)
        }.toMutableList()

        // 2. Set default User-Agent from NetworkDefaults if none provided
        val hasUserAgent = sanitizedHeaders.any { (k, _) -> k.equals("User-Agent", ignoreCase = true) }
        if (!hasUserAgent) {
            sanitizedHeaders.add("User-Agent" to NetworkDefaults.DEFAULT_USER_AGENT)
        }

        // 3. Configure client for redirect policy and timeout
        val client = baseClient.newBuilder().apply {
            when (request.redirect.lowercase()) {
                "manual" -> {
                    followRedirects(false)
                    followSslRedirects(false)
                }
                "error" -> {
                    followRedirects(false)
                    followSslRedirects(false)
                }
                else -> {
                    followRedirects(true)
                    followSslRedirects(true)
                }
            }
            connectTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
            readTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
            writeTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
        }.build()

        // 4. Build Request
        val requestBuilder = Request.Builder().url(request.url)
        var contentType: String? = null
        for ((name, value) in sanitizedHeaders) {
            requestBuilder.addHeader(name, value)
            if (name.equals("Content-Type", ignoreCase = true)) {
                contentType = value
            }
        }

        val mediaType = contentType?.toMediaTypeOrNull()
        val requestBody = when {
            request.bodyText != null -> request.bodyText.toRequestBody(mediaType)
            request.bodyBase64 != null -> {
                val bytes = Base64.decode(request.bodyBase64)
                bytes.toRequestBody(mediaType)
            }
            request.method.uppercase() in listOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody(mediaType)
            else -> null
        }

        requestBuilder.method(request.method.uppercase(), requestBody)
        val call = client.newCall(requestBuilder.build())

        // 5. Register in activeCalls until body is FULLY read
        activeCalls[requestId] = call

        try {
            val response = call.execute()

            if (request.redirect.lowercase() == "error" && response.isRedirect) {
                response.close()
                throw IOException("Redirect rejected by policy=error (HTTP ${response.code})")
            }

            val finalUrl = response.request.url.toString()
            val responseHeaders = mutableListOf<Pair<String, String>>()
            for (i in 0 until response.headers.size) {
                responseHeaders.add(response.headers.name(i) to response.headers.value(i))
            }

            // Stream body up to MAX_BODY_BYTES (10 MB), ignoring Content-Length
            val responseBody = response.body
            val bodyBytes = if (responseBody != null) {
                readStreamWithLimit(responseBody.byteStream(), MAX_BODY_BYTES, call)
            } else {
                ByteArray(0)
            }

            PluginHttpResponse(
                status = response.code,
                ok = response.isSuccessful,
                statusText = response.message.ifEmpty { if (response.isSuccessful) "OK" else "Error" },
                url = finalUrl,
                headers = responseHeaders,
                bodyBytes = bodyBytes
            )
        } catch (e: IOException) {
            if (call.isCanceled()) {
                throw PluginAbortException()
            }
            throw e
        } finally {
            // Unregister call ONLY when reading is fully finished or failed
            activeCalls.remove(requestId)
        }
    }

    private fun readStreamWithLimit(stream: InputStream, maxBytes: Long, call: Call): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        var totalRead = 0L

        stream.use { input ->
            while (true) {
                if (call.isCanceled()) {
                    throw PluginAbortException()
                }
                val read = input.read(buffer)
                if (read == -1) break

                totalRead += read
                if (totalRead > maxBytes) {
                    call.cancel()
                    throw PayloadTooLargeException("Response body exceeded ${maxBytes / (1024 * 1024)} MB limit")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    override fun abort(requestId: Int) {
        val call = activeCalls[requestId]
        if (call != null) {
            call.cancel()
        } else {
            preAbortedIds.add(requestId)
        }
    }

    override fun abortAll() {
        activeCalls.values.forEach { it.cancel() }
        activeCalls.clear()
        preAbortedIds.clear()
    }
}
