package com.example.ui.screens.profile

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.NavTab
import com.example.data.repository.NovelRepository
import com.example.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileViewModel(
    private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val novelRepository: NovelRepository,
    private val savedStateHandle: SavedStateHandle,
    private val text: ProfileText = AndroidProfileText(appContext)
) : ViewModel() {

    private val _storageSizes = MutableStateFlow(StorageSizes())
    private val _confirm = MutableStateFlow<ConfirmRequest?>(null)
    private val _message = MutableStateFlow<String?>(null)

    private enum class PendingConfirmAction {
        CLEAR_CACHE,
        RESET_ALL
    }

    private var pendingConfirmAction: PendingConfirmAction? = null

    val searchQuery: StateFlow<String> = savedStateHandle.getStateFlow(KEY_SEARCH_QUERY, "")
    val openCategoryId: StateFlow<String?> = savedStateHandle.getStateFlow(KEY_OPEN_CATEGORY, null)

    private val appVersion: String by lazy {
        try {
            val pInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val vName = pInfo.versionName ?: "1.0.0"
            val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            "$vName ($vCode)"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    private val initialUiState: ProfileUiState
        get() {
            val settings = settingsRepository.settings.value
            val hidden = NavTab.entries.filter { it !in settings.bottomBar.tabs }
            val query = savedStateHandle.get<String>(KEY_SEARCH_QUERY).orEmpty()
            return ProfileUiState(
                isLoading = false,
                searchQuery = query,
                openCategoryId = savedStateHandle.get<String>(KEY_OPEN_CATEGORY),
                header = ProfileHeader(
                    displayName = settings.profile.displayName,
                    realmLabel = "",
                    realmProgress = 0f
                ),
                categories = buildCategories(settings, _storageSizes.value, query, hidden, text),
                appVersion = appVersion,
                message = null,
                confirm = null
            )
        }

    private data class ProfileLocal(
        val query: String,
        val openId: String?,
        val confirm: ConfirmRequest?,
        val message: String?
    )

    private val localFlow = combine(
        searchQuery,
        openCategoryId,
        _confirm,
        _message
    ) { query, openId, confirm, message ->
        ProfileLocal(query, openId, confirm, message)
    }

    val uiState: StateFlow<ProfileUiState> = combine(
        settingsRepository.settings,
        _storageSizes,
        localFlow
    ) { settings, storage, local ->
        val hiddenTabs = NavTab.entries.filter { it !in settings.bottomBar.tabs }
        val categories = buildCategories(settings, storage, local.query, hiddenTabs, text)
        val header = ProfileHeader(
            displayName = settings.profile.displayName,
            realmLabel = "",
            realmProgress = 0f
        )
        ProfileUiState(
            isLoading = false,
            searchQuery = local.query,
            openCategoryId = local.openId,
            header = header,
            categories = categories,
            appVersion = appVersion,
            message = local.message,
            confirm = local.confirm
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = initialUiState
        )

    init {
        refreshStorageSizes()
    }

    fun refreshStorageSizes() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val downloads = novelRepository.getDownloadsSizeBytes()
                val epubs = novelRepository.getEpubsSizeBytes()
                val chapters = downloads + epubs
                val covers = novelRepository.getCoversSizeBytes()
                val cache = novelRepository.getCacheSizeBytes()
                _storageSizes.value = StorageSizes(
                    chaptersBytes = chapters,
                    coversBytes = covers,
                    cacheBytes = cache
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    fun onSearchChange(query: String) {
        savedStateHandle[KEY_SEARCH_QUERY] = query
    }

    fun onOpenCategory(categoryId: String) {
        savedStateHandle[KEY_OPEN_CATEGORY] = categoryId
        if (categoryId == SettingsCategoryIds.DOWNLOADS) {
            refreshStorageSizes()
        }
    }

    fun onCloseCategory() {
        savedStateHandle[KEY_OPEN_CATEGORY] = null
    }

    fun onSaveName(name: String) {
        val trimmed = name.trim().take(40)
        if (trimmed.isNotBlank()) {
            viewModelScope.launch {
                try {
                    settingsRepository.updateProfileSettings { it.copy(displayName = trimmed) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _message.value = e.message ?: "Failed to save profile name"
                }
            }
        }
    }

    private fun applySettingUpdate(key: String, value: Any) {
        viewModelScope.launch {
            try {
                val current = settingsRepository.settings.value
                val reduced = reduceSetting(current, key, value)
                if (reduced.theme != current.theme) {
                    settingsRepository.updateThemeSettings { reduced.theme }
                }
                if (reduced.reader != current.reader) {
                    settingsRepository.updateReaderSettings { reduced.reader }
                }
                if (reduced.downloads != current.downloads) {
                    settingsRepository.updateDownloadSettings { reduced.downloads }
                }
                if (reduced.library != current.library) {
                    settingsRepository.updateLibrarySettings { reduced.library }
                }
                if (reduced.cultivation != current.cultivation) {
                    settingsRepository.updateCultivationSettings { reduced.cultivation }
                }
                if (reduced.backup != current.backup) {
                    settingsRepository.updateBackupSettings { reduced.backup }
                }
                if (reduced.bottomBar != current.bottomBar) {
                    settingsRepository.updateBottomBarSettings { reduced.bottomBar }
                }
                if (reduced.profile != current.profile) {
                    settingsRepository.updateProfileSettings { reduced.profile }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _message.value = e.message ?: "Failed to update setting"
            }
        }
    }

    fun onToggle(key: String, value: Boolean) = applySettingUpdate(key, value)
    fun onSelect(key: String, optionId: String) = applySettingUpdate(key, optionId)
    fun onSlide(key: String, value: Float) = applySettingUpdate(key, value)
    fun onTimeChosen(key: String, minutesOfDay: Int) = applySettingUpdate(key, minutesOfDay)

    fun onAction(key: String) {
        when (key) {
            SettingKeys.CLEAR_CACHE -> {
                pendingConfirmAction = PendingConfirmAction.CLEAR_CACHE
                val formatted = formatBytes(_storageSizes.value.cacheBytes)
                _confirm.value = ConfirmRequest(
                    title = text.clearCacheDialogTitle,
                    message = text.clearCacheDialogMessage(formatted),
                    confirmLabel = text.clearCacheConfirmLabel,
                    isDanger = false
                )
            }
            SettingKeys.RESET_ALL -> {
                pendingConfirmAction = PendingConfirmAction.RESET_ALL
                _confirm.value = ConfirmRequest(
                    title = text.resetAllDialogTitle,
                    message = text.resetAllDialogMessage,
                    confirmLabel = text.resetAllConfirmLabel,
                    isDanger = true
                )
            }
            else -> {
                // All other unhandled actions do nothing
            }
        }
    }

    fun onConfirm() {
        val action = pendingConfirmAction
        pendingConfirmAction = null
        _confirm.value = null
        when (action) {
            PendingConfirmAction.CLEAR_CACHE -> {
                viewModelScope.launch {
                    try {
                        val freed = withContext(Dispatchers.IO) { novelRepository.clearCache() }
                        refreshStorageSizes()
                        _message.value = text.freedMessage(formatBytes(freed))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        _message.value = e.message ?: "Failed to clear cache"
                    }
                }
            }
            PendingConfirmAction.RESET_ALL -> {
                viewModelScope.launch {
                    try {
                        settingsRepository.resetAllSettings()
                        _message.value = text.resetSuccessMessage
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        _message.value = e.message ?: "Failed to reset settings"
                    }
                }
            }
            null -> {}
        }
    }

    fun onDismissConfirm() {
        pendingConfirmAction = null
        _confirm.value = null
    }

    fun onPicked(key: String, uri: Uri) {
        if (key == SettingKeys.DOWNLOAD_FOLDER) {
            viewModelScope.launch {
                try {
                    val doc = DocumentFile.fromTreeUri(appContext, uri)
                    val displayName = doc?.name ?: uri.lastPathSegment ?: "Custom Folder"
                    val ok = settingsRepository.setCustomDownloadLocation(uri, displayName)
                    if (ok) {
                        _message.value = text.downloadFolderUpdated(displayName)
                    } else {
                        _message.value = text.downloadFolderError
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _message.value = e.message ?: text.downloadFolderError
                }
            }
        }
    }

    fun onMessageShown() {
        _message.value = null
    }

    companion object {
        const val KEY_SEARCH_QUERY = "search_query"
        const val KEY_OPEN_CATEGORY = "open_category_id"
    }
}
