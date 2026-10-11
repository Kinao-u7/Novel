package com.example.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.data.local.db.entity.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingSessionDao {

    @Insert
    suspend fun insert(session: ReadingSessionEntity): Long

    @Query("SELECT * FROM reading_sessions ORDER BY start_time DESC")
    fun getAllSessionsFlow(): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions ORDER BY start_time DESC")
    suspend fun getAllSessions(): List<ReadingSessionEntity>

    @Query("SELECT * FROM reading_sessions WHERE novel_id = :novelId ORDER BY start_time DESC")
    suspend fun getSessionsForNovel(novelId: String): List<ReadingSessionEntity>

    @Query("SELECT * FROM reading_sessions WHERE date_key = :dateKey ORDER BY start_time DESC")
    suspend fun getSessionsByDate(dateKey: String): List<ReadingSessionEntity>

    @Query("SELECT SUM(words_read) FROM reading_sessions")
    fun getTotalWordsReadFlow(): Flow<Int?>

    @Query("SELECT SUM(duration_ms) FROM reading_sessions")
    fun getTotalDurationMsFlow(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM reading_sessions")
    suspend fun getSessionCount(): Int
}
