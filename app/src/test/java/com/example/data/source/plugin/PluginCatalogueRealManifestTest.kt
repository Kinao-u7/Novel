package com.example.data.source.plugin

import java.io.File
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PluginCatalogueRealManifestTest {

    @Test
    fun testRealCatalogueManifestParsesInCi() {
        val isCi = System.getenv("CI") == "true"
        if (!isCi) {
            val msg = "Skipping PluginCatalogueRealManifestTest outside CI environment"
            println("[CATALOGUE-PARSE] $msg")
            Assume.assumeTrue(msg, false)
            return
        }

        val rootDir = listOf(
            File("tools/catalogue-scan/.cache/lnreader-plugins-45daf1d"),
            File("../tools/catalogue-scan/.cache/lnreader-plugins-45daf1d")
        ).firstOrNull { it.exists() }

        val manifestFile = listOf(
            File("tools/catalogue-scan/.cache/lnreader-plugins-45daf1d/.dist/plugins.min.json"),
            File("../tools/catalogue-scan/.cache/lnreader-plugins-45daf1d/.dist/plugins.min.json")
        ).firstOrNull { it.exists() }

        if (manifestFile == null) {
            fail("Manifest file tools/catalogue-scan/.cache/lnreader-plugins-45daf1d/.dist/plugins.min.json is missing in CI")
            return
        }

        val pluginFiles = rootDir?.walkTopDown()?.filter {
            it.isFile && it.extension == "js" && it.path.contains("${File.separator}.js${File.separator}src${File.separator}plugins")
        }?.toList() ?: emptyList()

        if (pluginFiles.size < 300) {
            fail("Expected at least 300 plugin files in CI, but found ${pluginFiles.size}")
            return
        }

        val jsonStr = manifestFile.readText()
        val parsedEntries = PluginCatalogueParser.parseCatalogueJson(jsonStr)

        val rawArray = JSONArray(jsonStr)
        val validIds = parsedEntries.map { it.id }.toSet()
        val skippedReasons = mutableListOf<String>()

        for (i in 0 until rawArray.length()) {
            val obj = rawArray.optJSONObject(i)
            if (obj == null) {
                skippedReasons.add("Entry at index $i is not a JSON object")
                continue
            }
            val rawId = obj.optString("id").takeIf { it.isNotEmpty() && it != "null" }
            val name = obj.optString("name").takeIf { it.isNotEmpty() && it != "null" }
            val url = obj.optString("url").takeIf { it.isNotEmpty() && it != "null" }
            val version = obj.optString("version").takeIf { it.isNotEmpty() && it != "null" }

            if (rawId == null || name == null || url == null || version == null) {
                skippedReasons.add("Entry at index $i ('$name') missing required field (id=$rawId, name=$name, url=$url, version=$version)")
                continue
            }
            val sanitized = rawId.replace(Regex("""[^A-Za-z0-9_.-]"""), "_")
            if (!Regex("""^[A-Za-z0-9][A-Za-z0-9_.-]*$""").matches(sanitized)) {
                skippedReasons.add("Entry at index $i ('$name') failed ID validation: rawId='$rawId', sanitized='$sanitized'")
                continue
            }
            if (sanitized !in validIds) {
                skippedReasons.add("Entry at index $i ('$name') with id '$sanitized' skipped as duplicate")
                continue
            }
        }

        val sortedCodes = parsedEntries.map { it.lang }.toSortedSet()

        println("[CATALOGUE-PARSE] valid=${parsedEntries.size} skipped=${skippedReasons.size} languages=$sortedCodes")

        for (reason in skippedReasons) {
            println("[CATALOGUE-PARSE] SKIPPED: $reason")
        }

        assertTrue("Expected at least 320 valid entries, but got ${parsedEntries.size}", parsedEntries.size >= 320)

        for (entry in parsedEntries) {
            assertTrue(
                "Language '${entry.originalLang}' for '${entry.name}' (${entry.id}) should map to a real code, not fallback",
                PluginLanguageMapping.isExactMatch(entry.originalLang)
            )
        }

        val allIds = parsedEntries.map { it.id }
        assertEquals("Duplicate sanitized IDs found in parsed entries", allIds.size, allIds.toSet().size)
    }
}
