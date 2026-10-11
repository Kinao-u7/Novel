package com.example.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.local.db.entity.NovelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NovelDao {

    @Query("SELECT * FROM novels WHERE in_library = 1 ORDER BY last_read_timestamp DESC, created_at DESC")
    fun getLibraryNovels(): Flow<List<NovelEntity>>

    @Query("SELECT * FROM novels WHERE id = :id LIMIT 1")
    fun getNovelByIdFlow(id: String): Flow<NovelEntity?>

    @Query("SELECT * FROM novels WHERE id = :id LIMIT 1")
    suspend fun getNovelById(id: String): NovelEntity?

    @Query("SELECT * FROM novels WHERE source_url = :sourceUrl LIMIT 1")
    suspend fun getNovelBySourceUrl(sourceUrl: String): NovelEntity?

    @Query("SELECT * FROM novels WHERE source_id = :sourceId AND raw_id = :rawId LIMIT 1")
    suspend fun getNovelBySourceAndRawId(sourceId: String, rawId: String): NovelEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(novel: NovelEntity): Long

    @Update
    suspend fun update(novel: NovelEntity): Int

    @Transaction
    suspend fun upsertSafe(novel: NovelEntity) {
        val rowId = insertIgnore(novel)
        if (rowId == -1L) {
            update(novel)
        }
    }

    @Delete
    suspend fun delete(novel: NovelEntity): Int

    @Query("DELETE FROM novels WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("UPDATE novels SET in_library = :inLibrary WHERE id = :id")
    suspend fun setInLibrary(id: String, inLibrary: Boolean): Int

    @Query("UPDATE novels SET last_read_chapter_id = :chapterId, last_read_timestamp = :timestamp WHERE id = :id")
    suspend fun updateLastRead(id: String, chapterId: String, timestamp: Long): Int

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
          AND id NOT IN (SELECT DISTINCT novel_id FROM chapters WHERE storage_tier IN ('CACHE', 'DOWNLOADED', 'EPUB'))
    """)
    suspend fun purgeUnusedBrowseNovels(): Int
}
