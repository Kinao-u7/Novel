package com.example.data.local.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Query("SELECT * FROM novels WHERE in_library = 1")
    suspend fun getAllLibraryNovels(): List<NovelEntity>

    @Query("DELETE FROM novels")
    suspend fun deleteAll(): Int

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

    @Query("""
        UPDATE novels
        SET author = CASE WHEN (author IS NULL OR author = '') AND :newAuthor IS NOT NULL AND :newAuthor != '' THEN :newAuthor ELSE author END,
            description = CASE WHEN (description IS NULL OR description = '') AND :newDescription IS NOT NULL AND :newDescription != '' THEN :newDescription ELSE description END,
            genres_json = CASE WHEN (genres_json IS NULL OR genres_json = '' OR genres_json = '[]') AND :newGenresJson IS NOT NULL AND :newGenresJson != '[]' THEN :newGenresJson ELSE genres_json END,
            rating = CASE WHEN rating IS NULL AND :newRating IS NOT NULL THEN :newRating ELSE rating END,
            total_chapters = CASE WHEN (total_chapters IS NULL OR total_chapters = 0) AND :newTotalChapters > 0 THEN :newTotalChapters ELSE total_chapters END,
            status = CASE WHEN (status IS NULL OR status = '' OR status = 'Ongoing' OR status = 'Unknown') AND :newStatus IS NOT NULL AND :newStatus != '' THEN :newStatus ELSE status END,
            source_url = CASE WHEN (source_url IS NULL OR source_url = '') AND :newSourceUrl IS NOT NULL AND :newSourceUrl != '' THEN :newSourceUrl ELSE source_url END,
            cover_url = CASE WHEN (cover_url IS NULL OR cover_url = '') AND :newCoverUrl IS NOT NULL AND :newCoverUrl != '' THEN :newCoverUrl ELSE cover_url END
        WHERE id = :id
    """)
    suspend fun updateBlankDetails(
        id: String,
        newAuthor: String?,
        newDescription: String?,
        newGenresJson: String?,
        newRating: Float?,
        newTotalChapters: Int,
        newStatus: String?,
        newSourceUrl: String?,
        newCoverUrl: String?
    ): Int

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

    @Query("SELECT download_source_folder FROM novels WHERE source_id = :sourceId AND download_source_folder IS NOT NULL LIMIT 1")
    suspend fun getDownloadSourceFolderForSource(sourceId: String): String?

    @Query("SELECT DISTINCT download_source_folder FROM novels WHERE source_id != :sourceId AND download_source_folder IS NOT NULL")
    suspend fun getOtherSourcesDownloadFolders(sourceId: String): List<String>

    @Query("SELECT download_novel_folder FROM novels WHERE download_source_folder = :sourceFolder AND id != :novelId AND download_novel_folder IS NOT NULL")
    suspend fun getOtherNovelsDownloadFolders(sourceFolder: String, novelId: String): List<String>

    @Query("""
        UPDATE novels
        SET download_source_folder = COALESCE(download_source_folder, :sourceFolder),
            download_novel_folder = COALESCE(download_novel_folder, :novelFolder)
        WHERE id = :novelId AND (download_source_folder IS NULL OR download_novel_folder IS NULL)
    """)
    suspend fun setDownloadFoldersIfNull(novelId: String, sourceFolder: String, novelFolder: String): Int
}
