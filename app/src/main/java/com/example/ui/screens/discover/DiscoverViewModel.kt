package com.example.ui.screens.discover

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.repository.SettingsRepository
import com.example.data.source.SourceManager
import com.example.data.source.plugin.AddRepositoryResult
import com.example.data.source.plugin.InstalledPlugin
import com.example.data.source.plugin.InstalledPluginSources
import com.example.data.source.plugin.PluginCatalogueEntry
import com.example.data.source.plugin.PluginInstallationManager
import com.example.data.source.plugin.PluginRepositoryStore
import com.example.data.source.plugin.Repository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DiscoverViewModel(
    private val settingsRepository: SettingsRepository,
    private val repositoryStore: PluginRepositoryStore,
    private val installationManager: PluginInstallationManager,
    private val installedPluginSources: InstalledPluginSources,
    private val sourceManager: SourceManager = SourceManager,
    private val stringProvider: (resId: Int, args: Array<out Any>) -> String = { _, _ -> "" }
) : ViewModel() {

    private data class ExtensionData(
        val repositories: List<Repository> = emptyList(),
        val installedPlugins: List<InstalledPlugin> = emptyList(),
        val catalogueEntries: List<PluginCatalogueEntry>? = null,
        val catalogueError: String? = null,
        val busyIds: Set<String> = emptySet()
    )

    private data class UiFlags(
        val tab: DiscoverTab = DiscoverTab.SOURCES,
        val isSearchOpen: Boolean = false,
        val query: String = "",
        val showSettings: Boolean = false,
        val repositoryError: String? = null,
        val pendingUninstall: ExtensionItem? = null,
        val message: String? = null
    )

    private val extData = MutableStateFlow(ExtensionData())
    private val uiFlags = MutableStateFlow(UiFlags())
    private var catalogueJob: Job? = null
    private val initialLoadJob: Job

    val uiState: StateFlow<DiscoverUiState> = combine(
        sourceManager.sources,
        settingsRepository.discoverSettings,
        extData,
        uiFlags
    ) { sources, discoverSettings, ext, flags ->
        val hasEnabledRepo = ext.repositories.any { it.enabled }
        val activeCatalogue = if (hasEnabledRepo) (ext.catalogueEntries ?: emptyList()) else emptyList()
        val extLists = mapExtensionsState(
            sources, ext.installedPlugins, activeCatalogue, discoverSettings.enabledLanguages, flags.query, ext.busyIds
        )
        mapToDiscoverUiState(sources, discoverSettings, flags.query, flags.isSearchOpen, flags.message).copy(
            tab = flags.tab,
            updates = extLists.updates,
            installed = extLists.installed,
            available = extLists.available,
            languages = extLists.languages,
            repositories = ext.repositories.map { RepositoryItem(it.url, it.enabled) },
            showSettings = flags.showSettings,
            repositoryError = flags.repositoryError,
            catalogueError = if (hasEnabledRepo) ext.catalogueError else null,
            pendingUninstall = flags.pendingUninstall
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = mapToDiscoverUiState(sourceManager.sources.value, settingsRepository.discoverSettings.value)
    )

    init {
        initialLoadJob = viewModelScope.launch {
            try {
                val repos = repositoryStore.getAll()
                val installed = installationManager.installedPlugins()
                extData.update { it.copy(repositories = repos, installedPlugins = installed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load initial extension state", e)
            }
        }
    }

    private fun string(@StringRes id: Int, vararg args: Any): String = stringProvider(id, args)

    fun onToggleSearch() = uiFlags.update { cur ->
        val willOpen = !cur.isSearchOpen
        cur.copy(isSearchOpen = willOpen, query = if (willOpen) cur.query else "")
    }

    fun onQueryChange(newQuery: String) = uiFlags.update { it.copy(query = newQuery) }
    fun onMessageShown() = uiFlags.update { it.copy(message = null) }
    fun onOpenSettings() = uiFlags.update { it.copy(showSettings = true) }
    fun onDismissSettings() = uiFlags.update { it.copy(showSettings = false, repositoryError = null) }
    fun onDismissUninstall() = uiFlags.update { it.copy(pendingUninstall = null) }
    fun onRetryCatalogue() = loadCatalogue(force = true)

    fun onTabChange(newTab: DiscoverTab) {
        uiFlags.update { it.copy(tab = newTab) }
        if (newTab == DiscoverTab.EXTENSIONS) loadCatalogue(force = false)
    }

    fun onTogglePin(sourceId: String) {
        viewModelScope.launch {
            settingsRepository.updateDiscoverSettings { current ->
                val pinned = current.pinnedSourceIds.toMutableList()
                if (!pinned.remove(sourceId)) pinned.add(sourceId)
                current.copy(pinnedSourceIds = pinned)
            }
        }
    }

    fun onToggleLanguage(language: String) {
        viewModelScope.launch {
            settingsRepository.updateDiscoverSettings { current ->
                val list = current.enabledLanguages.toMutableList()
                val removed = list.removeAll { it.equals(language, ignoreCase = true) }
                if (!removed) list.add(language)
                current.copy(enabledLanguages = list)
            }
        }
    }

    private fun loadCatalogue(force: Boolean) {
        if (force) catalogueJob?.cancel()
        else if (catalogueJob?.isActive == true) return

        catalogueJob = viewModelScope.launch {
            initialLoadJob.join()
            val current = extData.value
            val enabledRepo = current.repositories.firstOrNull { it.enabled }
            if (enabledRepo == null) {
                extData.update { it.copy(catalogueEntries = null, catalogueError = null) }
                return@launch
            }
            if (!force && current.catalogueEntries != null) return@launch
            try {
                val entries = installationManager.fetchCatalogue(enabledRepo.url)
                extData.update { it.copy(catalogueEntries = entries, catalogueError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch catalogue [${enabledRepo.url}]", e)
                val msg = e.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: string(R.string.discover_catalogue_error_title)
                extData.update { it.copy(catalogueEntries = null, catalogueError = msg) }
            }
        }
    }

    fun onInstall(id: String) = installOrUpdate(id, isUpdate = false)
    fun onUpdate(id: String) = installOrUpdate(id, isUpdate = true)

    private fun installOrUpdate(id: String, isUpdate: Boolean) {
        val entry = extData.value.catalogueEntries?.firstOrNull { it.id == id } ?: return
        val errRes = if (isUpdate) R.string.discover_error_update_failed else R.string.discover_error_install_failed
        runPluginAction(id, errRes, entry.name) {
            if (isUpdate) installedPluginSources.update(entry) else installedPluginSources.install(entry)
        }
    }

    fun onUpdateAll() {
        val toUpdate = uiState.value.updates.mapNotNull { item ->
            extData.value.catalogueEntries?.firstOrNull { it.id == item.id }
        }
        if (toUpdate.isEmpty()) return
        viewModelScope.launch {
            for (entry in toUpdate) {
                executePluginAction(entry.id, R.string.discover_error_update_failed, entry.name) {
                    installedPluginSources.update(entry)
                }
            }
        }
    }

    fun onRequestUninstall(id: String) {
        val item = (uiState.value.installed + uiState.value.updates).firstOrNull { it.id == id } ?: return
        uiFlags.update { it.copy(pendingUninstall = item) }
    }

    fun onConfirmUninstall() {
        val item = uiFlags.value.pendingUninstall ?: return
        uiFlags.update { it.copy(pendingUninstall = null) }
        runPluginAction(item.id, R.string.discover_error_uninstall_failed, item.name) {
            installedPluginSources.uninstall(item.id)
        }
    }

    private fun runPluginAction(id: String, @StringRes errorRes: Int, name: String, block: suspend () -> Unit) {
        viewModelScope.launch { executePluginAction(id, errorRes, name, block) }
    }

    private suspend fun executePluginAction(id: String, @StringRes errorRes: Int, name: String, block: suspend () -> Unit) {
        extData.update { it.copy(busyIds = it.busyIds + id) }
        try {
            block()
            extData.update { it.copy(installedPlugins = installationManager.installedPlugins()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Plugin action failed [$id]", e)
            uiFlags.update { it.copy(message = string(errorRes, name)) }
        } finally {
            extData.update { it.copy(busyIds = it.busyIds - id) }
        }
    }

    fun onAddRepository(url: String) {
        viewModelScope.launch {
            try {
                val res = repositoryStore.add(url)
                val repos = repositoryStore.getAll()
                extData.update { it.copy(repositories = repos) }
                when (res) {
                    is AddRepositoryResult.Added -> {
                        uiFlags.update { it.copy(repositoryError = null) }
                        if (uiFlags.value.tab == DiscoverTab.EXTENSIONS) loadCatalogue(force = true)
                    }
                    AddRepositoryResult.Duplicate ->
                        uiFlags.update { it.copy(repositoryError = string(R.string.discover_repo_error_duplicate)) }
                    AddRepositoryResult.NotSupported ->
                        uiFlags.update { it.copy(repositoryError = string(R.string.discover_repo_error_not_supported)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add repository [$url]", e)
                uiFlags.update { it.copy(repositoryError = string(R.string.discover_error_repo_failed)) }
            }
        }
    }

    fun onToggleRepository(url: String) {
        viewModelScope.launch {
            try {
                val current = repositoryStore.getAll().firstOrNull { it.url == url }
                val nextEnabled = !(current?.enabled ?: true)
                repositoryStore.setEnabled(url, nextEnabled)
                val repos = repositoryStore.getAll()
                extData.update {
                    it.copy(
                        repositories = repos,
                        catalogueEntries = if (nextEnabled) it.catalogueEntries else null,
                        catalogueError = if (nextEnabled) it.catalogueError else null
                    )
                }
                uiFlags.update { it.copy(repositoryError = null) }
                if (nextEnabled && uiFlags.value.tab == DiscoverTab.EXTENSIONS) loadCatalogue(force = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to toggle repository [$url]", e)
                uiFlags.update { it.copy(message = string(R.string.discover_error_repo_failed)) }
            }
        }
    }

    fun onRemoveRepository(url: String) {
        viewModelScope.launch {
            try {
                repositoryStore.remove(url)
                val repos = repositoryStore.getAll()
                extData.update { it.copy(repositories = repos, catalogueEntries = null, catalogueError = null) }
                uiFlags.update { it.copy(repositoryError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove repository [$url]", e)
                uiFlags.update { it.copy(message = string(R.string.discover_error_repo_failed)) }
            }
        }
    }

    companion object {
        private const val TAG = "DiscoverViewModel"
    }
}
