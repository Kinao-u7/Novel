package com.example.data.local.db

import android.content.Context

/**
 * Lazy singleton holder for the Room [NovelDatabase] instance.
 * Ensures the database is constructed and initialized lazily on first access,
 * and never opened eagerly during Application.onCreate.
 */
object NovelDatabaseHolder {

    @Volatile
    private var instance: NovelDatabase? = null

    fun getInstance(context: Context): NovelDatabase {
        return instance ?: synchronized(this) {
            instance ?: NovelDatabase.buildDatabase(context.applicationContext).also { instance = it }
        }
    }

    /**
     * For unit tests requiring an isolated in-memory database instance.
     */
    fun createInMemory(context: Context): NovelDatabase {
        return NovelDatabase.buildInMemoryDatabase(context.applicationContext)
    }

    /**
     * For testing: resets the cached instance.
     */
    fun resetForTest() {
        synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}
