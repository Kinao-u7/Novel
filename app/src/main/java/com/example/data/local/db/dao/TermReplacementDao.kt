package com.example.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.db.entity.TermReplacementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TermReplacementDao {

    @Query("SELECT * FROM term_replacements WHERE novel_id IS NULL AND is_enabled = 1 ORDER BY (novel_id IS NULL) ASC, sort_order ASC, id ASC")
    fun getGlobalReplacementsFlow(): Flow<List<TermReplacementEntity>>

    @Query("SELECT * FROM term_replacements WHERE (novel_id = :novelId OR novel_id IS NULL) AND is_enabled = 1 ORDER BY (novel_id IS NULL) ASC, sort_order ASC, id ASC")
    fun getReplacementsForNovelFlow(novelId: String): Flow<List<TermReplacementEntity>>

    @Query("SELECT * FROM term_replacements WHERE (novel_id = :novelId OR novel_id IS NULL) AND is_enabled = 1 ORDER BY (novel_id IS NULL) ASC, sort_order ASC, id ASC")
    suspend fun getReplacementsForNovel(novelId: String): List<TermReplacementEntity>

    @Query("SELECT * FROM term_replacements ORDER BY (novel_id IS NULL) ASC, sort_order ASC, id ASC")
    fun getAllReplacementsFlow(): Flow<List<TermReplacementEntity>>

    @Query("SELECT * FROM term_replacements WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TermReplacementEntity?

    @Query("UPDATE term_replacements SET is_enabled = :isEnabled WHERE id = :id")
    suspend fun setEnabled(id: String, isEnabled: Boolean): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(replacement: TermReplacementEntity): Long

    @Update
    suspend fun update(replacement: TermReplacementEntity): Int

    @Delete
    suspend fun delete(replacement: TermReplacementEntity): Int

    @Query("DELETE FROM term_replacements WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
