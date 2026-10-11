package com.example.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "download_queue",
    primaryKeys = ["novel_id", "chapter_id"],
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novel_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["status", "queued_at"])
    ]
)
data class DownloadQueueEntity(
    @ColumnInfo(name = "novel_id")
    val novelId: String,
    @ColumnInfo(name = "chapter_id")
    val chapterId: String,
    val status: String = "QUEUED",
    @ColumnInfo(name = "queued_at")
    val queuedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
)
