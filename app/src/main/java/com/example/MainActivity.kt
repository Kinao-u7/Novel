package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.example.data.repository.NovelRepository
import com.example.ui.NovelReaderApp
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val repository = remember { NovelRepository(applicationContext) }
      val themeSettings by repository.themeSettings.collectAsState()

      MyApplicationTheme(themeSettings = themeSettings) {
        NovelReaderApp(repository = repository)
      }
    }
  }
}
