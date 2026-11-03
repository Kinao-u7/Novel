package com.example.ui.screens.browse

import com.example.data.model.Novel

object BrowseMapping {

    fun toBrowseNovelItem(novel: Novel, rank: Int? = null): BrowseNovelItem {
        return BrowseNovelItem(
            id = novel.id,
            title = novel.title,
            author = novel.author,
            rating = novel.rating,
            coverUrl = novel.coverUrl,
            rank = rank
        )
    }

    fun mapNovels(
        novels: List<Novel>,
        mode: BrowseMode,
        startingRank: Int = 1
    ): List<BrowseNovelItem> {
        return novels.mapIndexed { index, novel ->
            toBrowseNovelItem(
                novel = novel,
                rank = if (mode == BrowseMode.POPULAR) startingRank + index else null
            )
        }
    }

    fun appendAndDedupe(
        current: List<BrowseNovelItem>,
        newItems: List<BrowseNovelItem>,
        mode: BrowseMode
    ): List<BrowseNovelItem> {
        val seenIds = current.map { it.id }.toSet()
        val filteredNew = newItems.filter { it.id !in seenIds }
        val combined = current + filteredNew
        return if (mode == BrowseMode.POPULAR) {
            combined.mapIndexed { index, item ->
                item.copy(rank = index + 1)
            }
        } else {
            combined.map { it.copy(rank = null) }
        }
    }

    fun hasMorePages(newItemsCount: Int): Boolean {
        return newItemsCount > 0
    }
}
