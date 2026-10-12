package com.example.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.db.entity.CustomSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomSourceDao {

    @Query("SELECT * FROM custom_sources")
    fun getAllSourcesFlow(): Flow<List<CustomSourceEntity>>

    @Query("SELECT * FROM custom_sources")
    suspend fun getAllSources(): List<CustomSourceEntity>

    @Query("SELECT * FROM custom_sources WHERE url = :url LIMIT 1")
    suspend fun getSourceByUrl(url: String): CustomSourceEntity?

    @Query("SELECT * FROM custom_sources WHERE is_enabled = 1")
    fun getEnabledSourcesFlow(): Flow<List<CustomSourceEntity>>

    @Query("SELECT * FROM custom_sources WHERE is_enabled = 1")
    suspend fun getEnabledSources(): List<CustomSourceEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(source: CustomSourceEntity): Long

    @Update
    suspend fun update(source: CustomSourceEntity): Int

    @Delete
    suspend fun delete(source: CustomSourceEntity): Int

    @Query("DELETE FROM custom_sources WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
