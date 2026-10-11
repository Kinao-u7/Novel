package com.example.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "term_replacements",
    indices = [
        Index(value = ["novel_id"])
    ]
)
data class TermReplacementEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "novel_id")
    val novelId: String? = null,
    val pattern: String,
    val replacement: String,
    @ColumnInfo(name = "is_regex")
    val isRegex: Boolean = false,
    @ColumnInfo(name = "match_case")
    val matchCase: Boolean = false,
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true
)
