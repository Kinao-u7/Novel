package com.example.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.data.local.db.converters.DatabaseConverters
import com.example.data.local.db.dao.BookmarkDao
import com.example.data.local.db.dao.ChapterDao
import com.example.data.local.db.dao.CustomSourceDao
import com.example.data.local.db.dao.DownloadQueueDao
import com.example.data.local.db.dao.NotificationDao
import com.example.data.local.db.dao.NovelDao
import com.example.data.local.db.dao.ReadingSessionDao
import com.example.data.local.db.dao.RequestedSourceDao
import com.example.data.local.db.dao.TermReplacementDao
import com.example.data.local.db.entity.BookmarkEntity
import com.example.data.local.db.entity.ChapterEntity
import com.example.data.local.db.entity.CustomSourceEntity
import com.example.data.local.db.entity.DownloadQueueEntity
import com.example.data.local.db.entity.NotificationEntity
import com.example.data.local.db.entity.NovelEntity
import com.example.data.local.db.entity.ReadingSessionEntity
import com.example.data.local.db.entity.RequestedSourceEntity
import com.example.data.local.db.entity.TermReplacementEntity

@Database(
    entities = [
        NovelEntity::class,
        ChapterEntity::class,
        BookmarkEntity::class,
        DownloadQueueEntity::class,
        ReadingSessionEntity::class,
        TermReplacementEntity::class,
        NotificationEntity::class,
        CustomSourceEntity::class,
        RequestedSourceEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(DatabaseConverters::class)
abstract class NovelDatabase : RoomDatabase() {

    abstract fun novelDao(): NovelDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun downloadQueueDao(): DownloadQueueDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun termReplacementDao(): TermReplacementDao
    abstract fun notificationDao(): NotificationDao
    abstract fun customSourceDao(): CustomSourceDao
    abstract fun requestedSourceDao(): RequestedSourceDao

    companion object {
        const val DATABASE_NAME = "novel_reader.db"

        fun buildDatabase(context: Context): NovelDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                NovelDatabase::class.java,
                DATABASE_NAME
            )
                // Development only: remove fallbackToDestructiveMigration() and provide explicit Migration objects before production release.
                .fallbackToDestructiveMigration()
                .build()
        }

        fun buildInMemoryDatabase(context: Context): NovelDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                NovelDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }
    }
}
