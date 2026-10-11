package com.example.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novel_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["novel_id"])
    ]
)
data class BookmarkEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "novel_id")
    val novelId: String,
    @ColumnInfo(name = "chapter_id")
    val chapterId: String,
    @ColumnInfo(name = "chapter_title")
    val chapterTitle: String,
    @ColumnInfo(name = "paragraph_index")
    val paragraphIndex: Int,
    @ColumnInfo(name = "char_offset")
    val charOffset: Int,
    val excerpt: String,
    val note: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
