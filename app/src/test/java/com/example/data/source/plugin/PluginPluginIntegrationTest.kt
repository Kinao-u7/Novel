package com.example.data.source.plugin

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PluginPluginIntegrationTest {

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

    // 12. ArNovel_madara.js: load, read id/name/site, call popularNovels(1, {})
    @Test(timeout = 20_000)
    fun testArNovelPopularNovels() = runBlocking(Dispatchers.Default) {
        val startInit = System.currentTimeMillis()
        val html = """<div class="page-item-detail"><div class="post-title"><a href="$serverUrl/novel/shadow-slave">Shadow Slave</a></div><img src="$serverUrl/c1.jpg" /></div><div class="page-item-detail"><div class="post-title"><a href="$serverUrl/novel/lord-of-mysteries">Lord of Mysteries</a></div><img src="$serverUrl/c2.jpg" /></div><div class="page-item-detail"><div class="post-title"><a href="$serverUrl/novel/reverend-insanity">Reverend Insanity</a></div><img src="$serverUrl/c3.jpg" /></div>"""
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
            assertTrue("popularNovels must return at least 3 novels", novels.length() >= 3)
            assertEquals("Shadow Slave", novels.getJSONObject(0).getString("name"))
            assertEquals("Lord of Mysteries", novels.getJSONObject(1).getString("name"))
            assertEquals("Reverend Insanity", novels.getJSONObject(2).getString("name"))
            for (i in 0 until minOf(3, novels.length())) {
                println("[PLUGIN-TITLES] Novel $i: ${novels.getJSONObject(i).getString("name")}")
            }
        }
    }

    // 13. Awaited timer and fetchApi + timer
    @Test(timeout = 10_000)
    fun testAwaitedTimerAndFetchApiWithTimer() = runBlocking(Dispatchers.Default) {
        server!!.createContext("/timer-fetch") { ex ->
            val body = "data"
            ex.sendResponseHeaders(200, body.length.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        createBridge().use { b ->
            val res1 = b.quickJs.evaluate<String>("await new Promise(r => setTimeout(() => r('ok'), 50))")
            assertEquals("ok", res1)

            val script2 = "await (async () => { const r = await fetchApi('$serverUrl/timer-fetch'); const text = await r.text(); return await new Promise(res => setTimeout(() => res(text + '-ok'), 50)); })()"
            val res2 = b.quickJs.evaluate<String>(script2)
            assertEquals("data-ok", res2)
        }
    }
}
