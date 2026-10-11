package com.example.data.local.db

import com.example.data.local.db.dao.NovelWithChapterInfo
import com.example.data.local.db.entity.BookmarkEntity
import com.example.data.local.db.entity.ChapterEntity
import com.example.data.local.db.entity.NovelEntity
import com.example.data.model.Bookmark
import com.example.data.model.Chapter
import com.example.data.model.Novel
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
        rating = novel.rating ?: 4.8f,
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
        rating = rating ?: 4.8f,
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
        rawId = rawId,
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
    lastAccessedAt: Long = 0L,
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
        fileHash = fileHash,
        lastAccessedAt = lastAccessedAt
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
