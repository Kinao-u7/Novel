package com.example.data.local.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.local.db.entity.NovelEntity
import kotlinx.coroutines.flow.Flow

data class NovelWithChapterInfo(
    @Embedded val novel: NovelEntity,
    @ColumnInfo(name = "downloaded_count") val downloadedCount: Int,
    @ColumnInfo(name = "last_read_title") val lastReadTitle: String?,
    @ColumnInfo(name = "last_read_index") val lastReadIndex: Int?,
    @ColumnInfo(name = "last_read_paragraph") val lastReadParagraph: Int?,
    @ColumnInfo(name = "last_read_offset") val lastReadOffset: Int?
)

@Dao
interface NovelDao {

    @Query("SELECT * FROM novels WHERE in_library = 1 ORDER BY last_read_timestamp DESC, created_at DESC")
    fun getLibraryNovels(): Flow<List<NovelEntity>>

    @Query("""
        SELECT n.*,
          (SELECT COUNT(*) FROM chapters c WHERE c.novel_id = n.id AND c.storage_tier IN ('DOWNLOADED', 'EPUB')) AS downloaded_count,
          c_last.title AS last_read_title,
          c_last.chapter_index AS last_read_index,
          c_last.read_paragraph_index AS last_read_paragraph,
          c_last.read_char_offset AS last_read_offset
        FROM novels n
        LEFT JOIN chapters c_last ON c_last.novel_id = n.id AND c_last.id = n.last_read_chapter_id
        WHERE n.in_library = 1
        ORDER BY n.last_read_timestamp DESC, n.created_at DESC
    """)
    fun getLibraryNovelsWithChapterInfoFlow(): Flow<List<NovelWithChapterInfo>>

    @Query("SELECT * FROM novels WHERE id = :id LIMIT 1")
    fun getNovelByIdFlow(id: String): Flow<NovelEntity?>

    @Query("SELECT * FROM novels WHERE id = :id LIMIT 1")
    suspend fun getNovelById(id: String): NovelEntity?

    @Query("SELECT * FROM novels WHERE source_url = :sourceUrl LIMIT 1")
    suspend fun getNovelBySourceUrl(sourceUrl: String): NovelEntity?

    @Query("SELECT * FROM novels WHERE source_id = :sourceId AND raw_id = :rawId LIMIT 1")
    suspend fun getNovelBySourceAndRawId(sourceId: String, rawId: String): NovelEntity?

    @Query("SELECT * FROM novels WHERE is_epub = 1 AND LOWER(TRIM(title)) = LOWER(TRIM(:title)) AND LOWER(TRIM(author)) = LOWER(TRIM(:author)) LIMIT 1")
    suspend fun getEpubNovelByTitleAndAuthor(title: String, author: String): NovelEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(novel: NovelEntity): Long

    @Query("""
        UPDATE novels
        SET title = :title,
            author = :author,
            description = :description,
            cover_url = CASE WHEN :coverUrl IS NOT NULL THEN :coverUrl ELSE cover_url END,
            status = :status,
            genres_json = :genresJson,
            total_chapters = :totalChapters,
            slug = :slug,
            locale = :locale,
            raw_id = CASE WHEN raw_id IS NULL AND :newRawId IS NOT NULL THEN :newRawId ELSE raw_id END
        WHERE id = :id
    """)
    suspend fun updateSourceOwnedFields(
        id: String,
        title: String,
        author: String,
        description: String,
        coverUrl: String?,
        status: String,
        genresJson: String,
        totalChapters: Int,
        slug: String?,
        locale: String?,
        newRawId: String?
    ): Int

    @Transaction
    suspend fun upsertSafe(novel: NovelEntity): String {
        val normalizedRawId = novel.rawId?.trim()?.ifEmpty { null }
        val normalizedNovel = if (normalizedRawId != novel.rawId) novel.copy(rawId = normalizedRawId) else novel
        val existing = if (normalizedRawId != null) {
            getNovelBySourceAndRawId(novel.sourceId, normalizedRawId) ?: getNovelById(novel.id)
        } else {
            getNovelById(novel.id)
        }

        if (existing == null) {
            val inserted = insertIgnore(normalizedNovel)
            if (inserted != -1L) {
                return novel.id
            }
            val conflictRow = if (normalizedRawId != null) {
                getNovelBySourceAndRawId(novel.sourceId, normalizedRawId) ?: getNovelById(novel.id)
            } else {
                getNovelById(novel.id)
            }
            val targetId = conflictRow?.id ?: novel.id
            updateSourceOwnedFields(
                id = targetId,
                title = novel.title,
                author = novel.author,
                description = novel.description,
                coverUrl = novel.coverUrl,
                status = novel.status,
                genresJson = novel.genresJson,
                totalChapters = novel.totalChapters,
                slug = novel.slug,
                locale = novel.locale,
                newRawId = if (conflictRow?.rawId == null) normalizedRawId else null
            )
            return targetId
        } else {
            updateSourceOwnedFields(
                id = existing.id,
                title = novel.title,
                author = novel.author,
                description = novel.description,
                coverUrl = novel.coverUrl,
                status = novel.status,
                genresJson = novel.genresJson,
                totalChapters = novel.totalChapters,
                slug = novel.slug,
                locale = novel.locale,
                newRawId = if (existing.rawId == null) normalizedRawId else null
            )
            return existing.id
        }
    }

    @Delete
    suspend fun delete(novel: NovelEntity): Int

    @Query("DELETE FROM novels WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("UPDATE novels SET in_library = :inLibrary WHERE id = :id")
    suspend fun setInLibrary(id: String, inLibrary: Boolean): Int

    @Query("UPDATE novels SET last_read_chapter_id = :chapterId, last_read_timestamp = :timestamp WHERE id = :id")
    suspend fun updateLastRead(id: String, chapterId: String?, timestamp: Long): Int

    @Query("UPDATE novels SET last_read_timestamp = 0 WHERE id = :id")
    suspend fun clearLastReadTimestamp(id: String): Int

    @Query("""
        UPDATE novels 
        SET title = :title, 
            author = :author, 
            description = :description, 
            cover_url = :coverUrl 
        WHERE id = :id
    """)
    suspend fun updateDetails(id: String, title: String, author: String, description: String, coverUrl: String?): Int

    @Query("UPDATE novels SET rating = :rating WHERE id = :id")
    suspend fun updateRating(id: String, rating: Float?): Int

    /**
     * Purge novels opened from Browse (in_library = 0) with no reading progress, bookmarks, or downloads.
     */
    @Query("""
        DELETE FROM novels 
        WHERE in_library = 0 
          AND last_read_timestamp = 0 
          AND id NOT IN (SELECT DISTINCT novel_id FROM bookmarks) 
          AND id NOT IN (SELECT DISTINCT novel_id FROM chapters WHERE is_read = 1) 
          AND id NOT IN (SELECT DISTINCT novel_id FROM chapters WHERE storage_tier IN ('DOWNLOADED', 'EPUB'))
    """)
    suspend fun purgeUnusedBrowseNovels(): Int
}
