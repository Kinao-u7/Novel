package com.example.data.changelog

/**
 * Represents a single release entry in the What's New changelog.
 *
 * To add a new version in the future:
 * Simply add a new ChangelogRelease at the top of [ChangelogRepository.releases].
 */
data class ChangelogRelease(
    val versionCode: Int,
    val versionName: String,
    val releaseDate: String,
    val title: String,
    val highlights: List<String>
)

object ChangelogRepository {
    /**
     * All app releases ordered newest first.
     */
    val releases: List<ChangelogRelease> = listOf(
        ChangelogRelease(
            versionCode = 2,
            versionName = "1.0.1",
            releaseDate = "September 2026",
            title = "Scraping, Reader & Storage Enhancements",
            highlights = listOf(
                "Instant Chapter Loading: Cached and downloaded chapters load immediately without network wait.",
                "Cleaner Metadata: Scraped novel titles and authors automatically sanitized of token artifacts.",
                "Accurate Novel Ratings: Community ratings are now preserved directly from web sources.",
                "Quick Copy: Long-press any title or author in Novel Details to copy to clipboard.",
                "Custom Backup Folder: Exports are saved directly to a 'Backups' subfolder in your chosen storage directory.",
                "Reader Header: Clear chapter title display in the top navigation bar.",
                "Automatic Versioning: Auto-incrementing build codes and custom named APK outputs."
            )
        ),
        ChangelogRelease(
            versionCode = 1,
            versionName = "1.0.0",
            releaseDate = "September 2026",
            title = "Initial Release",
            highlights = listOf(
                "Full-featured web novel reader with customizable themes, fonts, and reading modes.",
                "Browse and search web sources with chapter downloading and offline reading.",
                "Local EPUB novel importing with automatic chapter parsing.",
                "Library categorization, reading history tracking, bookmarks, and term replacement rules.",
                "Comprehensive backup and restore system."
            )
        )
    )

    fun getLatestRelease(): ChangelogRelease = releases.first()

    fun getReleaseForVersion(versionCode: Int): ChangelogRelease? =
        releases.firstOrNull { it.versionCode == versionCode } ?: releases.firstOrNull()
}
