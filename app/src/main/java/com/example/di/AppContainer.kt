package com.example.di

import android.content.Context
import com.example.data.repository.NovelRepository
import com.example.data.repository.SettingsRepository
import com.example.data.source.SourceManager
import com.example.data.source.plugin.InstalledPluginSources
import com.example.data.source.plugin.PluginInstallationManager
import com.example.data.source.plugin.PluginRepositoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    val pluginRepositoryStore: PluginRepositoryStore by lazy {
        PluginRepositoryStore(appContext)
    }

    val pluginInstallationManager: PluginInstallationManager by lazy {
        PluginInstallationManager(appContext)
    }

    val installedPluginSources: InstalledPluginSources by lazy {
        InstalledPluginSources(pluginInstallationManager, appContext)
    }

    init {
        SourceManager.setPluginsReadySignal(installedPluginSources.readySignal)
        appScope.launch { installedPluginSources.loadAll() }
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
