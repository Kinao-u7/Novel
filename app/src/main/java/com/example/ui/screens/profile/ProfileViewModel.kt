package com.example.ui.screens.profile

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.backup.BackupCategory
import com.example.data.backup.BackupException
import com.example.data.backup.BackupManager
import com.example.data.backup.RestoreMode
import com.example.data.model.NavTab
import com.example.data.repository.NovelRepository
import com.example.data.repository.SettingsRepository
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val text: ProfileText = AndroidProfileText(appContext),
    private val backupManager: BackupManager = novelRepository.backupManager
) : ViewModel() {

    private val _storageSizes = MutableStateFlow(StorageSizes())
    private val _confirm = MutableStateFlow<ConfirmRequest?>(null)
    private val _message = MutableStateFlow<String?>(null)
    private val _backupSheet = MutableStateFlow<BackupSheetState?>(null)
    private val _restoreSheet = MutableStateFlow<RestoreSheetState?>(null)
    private var pendingRestoreUri: Uri? = null
    private var pendingRestoreManifestCategories: Set<String> = emptySet()

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
                confirm = null,
                backupSheet = null,
                restoreSheet = null
            )
        }

    private data class SheetsLocal(
        val confirm: ConfirmRequest?,
        val message: String?,
        val backupSheet: BackupSheetState?,
        val restoreSheet: RestoreSheetState?
    )

    private val sheetsFlow = combine(
        _confirm,
        _message,
        _backupSheet,
        _restoreSheet
    ) { confirm, message, backup, restore ->
        SheetsLocal(confirm, message, backup, restore)
    }

    private data class ProfileLocal(
        val query: String,
        val openId: String?,
        val sheets: SheetsLocal
    )

    private val localFlow = combine(
        searchQuery,
        openCategoryId,
        sheetsFlow
    ) { query, openId, sheets ->
        ProfileLocal(query, openId, sheets)
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
            message = local.sheets.message,
            confirm = local.sheets.confirm,
            backupSheet = local.sheets.backupSheet,
            restoreSheet = local.sheets.restoreSheet
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
            SettingKeys.CREATE_BACKUP -> {
                if (_backupSheet.value?.isWorking == true || _restoreSheet.value?.isWorking == true) return
                val options = listOf(
                    BackupOption(
                        id = BackupCategory.LIBRARY.name,
                        label = text.backupCatLibrary,
                        description = text.backupCatLibrarySub,
                        checked = true,
                        enabled = true
                    ),
                    BackupOption(
                        id = BackupCategory.BOOKMARKS.name,
                        label = text.backupCatBookmarks,
                        description = text.backupCatBookmarksSub,
                        checked = true,
                        enabled = true
                    ),
                    BackupOption(
                        id = BackupCategory.COVERS.name,
                        label = text.backupCatCovers,
                        description = text.backupCatCoversSub,
                        checked = true,
                        enabled = true
                    ),
                    BackupOption(
                        id = BackupCategory.EPUB_FILES.name,
                        label = text.backupCatEpubs,
                        description = "Original files, can be large",
                        checked = true,
                        enabled = true
                    ),
                    BackupOption(
                        id = BackupCategory.STATS.name,
                        label = text.backupCatStats,
                        description = text.backupCatStatsSub,
                        checked = true,
                        enabled = true
                    ),
                    BackupOption(
                        id = BackupCategory.TERM_REPLACEMENTS.name,
                        label = text.backupCatTerms,
                        description = text.backupCatTermsSub,
                        checked = true,
                        enabled = true
                    ),
                    BackupOption(
                        id = BackupCategory.CUSTOM_SOURCES.name,
                        label = text.backupCatSources,
                        description = text.backupCatSourcesSub,
                        checked = true,
                        enabled = true
                    ),
                    BackupOption(
                        id = BackupCategory.SETTINGS.name,
                        label = text.backupCatSettings,
                        description = text.backupCatSettingsSub,
                        checked = true,
                        enabled = true
                    )
                )
                _backupSheet.value = BackupSheetState(
                    options = options,
                    suggestedFileName = backupManager.suggestedFileName(),
                    isWorking = false,
                    progress = null
                )
            }
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

    fun onBackupOptionToggle(id: String) {
        val current = _backupSheet.value ?: return
        if (current.isWorking) return
        val target = current.options.firstOrNull { it.id == id } ?: return
        if (!target.enabled) return

        val newChecked = !target.checked
        val updated = current.options.map { opt ->
            if (opt.id == id) opt.copy(checked = newChecked) else opt
        }

        val finalOptions = if (id == BackupCategory.LIBRARY.name) {
            val libChecked = newChecked
            updated.map { opt ->
                val cat = try { BackupCategory.valueOf(opt.id) } catch (_: Exception) { null }
                if (cat != null && BackupCategory.LIBRARY in cat.dependsOn()) {
                    if (libChecked) {
                        opt.copy(enabled = true, checked = true)
                    } else {
                        opt.copy(enabled = false, checked = false)
                    }
                } else {
                    opt
                }
            }
        } else {
            updated
        }

        _backupSheet.value = current.copy(options = finalOptions)
    }

    fun onBackupDismiss() {
        if (_backupSheet.value?.isWorking == true) return
        _backupSheet.value = null
    }

    fun onBackupStart(uri: Uri) {
        val current = _backupSheet.value ?: return
        if (current.isWorking || _restoreSheet.value?.isWorking == true) return

        _backupSheet.value = current.copy(isWorking = true, progress = "Preparing backup...")

        val selectedCategories = current.options
            .filter { it.checked }
            .mapNotNull { opt ->
                try { BackupCategory.valueOf(opt.id) } catch (_: Exception) { null }
            }
            .toSet()

        viewModelScope.launch(Dispatchers.IO) {
            var outputStream: OutputStream? = null
            try {
                outputStream = appContext.contentResolver.openOutputStream(uri, "w")
                    ?: throw IOException("Could not open destination file")

                val summary = backupManager.createBackup(
                    output = outputStream,
                    categories = selectedCategories,
                    onProgress = { p ->
                        _backupSheet.value = _backupSheet.value?.copy(progress = p.label)
                    }
                )

                settingsRepository.updateBackupSettings {
                    it.copy(lastBackupAtMillis = System.currentTimeMillis())
                }

                _backupSheet.value = null
                _message.value = "Backup saved: ${summary.novels} novels"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                try {
                    outputStream?.close()
                } catch (_: Exception) {}
                try {
                    DocumentFile.fromSingleUri(appContext, uri)?.delete()
                } catch (_: Exception) {}

                _backupSheet.value = _backupSheet.value?.copy(isWorking = false, progress = null)
                _message.value = e.message ?: "Failed to create backup"
            } finally {
                try {
                    outputStream?.close()
                } catch (_: Exception) {}
            }
        }
    }

    fun onRestoreOptionToggle(id: String) {
        val current = _restoreSheet.value ?: return
        if (current.isWorking) return
        val target = current.options.firstOrNull { it.id == id } ?: return
        if (!target.enabled) return

        val newChecked = !target.checked
        val updated = current.options.map { opt ->
            if (opt.id == id) opt.copy(checked = newChecked) else opt
        }

        val finalOptions = if (id == BackupCategory.LIBRARY.name) {
            val libChecked = newChecked
            updated.map { opt ->
                val cat = try { BackupCategory.valueOf(opt.id) } catch (_: Exception) { null }
                if (cat != null && BackupCategory.LIBRARY in cat.dependsOn()) {
                    val presentInManifest = opt.id in pendingRestoreManifestCategories
                    if (libChecked && presentInManifest) {
                        opt.copy(enabled = true, checked = true)
                    } else {
                        opt.copy(enabled = false, checked = false)
                    }
                } else {
                    opt
                }
            }
        } else {
            updated
        }

        _restoreSheet.value = current.copy(options = finalOptions)
    }

    fun onRestoreModeChange(mode: RestoreMode) {
        val current = _restoreSheet.value ?: return
        if (current.isWorking) return
        _restoreSheet.value = current.copy(mode = mode)
    }

    fun onRestoreDismiss() {
        if (_restoreSheet.value?.isWorking == true) return
        _restoreSheet.value = null
        pendingRestoreUri = null
    }

    fun onRestoreStart() {
        val current = _restoreSheet.value ?: return
        val uri = pendingRestoreUri ?: return
        if (current.isWorking || _backupSheet.value?.isWorking == true) return

        _restoreSheet.value = current.copy(isWorking = true, progress = "Preparing restore...", error = null)

        val selectedCategories = current.options
            .filter { it.checked }
            .mapNotNull { opt ->
                try { BackupCategory.valueOf(opt.id) } catch (_: Exception) { null }
            }
            .toSet()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val summary = backupManager.restore(
                    openInput = {
                        appContext.contentResolver.openInputStream(uri)
                            ?: throw IOException("Could not open backup file")
                    },
                    categories = selectedCategories,
                    mode = current.mode,
                    onProgress = { p ->
                        _restoreSheet.value = _restoreSheet.value?.copy(progress = p.label)
                    }
                )

                _restoreSheet.value = null
                pendingRestoreUri = null

                val warningSuffix = if (summary.warnings.isNotEmpty()) " (${summary.warnings.size} warnings)" else ""
                _message.value = "Restored: ${summary.novelsAdded} added, ${summary.novelsUpdated} updated$warningSuffix"
                refreshStorageSizes()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _restoreSheet.value = _restoreSheet.value?.copy(
                    isWorking = false,
                    progress = null,
                    error = mapBackupException(e)
                )
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
        } else if (key == SettingKeys.RESTORE_BACKUP) {
            if (_backupSheet.value?.isWorking == true || _restoreSheet.value?.isWorking == true) return

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val inputStream = appContext.contentResolver.openInputStream(uri)
                        ?: throw IOException("Could not open backup file")

                    val manifest = inputStream.use {
                        backupManager.readManifest(it)
                    }

                    val doc = DocumentFile.fromSingleUri(appContext, uri)
                    val fileName = doc?.name ?: uri.lastPathSegment ?: "backup.zip"
                    val dateStr = if (manifest.createdAtMillis > 0L) {
                        SimpleDateFormat("MMM d, yyyy, HH:mm", Locale.US).format(Date(manifest.createdAtMillis))
                    } else "Unknown date"
                    val createdAtLabel = "Backup from $dateStr"
                    val novelsCount = manifest.counts["novels"] ?: 0
                    val chaptersCount = manifest.counts["chapters"] ?: 0
                    val summaryLine = "$novelsCount novels · $chaptersCount chapters"

                    val manifestCats = manifest.categories.toSet()
                    pendingRestoreUri = uri
                    pendingRestoreManifestCategories = manifestCats

                    val options = listOf(
                        BackupCategory.LIBRARY to (text.backupCatLibrary to text.backupCatLibrarySub),
                        BackupCategory.BOOKMARKS to (text.backupCatBookmarks to text.backupCatBookmarksSub),
                        BackupCategory.COVERS to (text.backupCatCovers to text.backupCatCoversSub),
                        BackupCategory.EPUB_FILES to (text.backupCatEpubs to "Original files, can be large"),
                        BackupCategory.STATS to (text.backupCatStats to text.backupCatStatsSub),
                        BackupCategory.TERM_REPLACEMENTS to (text.backupCatTerms to text.backupCatTermsSub),
                        BackupCategory.CUSTOM_SOURCES to (text.backupCatSources to text.backupCatSourcesSub),
                        BackupCategory.SETTINGS to (text.backupCatSettings to text.backupCatSettingsSub)
                    ).map { (cat, labels) ->
                        val present = cat.name in manifestCats
                        BackupOption(
                            id = cat.name,
                            label = labels.first,
                            description = labels.second,
                            checked = present,
                            enabled = present
                        )
                    }

                    _restoreSheet.value = RestoreSheetState(
                        fileName = fileName,
                        createdAtLabel = createdAtLabel,
                        appVersion = manifest.appVersion,
                        summary = summaryLine,
                        options = options,
                        mode = RestoreMode.MERGE,
                        isWorking = false,
                        progress = null,
                        error = null
                    )
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _message.value = mapBackupException(e)
                }
            }
        }
    }

    private fun mapBackupException(e: Throwable): String = when (e) {
        is BackupException.NotABackup -> "Not a backup file"
        is BackupException.TooNew -> "This backup is from a newer version of the app"
        is BackupException.Corrupt -> "The file is damaged"
        is BackupException.TooLarge -> "Backup exceeds maximum allowable size"
        is BackupException.NotEnoughSpace -> "Insufficient storage space for restore"
        else -> e.message ?: "Failed to process backup file"
    }

    fun onMessageShown() {
        _message.value = null
    }

    companion object {
        const val KEY_SEARCH_QUERY = "search_query"
        const val KEY_OPEN_CATEGORY = "open_category_id"
    }
}
