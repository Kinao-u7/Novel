package com.example.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.local.db.entity.NotificationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC, rowid DESC")
    fun getNotificationsFlow(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC, rowid DESC")
    suspend fun getAllNotifications(): List<NotificationEntity>

    @Query("SELECT COUNT(*) FROM notifications WHERE is_read = 0")
    fun getUnreadCountFlow(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(notification: NotificationEntity): Long

    @Query("""
        DELETE FROM notifications 
        WHERE rowid NOT IN (
            SELECT rowid FROM notifications 
            ORDER BY timestamp DESC, rowid DESC 
            LIMIT 200
        )
    """)
    suspend fun trimToNewest200(): Int

    @Transaction
    suspend fun insertAndTrim(notification: NotificationEntity) {
        insertIgnore(notification)
        trimToNewest200()
    }

    @Query("UPDATE notifications SET is_read = 1 WHERE id = :id")
    suspend fun markAsRead(id: String): Int

    @Query("UPDATE notifications SET is_read = 1")
    suspend fun markAllAsRead(): Int

    @Delete
    suspend fun delete(notification: NotificationEntity): Int

    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM notifications")
    suspend fun clearAll(): Int
}
