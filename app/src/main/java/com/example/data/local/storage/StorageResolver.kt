package com.example.data.local.storage

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Resolves all application file paths and deterministic SHA-256 file names under filesDir and cacheDir.
 *
 * File layout under filesDir:
 * - downloads/{novelId}/{sha256(chapterId)}.json
 * - epubs/{novelId}/{sha256(chapterId)}.json
 * - covers/{novelId}.jpg
 * - original_epubs/{novelId}.epub
 * - staging/
 *
 * File layout under cacheDir:
 * - chapters/{novelId}/{sha256(chapterId)}.json
 */
class StorageResolver(
    val filesDir: File,
    val cacheDir: File
) {

    constructor(context: Context) : this(
        filesDir = context.filesDir,
        cacheDir = context.cacheDir
    )

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun sha256(input: String): String = sha256(input.toByteArray(Charsets.UTF_8))

    fun sha256FileName(chapterId: String): String {
        return "${sha256(chapterId)}.json"
    }

    // downloads/{novelId}/{sha256(chapterId)}.json
    fun getDownloadsDir(): File = File(filesDir, "downloads")

    fun getNovelDownloadsDir(novelId: String): File = File(getDownloadsDir(), novelId)

    fun getDownloadChapterFile(novelId: String, chapterId: String): File =
        File(getNovelDownloadsDir(novelId), sha256FileName(chapterId))

    // epubs/{novelId}/{sha256(chapterId)}.json
    fun getEpubsDir(): File = File(filesDir, "epubs")

    fun getNovelEpubDir(novelId: String): File = File(getEpubsDir(), novelId)

    fun getEpubChapterFile(novelId: String, chapterId: String): File =
        File(getNovelEpubDir(novelId), sha256FileName(chapterId))

    // covers/{novelId}.jpg
    fun getCoversDir(): File = File(filesDir, "covers")

    fun getCoverFile(novelId: String): File = File(getCoversDir(), "$novelId.jpg")

    // original_epubs/{novelId}.epub
    fun getOriginalEpubsDir(): File = File(filesDir, "original_epubs")

    fun getOriginalEpubFile(novelId: String): File = File(getOriginalEpubsDir(), "$novelId.epub")

    // staging/
    fun getStagingDir(): File = File(filesDir, "staging")
}
