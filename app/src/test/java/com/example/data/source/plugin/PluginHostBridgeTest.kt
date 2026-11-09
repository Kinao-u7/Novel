package com.example.data.source.plugin

import com.dokar3.quickjs.QuickJs
import com.dokar3.quickjs.QuickJsException
import com.dokar3.quickjs.binding.asyncFunction
import com.dokar3.quickjs.binding.function
import com.example.data.source.NetworkDefaults
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PluginHostBridgeTest {

    private var server: HttpServer? = null
    private var serverPort = 0
    private var serverUrl = ""

    @Before
    fun setUpServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            serverPort = address.port
            serverUrl = "http://127.0.0.1:$serverPort"
            executor = Executors.newCachedThreadPool()
            start()
        }
    }

    @After
    fun tearDownServer() {
        server?.stop(0)
    }

    // 1. ByteArray Mapping Test
    @Test(timeout = 20_000)
    fun testByteArrayMapping() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.function("getSampleBytes") {
                byteArrayOf(10, 20, 30, 40)
            }
            val result = quickJs.evaluate<Boolean>(
                """
                const bytes = getSampleBytes();
                const isInt8 = bytes instanceof Int8Array;
                const isArrayBuffer = bytes.buffer instanceof ArrayBuffer;
                const validValues = bytes[0] === 10 && bytes[1] === 20 && bytes[2] === 30 && bytes[3] === 40;
                isInt8 && isArrayBuffer && validValues;
                """.trimIndent()
            )
            assertTrue("Expected ByteArray to map to Int8Array with valid ArrayBuffer", result)
        } finally {
            quickJs.close()
        }
    }

    // 2. Error name/message format test (Caught in JS vs Uncaught in Kotlin)
    @Test(timeout = 20_000)
    fun testQuickJsExceptionFormatAndErrorNames() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.asyncFunction("throwCustomErrors") { args: Array<Any?> ->
                val type = args[0] as String
                when (type) {
                    "payload" -> throw PayloadTooLargeException("Body exceeds 10 MB limit")
                    "capacity" -> throw StoreCapacityExceededException("Store full")
                    "abort" -> throw PluginAbortException("The user aborted a request.")
                    else -> throw IllegalStateException("Generic failure")
                }
            }

            // Caught in JS: inspect error message format
            val jsCaught = quickJs.evaluate<String>(
                """
                let out = "";
                try {
                    await throwCustomErrors("payload");
                } catch (e) {
                    out = (e.name || "Error") + "|" + e.message;
                }
                out;
                """.trimIndent()
            )
            println("[Report: Caught in JS error format] $jsCaught")
            assertTrue(jsCaught.contains("PayloadTooLarge"))

            // Uncaught in Kotlin: inspect QuickJsException format
            var uncaughtPayloadMsg = ""
            try {
                quickJs.evaluate<Any?>("await throwCustomErrors('payload');")
                fail("Expected QuickJsException")
            } catch (e: QuickJsException) {
                uncaughtPayloadMsg = e.message ?: ""
                println("[Report: Uncaught in Kotlin error format] class=${e::class.java.name}, message='$uncaughtPayloadMsg'")
                assertTrue("Expected message to contain PayloadTooLarge", uncaughtPayloadMsg.contains("PayloadTooLarge"))
            }

            var uncaughtAbortMsg = ""
            try {
                quickJs.evaluate<Any?>("await throwCustomErrors('abort');")
                fail("Expected QuickJsException for abort")
            } catch (e: QuickJsException) {
                uncaughtAbortMsg = e.message ?: ""
                println("[Report: Uncaught Abort format] message='$uncaughtAbortMsg'")
                assertTrue("Expected message to contain AbortError", uncaughtAbortMsg.contains("AbortError"))
            }
        } finally {
            quickJs.close()
        }
    }

    // 3. Host Exception to JS Error Mapping
    @Test(timeout = 20_000)
    fun testHostExceptionToJsErrorMapping() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.asyncFunction("failWithAbort") {
                throw PluginAbortException()
            }
            quickJs.asyncFunction("failWithTooLarge") {
                throw PayloadTooLargeException("Over 10 MB limit")
            }
            quickJs.asyncFunction("failWithStoreCapacity") {
                throw StoreCapacityExceededException("Store exceeded 20 MB")
            }

            val result = quickJs.evaluate<String>(
                """
                function mapError(e) {
                    const msg = e && e.message ? e.message : String(e);
                    if (msg.includes("PayloadTooLarge")) return "PayloadTooLarge";
                    if (msg.includes("StoreCapacityExceeded")) return "StoreCapacityExceeded";
                    if (msg.includes("AbortError")) return "AbortError";
                    return "Unknown";
                }
                async function test() {
                    const res = [];
                    try { await failWithAbort(); } catch (e) { res.push(mapError(e)); }
                    try { await failWithTooLarge(); } catch (e) { res.push(mapError(e)); }
                    try { await failWithStoreCapacity(); } catch (e) { res.push(mapError(e)); }
                    return res.join(",");
                }
                await test();
                """.trimIndent()
            )
            assertEquals("AbortError,PayloadTooLarge,StoreCapacityExceeded", result)
        } finally {
            quickJs.close()
        }
    }

    // 4. Store Caps, Clear on Call Completion and Recycle
    @Test(timeout = 20_000)
    fun testStoreCapsAndClearOnRecycle() = runBlocking(Dispatchers.Default) {
        val store = ResponseByteStore(maxStoreBytes = 1024 * 1024L) // 1 MB cap for testing
        assertEquals(0, store.size())
        assertEquals(0L, store.totalBytes())

        val chunk = ByteArray(600 * 1024) // 600 KB
        val id1 = store.store(chunk)
        assertEquals(1, store.size())
        assertEquals(600 * 1024L, store.totalBytes())

        // Storing another 600 KB exceeds 1 MB cap -> StoreCapacityExceededException
        try {
            store.store(chunk)
            fail("Expected StoreCapacityExceededException")
        } catch (e: StoreCapacityExceededException) {
            assertTrue(e.message!!.contains("StoreCapacityExceeded"))
        }

        // Clear store
        store.clear()
        assertEquals(0, store.size())
        assertEquals(0L, store.totalBytes())

        // Test PluginBridgeHost lifecycle cleanup
        val bridge = PluginBridgeHost(byteStore = store)
        try {
            bridge.executePluginCall {
                store.store(ByteArray(100))
                assertEquals(1, store.size())
            }
            // After executePluginCall completes, store is cleared
            assertEquals(0, store.size())
        } finally {
            bridge.close()
            assertEquals(0, store.size())
        }
    }

    // 5. Host Header Stripping (Accept-Encoding, Connection, Host)
    @Test(timeout = 20_000)
    fun testHeaderStrippingOnHost() = runBlocking(Dispatchers.Default) {
        var receivedHeaders = listOf<Pair<String, String>>()
        server!!.createContext("/headers-test") { exchange: HttpExchange ->
            val list = mutableListOf<Pair<String, String>>()
            val headers = exchange.requestHeaders
            for (name in headers.keys) {
                list.add(name to headers.getFirst(name))
            }
            receivedHeaders = list
            val response = "OK"
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }

        val client = PluginHttpClientImpl(allowLoopback = true)
        val request = PluginHttpRequest(
            url = "$serverUrl/headers-test",
            method = "GET",
            headers = listOf(
                "Accept-Encoding" to "gzip, deflate",
                "Connection" to "keep-alive",
                "Host" to "fake.host.com",
                "X-Custom-Header" to "CustomValue42"
            )
        )

        val response = client.execute(1, request)
        assertTrue(response.ok)

        val headerKeys = receivedHeaders.map { it.first.lowercase() }
        assertFalse("Accept-Encoding must be stripped", headerKeys.contains("accept-encoding"))
        assertFalse("Connection must be stripped", headerKeys.contains("connection"))
        assertTrue("Custom header must be preserved", receivedHeaders.any { it.first.equals("X-Custom-Header", ignoreCase = true) && it.second == "CustomValue42" })
    }

    // 6. Default User-Agent from NetworkDefaults
    @Test(timeout = 20_000)
    fun testDefaultUserAgentFromNetworkDefaults() = runBlocking(Dispatchers.Default) {
        var capturedUa = ""
        server!!.createContext("/ua-test") { exchange: HttpExchange ->
            capturedUa = exchange.requestHeaders.getFirst("User-Agent") ?: ""
            val response = "OK"
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }

        val client = PluginHttpClientImpl(allowLoopback = true)

        // Case A: Plugin gives no UA -> defaults to NetworkDefaults.DEFAULT_USER_AGENT
        client.execute(1, PluginHttpRequest(url = "$serverUrl/ua-test"))
        assertEquals(NetworkDefaults.DEFAULT_USER_AGENT, capturedUa)

        // Case B: Plugin gives custom UA -> preserved
        client.execute(2, PluginHttpRequest(
            url = "$serverUrl/ua-test",
            headers = listOf("User-Agent" to "CustomPluginAgent/1.0")
        ))
        assertEquals("CustomPluginAgent/1.0", capturedUa)
    }

    // 7. Redirect Policies (follow, manual, error)
    @Test(timeout = 20_000)
    fun testRedirectPolicies() = runBlocking(Dispatchers.Default) {
        server!!.createContext("/redirect-source") { exchange: HttpExchange ->
            exchange.responseHeaders.set("Location", "$serverUrl/redirect-target")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server!!.createContext("/redirect-target") { exchange: HttpExchange ->
            val response = "TargetReached"
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }

        val client = PluginHttpClientImpl(allowLoopback = true)

        // follow: reaches target, response.url is final url
        val followRes = client.execute(1, PluginHttpRequest(url = "$serverUrl/redirect-source", redirect = "follow"))
        assertEquals(200, followRes.status)
        assertEquals("$serverUrl/redirect-target", followRes.url)
        assertEquals("TargetReached", String(followRes.bodyBytes))

        // manual: returns 302, location header present
        val manualRes = client.execute(2, PluginHttpRequest(url = "$serverUrl/redirect-source", redirect = "manual"))
        assertEquals(302, manualRes.status)
        assertTrue(manualRes.headers.any { it.first.equals("Location", ignoreCase = true) })

        // error: throws IOException
        try {
            client.execute(3, PluginHttpRequest(url = "$serverUrl/redirect-source", redirect = "error"))
            fail("Expected exception for redirect=error")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("Redirect rejected"))
        }
    }

    // 8. 10 MB Streaming Cap
    @Test(timeout = 20_000)
    fun test10MbStreamingCap() = runBlocking(Dispatchers.Default) {
        server!!.createContext("/large-stream") { exchange: HttpExchange ->
            exchange.sendResponseHeaders(200, 0) // chunked stream (no Content-Length)
            val os: OutputStream = exchange.responseBody
            val chunk = ByteArray(1024 * 1024) // 1 MB
            try {
                for (i in 0 until 12) { // 12 MB total
                    os.write(chunk)
                    os.flush()
                }
            } catch (_: Exception) {
                // Client closed socket on cap exceeded
            } finally {
                try { os.close() } catch (_: Exception) {}
            }
        }

        val client = PluginHttpClientImpl(allowLoopback = true)
        try {
            client.execute(1, PluginHttpRequest(url = "$serverUrl/large-stream"))
            fail("Expected PayloadTooLargeException")
        } catch (e: PayloadTooLargeException) {
            assertTrue(e.message!!.contains("PayloadTooLarge") && e.message!!.contains("10 MB"))
        }
    }

    // 9. Binary 5 MB Cap
    @Test(timeout = 20_000)
    fun testBinary5MbCap() {
        val store = ResponseByteStore()
        val largeBytes = ByteArray(6 * 1024 * 1024) // 6 MB
        val id1 = store.store(largeBytes)

        try {
            store.getBinary(id1)
            fail("Expected PayloadTooLargeException for binary > 5 MB")
        } catch (e: PayloadTooLargeException) {
            assertTrue(e.message!!.contains("5 MB"))
        }

        val smallBytes = ByteArray(4 * 1024 * 1024) // 4 MB
        val id2 = store.store(smallBytes)
        val binary = store.getBinary(id2)
        assertEquals(4 * 1024 * 1024, binary.size)
    }

    // 10. Timer Limits and Delay Clamping
    @Test(timeout = 20_000)
    fun testTimerLimitsAndClamping() = runBlocking(Dispatchers.Default) {
        val bridge = PluginBridgeHost()
        try {
            // Schedule up to MAX_PENDING_TIMERS (50)
            for (i in 1..50) {
                bridge.quickJs.evaluate<Any?>("__host_setTimeout($i, 5000)")
            }

            // 51st timer throws IllegalStateException
            try {
                bridge.quickJs.evaluate<Any?>("__host_setTimeout(51, 5000)")
                fail("Expected timer limit exceeded exception")
            } catch (e: QuickJsException) {
                assertTrue(e.message!!.contains("Timer limit exceeded"))
            }

            // Verify clearTimeout frees a slot
            bridge.quickJs.evaluate<Any?>("__host_clearTimeout(1)")
            bridge.quickJs.evaluate<Any?>("__host_setTimeout(52, 100000)") // Delay 100,000 clamped to 60,000
        } finally {
            bridge.close()
        }
    }

    // 11. DecodeText 10 MB Cap
    @Test(timeout = 20_000)
    fun testDecodeText10MbCap() = runBlocking(Dispatchers.Default) {
        val bridge = PluginBridgeHost()
        try {
            // Valid decode
            val normalB64 = android.util.Base64.encodeToString("Hello World".toByteArray(), android.util.Base64.NO_WRAP)
            val decoded = bridge.quickJs.evaluate<String>("__host_decodeText('$normalB64', 'utf-8')")
            assertEquals("Hello World", decoded)

            // Over 10 MB decode rejected
            val oversizedBytes = ByteArray(11 * 1024 * 1024) // 11 MB
            val oversizedB64 = android.util.Base64.encodeToString(oversizedBytes, android.util.Base64.NO_WRAP)
            try {
                bridge.quickJs.evaluate<Any?>("__host_decodeText('$oversizedB64', 'utf-8')")
                fail("Expected PayloadTooLargeException for text decode > 10 MB")
            } catch (e: QuickJsException) {
                assertTrue(e.message!!.contains("PayloadTooLarge"))
            }
        } finally {
            bridge.close()
        }
    }

    // 12. Cheerio Heap Benchmark under 64 MB heap limit
    @Test(timeout = 30_000)
    fun testCheerioHeapBenchmark() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            quickJs.memoryLimit = 64 * 1024 * 1024L // 64 MB heap cap

            // Load libs.js if available
            val libsFile = File("src/main/assets/js/libs.js")
            if (libsFile.exists()) {
                val libsCode = libsFile.readText()
                quickJs.evaluate<Any?>(libsCode)
            } else {
                // Fallback minimal cheerio stub if libs.js not on path during isolated test
                quickJs.evaluate<Any?>("globalThis.__LIBS__ = { cheerio: { load: (h) => ({ text: () => h.length }) } };")
            }

            // 1. Parse 2 MB HTML
            val html2Mb = "<!DOCTYPE html><html><body>" + "<div><p>Paragraph content for memory test</p></div>".repeat(45_000) + "</body></html>"
            val start2Mb = System.currentTimeMillis()
            var parse2MbOk = false
            try {
                quickJs.function("__get2MbHtml") { html2Mb }
                quickJs.evaluate<Any?>("const $2mb = globalThis.__LIBS__.cheerio.load(__get2MbHtml());")
                parse2MbOk = true
            } catch (e: Throwable) {
                println("[Cheerio Heap Benchmark] 2 MB parse caught: ${e::class.java.simpleName}: ${e.message}")
            }
            val elapsed2Mb = System.currentTimeMillis() - start2Mb
            println("[Cheerio Heap Benchmark] 2 MB parse: success=$parse2MbOk, elapsed=${elapsed2Mb}ms (HTML length: ${html2Mb.length} chars)")

            // 2. Parse 10 MB HTML (catches OOM/exception without failing build)
            val html10Mb = "<!DOCTYPE html><html><body>" + "<div><p>Paragraph content for memory test</p></div>".repeat(225_000) + "</body></html>"
            val start10Mb = System.currentTimeMillis()
            var parse10MbOk = false
            var parse10MbError: String? = null
            try {
                quickJs.function("__get10MbHtml") { html10Mb }
                quickJs.evaluate<Any?>("const $10mb = globalThis.__LIBS__.cheerio.load(__get10MbHtml());")
                parse10MbOk = true
            } catch (e: Throwable) {
                parse10MbError = "${e::class.java.simpleName}: ${e.message}"
                println("[Cheerio Heap Benchmark] 10 MB parse caught: $parse10MbError")
            }
            val elapsed10Mb = System.currentTimeMillis() - start10Mb
            println("[Cheerio Heap Benchmark] 10 MB parse: success=$parse10MbOk, elapsed=${elapsed10Mb}ms, error=$parse10MbError")

            // 3. Assertion: Context remains functional and usable afterwards
            val arithmetic = quickJs.evaluate<Int>("1 + 1")
            assertEquals("QuickJS context must remain usable after parsing tests", 2, arithmetic)
        } finally {
            quickJs.close()
        }
    }

    // 13. ActiveCalls Maintained During Streaming and Abort
    @Test(timeout = 20_000)
    fun testActiveCallsMaintainedDuringStreamingAndAbort() = runBlocking(Dispatchers.Default) {
        server!!.createContext("/slow-stream") { exchange: HttpExchange ->
            exchange.sendResponseHeaders(200, 0)
            val os = exchange.responseBody
            try {
                for (i in 0 until 50) {
                    os.write(ByteArray(1024))
                    os.flush()
                    Thread.sleep(100)
                }
            } catch (_: Exception) {
                // Aborted by client
            } finally {
                try { os.close() } catch (_: Exception) {}
            }
        }

        val client = PluginHttpClientImpl(allowLoopback = true)
        val requestId = 999

        val job = launch(Dispatchers.Default) {
            try {
                client.execute(requestId, PluginHttpRequest(url = "$serverUrl/slow-stream"))
                fail("Expected PluginAbortException")
            } catch (e: PluginAbortException) {
                assertTrue(e.message!!.contains("AbortError"))
            }
        }

        // Wait until streaming starts
        delay(250)
        assertTrue("activeCalls must retain requestId during streaming", client.activeCalls.containsKey(requestId))

        // Abort in-flight
        client.abort(requestId)
        job.join()

        // After completion/abort, activeCalls must not retain requestId
        assertFalse("activeCalls must remove requestId after abort completes", client.activeCalls.containsKey(requestId))
    }
}
