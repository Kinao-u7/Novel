package com.example.data.source

import com.example.data.model.CustomNovelSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object SourceManager {
    private val webNovelSource = WebNovelSource()
    private val classicsSource = ClassicsSource()

    private val _sources = MutableStateFlow<List<NovelSource>>(
        listOf(webNovelSource, classicsSource)
    )
    val sources: StateFlow<List<NovelSource>> = _sources.asStateFlow()

    private val _customSources = MutableStateFlow<List<CustomNovelSource>>(emptyList())
    val customSources: StateFlow<List<CustomNovelSource>> = _customSources.asStateFlow()

    private val _activeSourceId = MutableStateFlow(webNovelSource.id)
    val activeSourceId: StateFlow<String> = _activeSourceId.asStateFlow()

    fun setCustomSources(list: List<CustomNovelSource>) {
        _customSources.value = list
    }

    fun getActiveSource(): NovelSource {
        val currentId = _activeSourceId.value
        return _sources.value.firstOrNull { it.id == currentId } ?: webNovelSource
    }

    fun setActiveSource(sourceId: String) {
        if (_sources.value.any { it.id == sourceId }) {
            _activeSourceId.value = sourceId
        }
    }

    fun getSourceById(id: String): NovelSource? {
        return _sources.value.firstOrNull { it.id == id }
    }

    fun registerSource(source: NovelSource) {
        // Keep default sources safe from accidental removal / overwrite
        if (source.id == webNovelSource.id || source.id == classicsSource.id) return
        _sources.update { list -> list.filterNot { it.id == source.id } + source }
    }

    fun unregisterSource(sourceId: String) {
        // Keep default sources safe from accidental removal
        if (sourceId == webNovelSource.id || sourceId == classicsSource.id) return
        _sources.update { list -> list.filterNot { it.id == sourceId } }
        if (_activeSourceId.value == sourceId) {
            _activeSourceId.value = webNovelSource.id
        }
    }
}
