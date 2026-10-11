package com.example.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("DELETE FROM download_queue WHERE novel_id = :novelId AND chapter_id = :chapterId")
    suspend fun delete(novelId: String, chapterId: String): Int

    @Query("DELETE FROM download_queue WHERE novel_id = :novelId")
    suspend fun deleteForNovel(novelId: String): Int

    @Query("DELETE FROM download_queue")
    suspend fun clearQueue(): Int
}
