package com.example.data.source.plugin

import com.example.data.model.Novel
import com.example.data.source.NovelSource
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LnPluginSourceTest {

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

    private fun loadArNovelScript(): String {
        val file = File("src/test/resources/fixtures/plugins/ArNovel_madara.js").let {
            if (it.exists()) it else File("app/src/test/resources/fixtures/plugins/ArNovel_madara.js")
        }
        return file.readText()
    }

    @Test(timeout = 20_000)
    fun testPopularNovelsThroughNovelSourceInterface() = runBlocking(Dispatchers.Default) {
        val html = """
            <div class="page-item-detail"><div class="post-title"><a href="$serverUrl/novel/shadow-slave">Shadow Slave</a></div><img src="$serverUrl/c1.jpg" /></div>
            <div class="page-item-detail"><div class="post-title"><a href="$serverUrl/novel/lord-of-mysteries">Lord of Mysteries</a></div><img src="$serverUrl/c2.jpg" /></div>
            <div class="page-item-detail"><div class="post-title"><a href="$serverUrl/novel/reverend-insanity">Reverend Insanity</a></div><img src="$serverUrl/c3.jpg" /></div>
        """.trimIndent()

        server!!.createContext("/page/1/") { ex ->
            ex.sendResponseHeaders(200, html.length.toLong())
            ex.responseBody.use { it.write(html.toByteArray()) }
        }

        val originalScript = loadArNovelScript()
        // Route site URL to mock server using script rewrite in test code
        val rewrittenScript = originalScript.replace(
            "this.site=\"https://arnovel.me\"",
            "this.site=\"$serverUrl\""
        ).replace(
            "sourceSite:\"https://arnovel.me\"",
            "sourceSite:\"$serverUrl\""
        )

        val source: NovelSource = LnPluginSource(
            pluginScript = rewrittenScript,
            pluginLanguage = "ar",
            allowLoopback = true
        )

        assertEquals("ln_arnovel", source.id)
        assertEquals("ArNovel", source.name)
        assertEquals("AR", source.language)
        assertFalse(source.isLocal)

        val novels: List<Novel> = source.getPopularNovels(1)
        assertTrue("popularNovels must return at least 3 novels", novels.size >= 3)
        assertEquals("Shadow Slave", novels[0].title)
        assertEquals("Lord of Mysteries", novels[1].title)
        assertEquals("Reverend Insanity", novels[2].title)

        for (i in 0 until minOf(3, novels.size)) {
            println("[LN-SOURCE-TITLES] Novel $i: ${novels[i].title}")
        }

        for (novel in novels) {
            assertTrue("Novel id must only contain [A-Za-z0-9_.-]", novel.id.matches(Regex("^[A-Za-z0-9_.-]+$")))
            assertEquals("ln_arnovel", novel.sourceId)
            assertEquals("ArNovel", novel.sourceName)
            assertEquals(null, novel.sourceUrl)
        }

        (source as? AutoCloseable)?.close()
    }

    @Test(timeout = 20_000)
    fun testDefaultFiltersPassedToPlugin() = runBlocking(Dispatchers.Default) {
        val filterScript = """
            function FilterPlugin() {
                this.id = "filter_test";
                this.name = "Filter Test";
                this.filters = {
                    sort: { type: "Picker", label: "Sort", value: "latest" }
                };
                this.popularNovels = function(page, options) {
                    if (!options || !options.filters || !options.filters.sort || options.filters.sort.value !== "latest") {
                        throw new Error("Missing or invalid filters.sort.value: " + JSON.stringify(options));
                    }
                    return [{ name: "Filtered Novel", path: "filtered-1" }];
                };
            }
            exports.default = new FilterPlugin();
        """.trimIndent()

        val source = LnPluginSource(pluginScript = filterScript)
        val novels = source.getPopularNovels(1)
        assertEquals(1, novels.size)
        assertEquals("Filtered Novel", novels[0].title)
        source.close()
    }

    @Test
    fun testIdRoundTripWithDotsUnderscoresDashesAndSanitization() {
        val adapter = LnPluginSource(
            pluginScript = "this.id = 'novelki.pl_v2-beta';",
            pluginLanguage = "pl"
        )
        assertEquals("ln_novelki.pl_v2-beta", adapter.id)
        assertEquals("novelki.pl_v2-beta", adapter.pluginId)

        val paths = listOf("novel/test_1.2-alpha", "/path/to/item?x=1&y=2", "simple")
        for (path in paths) {
            val encodedId = adapter.encodeNovelId(path)
            assertTrue("ID must match safe pattern: $encodedId", encodedId.matches(Regex("^[A-Za-z0-9_.-]+$")))
            assertEquals(path, adapter.decodeNovelPath(encodedId))
        }

        // Test unsanitized raw id with invalid characters gets sanitized
        val unsanitizedAdapter = LnPluginSource(
            pluginScript = "this.id = 'bad@id/name#1';",
            pluginLanguage = "en"
        )
        assertEquals("ln_bad_id_name_1", unsanitizedAdapter.id)
    }

    @Test(timeout = 20_000)
    fun testOverLongPathSkippedInListAndComputedLimit() = runBlocking(Dispatchers.Default) {
        val scriptWithOverlong = """
            function OverlongPlugin() {
                this.id = "overlong_test";
                this.name = "Overlong Test";
                this.popularNovels = function() {
                    return [
                        { name: "Safe Novel", path: "normal-path" },
                        { name: "Overlong Novel", path: "${"x".repeat(300)}" }
                    ];
                };
            }
            exports.default = new OverlongPlugin();
        """.trimIndent()

        val adapter = LnPluginSource(pluginScript = scriptWithOverlong)
        val novels = adapter.getPopularNovels(1)
        assertEquals(1, novels.size)
        assertEquals("Safe Novel", novels[0].title)
        assertEquals("normal-path", novels[0].rawId)
        adapter.close()
    }

    @Test(timeout = 20_000)
    fun testPluginExceptionPreservesCauseAndFormat() = runBlocking(Dispatchers.Default) {
        val failingScript = """
            function FailingPlugin() {
                this.id = "failing_plug";
                this.name = "Failing Plugin";
                this.popularNovels = function() {
                    throw new Error("Simulated crash");
                };
            }
            exports.default = new FailingPlugin();
        """.trimIndent()

        val adapter = LnPluginSource(pluginScript = failingScript)
        try {
            adapter.getPopularNovels(1)
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue("Message must contain plugin id and method: ${e.message}",
                e.message?.contains("failing_plug") == true && e.message?.contains("popularNovels") == true)
            assertNotNull("Original cause must be preserved", e.cause)
        } finally {
            adapter.close()
        }
    }

    @Test
    fun testCancellationExceptionIsRethrownNotWrapped() = runBlocking(Dispatchers.Default) {
        val adapter = LnPluginSource(pluginScript = "this.id = 'cancel_plug';")
        try {
            kotlinx.coroutines.coroutineScope {
                kotlinx.coroutines.cancel(CancellationException("Test cancellation"))
                adapter.getPopularNovels(1)
            }
            fail("Expected CancellationException")
        } catch (e: Throwable) {
            assertTrue("Must rethrow CancellationException directly", e is CancellationException)
            assertEquals("Test cancellation", e.message)
        } finally {
            adapter.close()
        }
    }
}
