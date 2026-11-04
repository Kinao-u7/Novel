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
    val confirm: ConfirmRequest? = null
)

@Immutable
data class ProfileActions(
    val onOpenCategory: (categoryId: String) -> Unit = {},
    val onCloseCategory: () -> Unit = {},
    val onSearchChange: (query: String) -> Unit = {},
    val onSaveName: (name: String) -> Unit = {},
    val onAction: (key: String) -> Unit = {},
    val onPicked: (key: String, uri: Uri) -> Unit = { _, _ -> },
    val onToggle: (key: String, value: Boolean) -> Unit = { _, _ -> },
    val onSelect: (key: String, optionId: String) -> Unit = { _, _ -> },
    val onSlide: (key: String, value: Float) -> Unit = { _, _ -> },
    val onTimeChosen: (key: String, minutesOfDay: Int) -> Unit = { _, _ -> },
    val onConfirm: () -> Unit = {},
    val onDismissConfirm: () -> Unit = {},
    val onMessageShown: () -> Unit = {}
) {
    companion object {
        val NoOp = ProfileActions()
    }
}
