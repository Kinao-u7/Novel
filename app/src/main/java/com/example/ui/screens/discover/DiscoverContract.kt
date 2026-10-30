package com.example.ui.screens.discover

import androidx.compose.runtime.Immutable

enum class DiscoverTab { SOURCES, EXTENSIONS }

enum class ExtensionStatus { AVAILABLE, INSTALLED, UPDATE_AVAILABLE }

@Immutable
data class SourceItem(
    val id: String,
    val name: String,
    val language: String,
    val version: String,
    val iconUrl: String? = null,
    val isAdult: Boolean = false,
    val isPinned: Boolean = false,
)

@Immutable
data class ExtensionItem(
    val id: String,
    val name: String,
    val language: String,
    val installedVersion: String?,
    val availableVersion: String,
    val size: String? = null,
    val iconUrl: String? = null,
    val isAdult: Boolean = false,
    val status: ExtensionStatus = ExtensionStatus.AVAILABLE,
    val progress: Float? = null,
)

@Immutable
data class LanguageGroup(
    val language: String,
    val sources: List<SourceItem>,
)

@Immutable
data class LanguageChoice(
    val language: String,
    val selected: Boolean,
)

@Immutable
data class RepositoryItem(
    val url: String,
    val enabled: Boolean,
)

@Immutable
data class DiscoverUiState(
    val tab: DiscoverTab = DiscoverTab.SOURCES,
    val extensionsEnabled: Boolean = true,
    val isSearchOpen: Boolean = false,
    val query: String = "",
    val pinned: List<SourceItem> = emptyList(),
    val sourceGroups: List<LanguageGroup> = emptyList(),
    val updates: List<ExtensionItem> = emptyList(),
    val installed: List<ExtensionItem> = emptyList(),
    val available: List<ExtensionItem> = emptyList(),
    val languages: List<LanguageChoice> = emptyList(),
    val repositories: List<RepositoryItem> = emptyList(),
    val showSettings: Boolean = false,
    val repositoryError: String? = null,
    val pendingUninstall: ExtensionItem? = null,
    val message: String? = null,
) {
    val updateCount: Int get() = updates.size
}

@Immutable
data class DiscoverActions(
    val onMessageShown: () -> Unit = {},
    val onToggleSearch: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onQueryChange: (String) -> Unit = {},
    val onTabChange: (DiscoverTab) -> Unit = {},
    val onOpenSource: (String) -> Unit = {},
    val onTogglePin: (String) -> Unit = {},
    val onUpdateAll: () -> Unit = {},
    val onInstall: (String) -> Unit = {},
    val onUpdate: (String) -> Unit = {},
    val onRequestUninstall: (String) -> Unit = {},
    val onDismissSettings: () -> Unit = {},
    val onToggleLanguage: (String) -> Unit = {},
    val onToggleRepository: (String) -> Unit = {},
    val onRemoveRepository: (String) -> Unit = {},
    val onAddRepository: (String) -> Unit = {},
    val onConfirmUninstall: () -> Unit = {},
    val onDismissUninstall: () -> Unit = {},
) {
    companion object {
        val NoOp = DiscoverActions()
    }
}
