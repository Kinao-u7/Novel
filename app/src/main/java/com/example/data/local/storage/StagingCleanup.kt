package com.example.data.local.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Minimal startup and restore staging cleanup.
 * Deletes temporary files inside staging/ older than 10 minutes.
 */
object StagingCleanup {
    suspend fun cleanStaging(
        stagingDir: File,
        maxAgeMs: Long = 10 * 60 * 1000L,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Int = withContext(ioDispatcher) {
        if (!stagingDir.exists()) return@withContext 0
        val now = System.currentTimeMillis()
        var count = 0
        stagingDir.listFiles()?.forEach { file ->
            if (file.isFile && (now - file.lastModified() > maxAgeMs)) {
                if (file.delete()) count++
            }
        }
        count
    }
}
