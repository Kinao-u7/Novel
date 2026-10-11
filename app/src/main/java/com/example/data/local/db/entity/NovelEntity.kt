package com.example.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "novels",
    indices = [
        Index(value = ["in_library"]),
        Index(value = ["source_id", "raw_id"]),
        Index(value = ["source_url"])
    ]
)
data class NovelEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String? = null,
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @ColumnInfo(name = "source_name")
    val sourceName: String,
    @ColumnInfo(name = "source_url")
    val sourceUrl: String? = null,
    val slug: String? = null,
    val locale: String? = null,
    @ColumnInfo(name = "raw_id")
    val rawId: String? = null,
    val status: String,
    @ColumnInfo(name = "genres_json")
    val genresJson: String = "[]",
    @ColumnInfo(name = "in_library")
    val inLibrary: Boolean = false,
    @ColumnInfo(name = "is_epub")
    val isEpub: Boolean = false,
    @ColumnInfo(name = "total_chapters")
    val totalChapters: Int = 0,
    val rating: Float? = null,
    @ColumnInfo(name = "last_read_chapter_id")
    val lastReadChapterId: String? = null,
    @ColumnInfo(name = "last_read_timestamp")
    val lastReadTimestamp: Long = 0L,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
