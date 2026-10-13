package com.example.data.local.storage

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Resolves all application file paths and deterministic SHA-256 file names under filesDir.
 *
 * File layout under filesDir:
 * - downloads/{novelId}/{sha256(chapterId)}.json
 * - cache/chapters/{novelId}/{sha256(chapterId)}.json
 * - epubs/{novelId}/{sha256(chapterId)}.json
 * - covers/{novelId}.jpg
 * - original_epubs/{novelId}.epub
 * - staging/
 */
class StorageResolver(val filesDir: File) {

    constructor(context: Context) : this(context.filesDir)

    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun sha256FileName(chapterId: String): String {
        return "${sha256(chapterId)}.json"
    }

    // downloads/{novelId}/{sha256(chapterId)}.json
    fun getDownloadsDir(): File = File(filesDir, "downloads")

    fun getNovelDownloadsDir(novelId: String): File = File(getDownloadsDir(), novelId)

    fun getDownloadChapterFile(novelId: String, chapterId: String): File =
        File(getNovelDownloadsDir(novelId), sha256FileName(chapterId))

    // cache/chapters/{novelId}/{sha256(chapterId)}.json
    fun getCacheDir(): File = File(filesDir, "cache")

    fun getCacheChaptersDir(): File = File(filesDir, "cache/chapters")

    fun getNovelCacheDir(novelId: String): File = File(getCacheChaptersDir(), novelId)

    fun getCacheChapterFile(novelId: String, chapterId: String): File =
        File(getNovelCacheDir(novelId), sha256FileName(chapterId))

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
