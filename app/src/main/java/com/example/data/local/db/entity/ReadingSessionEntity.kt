package com.example.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reading_sessions",
    indices = [
        Index(value = ["date_key"])
    ]
)
data class ReadingSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "novel_id")
    val novelId: String,
    @ColumnInfo(name = "novel_title")
    val novelTitle: String,
    @ColumnInfo(name = "chapter_id")
    val chapterId: String,
    @ColumnInfo(name = "start_time")
    val startTime: Long,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "words_read")
    val wordsRead: Int,
    @ColumnInfo(name = "date_key")
    val dateKey: String
)
