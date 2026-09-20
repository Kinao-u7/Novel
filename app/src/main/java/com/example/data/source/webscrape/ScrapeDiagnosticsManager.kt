package com.example.data.source.webscrape

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScrapeDiagnosticsManager {
    private const val TAG = "NOVEL_SCRAPE_DIAG"
    private const val MASTER_LOG_FILE_NAME = "scrape_diagnostics.txt"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileTimestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    @Volatile
    private var currentRunFileName: String? = null

    private fun getStorageDir(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getMasterDiagnosticsFile(context: Context): File {
        return File(getStorageDir(context), MASTER_LOG_FILE_NAME)
    }

    fun getCurrentOrLatestFile(context: Context): File {
        val runName = currentRunFileName
        if (runName != null) {
            val runFile = File(getStorageDir(context), runName)
            if (runFile.exists()) return runFile
        }
        return getMasterDiagnosticsFile(context)
    }

    @Synchronized
    fun startNewRun(context: Context, url: String) {
        val now = Date()
        val formattedTimestamp = dateFormat.format(now)
        val fileTs = fileTimestampFormat.format(now)
        currentRunFileName = "scrape_diagnostics_$fileTs.txt"

        val header = buildString {
            appendLine()
            appendLine("================================================================================")
            appendLine("NOVEL SCRAPE RUN: $formattedTimestamp")
            appendLine("TARGET URL: $url")
            appendLine("SESSION LOG FILE: ${currentRunFileName}")
            appendLine("================================================================================")
        }

        writeToFiles(context, header)
        Log.d(TAG, "[FILE_LOGGER] New scraping run initiated for: $url")
        Log.d(TAG, "[FILE_LOGGER] Master file: ${getMasterDiagnosticsFile(context).absolutePath}")
        Log.d(TAG, "[FILE_LOGGER] Run file: ${File(getStorageDir(context), currentRunFileName!!).absolutePath}")
    }

    @Synchronized
    fun log(context: Context, message: String) {
        val ts = dateFormat.format(Date())
        val formatted = "[$ts] $message\n"
        writeToFiles(context, formatted)
    }

    @Synchronized
    fun logLines(context: Context, lines: List<String>) {
        if (lines.isEmpty()) return
        val ts = dateFormat.format(Date())
        val sb = StringBuilder()
        for (line in lines) {
            sb.append("[$ts] ").append(line).append("\n")
        }
        writeToFiles(context, sb.toString())
    }

    @Synchronized
    fun finishRun(context: Context, status: String) {
        val now = Date()
        val formattedTimestamp = dateFormat.format(now)
        val footer = buildString {
            appendLine("--- SCRAPE RUN FINISHED: $status at $formattedTimestamp ---")
            appendLine("================================================================================")
            appendLine()
        }

        writeToFiles(context, footer)
        val masterFile = getMasterDiagnosticsFile(context)
        Log.d(TAG, "[FILE_LOGGER] Scraping run finished ($status). Total master log size: ${masterFile.length()} bytes")
        Log.d(TAG, "[FILE_LOGGER] Path: ${masterFile.absolutePath}")
    }

    private fun writeToFiles(context: Context, text: String) {
        try {
            // 1. Append to master cumulative log file (preserves all historical scrape attempts)
            val masterFile = getMasterDiagnosticsFile(context)
            FileWriter(masterFile, true).use { it.write(text) }

            // 2. Also write to timestamped file for this specific scrape run
            val runName = currentRunFileName
            if (runName != null) {
                val runFile = File(getStorageDir(context), runName)
                FileWriter(runFile, true).use { it.write(text) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing scrape diagnostic to file: ${e.message}", e)
        }
    }

    fun shareDiagnosticsFile(context: Context) {
        try {
            val fileToShare = getMasterDiagnosticsFile(context)
            if (!fileToShare.exists() || fileToShare.length() == 0L) {
                Toast.makeText(
                    context,
                    "No diagnostic logs found yet. Run a scrape in the browser first!\nTarget path: ${fileToShare.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, fileToShare)

            // Read text content (up to ~65KB to ensure it fits comfortably in Intent string extras for email/notes/chat)
            val fileText = try {
                val full = fileToShare.readText()
                if (full.length > 60_000) {
                    val head = full.substring(0, 10_000)
                    val tail = full.substring(full.length - 45_000)
                    "$head\n\n... [TRUNCATED FOR CLIPBOARD - COMPLETE LOG IN ATTACHED FILE] ...\n\n$tail"
                } else {
                    full
                }
            } catch (e: Exception) {
                "Scrape Diagnostics Log (${fileToShare.name})\nLocation: ${fileToShare.absolutePath}\nSize: ${fileToShare.length()} bytes"
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Novel Scrape Diagnostics - ${fileToShare.name}")
                putExtra(Intent.EXTRA_TEXT, fileText)
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(intent, "Share Scrape Diagnostics").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)

            Toast.makeText(
                context,
                "Diagnostics file: ${fileToShare.name} (${fileToShare.length() / 1024} KB)\n${fileToShare.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share diagnostics log: ${e.message}", e)
            Toast.makeText(
                context,
                "Unable to open share sheet: ${e.localizedMessage}\nFile path: ${getMasterDiagnosticsFile(context).absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
