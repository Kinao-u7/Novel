package com.example.data.source.webscrape

data class ScrapedChapter(
    val title: String,
    val url: String,
    val order: Int
)

data class ScrapedNovelData(
    val title: String,
    val author: String,
    val coverUrl: String?,
    val description: String,
    val genres: List<String>,
    val chapters: List<ScrapedChapter>,
    val sourceUrl: String
)
