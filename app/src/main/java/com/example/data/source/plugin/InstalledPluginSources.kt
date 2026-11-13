package com.example.data.source.plugin

import android.content.Context
import android.util.Log
import com.example.data.source.SourceManager
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class InstalledPluginSources(
    private val manager: PluginInstallationManager,
    private val appContext: Context? = null,
    private val sourceManager: SourceManager = SourceManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val allowLoopback: Boolean = false,
    private val scriptProviderFactory: ((InstalledPlugin) -> (() -> String)?)? = null
) {
    val readySignal = CompletableDeferred<Unit>()

    internal suspend fun awaitLoaded() {
        readySignal.await()
    }

    suspend fun loadAll() = withContext(ioDispatcher) {
        try {
            val entries = manager.installedPlugins()
            for (entry in entries) {
                try {
                    sourceManager.registerSource(createSource(entry))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping installed plugin [${entry.id}]", e)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load installed plugins", e)
        } finally {
            readySignal.complete(Unit)
        }
    }

    suspend fun install(entry: PluginCatalogueEntry): InstalledPlugin {
        val installed = manager.install(entry)
        sourceManager.registerSource(createSource(installed))
        return installed
    }

    suspend fun update(entry: PluginCatalogueEntry): InstalledPlugin {
        val installed = manager.update(entry)
        sourceManager.unregisterSource("${LnPluginSource.PREFIX}${installed.id}")
        sourceManager.registerSource(createSource(installed))
        return installed
    }

    suspend fun uninstall(id: String) {
        sourceManager.unregisterSource("${LnPluginSource.PREFIX}$id")
        manager.uninstall(id)
    }

    private fun createSource(entry: InstalledPlugin): LnPluginSource {
        val provider = scriptProviderFactory?.invoke(entry)
        val file = if (provider == null) manager.scriptFile(entry.id) else null
        return LnPluginSource(
            pluginLanguage = entry.language,
            context = appContext,
            customName = entry.name,
            customVersion = entry.version,
            customId = entry.id,
            allowLoopback = allowLoopback,
            ioDispatcher = ioDispatcher,
            scriptFile = file,
            scriptProvider = provider,
            initialLatestSupported = entry.latestSupported
        )
    }

    companion object {
        private const val TAG = "InstalledPluginSources"
    }
}
