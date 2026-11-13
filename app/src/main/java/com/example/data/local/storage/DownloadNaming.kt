package com.example.data.local.storage

import java.security.MessageDigest
import java.util.Locale

/**
 * Pure filesystem naming and safety helpers for readable download folders.
 * All directory and chapter file segments stay within [MAX_SEGMENT_BYTES] UTF-8 bytes.
 */
object DownloadNaming {
    const val MAX_SEGMENT_BYTES = 200
    private const val FALLBACK_FOLDER = "Untitled"
    private const val CHAPTER_EXTENSION = ".json"

    private val BIDI_AND_INVISIBLE = Regex("[\u200B-\u200F\u202A-\u202E\u2066-\u2069\uFEFF]")
    private val INVALID_OR_CONTROL = Regex("[\\u0000-\\u001F\\u007F/\\\\:*?\"<>|]")
    private val WHITESPACE_RUN = Regex("\\s+")
    private val UNDERSCORE_RUN = Regex("_+")
    private val RESERVED_DOS_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    fun shortHash6(id: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return digest.take(3).joinToString("") { "%02x".format(Locale.US, it) }
    }

    fun sanitizeFolderName(raw: String, maxBytes: Int = MAX_SEGMENT_BYTES): String {
        return sanitizeTitleOrNull(raw, maxBytes) ?: FALLBACK_FOLDER
    }

    fun resolveUniqueFolderName(
        rawName: String,
        id: String,
        existingNamesLower: Set<String>
    ): String {
        val primary = sanitizeFolderName(rawName, MAX_SEGMENT_BYTES)
        if (primary.lowercase(Locale.ROOT) !in existingNamesLower) {
            return primary
        }
        val suffix = "_${shortHash6(id)}"
        val maxBaseBytes = MAX_SEGMENT_BYTES - suffix.toByteArray(Charsets.UTF_8).size
        val base = sanitizeFolderName(rawName, maxBaseBytes)
        return "$base$suffix"
    }

    fun formatChapterFileName(
        chapterIndex: Int,
        rawTitle: String,
        chapterId: String,
        existingFileNamesLower: Set<String> = emptySet()
    ): String {
        val safeIndex = chapterIndex.coerceAtLeast(0)
        val indexStr = String.format(Locale.US, "%04d", safeIndex)
        val primary = buildChapterFileName(indexStr, rawTitle, suffix = "")
        if (primary.lowercase(Locale.ROOT) !in existingFileNamesLower) {
            return primary
        }
        return buildChapterFileName(indexStr, rawTitle, suffix = "_${shortHash6(chapterId)}")
    }

    fun requireSafeSegment(segment: String): String {
        require(segment.isNotEmpty()) { "Path segment must not be empty" }
        require(segment != "." && segment != "..") { "Path segment must not be '.' or '..'" }
        require(!segment.contains('/') && !segment.contains('\\') && !segment.contains('\u0000')) {
            "Path segment contains illegal separator or NUL: $segment"
        }
        require(segment.none { it.code < 0x20 || it.code == 0x7F }) {
            "Path segment contains control characters"
        }
        require(segment.toByteArray(Charsets.UTF_8).size <= 255) {
            "Path segment exceeds 255 UTF-8 bytes"
        }
        return segment
    }

    private fun buildChapterFileName(indexStr: String, rawTitle: String, suffix: String): String {
        val prefix = "$indexStr - "
        val tailBytes = suffix.toByteArray(Charsets.UTF_8).size + CHAPTER_EXTENSION.length
        val maxTitleBytes = MAX_SEGMENT_BYTES - prefix.toByteArray(Charsets.UTF_8).size - tailBytes
        val cleanTitle = sanitizeTitleOrNull(rawTitle, maxTitleBytes)
        return if (cleanTitle != null) {
            "$prefix$cleanTitle$suffix$CHAPTER_EXTENSION"
        } else {
            "$indexStr$suffix$CHAPTER_EXTENSION"
        }
    }

    private fun sanitizeTitleOrNull(raw: String, maxBytes: Int): String? {
        if (maxBytes <= 0) return null
        val cleaned = raw
            .replace(BIDI_AND_INVISIBLE, "")
            .replace(INVALID_OR_CONTROL, " ")
            .replace(WHITESPACE_RUN, " ")
            .replace(UNDERSCORE_RUN, "_")
            .trim { it.isWhitespace() || it == '.' }

        if (cleaned.isEmpty()) return null
        val safeName = if (cleaned.uppercase(Locale.ROOT) in RESERVED_DOS_NAMES) "${cleaned}_" else cleaned
        val truncated = truncateUtf8(safeName, maxBytes).trim { it.isWhitespace() || it == '.' }
        if (truncated.isEmpty()) return null
        return if (truncated.uppercase(Locale.ROOT) in RESERVED_DOS_NAMES) {
            truncateUtf8("${truncated}_", maxBytes).trim { it.isWhitespace() || it == '.' }.ifEmpty { null }
        } else {
            truncated
        }
    }

    private fun truncateUtf8(input: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        if (input.toByteArray(Charsets.UTF_8).size <= maxBytes) return input
        val sb = StringBuilder(input.length)
        var usedBytes = 0
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val charCount = Character.charCount(cp)
            val cpBytes = input.substring(i, i + charCount).toByteArray(Charsets.UTF_8).size
            if (usedBytes + cpBytes > maxBytes) break
            sb.appendCodePoint(cp)
            usedBytes += cpBytes
            i += charCount
        }
        return sb.toString()
    }
}
