package com.example.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.local.db.entity.DownloadQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadQueueDao {

    @Query("SELECT * FROM download_queue ORDER BY queued_at ASC")
    fun getQueueFlow(): Flow<List<DownloadQueueEntity>>

    @Query("SELECT * FROM download_queue WHERE status = 'QUEUED' ORDER BY queued_at ASC LIMIT 1")
    suspend fun getNextQueued(): DownloadQueueEntity?

    @Query("SELECT * FROM download_queue WHERE novel_id = :novelId AND chapter_id = :chapterId LIMIT 1")
    suspend fun getItem(novelId: String, chapterId: String): DownloadQueueEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(item: DownloadQueueEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoreAll(items: List<DownloadQueueEntity>): List<Long>

    @Query("""
        UPDATE download_queue 
        SET status = :status, error_message = :errorMessage 
        WHERE novel_id = :novelId AND chapter_id = :chapterId
    """)
    suspend fun updateStatus(novelId: String, chapterId: String, status: String, errorMessage: String? = null): Int

    @Query("SELECT chapter_id FROM download_queue WHERE novel_id = :novelId AND status IN ('QUEUED', 'DOWNLOADING', 'PAUSED')")
    suspend fun getQueuedChapterIds(novelId: String): List<String>

    @Query("""
        UPDATE download_queue 
        SET status = :status, error_message = :errorMessage 
        WHERE novel_id = :novelId AND chapter_id = :chapterId AND status = 'DOWNLOADING'
    """)
    suspend fun updateStatusIfDownloading(novelId: String, chapterId: String, status: String, errorMessage: String? = null): Int

    @Query("""
        UPDATE download_queue 
        SET status = :status, retry_count = :retryCount, error_message = :errorMessage 
        WHERE novel_id = :novelId AND chapter_id = :chapterId AND status = 'DOWNLOADING'
    """)
    suspend fun updateStatusAndRetryIfDownloading(
        novelId: String,
        chapterId: String,
        status: String,
        retryCount: Int,
        errorMessage: String? = null
    ): Int

    @Query("SELECT * FROM download_queue WHERE status = 'QUEUED' ORDER BY queued_at ASC LIMIT :limit")
    suspend fun getNextQueuedBatch(limit: Int): List<DownloadQueueEntity>

    @Query("SELECT COUNT(*) FROM download_queue")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM download_queue WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("SELECT COUNT(*) FROM download_queue WHERE status IN ('QUEUED', 'DOWNLOADING')")
    suspend fun countActiveRemaining(): Int

    @Query("UPDATE download_queue SET status = 'QUEUED', retry_count = 0, error_message = NULL, queued_at = :queuedAt WHERE novel_id = :novelId AND chapter_id IN (:chapterIds) AND status IN ('FAILED', 'COMPLETED')")
    suspend fun requeueChunk(novelId: String, chapterIds: List<String>, queuedAt: Long): Int

    @Transaction
    suspend fun bulkRequeue(novelId: String, chapterIds: List<String>, queuedAt: Long) {
        chapterIds.chunked(500).forEach { chunk ->
            requeueChunk(novelId, chunk, queuedAt)
        }
    }

    @Query("DELETE FROM download_queue WHERE novel_id = :novelId AND chapter_id = :chapterId")
    suspend fun delete(novelId: String, chapterId: String): Int

    @Query("DELETE FROM download_queue WHERE novel_id = :novelId")
    suspend fun deleteForNovel(novelId: String): Int

    @Query("DELETE FROM download_queue")
    suspend fun clearQueue(): Int

    @Query("""
        UPDATE download_queue 
        SET status = :status, retry_count = :retryCount, error_message = :errorMessage 
        WHERE novel_id = :novelId AND chapter_id = :chapterId
    """)
    suspend fun updateStatusAndRetry(
        novelId: String,
        chapterId: String,
        status: String,
        retryCount: Int,
        errorMessage: String? = null
    ): Int

    @Query("UPDATE download_queue SET status = 'QUEUED' WHERE status = 'DOWNLOADING'")
    suspend fun resetDownloadingToQueued(): Int

    @Query("UPDATE download_queue SET status = 'PAUSED' WHERE status IN ('QUEUED', 'DOWNLOADING')")
    suspend fun pauseQueue(): Int

    @Query("UPDATE download_queue SET status = 'QUEUED' WHERE status = 'PAUSED'")
    suspend fun resumeQueue(): Int

    @Query("UPDATE download_queue SET status = 'QUEUED', retry_count = 0, error_message = null WHERE status = 'FAILED'")
    suspend fun retryFailed(): Int

    @Query("DELETE FROM download_queue WHERE status = 'COMPLETED'")
    suspend fun clearCompleted(): Int
}
