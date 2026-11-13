package com.example.data.source.plugin

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PluginInstallationManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var pluginsDir: File
    private var server: HttpServer? = null
    private var serverUrl = ""

    @Before
    fun setUp() {
        pluginsDir = tempFolder.newFolder("plugins")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            serverUrl = "http://127.0.0.1:${address.port}"
            executor = Executors.newCachedThreadPool()
            start()
        }
    }

    @After
    fun tearDown() {
        server?.stop(0)
    }

    private fun serve(path: String, status: Int = 200, body: String = "", onHit: (() -> Unit)? = null) {
        server!!.createContext(path) { ex ->
            onHit?.invoke()
            if (status != 200 || body.isEmpty()) {
                ex.sendResponseHeaders(status, -1)
            } else {
                val bytes = body.toByteArray(Charsets.UTF_8)
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
        }
    }

    private fun sampleEntry(
        id: String = "sample",
        version: String = "1.0.0",
        url: String = "$serverUrl/sample.js"
    ) = PluginCatalogueEntry(
        id = id, name = "Sample Novel", site = "https://example.com",
        lang = "EN", originalLang = "English", version = version,
        url = url, iconUrl = "https://example.com/icon.png"
    )

    @Test
    fun installSuccessWritesFileAndJsonRecord() = runBlocking<Unit> {
        val script = "const plugin = { id: 'sample', version: '1.0.0' }; module.exports = plugin;"
        serve("/sample.js", body = script)

        val manager = PluginInstallationManager(pluginsDir, allowHttp = true)
        val installed = manager.install(sampleEntry())

        assertEquals("sample", installed.id)
        assertEquals(script, File(pluginsDir, "sample.js").readText(Charsets.UTF_8))
        assertFalse(File(pluginsDir, "sample.js.tmp").exists())
        assertTrue(File(pluginsDir, "installed_plugins.json").exists())
        assertEquals(listOf(installed), manager.installedPlugins())
    }

    @Test
    fun httpUrlIsRejectedBeforeRequest() = runBlocking<Unit> {
        val hit = AtomicBoolean(false)
        serve("/blocked.js", onHit = { hit.set(true) })

        val manager = PluginInstallationManager(pluginsDir)
        try {
            manager.fetchCatalogue("$serverUrl/plugins.min.json")
            fail("Expected http catalogue URL to be rejected")
        } catch (_: IllegalArgumentException) {}
        try {
            manager.install(sampleEntry(url = "$serverUrl/blocked.js"))
            fail("Expected http plugin URL to be rejected")
        } catch (_: IllegalArgumentException) {}

        assertFalse(hit.get())
        assertFalse(File(pluginsDir, "sample.js").exists())
        assertFalse(File(pluginsDir, "sample.js.tmp").exists())
    }

    @Test
    fun failedUpdateKeepsOldFileAndVersion() = runBlocking<Unit> {
        val v1Script = "// plugin v1.0.0"
        serve("/v1.js", body = v1Script)
        serve("/v2.js", status = 500)

        val manager = PluginInstallationManager(pluginsDir, allowHttp = true)
        manager.install(sampleEntry(version = "1.0.0", url = "$serverUrl/v1.js"))

        try {
            manager.update(sampleEntry(version = "2.0.0", url = "$serverUrl/v2.js"))
            fail("Expected update to fail on HTTP 500")
        } catch (_: IOException) {}

        assertEquals(v1Script, File(pluginsDir, "sample.js").readText(Charsets.UTF_8))
        assertFalse(File(pluginsDir, "sample.js.tmp").exists())
        assertEquals("1.0.0", manager.installedPlugins().single().version)
    }

    @Test
    fun installedListSurvivesNewManagerInstance() = runBlocking<Unit> {
        serve("/p1.js", body = "// p1")
        serve("/p2.js", body = "// p2")

        val manager1 = PluginInstallationManager(pluginsDir, allowHttp = true)
        manager1.install(sampleEntry(id = "p1", version = "1.0.0", url = "$serverUrl/p1.js"))
        manager1.install(sampleEntry(id = "p2", version = "2.1.0", url = "$serverUrl/p2.js"))
        manager1.uninstall("p1")

        val manager2 = PluginInstallationManager(pluginsDir, allowHttp = true)
        val persisted = manager2.installedPlugins()
        assertEquals(1, persisted.size)
        assertEquals("p2", persisted.single().id)
        assertEquals("2.1.0", persisted.single().version)
        assertFalse(File(pluginsDir, "p1.js").exists())
        assertTrue(File(pluginsDir, "p2.js").exists())
    }
}
