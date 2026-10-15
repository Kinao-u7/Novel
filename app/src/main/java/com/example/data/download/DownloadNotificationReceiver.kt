package com.example.data.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val ACTION_PAUSE_DOWNLOADS = "com.example.data.download.ACTION_PAUSE_DOWNLOADS"
const val ACTION_CANCEL_DOWNLOADS = "com.example.data.download.ACTION_CANCEL_DOWNLOADS"

class DownloadNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val repository = DownloadEngineProvider.getRepository(context)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_PAUSE_DOWNLOADS -> repository.downloadEngine.pauseQueue()
                    ACTION_CANCEL_DOWNLOADS -> repository.downloadEngine.cancelAll()
                }
            } catch (e: Exception) {
                android.util.Log.e("DownloadNotificationReceiver", "Error processing notification action: $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
