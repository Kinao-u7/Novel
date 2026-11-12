package com.example.data.source.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PluginCatalogueParserTest {

    @Test
    fun testValidEntryParsesSuccessfully() {
        val json = """
            [
              {
                "id": "arnovel",
                "name": "ArNovel",
                "site": "https://ar-no.com/",
                "lang": "‎العربية",
                "version": "2.4.0",
                "url": "https://example.com/arnovel.js",
                "iconUrl": "https://example.com/arnovel.png",
                "customCSS": "body { color: red; }"
              }
            ]
        """.trimIndent()

        val list = PluginCatalogueParser.parseCatalogueJson(json)
        assertEquals(1, list.size)
        val entry = list[0]
        assertEquals("arnovel", entry.id)
        assertEquals("ArNovel", entry.name)
        assertEquals("https://ar-no.com/", entry.site)
        assertEquals("AR", entry.lang)
        assertEquals("‎العربية", entry.originalLang)
        assertEquals("2.4.0", entry.version)
        assertEquals("https://example.com/arnovel.js", entry.url)
        assertEquals("https://example.com/arnovel.png", entry.iconUrl)
        assertEquals("body { color: red; }", entry.customCss)
    }

    @Test
    fun testMissingRequiredFieldsSkipsEntries() {
        val json = """
            [
              { "name": "No Id", "url": "https://a.com", "version": "1.0" },
              { "id": "valid1", "url": "https://a.com", "version": "1.0" },
              { "id": "valid2", "name": "No Url", "version": "1.0" },
              { "id": "valid3", "name": "No Version", "url": "https://a.com" },
              { "id": "valid4", "name": "All Present", "url": "https://a.com", "version": "1.0" }
            ]
        """.trimIndent()

        val list = PluginCatalogueParser.parseCatalogueJson(json)
        assertEquals(1, list.size)
        assertEquals("valid4", list[0].id)
    }

    @Test
    fun testIdValidationAndSanitization() {
        val json = """
            [
              { "id": "..", "name": "DotDot", "url": "https://a.com", "version": "1.0" },
              { "id": ".hidden", "name": "Hidden", "url": "https://a.com", "version": "1.0" },
              { "id": "a/b", "name": "SlashValid", "url": "https://a.com", "version": "1.0" },
              { "id": "/ab", "name": "SlashStart", "url": "https://a.com", "version": "1.0" },
              { "id": "", "name": "EmptyId", "url": "https://a.com", "version": "1.0" },
              { "id": "valid-id.1_2", "name": "PunctuationValid", "url": "https://a.com", "version": "1.0" }
            ]
        """.trimIndent()

        val list = PluginCatalogueParser.parseCatalogueJson(json)
        assertEquals(2, list.size)
        assertEquals("a_b", list[0].id)
        assertEquals("valid-id.1_2", list[1].id)
    }

    @Test
    fun testDuplicateSanitizedIdCollisionSkipped() {
        val json = """
            [
              { "id": "my_plugin", "name": "First", "url": "https://a.com/1.js", "version": "1.0" },
              { "id": "my/plugin", "name": "CollidingSlash", "url": "https://a.com/2.js", "version": "1.1" },
              { "id": "my_plugin", "name": "ExactDuplicate", "url": "https://a.com/3.js", "version": "1.2" },
              { "id": "other_plugin", "name": "Different", "url": "https://a.com/4.js", "version": "1.0" }
            ]
        """.trimIndent()

        val list = PluginCatalogueParser.parseCatalogueJson(json)
        assertEquals(2, list.size)
        assertEquals("my_plugin", list[0].id)
        assertEquals("First", list[0].name)
        assertEquals("other_plugin", list[1].id)
    }

    @Test
    fun testLanguageMappingTable() {
        val testLangs = listOf(
            "English" to "EN",
            "‎العربية" to "AR",
            "العربية" to "AR",
            "Español" to "ES",
            "Русский" to "RU",
            "Français" to "FR",
            "Türkçe" to "TR",
            "Bahasa Indonesia" to "ID",
            "Português" to "PT",
            "中文, 汉语, 漢語" to "ZH",
            "Tiếng Việt" to "VI",
            "日本語" to "JA",
            "조선말, 한국어" to "KO",
            "Українська" to "UK",
            "ไทย" to "TH",
            "Polski" to "PL",
            "বাংলা" to "BN",
            "Multi" to "MULTI",
            "de" to "DE",
            "German" to "GE",
            "" to "OTHER",
            null to "OTHER"
        )

        for ((input, expected) to in testLangs) {
            val code = PluginLanguageMapping.toAppLanguageCode(input)
            assertEquals("Language mapping mismatch for '$input'", expected, code)
        }
    }

    @Test
    fun testMarkedLanguageStringStripping() {
        val marked = "\u200Eالعربية"
        val code = PluginLanguageMapping.toAppLanguageCode(marked)
        assertEquals("AR", code)
        assertTrue(PluginLanguageMapping.isExactMatch(marked))

        val paddedWithMarkers = "  \u200E  العربية  \u200F  "
        val paddedCode = PluginLanguageMapping.toAppLanguageCode(paddedWithMarkers)
        assertEquals("AR", paddedCode)
        assertTrue(PluginLanguageMapping.isExactMatch(paddedWithMarkers))
    }

    @Test
    fun testVersionCompareHelper() {
        assertTrue(comparePluginVersions("1.0.0", "1.0.1") < 0)
        assertTrue(comparePluginVersions("1.0.1", "1.0.0") > 0)
        assertEquals(0, comparePluginVersions("1.0.0", "1.0.0"))

        // Tolerates 'v' prefix
        assertEquals(0, comparePluginVersions("v1.2.3", "1.2.3"))

        // Tolerates non-numeric suffix
        assertTrue(comparePluginVersions("1.0.5-beta", "1.0.5") != 0)
        assertTrue(comparePluginVersions("2.0.0", "1.9.9") > 0)

        // Tolerates empty / null / partial
        assertTrue(comparePluginVersions("", "1.0") < 0)
        assertTrue(comparePluginVersions("1.0", "") > 0)
        assertEquals(0, comparePluginVersions(null, null))
        assertEquals(0, comparePluginVersions("", "   "))
    }
}
