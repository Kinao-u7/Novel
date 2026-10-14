package com.example.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Temporary storage adapter retained until Stage 6 (Backup/Restore rewrite).
 *
 * All chapter storage, caching, downloads, and EPUB operations have been migrated to
 * ChapterStorage, ChapterRepository, and EpubParser. Only saveBackupToCustomStorage remains
 * for BackupManager until Stage 6.
 */
class StorageManager(private val context: Context) {

    /**
     * Saves a backup JSON string into the "Backups" subfolder inside the user-configured SAF folder.
     * Retained for BackupManager until Stage 6.
     */
    fun saveBackupToCustomStorage(jsonString: String, fileName: String, customUriStr: String?): String? {
        if (customUriStr.isNullOrBlank()) return null
        return try {
            val rootTreeUri = Uri.parse(customUriStr)
            val rootDoc = DocumentFile.fromTreeUri(context, rootTreeUri)
            if (rootDoc != null && rootDoc.canWrite()) {
                var backupsDoc = rootDoc.findFile("Backups")
                if (backupsDoc == null || !backupsDoc.isDirectory) {
                    backupsDoc = rootDoc.createDirectory("Backups")
                }
                if (backupsDoc != null) {
                    var backupFileDoc = backupsDoc.findFile(fileName)
                    if (backupFileDoc == null) {
                        backupFileDoc = backupsDoc.createFile("application/json", fileName)
                    }
                    if (backupFileDoc != null) {
                        context.contentResolver.openOutputStream(backupFileDoc.uri)?.use { os ->
                            os.write(jsonString.toByteArray(Charsets.UTF_8))
                        }
                        val folderName = rootDoc.name ?: "Custom Storage"
                        "$folderName/Backups/$fileName"
                    } else null
                } else null
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
