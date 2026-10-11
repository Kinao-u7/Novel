package com.example.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.db.entity.RequestedSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RequestedSourceDao {

    @Query("SELECT * FROM requested_sources ORDER BY requested_at DESC")
    fun getAllRequestsFlow(): Flow<List<RequestedSourceEntity>>

    @Query("SELECT * FROM requested_sources ORDER BY requested_at DESC")
    suspend fun getAllRequests(): List<RequestedSourceEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(source: RequestedSourceEntity): Long

    @Delete
    suspend fun delete(source: RequestedSourceEntity): Int

    @Query("DELETE FROM requested_sources WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
