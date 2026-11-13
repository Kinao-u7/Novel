package com.example.data.source.plugin

import android.util.Log

object PluginLanguageMapping {

    private const val TAG = "PluginLanguageMapping"

    private val EXACT_MAP = mapOf(
        "english" to "EN",
        "العربية" to "AR",
        "español" to "ES",
        "русский" to "RU",
        "français" to "FR",
        "türkçe" to "TR",
        "bahasa indonesia" to "ID",
        "português" to "PT",
        "中文, 汉语, 漢語" to "ZH",
        "tiếng việt" to "VI",
        "日本語" to "JA",
        "조선말, 한국어" to "KO",
        "українська" to "UK",
        "ไทย" to "TH",
        "polski" to "PL",
        "বাংলা" to "BN",
        "multi" to "MULTI"
    )

    fun cleanRawLanguage(rawLang: String?): String {
        if (rawLang.isNullOrBlank()) return ""
        return rawLang.replace("\u200E", "").replace("\u200F", "").trim()
    }

    fun isExactMatch(rawLang: String?): Boolean {
        val clean = cleanRawLanguage(rawLang).lowercase()
        return clean in EXACT_MAP
    }

    fun toAppLanguageCode(rawLang: String?): String {
        val clean = cleanRawLanguage(rawLang).lowercase()
        if (clean.isEmpty()) return "OTHER"

        EXACT_MAP[clean]?.let { return it }

        // Keyword containment fallback
        val code = when {
            clean.contains("english") -> "EN"
            clean.contains("arabic") || clean.contains("عرب") -> "AR"
            clean.contains("spanish") || clean.contains("español") -> "ES"
            clean.contains("russian") || clean.contains("рус") -> "RU"
            clean.contains("french") || clean.contains("français") -> "FR"
            clean.contains("turkish") || clean.contains("türk") -> "TR"
            clean.contains("indonesia") -> "ID"
            clean.contains("portuguese") || clean.contains("português") -> "PT"
            clean.contains("chinese") || clean.contains("中文") -> "ZH"
            clean.contains("vietnamese") || clean.contains("việt") -> "VI"
            clean.contains("japanese") || clean.contains("日本") -> "JA"
            clean.contains("korean") || clean.contains("한국") || clean.contains("조선") -> "KO"
            clean.contains("ukrainian") || clean.contains("україн") -> "UK"
            clean.contains("thai") || clean.contains("ไทย") -> "TH"
            clean.contains("polish") || clean.contains("polsk") -> "PL"
            clean.contains("bengali") || clean.contains("বাংলা") -> "BN"
            clean.length in 2..3 && clean.all { it.isLetter() } -> clean.uppercase()
            else -> "OTHER"
        }

        Log.w(TAG, "Unknown catalogue language '$rawLang', mapped via fallback to '$code'")
        return code
    }
}
