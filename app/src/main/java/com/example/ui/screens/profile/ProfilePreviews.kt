package com.example.ui.screens.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.data.model.AppThemeMode
import com.example.ui.theme.XianTheme

private val previewState = ProfileUiState(
    isLoading = false,
    header = ProfileHeader("Lin Yue", "Qi Condensation · Layer 5 of 9", 0.56f),
    appVersion = "1.0.0 (build 42)",
    categories = listOf(
        SettingsCategory(
            SettingsCategoryIds.APPEARANCE, SettingsGroup.GENERAL, "Appearance", "Ink · Jade accent",
            listOf(
                ToggleRow("match_system", "Match system theme", "Switch between light and dark automatically", checked = false),
                SwatchRow(
                    "theme", "Theme",
                    options = listOf(
                        SwatchOption("ink", "Ink", 0xFF0A1214, 0xFF7CF2C3),
                        SwatchOption("paper", "Paper", 0xFFF6F3EC, 0xFF1F7A62)
                    ),
                    selectedId = "ink"
                )
            )
        ),
        SettingsCategory(
            SettingsCategoryIds.READER, SettingsGroup.GENERAL, "Reader", "Literata · 19 px",
            listOf(
                TextPreviewRow("reader_preview", "Preview", sample = "The lantern had been burning for nine hundred years, and no one on Cold Mountain could say who had lit it.", fontSizeSp = 19, lineSpacing = 1.75f, justified = false),
                SliderRow("reader_size", "Text size", value = 19f, min = 15f, max = 28f, step = 1f, valueLabel = "19 px"),
                SegmentedRow("reader_mode", "Reading mode", options = listOf(ChoiceOption("scroll", "Scroll"), ChoiceOption("pages", "Pages")), selectedId = "scroll")
            )
        ),
        SettingsCategory(
            SettingsCategoryIds.DOWNLOADS, SettingsGroup.READING_LIFE, "Downloads & storage", "175 MB used",
            listOf(
                StorageRow("storage", "Storage", chaptersBytes = 96L shl 20, coversBytes = 31L shl 20, cacheBytes = 48L shl 20),
                ActionRow("clear_cache", "Clear cache", trailing = "48 MB")
            )
        ),
        SettingsCategory(
            SettingsCategoryIds.ABOUT, SettingsGroup.DATA_INFO, "About", "Updates, licences, privacy",
            listOf(ActionRow("reset_all", "Reset all settings", isDanger = true))
        )
    )
)

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun PreviewInk() = XianTheme(themeMode = AppThemeMode.DARK, accentHex = "#7CF2C3") {
    ProfileScreen(previewState, ProfileActions.NoOp)
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun PreviewPaper() = XianTheme(themeMode = AppThemeMode.SEPIA, accentHex = "#1F7A62") {
    ProfileScreen(previewState, ProfileActions.NoOp)
}
