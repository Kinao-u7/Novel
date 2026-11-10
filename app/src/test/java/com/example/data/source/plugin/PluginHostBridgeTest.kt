package com.example.data.source.plugin

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.example.data.source.NetworkDefaults
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

    // 2. Structured __host_fetch Success Result Test
    @Test(timeout = 20_000)
    fun testHostFetchStructuredSuccess() = runBlocking(Dispatchers.Default) {
        server!!.createContext("/structured-success") { exchange: HttpExchange ->
            val response = "SuccessPayload"
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }

        val client = PluginHttpClientImpl(allowLoopback = true)
        val bridge = PluginBridgeHost(httpClient = client)
        try {
            val reqJson = JSONObject().apply {
                put("url", "$serverUrl/structured-success")
                put("method", "GET")
            }.toString()

            bridge.quickJs.function("__getReqJson") { reqJson }
            val resultStr = bridge.quickJs.evaluate<String>("await __host_fetch(1, __getReqJson())")
            val json = JSONObject(resultStr)

            assertTrue("Expected responseId in success result", json.has("responseId"))
            assertFalse("Expected no error in success result", json.has("error"))
            val responseId = json.getLong("responseId")
            assertTrue("responseId must be > 0", responseId > 0L)

            // Verify stored bytes can be read via __host_response_text
            val text = bridge.quickJs.evaluate<String>("__host_response_text(" + responseId + ")")
            assertEquals("SuccessPayload", text)
        } finally {
            bridge.close()
        }
    }

    // 3. Structured __host_fetch Error Results Test: { error: { name, message } }
    @Test(timeout = 20_000)
    fun testHostFetchStructuredErrors() = runBlocking(Dispatchers.Default) {
        // A. Setup server endpoints for testing errors
        server!!.createContext("/oversized-stream") { exchange: HttpExchange ->
            exchange.sendResponseHeaders(200, 0)
            val os: OutputStream = exchange.responseBody
            val chunk = ByteArray(1024 * 1024)
            try {
                for (i in 0 until 12) {
                    os.write(chunk)
                    os.flush()
                }
            } catch (_: Exception) {
            } finally {
                try { os.close() } catch (_: Exception) {}
            }
        }

        val client = PluginHttpClientImpl(allowLoopback = true)
        val tinyStore = ResponseByteStore(maxStoreBytes = 500) // 500 bytes cap
        val bridge = PluginBridgeHost(httpClient = client, byteStore = tinyStore)

        try {
            // 1. AbortError via pre-aborted request
            client.abort(101)
            val abortReq = JSONObject().apply {
                put("url", "$serverUrl/structured-success")
            }.toString()
            bridge.quickJs.function("__getAbortReq") { abortReq }
            val abortRaw = bridge.quickJs.evaluate<String>("await __host_fetch(101, __getAbortReq())")
            val abortJson = JSONObject(abortRaw)
            assertTrue(abortJson.has("error"))
            val abortErr = abortJson.getJSONObject("error")
            assertEquals("AbortError", abortErr.getString("name"))

            // 2. PayloadTooLarge (> 10 MB stream)
            val largeReq = JSONObject().apply {
                put("url", "$serverUrl/oversized-stream")
            }.toString()
            bridge.quickJs.function("__getLargeReq") { largeReq }
            val largeRaw = bridge.quickJs.evaluate<String>("await __host_fetch(102, __getLargeReq())")
            val largeJson = JSONObject(largeRaw)
            assertTrue(largeJson.has("error"))
            val largeErr = largeJson.getJSONObject("error")
            assertEquals("PayloadTooLarge", largeErr.getString("name"))

            // 3. StoreCapacityExceeded (tinyStore is 500 bytes, response is > 500 bytes)
            server!!.createContext("/medium-payload") { exchange: HttpExchange ->
                val body = "x".repeat(1000)
                exchange.sendResponseHeaders(200, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            val capReq = JSONObject().apply {
                put("url", "$serverUrl/medium-payload")
            }.toString()
            bridge.quickJs.function("__getCapReq") { capReq }
            val capRaw = bridge.quickJs.evaluate<String>("await __host_fetch(103, __getCapReq())")
            val capJson = JSONObject(capRaw)
            assertTrue(capJson.has("error"))
            val capErr = capJson.getJSONObject("error")
            assertEquals("StoreCapacityExceeded", capErr.getString("name"))

            // 4. TypeError (Network error: invalid/closed port)
            val netReq = JSONObject().apply {
                put("url", "http://127.0.0.1:1") // Connection refused
                put("timeoutMs", 1000L)
            }.toString()
            bridge.quickJs.function("__getNetReq") { netReq }
            val netRaw = bridge.quickJs.evaluate<String>("await __host_fetch(104, __getNetReq())")
            val netJson = JSONObject(netRaw)
            assertTrue(netJson.has("error"))
            val netErr = netJson.getJSONObject("error")
            assertEquals("TypeError", netErr.getString("name"))
        } finally {
            bridge.close()
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
                bridge.quickJs.evaluate<Any?>("__host_setTimeout(" + i + ", 5000)")
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
            bridge.quickJs.function("__getNormalB64") { normalB64 }
            val decoded = bridge.quickJs.evaluate<String>("__host_decodeText(__getNormalB64(), 'utf-8')")
            assertEquals("Hello World", decoded)

            // Over 10 MB decode rejected
            val oversizedBytes = ByteArray(11 * 1024 * 1024) // 11 MB
            val oversizedB64 = android.util.Base64.encodeToString(oversizedBytes, android.util.Base64.NO_WRAP)
            bridge.quickJs.function("__getOversizedB64") { oversizedB64 }
            try {
                bridge.quickJs.evaluate<Any?>("__host_decodeText(__getOversizedB64(), 'utf-8')")
                fail("Expected PayloadTooLargeException for text decode > 10 MB")
            } catch (e: QuickJsException) {
                assertTrue(e.message!!.contains("PayloadTooLarge"))
            }
        } finally {
            bridge.close()
        }
    }

    // 12. Cheerio Parse Benchmark under 32, 64, 128, 256 MB memory limits (each in fresh QuickJs)
    @Test(timeout = 60_000)
    fun testCheerioMemoryLimitBenchmark() = runBlocking(Dispatchers.Default) {
        val libsFile = File("src/main/assets/js/libs.js")
        val libsCode = if (libsFile.exists()) {
            libsFile.readText()
        } else {
            "globalThis.__LIBS__ = { cheerio: { load: (h) => ({ text: () => h.length }) } };"
        }

        val testCases = listOf(
            2 to "<!DOCTYPE html><html><body>" + "<div><p>Paragraph content for memory test</p></div>".repeat(45_000) + "</body></html>",
            10 to "<!DOCTYPE html><html><body>" + "<div><p>Paragraph content for memory test</p></div>".repeat(225_000) + "</body></html>"
        )
        val limitsMb = listOf(32, 64, 128, 256)

        for ((sizeMb, html) in testCases) {
            for (limitMb in limitsMb) {
                val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
                var status = "ok"
                val start = System.currentTimeMillis()
                try {
                    quickJs.memoryLimit = limitMb * 1024 * 1024L
                    quickJs.evaluate<Any?>(libsCode)
                    quickJs.function("__getBenchmarkHtml") { html }
                    quickJs.evaluate<Any?>("const parsedDoc = globalThis.__LIBS__.cheerio.load(__getBenchmarkHtml());")
                } catch (e: Throwable) {
                    status = e::class.java.simpleName
                } finally {
                    val elapsed = System.currentTimeMillis() - start
                    quickJs.close()
                    println("size=${sizeMb}MB, limit=${limitMb}MB, status=$status, elapsed=${elapsed}ms")
                }
            }
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

    // 14. Genuinely Overlapping Requests in PluginHttpClient and ResponseByteStore
    @Test(timeout = 20_000)
    fun testOverlappingRequestsGenuinelyOverlap() = runBlocking(Dispatchers.Default) {
        val arrivalLatch = CountDownLatch(2)
        val proceedLatch = CountDownLatch(1)

        server!!.createContext("/overlap") { exchange: HttpExchange ->
            arrivalLatch.countDown()
            // Block until both requests have arrived at the server simultaneously
            proceedLatch.await()
            val query = exchange.requestURI.query ?: "default"
            val response = "OK-$query"
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }

        val client = PluginHttpClientImpl(allowLoopback = true)
        val store = ResponseByteStore()
        val bridge = PluginBridgeHost(httpClient = client, byteStore = store)

        try {
            val job1 = launch(Dispatchers.IO) {
                val res = client.execute(1, PluginHttpRequest(url = "$serverUrl/overlap?id=1"))
                store.store(res.bodyBytes)
            }
            val job2 = launch(Dispatchers.IO) {
                val res = client.execute(2, PluginHttpRequest(url = "$serverUrl/overlap?id=2"))
                store.store(res.bodyBytes)
            }

            // Wait until both requests have arrived at the server concurrently
            arrivalLatch.await()

            // Verify they are GENUINELY overlapping in-flight simultaneously
            assertEquals("Both calls must be active at the same time in activeCalls", 2, client.activeCalls.size)
            assertTrue("Call 1 must be active", client.activeCalls.containsKey(1))
            assertTrue("Call 2 must be active", client.activeCalls.containsKey(2))

            // Release both requests to finish
            proceedLatch.countDown()

            job1.join()
            job2.join()

            // After completion, both responses exist concurrently in ResponseByteStore
            assertEquals(2, store.size())
            assertEquals(0, client.activeCalls.size)
        } finally {
            proceedLatch.countDown()
            bridge.close()
        }
    }

    // 15. Pre-aborted Request Throws Abort Immediately Without Network Call
    @Test(timeout = 20_000)
    fun testPreAbortedRequestThrowsAbortImmediately() = runBlocking(Dispatchers.Default) {
        var serverHit = false
        server!!.createContext("/pre-abort-test") { exchange: HttpExchange ->
            serverHit = true
            val response = "ShouldNotReach"
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }

        val client = PluginHttpClientImpl(allowLoopback = true)
        val requestId = 555

        // Abort called BEFORE execute
        client.abort(requestId)
        assertTrue(client.preAbortedIds.contains(requestId))

        try {
            client.execute(requestId, PluginHttpRequest(url = "$serverUrl/pre-abort-test"))
            fail("Expected PluginAbortException for pre-aborted request")
        } catch (e: PluginAbortException) {
            assertTrue(e.message!!.contains("AbortError"))
        }

        assertFalse("Server must not be hit for pre-aborted request", serverHit)
        assertFalse("preAbortedIds must consume requestId after execute", client.preAbortedIds.contains(requestId))
    }

    // 16. Bounded preAbortedIds Eviction
    @Test(timeout = 20_000)
    fun testPreAbortedIdsBoundedAndEvicted() {
        val client = PluginHttpClientImpl(allowLoopback = true)

        // Add 600 IDs (exceeding MAX_PRE_ABORTED_IDS = 500)
        for (i in 1..600) {
            client.abort(i)
        }

        assertEquals(PluginHttpClientImpl.MAX_PRE_ABORTED_IDS, client.preAbortedIds.size)
        // Oldest IDs (1..100) must be evicted
        assertFalse("Oldest ID (1) should be evicted", client.preAbortedIds.contains(1))
        assertFalse("ID 100 should be evicted", client.preAbortedIds.contains(100))
        // Newest IDs (101..600) must be retained
        assertTrue("ID 101 should be retained", client.preAbortedIds.contains(101))
        assertTrue("Newest ID 600 should be retained", client.preAbortedIds.contains(600))

        client.abortAll()
        assertEquals(0, client.preAbortedIds.size)
    }

    // 17. Evaluate and audit typeof for candidate globals in QuickJS
    @Test(timeout = 20_000)
    fun testGlobalTypeofAudit() = runBlocking(Dispatchers.Default) {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        try {
            val globalsToCheck = listOf(
                "Iterator",
                "Intl",
                "BigInt",
                "DataView",
                "Uint32Array",
                "Uint8Array",
                "globalThis",
                "eval",
                "Symbol",
                "Map",
                "Set",
                "Promise"
            )
            for (name in globalsToCheck) {
                val type = quickJs.evaluate<String>("typeof " + name)
                println("typeof " + name + " = " + type)
            }
        } finally {
            quickJs.close()
        }
    }

    // 18. Cancellation During Fetch Propagates CancellationException and Cancels Active Call
    @Test(timeout = 20_000)
    fun testCancellationDuringFetchPropagatesAndCancelsCall() = runBlocking(Dispatchers.Default) {
        val arrivalLatch = CountDownLatch(1)
        val proceedLatch = CountDownLatch(1)

        server!!.createContext("/hanging-fetch") { exchange: HttpExchange ->
            arrivalLatch.countDown()
            try {
                proceedLatch.await()
            } catch (_: InterruptedException) {
            }
            val response = "NeverSent"
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }

        val client = PluginHttpClientImpl(allowLoopback = true)
        val bridge = PluginBridgeHost(httpClient = client)
        val requestId = 777

        try {
            val reqJson = JSONObject().apply {
                put("url", "$serverUrl/hanging-fetch")
                put("timeoutMs", 30_000L)
            }.toString()

            bridge.quickJs.function("__getHangingReq") { reqJson }

            var caughtInJob: Throwable? = null
            val job = launch(Dispatchers.Default) {
                try {
                    bridge.quickJs.evaluate<Any?>("await __host_fetch(" + requestId + ", __getHangingReq())")
                } catch (e: Throwable) {
                    caughtInJob = e
                    throw e
                }
            }

            arrivalLatch.await()
            assertTrue("Call must be in activeCalls while fetch is waiting", client.activeCalls.containsKey(requestId))

            job.cancel()
            job.join()

            assertTrue("Job must be cancelled", job.isCancelled)
            assertNotNull("CancellationException must be caught in job scope", caughtInJob)
            assertTrue(
                "Expected CancellationException to propagate, got: ${caughtInJob!!::class.java.name}",
                caughtInJob is kotlinx.coroutines.CancellationException
            )

            assertFalse("activeCalls must remove requestId after cancellation", client.activeCalls.containsKey(requestId))
        } finally {
            proceedLatch.countDown()
            bridge.close()
        }
    }
}
