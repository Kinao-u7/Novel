package com.example.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_sources")
data class CustomSourceEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val url: String,
    val language: String,
    val description: String,
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = true
)
