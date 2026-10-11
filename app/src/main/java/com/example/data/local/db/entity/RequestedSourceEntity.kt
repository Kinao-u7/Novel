package com.example.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "requested_sources")
data class RequestedSourceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "site_name")
    val siteName: String,
    val url: String,
    @ColumnInfo(name = "requested_at")
    val requestedAt: Long = System.currentTimeMillis()
)
