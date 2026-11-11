package com.example.data.source.plugin

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

class PluginCatalogueLoadTest {

    @Test(timeout = 120_000)
    fun testCataloguePluginsLoadInQuickJs() = runBlocking<Unit>(Dispatchers.Default) {
        val isCi = System.getenv("CI") == "true"
        val rootDir = listOf(
            File("tools/catalogue-scan/.cache/lnreader-plugins-45daf1d"),
            File("../tools/catalogue-scan/.cache/lnreader-plugins-45daf1d")
        ).firstOrNull { it.exists() }

        if (rootDir == null) {
            val msg = "Plugin cache directory tools/catalogue-scan/.cache/lnreader-plugins-45daf1d is absent"
            if (isCi) fail(msg)
            println("[CATALOGUE-QJS] Skipping test: $msg")
            Assume.assumeTrue(msg, false)
            return@runBlocking
        }

        val pluginFiles = rootDir.walkTopDown().filter {
            it.isFile && it.extension == "js" && it.path.contains("${File.separator}.js${File.separator}src${File.separator}plugins")
        }.toList()

        if (pluginFiles.size < 300) {
            val msg = "Expected at least 300 plugin files in ${rootDir.path}, but found ${pluginFiles.size}"
            if (isCi) fail(msg)
            println("[CATALOGUE-QJS] Skipping test: $msg")
            Assume.assumeTrue(msg, false)
            return@runBlocking
        }

        val polyfillsJs = File("src/main/assets/js/polyfills.js").let { if (it.exists()) it else File("app/src/main/assets/js/polyfills.js") }.readText()
        val libsJs = File("src/main/assets/js/libs.js").let { if (it.exists()) it else File("app/src/main/assets/js/libs.js") }.readText()
        val harnessJs = File("src/main/assets/js/harness.js").let { if (it.exists()) it else File("app/src/main/assets/js/harness.js") }.readText()

        val bridge = PluginBridgeHost(httpClient = PluginHttpClientImpl(allowLoopback = true))
        bridge.use { b ->
            runBlocking(b.jsDispatcher) { b.initHarness(polyfillsJs, libsJs, harnessJs) }
            var loaded = 0
            val failures = mutableListOf<Pair<String, String>>()

            for (file in pluginFiles) {
                val id = file.nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9_]"), "_")
                try {
                    val meta = b.loadPlugin(id, file.readText())
                    val name = meta.optString("name", "")
                    val site = meta.optString("site", "")
                    if (name.isNotEmpty() || site.isNotEmpty() || meta.has("id")) {
                        loaded++
                    } else {
                        failures.add(file.name to "Empty id/name/site")
                    }
                } catch (e: Throwable) {
                    val errLine = (e.message ?: e.toString()).lines().firstOrNull()?.trim() ?: "Unknown error"
                    failures.add(file.name to errLine)
                }
            }

            println("[CATALOGUE-QJS] loaded $loaded/${pluginFiles.size}")
            for ((name, err) in failures) {
                println("[CATALOGUE-QJS] FAILED $name: $err")
            }
            assertTrue("Expected at least 300 plugins to load, but got $loaded/${pluginFiles.size}", loaded >= 300)
        }
    }
}
