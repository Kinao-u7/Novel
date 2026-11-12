package com.example.data.source.plugin

/**
 * Model representing a parsed plugin entry from the catalogue manifest (plugins.min.json).
 */
data class PluginCatalogueEntry(
    val id: String,
    val name: String,
    val site: String,
    val lang: String,
    val originalLang: String,
    val version: String,
    val url: String,
    val iconUrl: String? = null,
    val customCss: String? = null
)

/**
 * Compares two semver-like version strings tolerating non-numeric tokens, prefixes, and suffixes.
 * Returns negative if v1 < v2, zero if v1 == v2, positive if v1 > v2.
 */
fun comparePluginVersions(v1: String?, v2: String?): Int {
    if (v1.isNullOrBlank() && v2.isNullOrBlank()) return 0
    if (v1.isNullOrBlank()) return -1
    if (v2.isNullOrBlank()) return 1

    val clean1 = v1.trim().removePrefix("v").removePrefix("V")
    val clean2 = v2.trim().removePrefix("v").removePrefix("V")

    val tokens1 = clean1.split('.', '-', '_')
    val tokens2 = clean2.split('.', '-', '_')
    val maxLen = maxOf(tokens1.size, tokens2.size)

    for (i in 0 until maxLen) {
        val t1 = tokens1.getOrNull(i)?.trim() ?: ""
        val t2 = tokens2.getOrNull(i)?.trim() ?: ""

        val num1 = t1.toLongOrNull()
        val num2 = t2.toLongOrNull()

        if (num1 != null && num2 != null) {
            val cmp = num1.compareTo(num2)
            if (cmp != 0) return cmp
        } else {
            // Non-numeric comparison or one is numeric and other is string
            val cmp = t1.compareTo(t2, ignoreCase = true)
            if (cmp != 0) return cmp
        }
    }
    return 0
}
