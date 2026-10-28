package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object Library

@Serializable
data object Discover

@Serializable
data object Cultivate

@Serializable
data object Downloads

@Serializable
data class Profile(
    val subPage: String? = null
)

@Serializable
data class NovelDetail(
    val novelId: String
)

@Serializable
data class Reader(
    val novelId: String,
    val chapterIndex: Int
)

@Serializable
data class BrowseSource(
    val sourceId: String
)

@Serializable
data class WebBrowser(
    val initialUrl: String? = null
)
