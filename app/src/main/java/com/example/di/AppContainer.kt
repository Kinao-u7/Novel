package com.example.di

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.repository.NovelRepository
import com.example.data.repository.SettingsRepository
import com.example.data.source.SourceManager
import com.example.data.source.plugin.InstalledPluginSources
import com.example.data.source.plugin.LnPluginSource
import com.example.data.source.plugin.PluginInstallationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val DEBUG_PLUGIN_ASSET = "plugins/FansMTL_readwn.js"

/**
 * Dependency container for application-scoped singletons.
 *
 * Temporary adapter note:
 * Owns [novelRepository] by wrapping [NovelRepository.getInstance].
 * UI components and ViewModels obtain the repository ONLY via this container.
 * This adapter will be deleted in Stage 2b when repositories are decoupled from singletons
 * and wired directly into dedicated ViewModels.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext ?: context
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val installedPluginSources: InstalledPluginSources by lazy {
        InstalledPluginSources(PluginInstallationManager(appContext), appContext)
    }

    init {
        registerDebugPluginIfPresent()
        SourceManager.setPluginsReadySignal(installedPluginSources.readySignal)
        appScope.launch { installedPluginSources.loadAll() }
    }

    private fun registerDebugPluginIfPresent() {
        if (!BuildConfig.DEBUG) return
        try {
            val bytes = appContext.assets.open(DEBUG_PLUGIN_ASSET).use { it.readBytes() }
            val script = String(bytes, Charsets.UTF_8)
            val plugin = LnPluginSource(
                pluginScript = script,
                pluginLanguage = "EN",
                customName = "FanMTL (plugin)",
                customVersion = "1.0.5",
                customId = "wuxiamtl",
                context = appContext
            )
            SourceManager.registerSource(plugin)
            Log.i("AppContainer", "Registered debug plugin [${plugin.id}] from asset, ${bytes.size} bytes")
        } catch (e: Exception) {
            Log.w("AppContainer", "Debug plugin registration skipped or failed", e)
        }
    }

    val novelRepository: NovelRepository by lazy {
        NovelRepository.getInstance(appContext)
    }

    val settingsRepository: SettingsRepository
        get() = novelRepository.settingsRepository

    val backupManager: com.example.data.backup.BackupManager by lazy {
        novelRepository.backupManager
    }

    var chapterFinishedListener: ChapterFinishedListener = ChapterFinishedListener.NoOp
}
