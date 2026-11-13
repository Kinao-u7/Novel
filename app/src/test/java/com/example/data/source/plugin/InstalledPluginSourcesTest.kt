package com.example.data.source.plugin

import com.example.data.source.SourceManager
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InstalledPluginSourcesTest {

    @get:Rule val tempFolder = TemporaryFolder()
    private lateinit var pluginsDir: File
    private lateinit var manager: PluginInstallationManager
    private var server: HttpServer? = null
    private var serverUrl = ""

    @Before fun setUp() {
        pluginsDir = tempFolder.newFolder("plugins")
        manager = PluginInstallationManager(pluginsDir, allowHttp = true)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            serverUrl = "http://127.0.0.1:${address.port}"
            executor = Executors.newCachedThreadPool()
            start()
        }
    }

    @After fun tearDown() {
        SourceManager.sources.value.map { it.id }.filter { it.startsWith(LnPluginSource.PREFIX) }
            .forEach { SourceManager.unregisterSource(it) }
        SourceManager.setPluginsReadySignal(CompletableDeferred(Unit))
        server?.stop(0)
    }

    private fun serve(path: String, status: Int = 200, body: String = "") {
        server!!.createContext(path) { ex ->
            if (status != 200 || body.isEmpty()) ex.sendResponseHeaders(status, -1)
            else {
                val bytes = body.toByteArray(Charsets.UTF_8)
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
    }

    private fun fakeScript(id: String, title: String) =
        "function P(){this.id='$id';this.name='$id';this.popularNovels=function(){return[{name:'$title',path:'/n1'}];};}exports.default=new P();"

    private fun writeManifest(vararg entries: InstalledPlugin) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("name", e.name).put("language", e.language)
                .put("version", e.version).put("site", e.site))
        }
        File(pluginsDir, "installed_plugins.json").writeText(arr.toString(), Charsets.UTF_8)
    }

    private fun entry(id: String, version: String, url: String) = PluginCatalogueEntry(
        id = id, name = "Plugin $id", site = "https://example.com",
        lang = "EN", originalLang = "English", version = version, url = url, iconUrl = null
    )

    @Test fun loadAllRegistersValidSkipsInvalidAndMissingFileThrowsWithId() = runBlocking<Unit> {
        writeManifest(
            InstalledPlugin("../bad", "Bad", "EN", "1.0.0", "https://example.com"),
            InstalledPlugin("missing_js", "Missing", "AR", "1.2.0", "https://example.com"),
            InstalledPlugin("valid_js", "Valid Plug", "FR", "2.0.1", "https://example.com")
        )
        File(pluginsDir, "valid_js.js").writeText(fakeScript("valid_js", "Bonjour"), Charsets.UTF_8)

        val sources = InstalledPluginSources(manager)
        sources.loadAll()

        val valid = SourceManager.getSourceById("ln_valid_js")
        assertNotNull(valid)
        assertEquals("Valid Plug", valid!!.name)
        assertEquals("FR", valid.language)
        assertEquals("2.0.1", valid.version)
        assertEquals("Bonjour", valid.getPopularNovels(1).single().title)

        val missing = SourceManager.getSourceById("ln_missing_js")
        assertNotNull(missing)
        try {
            missing!!.getPopularNovels(1)
            fail("Expected IOException for missing script file")
        } catch (e: IOException) {
            assertTrue("Message should mention plugin id: ${e.message}", e.message?.contains("missing_js") == true)
        }
    }

    @Test fun lazyLoadingCallsProviderZeroTimesAtLoadAndOnceOnFirstUse() = runBlocking<Unit> {
        val entries = (0 until 50).map { i ->
            InstalledPlugin("p$i", "Plug $i", "EN", "1.0.0", "https://example.com")
        }
        writeManifest(*entries.toTypedArray())

        val reads = ConcurrentHashMap<String, AtomicInteger>()
        val sources = InstalledPluginSources(manager) { entry ->
            {
                reads.getOrPut(entry.id) { AtomicInteger(0) }.incrementAndGet()
                fakeScript(entry.id, "Novel ${entry.id}")
            }
        }
        sources.loadAll()

        assertEquals(0, reads.values.sumOf { it.get() })
        assertNotNull(SourceManager.getSourceById("ln_p0"))
        assertNotNull(SourceManager.getSourceById("ln_p49"))

        val s0 = SourceManager.getSourceById("ln_p0")!!
        assertEquals("Novel p0", s0.getPopularNovels(1).single().title)
        assertEquals("Novel p0", s0.getPopularNovels(2).single().title)
        assertEquals(1, reads["p0"]?.get())
        assertEquals(1, reads.values.sumOf { it.get() })
    }

    @Test fun readinessSignalCompletesEvenWhenManifestIsCorrupt() = runBlocking<Unit> {
        File(pluginsDir, "installed_plugins.json").writeText("{corrupt", Charsets.UTF_8)
        val sources = InstalledPluginSources(manager)
        sources.loadAll()
        sources.awaitLoaded()
        assertTrue(sources.readySignal.isCompleted)
    }

    @Test fun updateSwapsAndClosesOldSourceAndFailedUpdateKeepsOld() = runBlocking<Unit> {
        serve("/swap_v1.js", body = fakeScript("swap_p", "V1 Novel"))
        serve("/swap_err.js", status = 500)
        serve("/swap_v2.js", body = fakeScript("swap_p", "V2 Novel"))

        val sources = InstalledPluginSources(manager)
        sources.install(entry("swap_p", "1.0.0", "$serverUrl/swap_v1.js"))
        val oldSource = SourceManager.getSourceById("ln_swap_p") as LnPluginSource
        assertEquals("V1 Novel", oldSource.getPopularNovels(1).single().title)
        assertNotNull(oldSource.getBridgeHost())

        try {
            sources.update(entry("swap_p", "2.0.0", "$serverUrl/swap_err.js"))
            fail("Expected HTTP 500 update failure")
        } catch (_: IOException) {}
        assertEquals("V1 Novel", SourceManager.getSourceById("ln_swap_p")!!.getPopularNovels(1).single().title)

        sources.update(entry("swap_p", "2.0.0", "$serverUrl/swap_v2.js"))
        assertNull(oldSource.getBridgeHost())
        val newSource = SourceManager.getSourceById("ln_swap_p")!!
        assertEquals("2.0.0", newSource.version)
        assertEquals("V2 Novel", newSource.getPopularNovels(1).single().title)
    }

    @Test fun uninstallUnregistersClosesAndDeletesScriptFile() = runBlocking<Unit> {
        serve("/del.js", body = fakeScript("del_p", "Delete Me"))
        val sources = InstalledPluginSources(manager)
        sources.install(entry("del_p", "1.0.0", "$serverUrl/del.js"))
        val src = SourceManager.getSourceById("ln_del_p") as LnPluginSource
        src.getPopularNovels(1)
        assertNotNull(src.getBridgeHost())

        sources.uninstall("del_p")
        assertNull(SourceManager.getSourceById("ln_del_p"))
        assertNull(src.getBridgeHost())
        assertFalse(File(pluginsDir, "del_p.js").exists())
    }

    @Test fun awaitSourceByIdWaitsForDelayedLoadAndReturnsNullOnMissing() = runBlocking<Unit> {
        writeManifest(InstalledPlugin("delayed_p", "Delayed", "EN", "1.0.0", "https://example.com"))
        File(pluginsDir, "delayed_p.js").writeText(fakeScript("delayed_p", "Ready"), Charsets.UTF_8)

        val sources = InstalledPluginSources(manager)
        SourceManager.setPluginsReadySignal(sources.readySignal)
        val job = launch(Dispatchers.IO) {
            delay(100)
            sources.loadAll()
        }
        val found = SourceManager.awaitSourceById("ln_delayed_p", timeoutMs = 2_000L)
        job.join()
        assertNotNull(found)
        assertNull(SourceManager.awaitSourceById("ln_never_exists", timeoutMs = 50L))
    }
}
