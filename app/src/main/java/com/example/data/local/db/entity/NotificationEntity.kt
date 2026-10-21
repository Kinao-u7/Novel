package com.example.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.NotificationTypes

@Entity(
    tableName = "notifications",
    indices = [
        Index(value = ["timestamp"])
    ]
)
data class NotificationEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val message: String,
    val type: String,
    val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_read")
    val isRead: Boolean = false
)
