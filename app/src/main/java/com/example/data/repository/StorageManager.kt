package com.example.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.data.epub.EpubParser
import com.example.data.epub.ParsedEpub

/**
 * Temporary storage adapter retained until Stage 4b/6.
 *
 * All chapter storage, caching, and downloads have been migrated to ChapterStorage
 * and ChapterRepository. Only members required for EPUB parsing and SAF custom storage
 * backup mirroring remain.
 */
class StorageManager(private val context: Context) {

    fun parseEpub(uri: Uri, fileName: String): ParsedEpub {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open stream for URI")
        return EpubParser.parseEpub(context, inputStream, fileName)
    }

    fun getSafeNovelId(novelId: String): String {
        return novelId.replace("[^a-zA-Z0-9_.-]".toRegex(), "_")
    }

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
