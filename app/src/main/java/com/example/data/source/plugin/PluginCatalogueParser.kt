package com.example.data.source.plugin

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object PluginCatalogueParser {

    private const val TAG = "PluginCatalogueParser"
    private val ID_SANITIZATION_REGEX = Regex("""[^A-Za-z0-9_.-]""")
    private val ID_VALIDATION_REGEX = Regex("""^[A-Za-z0-9][A-Za-z0-9_.-]*$""")

    fun parseCatalogueJson(jsonStr: String): List<PluginCatalogueEntry> {
        if (jsonStr.isBlank()) {
            Log.w(TAG, "Catalogue parsed: 0 valid entries, 0 skipped")
            return emptyList()
        }

        val array = try {
            JSONArray(jsonStr)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse catalogue JSON array: ${e.message}")
            Log.w(TAG, "Catalogue parsed: 0 valid entries, 0 skipped")
            return emptyList()
        }

        val entries = mutableListOf<PluginCatalogueEntry>()
        val seenIds = mutableSetOf<String>()
        var skippedCount = 0

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            if (obj == null) {
                skippedCount++
                continue
            }

            val rawId = optNonEmptyString(obj, "id")
            val name = optNonEmptyString(obj, "name")
            val url = optNonEmptyString(obj, "url")
            val version = optNonEmptyString(obj, "version")

            if (rawId == null || name == null || url == null || version == null) {
                skippedCount++
                continue
            }

            val sanitizedId = rawId.replace(ID_SANITIZATION_REGEX, "_")
            if (!ID_VALIDATION_REGEX.matches(sanitizedId)) {
                skippedCount++
                continue
            }

            if (sanitizedId in seenIds) {
                Log.w(TAG, "Skipping entry with duplicate sanitized id '$sanitizedId' (name='$name')")
                skippedCount++
                continue
            }

            seenIds.add(sanitizedId)
            val rawLang = optNonEmptyString(obj, "lang") ?: ""
            val normalizedLang = PluginLanguageMapping.toAppLanguageCode(rawLang)
            val site = optNonEmptyString(obj, "site") ?: ""
            val iconUrl = optNonEmptyString(obj, "iconUrl")
            val customCss = optNonEmptyString(obj, "customCSS")

            entries.add(
                PluginCatalogueEntry(
                    id = sanitizedId,
                    name = name,
                    site = site,
                    lang = normalizedLang,
                    originalLang = rawLang,
                    version = version,
                    url = url,
                    iconUrl = iconUrl,
                    customCss = customCss
                )
            )
        }

        Log.w(TAG, "Catalogue parsed: ${entries.size} valid entries, $skippedCount skipped")
        return entries
    }

    private fun optNonEmptyString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        val s = json.optString(key, "").trim()
        return if (s.isEmpty() || s.equals("null", ignoreCase = true)) null else s
    }
}
