package com.example.ui.screens.discover

import com.example.data.model.DiscoverSettings
import com.example.data.source.NovelSource

/**
 * Checks whether the source exposes an adult content flag, safely via reflection.
 */
fun sourceIsAdult(source: NovelSource): Boolean {
    return try {
        val method = source.javaClass.methods.firstOrNull {
            it.name == "isAdult" || it.name == "getIsAdult"
        }
        if (method != null && (method.returnType == java.lang.Boolean.TYPE || method.returnType == java.lang.Boolean::class.java)) {
            (method.invoke(source) as? Boolean) ?: false
        } else {
            val field = source.javaClass.fields.firstOrNull { it.name == "isAdult" }
            (field?.get(source) as? Boolean) ?: false
        }
    } catch (_: Throwable) {
        false
    }
}

/**
 * Converts a [NovelSource] to a [SourceItem].
 */
fun NovelSource.toSourceItem(isPinned: Boolean): SourceItem {
    return SourceItem(
        id = id,
        name = name,
        language = language,
        version = version,
        iconUrl = null,
        isAdult = sourceIsAdult(this),
        isPinned = isPinned
    )
}

/**
 * Filters sources by enabled languages. An empty list means all languages are enabled.
 */
fun filterSourcesByLanguage(sources: List<NovelSource>, enabledLanguages: List<String>): List<NovelSource> {
    if (enabledLanguages.isEmpty()) return sources
    return sources.filter { source ->
        enabledLanguages.any { it.equals(source.language, ignoreCase = true) }
    }
}

/**
 * Filters sources by query (matching name or language case-insensitively).
 */
fun filterSourcesByQuery(sources: List<NovelSource>, query: String): List<NovelSource> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return sources
    val q = trimmed.lowercase()
    return sources.filter { source ->
        source.name.lowercase().contains(q) || source.language.lowercase().contains(q)
    }
}

/**
 * Builds the pinned source list in the exact order specified by [pinnedSourceIds].
 */
fun buildPinnedSources(sources: List<NovelSource>, pinnedSourceIds: List<String>): List<SourceItem> {
    if (pinnedSourceIds.isEmpty() || sources.isEmpty()) return emptyList()
    val sourcesById = sources.associateBy { it.id }
    return pinnedSourceIds.mapNotNull { id ->
        sourcesById[id]?.toSourceItem(isPinned = true)
    }
}

/**
 * Groups unpinned sources by language, sorted by language then source name.
 */
fun buildLanguageGroups(sources: List<NovelSource>, pinnedSourceIds: List<String>): List<LanguageGroup> {
    val pinnedSet = pinnedSourceIds.toSet()
    val unpinnedSources = sources.filter { it.id !in pinnedSet }
    if (unpinnedSources.isEmpty()) return emptyList()

    return unpinnedSources
        .groupBy { it.language }
        .entries
        .sortedBy { it.key.lowercase() }
        .map { (language, groupSources) ->
            LanguageGroup(
                language = language,
                sources = groupSources
                    .map { it.toSourceItem(isPinned = false) }
                    .sortedBy { it.name.lowercase() }
            )
        }
}

/**
 * Pure mapping function producing [DiscoverUiState] from inputs.
 */
fun mapToDiscoverUiState(
    sources: List<NovelSource>,
    discoverSettings: DiscoverSettings,
    query: String = "",
    isSearchOpen: Boolean = false,
    message: String? = null
): DiscoverUiState {
    val langFiltered = filterSourcesByLanguage(sources, discoverSettings.enabledLanguages)
    val queryFiltered = filterSourcesByQuery(langFiltered, query)

    val pinned = buildPinnedSources(queryFiltered, discoverSettings.pinnedSourceIds)
    val groups = buildLanguageGroups(queryFiltered, discoverSettings.pinnedSourceIds)

    return DiscoverUiState(
        tab = DiscoverTab.SOURCES,
        extensionsEnabled = false,
        isSearchOpen = isSearchOpen,
        query = query,
        pinned = pinned,
        sourceGroups = groups,
        message = message
    )
}
