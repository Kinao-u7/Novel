package com.example.data.backup

import com.example.data.local.db.entity.BookmarkEntity
import com.example.data.local.db.entity.ChapterEntity
import com.example.data.local.db.entity.CustomSourceEntity
import com.example.data.local.db.entity.NovelEntity
import com.example.data.local.db.entity.ReadingSessionEntity
import com.example.data.local.db.entity.TermReplacementEntity
import kotlinx.serialization.Serializable
import java.io.IOException

enum class BackupCategory {
    LIBRARY,
    BOOKMARKS,
    TERM_REPLACEMENTS,
    CUSTOM_SOURCES,
    STATS,
    SETTINGS,
    COVERS,
    EPUB_FILES;

    fun dependsOn(): Set<BackupCategory> = when (this) {
        BOOKMARKS, STATS, COVERS, EPUB_FILES -> setOf(LIBRARY)
        else -> emptySet()
    }
}

enum class RestoreMode {
    MERGE,
    REPLACE
}

@Serializable
data class BackupManifest(
    val schemaVersion: Int = 1,
    val appVersion: String = "1.0.0",
    val createdAtMillis: Long = 0L,
    val categories: List<String> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val totalBytes: Long = 0L
)

data class BackupSummary(
    val novels: Int,
    val chapters: Int,
    val bookmarks: Int,
    val bytes: Long
)

data class RestoreSummary(
    val novelsAdded: Int = 0,
    val novelsUpdated: Int = 0,
    val chaptersMarkedRead: Int = 0,
    val bookmarks: Int = 0,
    val settingsApplied: Boolean = false,
    val warnings: List<String> = emptyList()
)

data class BackupProgress(
    val label: String,
    val done: Int,
    val total: Int
)

sealed class BackupException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    data object NotABackup : BackupException("File is not a valid Xian backup")
    data class Corrupt(val reason: String) : BackupException("Backup archive is corrupt: $reason")
    data class TooNew(val version: Int) : BackupException("Backup version $version is newer than supported version 1")
    data object TooLarge : BackupException("Backup exceeds maximum allowable size")
    data object NotEnoughSpace : BackupException("Insufficient storage space for restore")
}

@Serializable
data class BackupNovel(
    val id: String = "",
    val title: String = "",
    val author: String = "",
    val description: String = "",
    val coverUrl: String? = null,
    val sourceId: String = "",
    val sourceName: String = "",
    val sourceUrl: String? = null,
    val slug: String? = null,
    val locale: String? = null,
    val rawId: String? = null,
    val status: String = "ONGOING",
    val genresJson: String = "[]",
    val inLibrary: Boolean = true,
    val isEpub: Boolean = false,
    val totalChapters: Int = 0,
    val rating: Float? = null,
    val lastReadChapterId: String? = null,
    val lastReadTimestamp: Long = 0L,
    val createdAt: Long = 0L
)

@Serializable
data class BackupChapter(
    val novelId: String = "",
    val id: String = "",
    val title: String = "",
    val chapterIndex: Int = 0,
    val releaseDate: String = "",
    val wordCount: Int = 0,
    val isRead: Boolean = false,
    val readParagraphIndex: Int = 0,
    val readCharOffset: Int = 0
)

@Serializable
data class BackupBookmark(
    val id: String = "",
    val novelId: String = "",
    val chapterId: String = "",
    val chapterTitle: String = "",
    val paragraphIndex: Int = 0,
    val charOffset: Int = 0,
    val excerpt: String = "",
    val note: String = "",
    val createdAt: Long = 0L
)

@Serializable
data class BackupTermReplacement(
    val id: String = "",
    val novelId: String? = null,
    val pattern: String = "",
    val replacement: String = "",
    val isRegex: Boolean = false,
    val matchCase: Boolean = false,
    val isEnabled: Boolean = true,
    val sortOrder: Int = 0
)

@Serializable
data class BackupCustomSource(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val language: String = "",
    val description: String = "",
    val isEnabled: Boolean = true
)

@Serializable
data class BackupReadingSession(
    val novelId: String = "",
    val novelTitle: String = "",
    val chapterId: String = "",
    val startTime: Long = 0L,
    val durationMs: Long = 0L,
    val wordsRead: Int = 0,
    val dateKey: String = ""
)

@Serializable
data class BackupData(
    val novels: List<BackupNovel> = emptyList(),
    val chapters: List<BackupChapter> = emptyList(),
    val bookmarks: List<BackupBookmark> = emptyList(),
    val termReplacements: List<BackupTermReplacement> = emptyList(),
    val customSources: List<BackupCustomSource> = emptyList(),
    val readingSessions: List<BackupReadingSession> = emptyList()
)

// Mapping extensions

fun NovelEntity.toBackup(): BackupNovel = BackupNovel(
    id = id,
    title = title,
    author = author,
    description = description,
    coverUrl = coverUrl,
    sourceId = sourceId,
    sourceName = sourceName,
    sourceUrl = sourceUrl,
    slug = slug,
    locale = locale,
    rawId = rawId,
    status = status,
    genresJson = genresJson,
    inLibrary = inLibrary,
    isEpub = isEpub,
    totalChapters = totalChapters,
    rating = rating,
    lastReadChapterId = lastReadChapterId,
    lastReadTimestamp = lastReadTimestamp,
    createdAt = createdAt
)

fun BackupNovel.toEntity(): NovelEntity = NovelEntity(
    id = id,
    title = title,
    author = author,
    description = description,
    coverUrl = coverUrl,
    sourceId = sourceId,
    sourceName = sourceName,
    sourceUrl = sourceUrl,
    slug = slug,
    locale = locale,
    rawId = rawId,
    status = status,
    genresJson = genresJson,
    inLibrary = inLibrary,
    isEpub = isEpub,
    totalChapters = totalChapters,
    rating = rating,
    lastReadChapterId = lastReadChapterId,
    lastReadTimestamp = lastReadTimestamp,
    createdAt = if (createdAt > 0L) createdAt else System.currentTimeMillis()
)

fun ChapterEntity.toBackup(): BackupChapter = BackupChapter(
    novelId = novelId,
    id = id,
    title = title,
    chapterIndex = chapterIndex,
    releaseDate = releaseDate,
    wordCount = wordCount,
    isRead = isRead,
    readParagraphIndex = readParagraphIndex,
    readCharOffset = readCharOffset
)

fun BackupChapter.toEntity(
    targetNovelId: String = novelId,
    hash: String
): ChapterEntity = ChapterEntity(
    novelId = targetNovelId,
    id = id,
    title = title,
    chapterIndex = chapterIndex,
    releaseDate = releaseDate,
    wordCount = wordCount,
    isRead = isRead,
    readParagraphIndex = readParagraphIndex,
    readCharOffset = readCharOffset,
    storageTier = "NONE",
    storageLocation = "INTERNAL",
    fileHash = hash
)

fun BookmarkEntity.toBackup(): BackupBookmark = BackupBookmark(
    id = id,
    novelId = novelId,
    chapterId = chapterId,
    chapterTitle = chapterTitle,
    paragraphIndex = paragraphIndex,
    charOffset = charOffset,
    excerpt = excerpt,
    note = note,
    createdAt = createdAt
)

fun BackupBookmark.toEntity(targetNovelId: String = novelId): BookmarkEntity = BookmarkEntity(
    id = id,
    novelId = targetNovelId,
    chapterId = chapterId,
    chapterTitle = chapterTitle,
    paragraphIndex = paragraphIndex,
    charOffset = charOffset,
    excerpt = excerpt,
    note = note,
    createdAt = if (createdAt > 0L) createdAt else System.currentTimeMillis()
)

fun TermReplacementEntity.toBackup(): BackupTermReplacement = BackupTermReplacement(
    id = id,
    novelId = novelId,
    pattern = pattern,
    replacement = replacement,
    isRegex = isRegex,
    matchCase = matchCase,
    isEnabled = isEnabled,
    sortOrder = sortOrder
)

fun BackupTermReplacement.toEntity(targetNovelId: String? = novelId): TermReplacementEntity = TermReplacementEntity(
    id = id,
    novelId = targetNovelId,
    pattern = pattern,
    replacement = replacement,
    isRegex = isRegex,
    matchCase = matchCase,
    isEnabled = isEnabled,
    sortOrder = sortOrder
)

fun CustomSourceEntity.toBackup(): BackupCustomSource = BackupCustomSource(
    id = id,
    name = name,
    url = url,
    language = language,
    description = description,
    isEnabled = isEnabled
)

fun BackupCustomSource.toEntity(): CustomSourceEntity = CustomSourceEntity(
    id = id,
    name = name,
    url = url,
    language = language,
    description = description,
    isEnabled = isEnabled
)

fun ReadingSessionEntity.toBackup(): BackupReadingSession = BackupReadingSession(
    novelId = novelId,
    novelTitle = novelTitle,
    chapterId = chapterId,
    startTime = startTime,
    durationMs = durationMs,
    wordsRead = wordsRead,
    dateKey = dateKey
)

fun BackupReadingSession.toEntity(targetNovelId: String = novelId): ReadingSessionEntity = ReadingSessionEntity(
    id = 0,
    novelId = targetNovelId,
    novelTitle = novelTitle,
    chapterId = chapterId,
    startTime = startTime,
    durationMs = durationMs,
    wordsRead = wordsRead,
    dateKey = dateKey
)
