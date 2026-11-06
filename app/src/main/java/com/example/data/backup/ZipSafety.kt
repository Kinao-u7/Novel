package com.example.data.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream

object ZipSafety {
    const val MAX_ENTRIES = 50_000
    const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024 // 4 GiB
    const val MAX_DATA_JSON_BYTES = 256L * 1024 * 1024 // 256 MiB

    private val SAFE_FILE_NAME_REGEX = Regex("^[A-Za-z0-9._-]+$")

    /**
     * Validates whether a zip entry name adheres to the strict backup whitelist.
     * Prevents directory traversal (zip-slip) and arbitrary file writes.
     */
    fun isValidEntryName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.startsWith("/") || name.startsWith("\\")) return false
        if (name.contains("..") || name.contains("\\")) return false

        if (name == "manifest.json" || name == "settings.json" || name == "data.json") {
            return true
        }

        if (name.startsWith("covers/")) {
            val filePart = name.removePrefix("covers/")
            return filePart.isNotEmpty() && !filePart.contains("/") && filePart.matches(SAFE_FILE_NAME_REGEX)
        }

        if (name.startsWith("original_epubs/")) {
            val filePart = name.removePrefix("original_epubs/")
            return filePart.isNotEmpty() && !filePart.contains("/") && filePart.matches(SAFE_FILE_NAME_REGEX)
        }

        return false
    }

    /**
     * Validates an entry name or throws BackupException.Corrupt.
     */
    fun assertSafeEntry(name: String) {
        if (!isValidEntryName(name)) {
            throw BackupException.Corrupt("Forbidden or malformed zip entry: $name")
        }
    }

    /**
     * Copies from [input] to [output] enforcing the uncompressed byte limits.
     * Updates [totalBytesTracker] and checks against [MAX_TOTAL_BYTES].
     * For `data.json`, checks against [MAX_DATA_JSON_BYTES].
     */
    fun safeCopy(
        input: InputStream,
        output: OutputStream,
        entryName: String,
        currentTotalBytes: Long
    ): Long {
        val buffer = ByteArray(8192)
        var entryBytes = 0L
        var totalBytes = currentTotalBytes
        var len: Int

        while (input.read(buffer).also { len = it } > 0) {
            entryBytes += len
            totalBytes += len

            if (totalBytes > MAX_TOTAL_BYTES) {
                throw BackupException.TooLarge
            }

            if (entryName == "data.json" && entryBytes > MAX_DATA_JSON_BYTES) {
                throw BackupException.TooLarge
            }

            output.write(buffer, 0, len)
        }

        return entryBytes
    }
}
