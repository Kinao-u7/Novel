package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.download.DownloadNotificationHelper
import com.example.di.LocalAppContainer
import com.example.ui.navigation.AppNavHost
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DownloadNotificationHelper.createNotificationChannel(this)
        DownloadNotificationHelper.requestNotificationPermission(this)

        val app = application as NovelApplication
        val container = app.container

        setContent {
            val themeSettings by container.settingsRepository.themeSettings.collectAsStateWithLifecycle()

            MyApplicationTheme(themeSettings = themeSettings) {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    AppNavHost()
                }
            }
        }
    }
}
