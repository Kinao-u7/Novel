package com.example.ui.screens.discover

import com.example.data.source.NovelSource
import com.example.data.source.plugin.InstalledPlugin
import com.example.data.source.plugin.PluginCatalogueEntry
import com.example.data.source.plugin.comparePluginVersions

internal data class ExtensionLists(
    val updates: List<ExtensionItem>,
    val installed: List<ExtensionItem>,
    val available: List<ExtensionItem>,
    val languages: List<LanguageChoice>
)

internal fun mapExtensionsState(
    sources: List<NovelSource>,
    installedPlugins: List<InstalledPlugin>,
    catalogueEntries: List<PluginCatalogueEntry>,
    enabledLanguages: List<String>,
    query: String,
    busyIds: Set<String>
): ExtensionLists {
    val catalogueById = catalogueEntries.associateBy { it.id }
    val installedById = installedPlugins.associateBy { it.id }

    val updates = mutableListOf<ExtensionItem>()
    val installed = mutableListOf<ExtensionItem>()
    for (plugin in installedPlugins) {
        val cat = catalogueById[plugin.id]
        val hasUpdate = cat != null && comparePluginVersions(cat.version, plugin.version) > 0
        if (!matchesLanguageFilter(plugin.language, enabledLanguages)) continue
        if (!matchesSearchQuery(plugin.name, plugin.language, query)) continue
        val progress = if (plugin.id in busyIds) -1f else null
        if (hasUpdate && cat != null) {
            updates += ExtensionItem(
                id = plugin.id, name = plugin.name, language = plugin.language,
                installedVersion = plugin.version, availableVersion = cat.version,
                iconUrl = cat.iconUrl ?: plugin.iconUrl,
                status = ExtensionStatus.UPDATE_AVAILABLE, progress = progress
            )
        } else {
            installed += ExtensionItem(
                id = plugin.id, name = plugin.name, language = plugin.language,
                installedVersion = plugin.version, availableVersion = plugin.version,
                iconUrl = plugin.iconUrl, status = ExtensionStatus.INSTALLED, progress = progress
            )
        }
    }

    val available = catalogueEntries
        .filter { it.id !in installedById }
        .filter { matchesLanguageFilter(it.lang, enabledLanguages) && matchesSearchQuery(it.name, it.lang, query) }
        .map { cat ->
            ExtensionItem(
                id = cat.id, name = cat.name, language = cat.lang,
                installedVersion = null, availableVersion = cat.version,
                iconUrl = cat.iconUrl, status = ExtensionStatus.AVAILABLE,
                progress = if (cat.id in busyIds) -1f else null
            )
        }

    val allLangs = (sources.map { it.language.uppercase() } +
        installedPlugins.map { it.language.uppercase() } +
        catalogueEntries.map { it.lang.uppercase() })
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
    val languageChoices = allLangs.map { lang ->
        LanguageChoice(lang, enabledLanguages.any { it.equals(lang, ignoreCase = true) })
    }

    return ExtensionLists(
        updates = updates.sortedBy { it.name.lowercase() },
        installed = installed.sortedBy { it.name.lowercase() },
        available = available.sortedBy { it.name.lowercase() },
        languages = languageChoices
    )
}
