package com.example.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "chapters",
    primaryKeys = ["novel_id", "id"],
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novel_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["novel_id", "chapter_index"]),
        Index(value = ["is_read", "novel_id"]),
        Index(value = ["novel_id", "storage_tier"])
    ]
)
data class ChapterEntity(
    @ColumnInfo(name = "novel_id")
    val novelId: String,
    val id: String,
    val title: String,
    @ColumnInfo(name = "chapter_index")
    val chapterIndex: Int,
    @ColumnInfo(name = "release_date")
    val releaseDate: String = "",
    @ColumnInfo(name = "word_count")
    val wordCount: Int = 0,
    @ColumnInfo(name = "is_read")
    val isRead: Boolean = false,
    @ColumnInfo(name = "read_paragraph_index")
    val readParagraphIndex: Int = 0,
    @ColumnInfo(name = "read_char_offset")
    val readCharOffset: Int = 0,
    @ColumnInfo(name = "storage_tier")
    val storageTier: String = "NONE",
    @ColumnInfo(name = "storage_location")
    val storageLocation: String = "INTERNAL",
    @ColumnInfo(name = "file_hash")
    val fileHash: String = ""
)
