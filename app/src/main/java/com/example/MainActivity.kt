package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.download.DownloadNotificationHelper
import com.example.di.LocalAppContainer
import com.example.ui.navigation.AppNavHost
import com.example.ui.theme.AppTheme
import com.example.ui.theme.XianTheme

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

            XianTheme(
                themeMode = themeSettings.themeMode,
                accentHex = themeSettings.accentColorHex
            ) {
                val colors = AppTheme.colors
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colors.bg)
                        .drawBehind {
                            if (colors.glow != Color.Transparent) {
                                drawRect(
                                    brush = Brush.radialGradient(
                                        colors = listOf(colors.glow, Color.Transparent),
                                        center = Offset(size.width / 2f, 0f),
                                        radius = size.width * 0.9f
                                    )
                                )
                            }
                        }
                ) {
                    CompositionLocalProvider(LocalAppContainer provides container) {
                        AppNavHost()
                    }
                }
            }
        }
    }
}
