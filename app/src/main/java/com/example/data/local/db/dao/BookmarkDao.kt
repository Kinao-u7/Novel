package com.example.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.db.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks ORDER BY created_at DESC")
    fun getAllBookmarksFlow(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE novel_id = :novelId ORDER BY created_at DESC")
    fun getBookmarksForNovelFlow(novelId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE novel_id = :novelId ORDER BY created_at DESC")
    suspend fun getBookmarksForNovel(novelId: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE novel_id = :novelId AND chapter_id = :chapterId ORDER BY created_at DESC")
    suspend fun getBookmarksForChapter(novelId: String, chapterId: String): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(bookmark: BookmarkEntity): Long

    @Delete
    suspend fun delete(bookmark: BookmarkEntity): Int

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM bookmarks WHERE novel_id = :novelId")
    suspend fun deleteForNovel(novelId: String): Int
}
