package com.example.ui.screens.profile

import android.net.Uri
import androidx.compose.runtime.Immutable

enum class SettingsGroup {
    GENERAL,
    READING_LIFE,
    DATA_INFO
}

enum class PickerKind {
    NONE,
    FOLDER,
    BACKUP_FILE
}

object SettingsCategoryIds {
    const val APPEARANCE = "appearance"
    const val READER = "reader"
    const val LIBRARY = "library"
    const val DOWNLOADS = "downloads"
    const val CULTIVATION = "cultivation"
    const val BACKUP = "backup"
    const val ABOUT = "about"
}

@Immutable
data class ChoiceOption(
    val id: String,
    val label: String
)

@Immutable
data class SwatchOption(
    val id: String,
    val label: String,
    val color: Long,
    val accent: Long? = null
)

@Immutable
sealed interface SettingRow {
    val key: String
    val title: String
    val subtitle: String?
    val enabled: Boolean
}

@Immutable
data class ToggleRow(
    override val key: String,
    override val title: String,
    override val subtitle: String? = null,
    val checked: Boolean,
    override val enabled: Boolean = true
) : SettingRow

@Immutable
data class SegmentedRow(
    override val key: String,
    override val title: String,
    override val subtitle: String? = null,
    val options: List<ChoiceOption>,
    val selectedId: String,
    override val enabled: Boolean = true
) : SettingRow

@Immutable
data class ChipsRow(
    override val key: String,
    override val title: String,
    override val subtitle: String? = null,
    val options: List<ChoiceOption>,
    val selectedId: String,
    override val enabled: Boolean = true
) : SettingRow

@Immutable
data class SliderRow(
    override val key: String,
    override val title: String,
    override val subtitle: String? = null,
    val value: Float,
    val min: Float,
    val max: Float,
    val step: Float = 1f,
    val valueLabel: String? = null,
    override val enabled: Boolean = true
) : SettingRow

@Immutable
data class SwatchRow(
    override val key: String,
    override val title: String,
    override val subtitle: String? = null,
    val options: List<SwatchOption>,
    val selectedId: String,
    override val enabled: Boolean = true
) : SettingRow

@Immutable
data class TimeRow(
    override val key: String,
    override val title: String,
    override val subtitle: String? = null,
    val minutesOfDay: Int,
    override val enabled: Boolean = true
) : SettingRow

@Immutable
data class StorageRow(
    override val key: String,
    override val title: String,
    override val subtitle: String? = null,
    val chaptersBytes: Long = 0L,
    val coversBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    override val enabled: Boolean = true
) : SettingRow

@Immutable
data class TextPreviewRow(
    override val key: String,
    override val title: String,
    override val subtitle: String? = null,
    val sample: String,
    val fontSizeSp: Int,
    val lineSpacing: Float,
    val justified: Boolean,
    override val enabled: Boolean = true
) : SettingRow

@Immutable
data class ActionRow(
    override val key: String,
    override val title: String,
    override val subtitle: String? = null,
    val trailing: String? = null,
    val isDanger: Boolean = false,
    val picker: PickerKind = PickerKind.NONE,
    override val enabled: Boolean = true
) : SettingRow

@Immutable
data class SettingsCategory(
    val id: String,
    val group: SettingsGroup,
    val title: String,
    val summary: String,
    val rows: List<SettingRow>
)

@Immutable
data class ProfileHeader(
    val displayName: String = "",
    val realmLabel: String = "",
    val realmProgress: Float = 0f
)

@Immutable
data class BackupOption(
    val id: String,
    val label: String,
    val description: String?,
    val checked: Boolean,
    val enabled: Boolean = true
)

@Immutable
data class BackupSheetState(
    val options: List<BackupOption>,
    val suggestedFileName: String,
    val isWorking: Boolean = false,
    val progress: String? = null
)

@Immutable
data class RestoreSheetState(
    val fileName: String,
    val createdAtLabel: String,
    val appVersion: String,
    val summary: String,
    val options: List<BackupOption>,
    val mode: com.example.data.backup.RestoreMode,
    val isWorking: Boolean = false,
    val progress: String? = null,
    val error: String? = null
)

@Immutable
data class ConfirmRequest(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val isDanger: Boolean = false
)

@Immutable
data class ProfileUiState(
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val openCategoryId: String? = null,
    val header: ProfileHeader = ProfileHeader(),
    val categories: List<SettingsCategory> = emptyList(),
    val appVersion: String = "",
    val message: String? = null,
    val confirm: ConfirmRequest? = null,
    val backupSheet: BackupSheetState? = null,
    val restoreSheet: RestoreSheetState? = null
)

@Immutable
data class ProfileActions(
    val onOpenCategory: (categoryId: String) -> Unit,
    val onCloseCategory: () -> Unit,
    val onSearchChange: (query: String) -> Unit,
    val onSaveName: (name: String) -> Unit,
    val onAction: (key: String) -> Unit,
    val onPicked: (key: String, uri: Uri) -> Unit,
    val onToggle: (key: String, value: Boolean) -> Unit,
    val onSelect: (key: String, optionId: String) -> Unit,
    val onSlide: (key: String, value: Float) -> Unit,
    val onTimeChosen: (key: String, minutesOfDay: Int) -> Unit,
    val onConfirm: () -> Unit,
    val onDismissConfirm: () -> Unit,
    val onMessageShown: () -> Unit,
    val onBackupOptionToggle: (id: String) -> Unit,
    val onBackupDismiss: () -> Unit,
    val onBackupStart: (uri: Uri) -> Unit,
    val onRestoreOptionToggle: (id: String) -> Unit,
    val onRestoreModeChange: (mode: com.example.data.backup.RestoreMode) -> Unit,
    val onRestoreDismiss: () -> Unit,
    val onRestoreStart: () -> Unit
) {
    companion object {
        val NoOp = ProfileActions(
            onOpenCategory = {},
            onCloseCategory = {},
            onSearchChange = {},
            onSaveName = {},
            onAction = {},
            onPicked = { _, _ -> },
            onToggle = { _, _ -> },
            onSelect = { _, _ -> },
            onSlide = { _, _ -> },
            onTimeChosen = { _, _ -> },
            onConfirm = {},
            onDismissConfirm = {},
            onMessageShown = {},
            onBackupOptionToggle = {},
            onBackupDismiss = {},
            onBackupStart = {},
            onRestoreOptionToggle = {},
            onRestoreModeChange = {},
            onRestoreDismiss = {},
            onRestoreStart = {}
        )
    }
}

object SettingKeys {
    const val OPEN_TAB_PREFIX = "open_tab_"
    const val THEME = "theme"
    const val MATCH_SYSTEM = "match_system"
    const val ACCENT = "accent"
    const val BAR_SLOT_2 = "bar_slot_2"
    const val BAR_SLOT_3 = "bar_slot_3"
    const val READER_PREVIEW = "reader_preview"
    const val READER_FONT = "reader_font"
    const val READER_SIZE = "reader_size"
    const val READER_SPACING = "reader_spacing"
    const val READER_JUSTIFY = "reader_justify"
    const val READER_MODE = "reader_mode"
    const val KEEP_SCREEN_ON = "keep_screen_on"
    const val VOLUME_KEYS = "volume_keys"
    const val UPDATE_ON_LAUNCH = "update_on_launch"
    const val UNREAD_BADGES = "unread_badges"
    const val DEFAULT_SHELF = "default_shelf"
    const val AUTO_DOWNLOAD = "auto_download"
    const val DELETE_AFTER_READING = "delete_after_reading"
    const val WIFI_ONLY = "wifi_only"
    const val THROTTLING = "throttling"
    const val THROTTLE_DELAY = "throttle_delay"
    const val DOWNLOAD_FOLDER = "download_folder"
    const val STORAGE = "storage"
    const val CLEAR_CACHE = "clear_cache"
    const val QI_REWARDS = "qi_rewards"
    const val BREAKTHROUGH_FX = "breakthrough_fx"
    const val HAPTICS = "haptics"
    const val REDUCE_MOTION = "reduce_motion"
    const val DAILY_GOAL = "daily_goal"
    const val REMINDER = "reminder"
    const val REMINDER_TIME = "reminder_time"
    const val CREATE_BACKUP = "create_backup"
    const val RESTORE_BACKUP = "restore_backup"
    const val AUTO_BACKUP = "auto_backup"
    const val CHECK_UPDATES = "check_updates"
    const val LICENCES = "licences"
    const val PRIVACY = "privacy"
    const val RESET_ALL = "reset_all"
}
