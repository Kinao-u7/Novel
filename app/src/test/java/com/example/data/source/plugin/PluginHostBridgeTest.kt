package com.example.data.source.plugin

import com.example.data.source.NetworkDefaults
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PluginHostBridgeTest {

    private var server: HttpServer? = null
    private var serverUrl = ""

    @Before
    fun setUpServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            serverUrl = "http://127.0.0.1:${address.port}"
            executor = Executors.newCachedThreadPool()
            start()
        }
    }

    @After
    fun tearDownServer() {
        server?.stop(0)
    }

    private fun createBridge(): PluginBridgeHost {
        val bridge = PluginBridgeHost(httpClient = PluginHttpClientImpl(allowLoopback = true))
        val polyfillsJs = File("src/main/assets/js/polyfills.js").let { if (it.exists()) it else File("app/src/main/assets/js/polyfills.js") }.readText()
        val libsJs = File("src/main/assets/js/libs.js").let { if (it.exists()) it else File("app/src/main/assets/js/libs.js") }.readText()
        val harnessJs = File("src/main/assets/js/harness.js").let { if (it.exists()) it else File("app/src/main/assets/js/harness.js") }.readText()
        runBlocking(bridge.jsDispatcher) { bridge.initHarness(polyfillsJs, libsJs, harnessJs) }
        return bridge
    }


    // 1. every require() module resolves
    @Test(timeout = 20_000)
    fun testEveryRequireModuleResolves() = runBlocking(Dispatchers.Default) {
        createBridge().use { b ->
            val modules = listOf(
                "cheerio", "htmlparser2", "dayjs", "whatwg-url",
                "@libs/fetch", "@libs/novelStatus", "@libs/filterInputs",
                "@libs/defaultCover", "@libs/isAbsoluteUrl", "@libs/storage",
                "@libs/aes", "@/types/constants"
            )
            for (m in modules) {
                assertTrue("Module '$m' must resolve", b.quickJs.evaluate<Boolean>("typeof require('$m') !== 'undefined'"))
            }
        }
    }

    // 2. browser globals exist
    @Test(timeout = 20_000)
    fun testBrowserGlobalsExist() = runBlocking(Dispatchers.Default) {
        createBridge().use { b ->
            val globals = listOf("fetch", "Headers", "FormData", "URL", "URLSearchParams", "TextEncoder", "TextDecoder", "atob", "btoa", "setTimeout", "AbortController")
            for (g in globals) {
                assertEquals("typeof $g == 'function'", "function", b.quickJs.evaluate<String>("typeof globalThis.$g"))
            }
        }
    }

    // 3. fetchApi returns text/json/status/url/headers (MockWebServer)
    @Test(timeout = 20_000)
    fun testFetchApiReturnsTextJsonStatusUrlHeaders() = runBlocking(Dispatchers.Default) {
        server!!.createContext("/api/test") { ex ->
            ex.responseHeaders.add("X-Custom", "TestVal")
            val body = """{"msg":"ok"}"""
            ex.sendResponseHeaders(200, body.length.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        createBridge().use { b ->
            val script = "(async () => { const r = await fetchApi('$serverUrl/api/test'); return JSON.stringify({ status: r.status, ok: r.ok, url: r.url, header: r.headers.get('x-custom'), text: await r.text(), msg: (await r.json()).msg }); })()"
            val json = JSONObject(b.quickJs.evaluate<String>(script))
            assertEquals(200, json.getInt("status"))
            assertTrue(json.getBoolean("ok"))
            assertEquals("$serverUrl/api/test", json.getString("url"))
            assertEquals("TestVal", json.getString("header"))
            assertEquals("""{"msg":"ok"}""", json.getString("text"))
            assertEquals("ok", json.getString("msg"))
        }
    }

    // 4. fetchText returns "" on 404
    @Test(timeout = 20_000)
    fun testFetchTextReturnsEmptyOn404() = runBlocking(Dispatchers.Default) {
        server!!.createContext("/not-found") { it.sendResponseHeaders(404, -1) }
        createBridge().use { b ->
            assertEquals("", b.quickJs.evaluate<String>("await fetchText('$serverUrl/not-found')"))
        }
    }

    // 5. header stripping + default UA
    @Test(timeout = 20_000)
    fun testHeaderStrippingAndDefaultUserAgent() = runBlocking(Dispatchers.Default) {
        var receivedUa = ""
        var strippedFound = false
        server!!.createContext("/headers") { ex ->
            receivedUa = ex.requestHeaders.getFirst("User-Agent") ?: ""
            strippedFound = ex.requestHeaders.containsKey("Accept-Encoding") || ex.requestHeaders.containsKey("Connection")
            ex.sendResponseHeaders(200, -1)
        }
        createBridge().use { b ->
            b.quickJs.evaluate<Any?>("await fetchApi('$serverUrl/headers', { headers: { 'Accept-Encoding': 'gzip', 'Connection': 'close' } })")
            assertEquals(NetworkDefaults.DEFAULT_USER_AGENT, receivedUa)
            assertFalse("Forbidden headers must be stripped", strippedFound)
        }
    }

    // 6. FormData urlencoded body
    @Test(timeout = 20_000)
    fun testFormDataUrlencodedBody() = runBlocking(Dispatchers.Default) {
        var receivedBody = ""
        var contentType = ""
        server!!.createContext("/form") { ex ->
            contentType = ex.requestHeaders.getFirst("Content-Type") ?: ""
            receivedBody = ex.requestBody.bufferedReader().readText()
            ex.sendResponseHeaders(200, -1)
        }
        createBridge().use { b ->
            val script = "(async () => { const fd = new FormData(); fd.append('title', 'A & B'); fd.set('page', '2'); await fetchApi('$serverUrl/form', { method: 'POST', body: fd }); })()"
            b.quickJs.evaluate<Any?>(script)
            assertTrue(contentType.contains("application/x-www-form-urlencoded"))
            assertEquals("title=A%20%26%20B&page=2", receivedBody)
        }
    }

    // 7. redirect follow, res.url
    @Test(timeout = 20_000)
    fun testRedirectFollowAndResUrl() = runBlocking(Dispatchers.Default) {
        server!!.createContext("/redirect") { ex ->
            ex.responseHeaders.add("Location", "$serverUrl/target")
            ex.sendResponseHeaders(302, -1)
        }
        server!!.createContext("/target") { ex ->
            val body = "ok"
            ex.sendResponseHeaders(200, body.length.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        createBridge().use { b ->
            assertEquals("$serverUrl/target", b.quickJs.evaluate<String>("(async () => (await fetchApi('$serverUrl/redirect')).url)()"))
        }
    }

    // 8. setTimeout fires; clearTimeout cancels
    @Test(timeout = 20_000)
    fun testSetTimeoutFiresAndClearTimeoutCancels() = runBlocking(Dispatchers.Default) {
        createBridge().use { b ->
            val script = "(async () => { let f1 = false, f2 = false; setTimeout(() => { f1 = true; }, 40); const id = setTimeout(() => { f2 = true; }, 40); clearTimeout(id); await new Promise(r => setTimeout(r, 120)); return JSON.stringify({ f1, f2 }); })()"
            val json = JSONObject(b.quickJs.evaluate<String>(script))
            assertTrue(json.getBoolean("f1"))
            assertFalse(json.getBoolean("f2"))
        }
    }

    // 9. storage get/set/getAllKeys
    @Test(timeout = 20_000)
    fun testStorageGetSetGetAllKeys() = runBlocking(Dispatchers.Default) {
        createBridge().use { b ->
            val script = "(() => { const s = require('@libs/storage').storage; s.clearAll(); s.set('k1', 'v1'); s.set('k2', 42); const v1 = s.get('k1'); const len = s.getAllKeys().length; s.delete('k1'); return JSON.stringify({ v1, len, hasK1: s.get('k1') !== undefined }); })()"
            val json = JSONObject(b.quickJs.evaluate<String>(script))
            assertEquals("v1", json.getString("v1"))
            assertEquals(2, json.getInt("len"))
            assertFalse(json.getBoolean("hasK1"))
        }
    }

    // 10. unknown require -> err.name == "UnsupportedPluginFeature"
    @Test(timeout = 20_000)
    fun testUnknownRequireThrowsUnsupportedPluginFeature() = runBlocking(Dispatchers.Default) {
        createBridge().use { b ->
            val errName = b.quickJs.evaluate<String>("(() => { try { require('unknown-lib'); return 'none'; } catch (e) { return e.name; } })()")
            assertEquals("UnsupportedPluginFeature", errName)
        }
    }

    // 11. AES-GCM encrypt/decrypt round trip
    @Test(timeout = 20_000)
    fun testAesGcmEncryptDecryptRoundTrip() = runBlocking(Dispatchers.Default) {
        createBridge().use { b ->
            val script = "(() => { const { gcm } = require('@libs/aes'); const key = new Uint8Array(32), nonce = new Uint8Array(12); for (let i = 0; i < 32; i++) key[i] = i; for (let i = 0; i < 12; i++) nonce[i] = i; const c = gcm(key, nonce); const plain = new TextEncoder().encode('Hello AES!'); return new TextDecoder().decode(c.decrypt(c.encrypt(plain))); })()"
            assertEquals("Hello AES!", b.quickJs.evaluate<String>(script))
        }
    }

    // 12. ArNovel_madara.js: load, read id/name/site, call popularNovels(1, {})
    @Test(timeout = 20_000)
    fun testArNovelPopularNovels() = runBlocking(Dispatchers.Default) {
        val startInit = System.currentTimeMillis()
        val html = """<div class="page-item-detail"><div class="post-title"><a href="$serverUrl/novel/shadow-slave">Shadow Slave</a></div><img src="$serverUrl/cover.jpg" /></div>"""
        server!!.createContext("/page/1/") { ex ->
            ex.sendResponseHeaders(200, html.length.toLong())
            ex.responseBody.use { it.write(html.toByteArray()) }
        }

        createBridge().use { b ->
            val arNovelFile = File("src/test/resources/fixtures/plugins/ArNovel_madara.js").let { if (it.exists()) it else File("app/src/test/resources/fixtures/plugins/ArNovel_madara.js") }
            val pluginMeta = b.loadPlugin("arnovel", arNovelFile.readText())
            val duration = System.currentTimeMillis() - startInit
            println("Plugin initialization timing: ${duration}ms")

            assertEquals("arnovel", pluginMeta.getString("id"))
            assertEquals("ArNovel", pluginMeta.getString("name"))
            assertTrue(pluginMeta.getString("site").isNotEmpty())

            b.quickJs.evaluate<Any?>("__loadedPlugins.get('arnovel').site = '$serverUrl/'")
            val novelsJson = b.callPlugin("arnovel", "popularNovels", listOf(1, JSONObject()))
            val novels = JSONArray(novelsJson)
            assertTrue("popularNovels must return novels", novels.length() > 0)
            assertEquals("Shadow Slave", novels.getJSONObject(0).getString("name"))
        }
    }

    // 13. Awaited timer and fetchApi + timer
    @Test(timeout = 2_000)
    fun testAwaitedTimerAndFetchApiWithTimer() = runBlocking(Dispatchers.Default) {
        server!!.createContext("/timer-fetch") { ex ->
            val body = "data"
            ex.sendResponseHeaders(200, body.length.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        createBridge().use { b ->
            val res1 = b.quickJs.evaluate<String>("await new Promise(r => setTimeout(() => r('ok'), 50))")
            assertEquals("ok", res1)

            val script2 = "(async () => { const r = await fetchApi('$serverUrl/timer-fetch'); const text = await r.text(); return await new Promise(res => setTimeout(() => res(text + '-ok'), 50)); })()"
            val res2 = b.quickJs.evaluate<String>(script2)
            assertEquals("data-ok", res2)
        }
    }
}

