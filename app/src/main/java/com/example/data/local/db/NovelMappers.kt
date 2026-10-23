package com.example.data.local.db

import com.example.data.local.db.dao.NovelWithChapterInfo
import com.example.data.local.db.entity.BookmarkEntity
import com.example.data.local.db.entity.ChapterEntity
import com.example.data.local.db.entity.CustomSourceEntity
import com.example.data.local.db.entity.NotificationEntity
import com.example.data.local.db.entity.NovelEntity
import com.example.data.local.db.entity.TermReplacementEntity
import com.example.data.model.AppNotificationItem
import com.example.data.model.Bookmark
import com.example.data.model.Chapter
import com.example.data.model.CustomNovelSource
import com.example.data.model.Novel
import com.example.data.model.TermReplacementRule
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI

private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}

fun normalizeSourceUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return try {
        val uri = URI(url.trim())
        val scheme = (uri.scheme ?: "https").lowercase()
        val host = (uri.host ?: "").lowercase().removePrefix("www.")
        val port = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
        val path = (uri.path ?: "").trimEnd('/')
        val query = if (!uri.query.isNullOrEmpty()) "?${uri.query}" else ""
        if (host.isEmpty()) url.trim().trimEnd('/') else "$scheme://$host$port$path$query"
    } catch (e: Exception) {
        url.trim().trimEnd('/')
    }
}

fun NovelWithChapterInfo.toModel(): Novel {
    val genresList = try {
        json.decodeFromString<List<String>>(novel.genresJson)
    } catch (e: Exception) {
        emptyList()
    }
    return Novel(
        id = novel.id,
        title = novel.title,
        author = novel.author,
        coverUrl = novel.coverUrl,
        description = novel.description,
        genres = genresList,
        status = novel.status,
        sourceId = novel.sourceId,
        sourceName = novel.sourceName,
        inLibrary = novel.inLibrary,
        lastReadChapterId = novel.lastReadChapterId,
        lastReadChapterTitle = lastReadTitle,
        lastReadChapterIndex = lastReadIndex ?: 0,
        lastReadScrollPosition = lastReadParagraph ?: 0,
        totalChapters = novel.totalChapters,
        downloadedChaptersCount = downloadedCount,
        rating = novel.rating,
        lastReadTimestamp = novel.lastReadTimestamp,
        isEpub = novel.isEpub,
        rawId = novel.rawId,
        locale = novel.locale,
        sourceUrl = novel.sourceUrl
    )
}

fun NovelEntity.toModel(
    downloadedCount: Int = 0,
    lastReadTitle: String? = null,
    lastReadIndex: Int = 0,
    lastReadPosition: Int = 0
): Novel {
    val genresList = try {
        json.decodeFromString<List<String>>(genresJson)
    } catch (e: Exception) {
        emptyList()
    }
    return Novel(
        id = id,
        title = title,
        author = author,
        coverUrl = coverUrl,
        description = description,
        genres = genresList,
        status = status,
        sourceId = sourceId,
        sourceName = sourceName,
        inLibrary = inLibrary,
        lastReadChapterId = lastReadChapterId,
        lastReadChapterTitle = lastReadTitle,
        lastReadChapterIndex = lastReadIndex,
        lastReadScrollPosition = lastReadPosition,
        totalChapters = totalChapters,
        downloadedChaptersCount = downloadedCount,
        rating = rating,
        lastReadTimestamp = lastReadTimestamp,
        isEpub = isEpub,
        rawId = rawId,
        locale = locale,
        sourceUrl = sourceUrl
    )
}

fun Novel.toEntity(genresJsonOverride: String? = null): NovelEntity {
    val encodedGenres = genresJsonOverride ?: try {
        json.encodeToString(genres)
    } catch (e: Exception) {
        "[]"
    }
    return NovelEntity(
        id = id,
        title = title,
        author = author,
        description = description,
        coverUrl = coverUrl,
        sourceId = sourceId,
        sourceName = sourceName,
        sourceUrl = normalizeSourceUrl(sourceUrl),
        slug = null,
        locale = locale,
        rawId = rawId?.trim()?.ifEmpty { null },
        status = status,
        genresJson = encodedGenres,
        inLibrary = inLibrary,
        isEpub = isEpub,
        totalChapters = totalChapters,
        rating = rating,
        lastReadChapterId = lastReadChapterId,
        lastReadTimestamp = lastReadTimestamp,
        createdAt = System.currentTimeMillis()
    )
}

fun ChapterEntity.toModel(): Chapter {
    return Chapter(
        id = id,
        novelId = novelId,
        title = title,
        chapterIndex = chapterIndex,
        releaseDate = releaseDate,
        isDownloaded = storageTier == "DOWNLOADED" || storageTier == "EPUB",
        isRead = isRead,
        wordCount = wordCount
    )
}

fun Chapter.toEntity(
    storageTier: String = if (isDownloaded) "DOWNLOADED" else "NONE",
    storageLocation: String = "INTERNAL",
    fileHash: String = "",
    readParagraphIndex: Int = 0,
    readCharOffset: Int = 0
): ChapterEntity {
    return ChapterEntity(
        novelId = novelId,
        id = id,
        title = title,
        chapterIndex = chapterIndex,
        releaseDate = releaseDate,
        wordCount = wordCount,
        isRead = isRead,
        readParagraphIndex = readParagraphIndex,
        readCharOffset = readCharOffset,
        storageTier = storageTier,
        storageLocation = storageLocation,
        fileHash = fileHash
    )
}

fun BookmarkEntity.toModel(): Bookmark {
    return Bookmark(
        id = id,
        novelId = novelId,
        chapterId = chapterId,
        chapterTitle = chapterTitle,
        paragraphIndex = paragraphIndex,
        excerpt = excerpt,
        note = note,
        timestamp = createdAt
    )
}

fun Bookmark.toEntity(): BookmarkEntity {
    return BookmarkEntity(
        id = id,
        novelId = novelId,
        chapterId = chapterId,
        chapterTitle = chapterTitle,
        paragraphIndex = paragraphIndex,
        charOffset = 0,
        excerpt = excerpt,
        note = note,
        createdAt = timestamp
    )
}

fun getCustomSourceDedupeKey(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.equals("https://", ignoreCase = true) || trimmed.equals("http://", ignoreCase = true) ||
        trimmed.equals("https:", ignoreCase = true) || trimmed.equals("http:", ignoreCase = true)) {
        return null
    }
    val candidate = if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
        if (trimmed.contains("://")) return null // unsupported scheme
        "https://$trimmed"
    } else {
        trimmed
    }
    return try {
        val uri = URI(candidate)
        val scheme = (uri.scheme ?: "https").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.removePrefix("www.")
        if (host.isNullOrBlank()) return null
        val port = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
        val path = (uri.rawPath ?: "").trimEnd('/')
        "$host$port$path"
    } catch (e: Exception) {
        null
    }
}

fun normalizeCustomSourceUrl(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.equals("https://", ignoreCase = true) || trimmed.equals("http://", ignoreCase = true) ||
        trimmed.equals("https:", ignoreCase = true) || trimmed.equals("http:", ignoreCase = true)) {
        return null
    }
    val candidate = if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
        if (trimmed.contains("://")) return null // unsupported scheme
        "https://$trimmed"
    } else {
        trimmed
    }
    return try {
        val uri = URI(candidate)
        val scheme = (uri.scheme ?: "https").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase()?.removePrefix("www.")
        if (host.isNullOrBlank()) return null
        val port = if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
        val path = (uri.rawPath ?: "").trimEnd('/')
        val query = if (!uri.rawQuery.isNullOrEmpty()) "?${uri.rawQuery}" else ""
        val normalized = "$scheme://$host$port$path$query"
        if (normalized.equals("https://", ignoreCase = true) || normalized.equals("http://", ignoreCase = true)) {
            null
        } else {
            normalized
        }
    } catch (e: Exception) {
        null
    }
}

fun TermReplacementEntity.toModel(): TermReplacementRule {
    return TermReplacementRule(
        id = id,
        original = pattern,
        replacement = replacement,
        isEnabled = isEnabled,
        sortOrder = sortOrder
    )
}

fun TermReplacementRule.toEntity(
    novelId: String? = null,
    isRegex: Boolean = false,
    matchCase: Boolean = false
): TermReplacementEntity {
    return TermReplacementEntity(
        id = id,
        novelId = novelId,
        pattern = original,
        replacement = replacement,
        isRegex = isRegex,
        matchCase = matchCase,
        isEnabled = isEnabled,
        sortOrder = sortOrder
    )
}

fun CustomSourceEntity.toModel(): CustomNovelSource {
    return CustomNovelSource(
        id = id,
        name = name,
        url = url,
        language = language,
        description = description
    )
}

fun CustomNovelSource.toEntity(isEnabled: Boolean = true): CustomSourceEntity {
    return CustomSourceEntity(
        id = id,
        name = name,
        url = url,
        language = language,
        description = description,
        isEnabled = isEnabled
    )
}

fun NotificationEntity.toModel(): AppNotificationItem {
    return AppNotificationItem(
        id = id,
        title = title,
        message = message,
        timestamp = timestamp,
        type = type
    )
}

fun AppNotificationItem.toEntity(isRead: Boolean = false): NotificationEntity {
    return NotificationEntity(
        id = id,
        title = title,
        message = message,
        type = type,
        timestamp = timestamp,
        isRead = isRead
    )
}
