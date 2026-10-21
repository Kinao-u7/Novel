package com.example.data.local.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.local.db.entity.ChapterEntity
import kotlinx.coroutines.flow.Flow

data class ChapterReadPair(
    @ColumnInfo(name = "novel_id") val novelId: String,
    val id: String
)

@Dao
interface ChapterDao {

    @Query("SELECT * FROM chapters WHERE novel_id = :novelId ORDER BY chapter_index ASC")
    fun getChaptersFlow(novelId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE novel_id = :novelId ORDER BY chapter_index ASC")
    suspend fun getChapters(novelId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE novel_id = :novelId AND id = :chapterId LIMIT 1")
    suspend fun getChapter(novelId: String, chapterId: String): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE novel_id = :novelId AND id = :chapterId LIMIT 1")
    suspend fun getChapterById(novelId: String, chapterId: String): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE novel_id = :novelId AND chapter_index = :index LIMIT 1")
    suspend fun getChapterByIndex(novelId: String, index: Int): ChapterEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(chapters: List<ChapterEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(chapter: ChapterEntity): Long

    @Query("""
        UPDATE chapters 
        SET title = :title, 
            chapter_index = :chapterIndex, 
            word_count = :wordCount, 
            release_date = :releaseDate 
        WHERE novel_id = :novelId AND id = :id
    """)
    suspend fun updateMetadata(
        novelId: String,
        id: String,
        title: String,
        chapterIndex: Int,
        wordCount: Int,
        releaseDate: String
    ): Int

    /**
     * Chapter refresh: INSERT OR IGNORE new rows, then UPDATE only title, chapter_index, word_count, release_date.
     * Read state, position, tier, location, hash and bookmarks are never overwritten.
     */
    @Transaction
    suspend fun refreshChapters(novelId: String, chapters: List<ChapterEntity>) {
        insertIgnore(chapters)
        for (chapter in chapters) {
            updateMetadata(
                novelId = novelId,
                id = chapter.id,
                title = chapter.title,
                chapterIndex = chapter.chapterIndex,
                wordCount = chapter.wordCount,
                releaseDate = chapter.releaseDate
            )
        }
    }

    @Query("""
        UPDATE chapters 
        SET is_read = :isRead, 
            read_paragraph_index = :paragraphIndex, 
            read_char_offset = :charOffset 
        WHERE novel_id = :novelId AND id = :id
    """)
    suspend fun updateReadingProgress(
        novelId: String,
        id: String,
        isRead: Boolean,
        paragraphIndex: Int,
        charOffset: Int
    ): Int

    @Query("""
        UPDATE chapters 
        SET storage_tier = :storageTier, 
            storage_location = :storageLocation, 
            file_hash = :fileHash, 
            last_accessed_at = :lastAccessedAt 
        WHERE novel_id = :novelId AND id = :id
    """)
    suspend fun updateStorageTier(
        novelId: String,
        id: String,
        storageTier: String,
        storageLocation: String,
        fileHash: String,
        lastAccessedAt: Long
    ): Int

    @Query("""
        UPDATE chapters 
        SET storage_tier = :storageTier, 
            storage_location = :storageLocation, 
            file_hash = :fileHash, 
            last_accessed_at = :lastAccessedAt 
        WHERE novel_id = :novelId AND id = :id AND storage_tier IN (:allowedPreviousTiers)
    """)
    suspend fun updateStorageTierCas(
        novelId: String,
        id: String,
        storageTier: String,
        storageLocation: String,
        fileHash: String,
        lastAccessedAt: Long,
        allowedPreviousTiers: List<String>
    ): Int

    @Query("""
        UPDATE chapters 
        SET storage_tier = 'CACHE', 
            storage_location = :storageLocation, 
            file_hash = :fileHash, 
            last_accessed_at = :lastAccessedAt 
        WHERE novel_id = :novelId AND id = :id AND storage_tier IN ('NONE', 'CACHE')
    """)
    suspend fun updateTierToCacheIfNoneOrCache(
        novelId: String,
        id: String,
        storageLocation: String,
        fileHash: String,
        lastAccessedAt: Long
    ): Int

    @Query("""
        UPDATE chapters 
        SET storage_tier = 'NONE', 
            last_accessed_at = :lastAccessedAt 
        WHERE novel_id = :novelId AND id = :id AND storage_tier = 'CACHE'
    """)
    suspend fun updateTierToNoneIfCache(
        novelId: String,
        id: String,
        lastAccessedAt: Long
    ): Int

    @Query("""
        UPDATE chapters 
        SET storage_tier = 'DOWNLOADED', 
            storage_location = :storageLocation, 
            file_hash = :fileHash, 
            last_accessed_at = :lastAccessedAt 
        WHERE novel_id = :novelId AND id = :id AND storage_tier IN ('NONE', 'CACHE', 'DOWNLOADED')
    """)
    suspend fun updateTierToDownloaded(
        novelId: String,
        id: String,
        storageLocation: String,
        fileHash: String,
        lastAccessedAt: Long
    ): Int

    @Query("UPDATE chapters SET last_accessed_at = :timestamp WHERE novel_id = :novelId AND id = :id")
    suspend fun updateLastAccessed(novelId: String, id: String, timestamp: Long): Int

    /**
     * LRU query returning only CACHE chapters ordered by last_accessed_at ascending
     * (tie-break by chapter_index ascending).
     * EPUB, DOWNLOADED, and NONE chapters are never returned by eviction queries.
     */
    @Query("SELECT * FROM chapters WHERE storage_tier = 'CACHE' ORDER BY last_accessed_at ASC, chapter_index ASC")
    suspend fun getCacheChaptersLru(): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE storage_tier = 'CACHE' ORDER BY last_accessed_at ASC, chapter_index ASC LIMIT :limit")
    suspend fun getCacheChaptersLru(limit: Int): List<ChapterEntity>

    @Query("UPDATE chapters SET storage_tier = 'NONE' WHERE storage_tier = 'CACHE'")
    suspend fun clearCacheTier(): Int


    @Query("SELECT * FROM chapters WHERE novel_id = :novelId AND storage_tier = 'DOWNLOADED'")
    suspend fun getDownloadedChapters(novelId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE storage_tier = 'DOWNLOADED'")
    suspend fun getAllDownloadedChapters(): List<ChapterEntity>

    @Query("SELECT COUNT(*) FROM chapters WHERE novel_id = :novelId AND storage_tier = 'DOWNLOADED'")
    fun getDownloadedCountFlow(novelId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM chapters WHERE novel_id = :novelId AND storage_tier = 'DOWNLOADED'")
    suspend fun getDownloadedCount(novelId: String): Int

    @Query("SELECT novel_id, id FROM chapters WHERE is_read = 1")
    fun getAllReadChaptersFlow(): Flow<List<ChapterReadPair>>

    @Query("SELECT id FROM chapters WHERE novel_id = :novelId AND is_read = 1")
    fun getReadChapterIdsFlow(novelId: String): Flow<List<String>>

    @Query("SELECT novel_id, id FROM chapters WHERE storage_tier IN ('DOWNLOADED', 'EPUB')")
    fun getAllDownloadedChaptersFlow(): Flow<List<ChapterReadPair>>

    @Query("UPDATE chapters SET is_read = 1 WHERE novel_id = :novelId AND id = :id AND is_read = 0")
    suspend fun markChapterRead(novelId: String, id: String): Int

    @Query("UPDATE chapters SET read_paragraph_index = :paragraphIndex, read_char_offset = :charOffset WHERE novel_id = :novelId AND id = :id")
    suspend fun updatePosition(novelId: String, id: String, paragraphIndex: Int, charOffset: Int): Int

    @Query("UPDATE chapters SET storage_tier = CASE WHEN storage_tier = 'NONE' THEN 'CACHE' ELSE storage_tier END, last_accessed_at = :lastAccessedAt WHERE novel_id = :novelId AND id = :id")
    suspend fun recordChapterAccess(novelId: String, id: String, lastAccessedAt: Long = System.currentTimeMillis()): Int

    @Query("UPDATE chapters SET storage_tier = 'DOWNLOADED', storage_location = 'INTERNAL', last_accessed_at = :timestamp WHERE novel_id = :novelId AND id = :id")
    suspend fun markChapterDownloaded(novelId: String, id: String, timestamp: Long = System.currentTimeMillis()): Int

    @Query("UPDATE chapters SET storage_tier = 'DOWNLOADED', storage_location = 'INTERNAL', last_accessed_at = :timestamp WHERE novel_id = :novelId AND id IN (:ids)")
    suspend fun markChaptersDownloaded(novelId: String, ids: Collection<String>, timestamp: Long = System.currentTimeMillis()): Int

    @Query("UPDATE chapters SET storage_tier = 'NONE' WHERE novel_id = :novelId AND storage_tier IN ('DOWNLOADED', 'CACHE')")
    suspend fun deleteNovelDownloads(novelId: String): Int

    @Query("UPDATE chapters SET storage_tier = 'NONE' WHERE storage_tier IN ('DOWNLOADED', 'CACHE')")
    suspend fun deleteAllDownloads(): Int

    @Query("DELETE FROM chapters WHERE novel_id = :novelId")
    suspend fun deleteChaptersForNovel(novelId: String): Int
}
