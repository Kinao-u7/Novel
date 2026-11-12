package com.example.data.repository

import android.util.Log
import com.example.data.local.db.dao.NovelDao
import com.example.data.local.db.entity.NovelEntity
import com.example.data.local.db.normalizeSourceUrl
import com.example.data.model.Novel
import com.example.data.source.SourceManager
import com.example.data.source.plugin.LnPluginSource
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Enriches missing metadata (author, synopsis, genres, etc.) on-demand when a novel detail screen opens.
 *
 * Built-in sources keep the old behavior exactly; enrichment is limited to plugin sources (sourceId
 * starting with [LnPluginSource.PREFIX]) for now.
 *
 * Updates use targeted SQL touching ONLY columns being filled while still blank/default, and never
 * write back whole entities, protecting concurrent updates to inLibrary or lastRead progress.
 */
object NovelDetailEnricher {
    private const val TAG = "NovelDetailEnricher"
    private val attemptedNovelIds = ConcurrentHashMap.newKeySet<String>()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    internal fun clearAttemptedIdsForTesting() {
        attemptedNovelIds.clear()
    }

    suspend fun enrichNovelIfNeeded(
        roomNovel: NovelEntity,
        novelDao: NovelDao,
        sourceManager: SourceManager = SourceManager
    ): NovelEntity {
        val novelId = roomNovel.id

        // Must have blank description OR blank author to be considered under-populated
        if (roomNovel.description.isNotBlank() && roomNovel.author.isNotBlank()) {
            return roomNovel
        }

        // Must not be local/epub
        if (roomNovel.isEpub || roomNovel.sourceId == "local_epub") {
            return roomNovel
        }

        // Limit enrichment to plugin sources for now; built-in sources keep the old behavior exactly
        if (!roomNovel.sourceId.startsWith(LnPluginSource.PREFIX)) {
            return roomNovel
        }

        // Run at most once per novel per app session; check if already completed
        if (attemptedNovelIds.contains(novelId)) {
            return roomNovel
        }

        // Must find its specific source, not looping over all sources
        val source = sourceManager.getSourceById(roomNovel.sourceId) ?: return roomNovel
        if (source.isLocal) {
            return roomNovel
        }

        val fetched: Novel = try {
            val details = source.getNovelDetails(novelId)
            // Mark as attempted ONLY after call finishes (success or failure)
            attemptedNovelIds.add(novelId)
            details
        } catch (e: Throwable) {
            if (e is CancellationException) {
                // NEVER mark as attempted when cancelled
                throw e
            }
            attemptedNovelIds.add(novelId)
            Log.w(TAG, "Failed to enrich details for novel [$novelId] from source [${roomNovel.sourceId}]", e)
            return roomNovel
        }

        // Prepare targeted fields to fill ONLY if currently blank/default
        val newAuthor = if (fetched.author.isNotBlank()) fetched.author else null
        val newDescription = if (fetched.description.isNotBlank()) fetched.description else null
        val newGenresJson = if (fetched.genres.isNotEmpty()) {
            try {
                json.encodeToString(fetched.genres)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val newRating = fetched.rating
        val newTotalChapters = fetched.totalChapters
        val newStatus = if (fetched.status.isNotBlank()) fetched.status else null
        val newSourceUrl = if (!fetched.sourceUrl.isNullOrBlank()) normalizeSourceUrl(fetched.sourceUrl) else null
        val newCoverUrl = if (!fetched.coverUrl.isNullOrBlank()) fetched.coverUrl else null

        // Execute targeted update touching ONLY the columns being filled
        novelDao.updateBlankDetails(
            id = novelId,
            newAuthor = newAuthor,
            newDescription = newDescription,
            newGenresJson = newGenresJson,
            newRating = newRating,
            newTotalChapters = newTotalChapters,
            newStatus = newStatus,
            newSourceUrl = newSourceUrl,
            newCoverUrl = newCoverUrl
        )

        // Return fresh Room entity, preserving any concurrent changes to inLibrary, last_read_*, etc.
        return novelDao.getNovelById(novelId) ?: roomNovel
    }
}
