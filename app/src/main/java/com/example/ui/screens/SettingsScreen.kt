package com.example.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.data.model.*
import com.example.data.repository.NovelRepository
import com.example.data.source.NovelSource
import com.example.data.source.SourceManager
import com.example.ui.components.NovelCoverImage
import com.example.ui.theme.calculateOnColor
import com.example.ui.theme.parseColorHex
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class SettingsSubPage(val title: String) {
    HUB("Settings"),
    APPEARANCE("Appearance"),
    READER("Reader"),
    SOURCES("Sources"),
    DOWNLOADS("Downloads"),
    STORAGE("Storage & Cache"),
    NOTIFICATIONS("Notifications"),
    HISTORY("Reading History"),
    STATS("Reading Stats"),
    GENERAL("General")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: NovelRepository,
    initialSubPage: SettingsSubPage = SettingsSubPage.HUB,
    onNavigateToDownloads: () -> Unit = {},
    onReadChapter: (Novel, String, Int) -> Unit = { _, _, _ -> },
    onBack: () -> Unit = {}
) {
    var currentSubPage by remember { mutableStateOf(initialSubPage) }

    LaunchedEffect(initialSubPage) {
        currentSubPage = initialSubPage
    }

    // Handle back button when within a subpage
    BackHandler(enabled = currentSubPage != SettingsSubPage.HUB) {
        currentSubPage = SettingsSubPage.HUB
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentSubPage.title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = com.example.ui.theme.BookSerifFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                    )
                },
                navigationIcon = {
                    if (currentSubPage != SettingsSubPage.HUB) {
                        IconButton(onClick = { currentSubPage = SettingsSubPage.HUB }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentSubPage,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            label = "SettingsSubPageTransition"
        ) { page ->
            when (page) {
                SettingsSubPage.HUB -> SettingsHubPage(
                    repository = repository,
                    onNavigate = { currentSubPage = it }
                )
                SettingsSubPage.APPEARANCE -> AppearanceSettingsPage(repository = repository)
                SettingsSubPage.READER -> ReaderSettingsPage(repository = repository)
                SettingsSubPage.SOURCES -> SourcesSettingsPage()
                SettingsSubPage.DOWNLOADS -> DownloadSettingsPage(repository = repository)
                SettingsSubPage.STORAGE -> StorageSettingsPage(repository = repository)
                SettingsSubPage.NOTIFICATIONS -> NotificationsSettingsPage(repository = repository)
                SettingsSubPage.HISTORY -> ReadingHistoryPage(repository = repository, onReadChapter = onReadChapter)
                SettingsSubPage.STATS -> StatsScreen(repository = repository)
                SettingsSubPage.GENERAL -> GeneralSettingsPage(repository = repository)
            }
        }
    }
}

// =============================================================================
// SETTINGS HUB
// =============================================================================
@Composable
private fun SettingsHubPage(
    repository: NovelRepository,
    onNavigate: (SettingsSubPage) -> Unit
) {
    val themeSettings by repository.themeSettings.collectAsState()
    val readerSettings by repository.readerSettings.collectAsState()
    val downloadSettings by repository.downloadSettings.collectAsState()
    val notificationSettings by repository.notificationSettings.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            com.example.ui.components.EditorialSectionHeader(title = "Preferences & Customization")
        }

        item {
            Card(
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    HubItemRow(
                        icon = Icons.Default.Palette,
                        title = "Appearance",
                        subtitle = "Theme (${themeSettings.themeMode.name.lowercase().replaceFirstChar { it.uppercase() }}), Accent Color",
                        onClick = { onNavigate(SettingsSubPage.APPEARANCE) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    HubItemRow(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        title = "Reader",
                        subtitle = "Font, spacing, themes, live preview, TTS",
                        onClick = { onNavigate(SettingsSubPage.READER) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    HubItemRow(
                        icon = Icons.Default.Extension,
                        title = "Sources",
                        subtitle = "Manage active catalog sources and plugins",
                        onClick = { onNavigate(SettingsSubPage.SOURCES) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    HubItemRow(
                        icon = Icons.Default.CloudDownload,
                        title = "Downloads",
                        subtitle = "Wi-Fi only (${if (downloadSettings.wifiOnly) "Enabled" else "Disabled"}), Simultaneous (${downloadSettings.simultaneousDownloads})",
                        onClick = { onNavigate(SettingsSubPage.DOWNLOADS) }
                    )
                }
            }
        }

        item {
            com.example.ui.components.EditorialSectionHeader(title = "Data & Insights")
        }

        item {
            Card(
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    HubItemRow(
                        icon = Icons.Default.Storage,
                        title = "Storage & Cache",
                        subtitle = "Storage size, cache cleaning, delete downloaded chapters",
                        onClick = { onNavigate(SettingsSubPage.STORAGE) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    HubItemRow(
                        icon = Icons.Default.Notifications,
                        title = "Notifications",
                        subtitle = "Chapter updates, reminders (${if (notificationSettings.notifyOnNovelUpdate) "Active" else "Muted"})",
                        onClick = { onNavigate(SettingsSubPage.NOTIFICATIONS) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    HubItemRow(
                        icon = Icons.Default.History,
                        title = "Reading History",
                        subtitle = "View and resume recently read novels and chapters",
                        onClick = { onNavigate(SettingsSubPage.HISTORY) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    HubItemRow(
                        icon = Icons.Default.BarChart,
                        title = "Reading Stats",
                        subtitle = "Reading streaks, total words, reading velocity",
                        onClick = { onNavigate(SettingsSubPage.STATS) }
                    )
                }
            }
        }

        item {
            com.example.ui.components.EditorialSectionHeader(title = "Application")
        }

        item {
            Card(
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    HubItemRow(
                        icon = Icons.Default.Info,
                        title = "General",
                        subtitle = "About Novel Reader v${BuildConfig.VERSION_NAME}, Reset Preferences",
                        onClick = { onNavigate(SettingsSubPage.GENERAL) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HubItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = com.example.ui.theme.BookSerifFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

// =============================================================================
// 1. APPEARANCE SETTINGS PAGE
// =============================================================================
@Composable
private fun AppearanceSettingsPage(repository: NovelRepository) {
    val themeSettings by repository.themeSettings.collectAsState()
    var showCustomHexDialog by remember { mutableStateOf(false) }
    var customHexInput by remember { mutableStateOf(themeSettings.accentColorHex) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "App Theme Mode",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "Choose the base lighting scheme for the application UI",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(AppThemeMode.SYSTEM, AppThemeMode.LIGHT, AppThemeMode.DARK).forEach { mode ->
                        val isSelected = themeSettings.themeMode == mode
                        val labelText = if (mode == AppThemeMode.SYSTEM) "System" else mode.displayName
                        ElevatedFilterChip(
                            selected = isSelected,
                            onClick = {
                                repository.updateThemeSettings(themeSettings.copy(themeMode = mode))
                            },
                            label = {
                                Text(
                                    text = labelText,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )
                            },
                            leadingIcon = {
                                when (mode) {
                                    AppThemeMode.SYSTEM -> Icon(Icons.Default.BrightnessAuto, contentDescription = null, modifier = Modifier.size(16.dp))
                                    AppThemeMode.LIGHT -> Icon(Icons.Default.LightMode, contentDescription = null, modifier = Modifier.size(16.dp))
                                    AppThemeMode.DARK -> Icon(Icons.Default.DarkMode, contentDescription = null, modifier = Modifier.size(16.dp))
                                    else -> {}
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(AppThemeMode.AMOLED, AppThemeMode.SEPIA).forEach { mode ->
                        val isSelected = themeSettings.themeMode == mode
                        ElevatedFilterChip(
                            selected = isSelected,
                            onClick = {
                                repository.updateThemeSettings(themeSettings.copy(themeMode = mode))
                            },
                            label = {
                                Text(
                                    text = mode.displayName,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )
                            },
                            leadingIcon = {
                                when (mode) {
                                    AppThemeMode.AMOLED -> Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(16.dp))
                                    AppThemeMode.SEPIA -> Icon(Icons.Default.ColorLens, contentDescription = null, modifier = Modifier.size(16.dp))
                                    else -> {}
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Dynamic Colors (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Dynamic Colors (Material You)",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = "Derive theme accent colors from your Android system wallpaper",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = themeSettings.useDynamicColor,
                            onCheckedChange = { checked ->
                                repository.updateThemeSettings(themeSettings.copy(useDynamicColor = checked))
                            }
                        )
                    }
                }
            }
        }

        // Preset Accent Colors
        item {
            Text(
                text = "Accent Color",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = "Primary color for highlights, buttons, and badges",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PRESET_ACCENT_COLORS.take(7).forEach { preset ->
                    val color = parseColorHex(preset.hex)
                    val isSelected = !themeSettings.useDynamicColor && themeSettings.accentColorHex.equals(preset.hex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable {
                                repository.updateThemeSettings(
                                    themeSettings.copy(accentColorHex = preset.hex, useDynamicColor = false)
                                )
                            }
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = calculateOnColor(color),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Custom Hex Button
                IconButton(
                    onClick = {
                        customHexInput = themeSettings.accentColorHex
                        showCustomHexDialog = true
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Custom Color")
                }
            }
        }
    }

    if (showCustomHexDialog) {
        AlertDialog(
            onDismissRequest = { showCustomHexDialog = false },
            title = { Text("Custom Accent Color") },
            text = {
                Column {
                    Text("Enter a hex code (e.g. #6366F1):", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customHexInput,
                        onValueChange = { customHexInput = it },
                        singleLine = true,
                        placeholder = { Text("#6366F1") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (customHexInput.matches("^#([A-Fa-f0-9]{6})$".toRegex())) {
                            repository.updateThemeSettings(
                                themeSettings.copy(accentColorHex = customHexInput, useDynamicColor = false)
                            )
                            showCustomHexDialog = false
                        }
                    }
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomHexDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// =============================================================================
// 2. READER SETTINGS PAGE (With Live Preview, Typography, Rules, TTS)
// =============================================================================
@Composable
private fun ReaderSettingsPage(repository: NovelRepository) {
    val readerSettings by repository.readerSettings.collectAsState()
    val termReplacements by repository.termReplacements.collectAsState()
    val themeSettings by repository.themeSettings.collectAsState()
    val accentColor = parseColorHex(themeSettings.accentColorHex)
    val readerColors = getReaderColors(readerSettings.theme, accentColor)

    var showAddRuleDialog by remember { mutableStateOf(false) }
    var newRuleFind by remember { mutableStateOf("") }
    var newRuleReplace by remember { mutableStateOf("") }

    val currentFontFamily = when (readerSettings.fontType) {
        FontType.SERIF -> FontFamily.Serif
        FontType.SANS -> FontFamily.SansSerif
        FontType.MONOSPACE -> FontFamily.Monospace
        FontType.ROUNDED -> FontFamily.Default
    }

    val currentFontWeight = when (readerSettings.fontWeight) {
        ReaderFontWeight.NORMAL -> FontWeight.Normal
        ReaderFontWeight.MEDIUM -> FontWeight.Medium
        ReaderFontWeight.BOLD -> FontWeight.Bold
    }

    val currentTextAlign = when (readerSettings.textAlign) {
        ReaderTextAlign.LEFT -> TextAlign.Start
        ReaderTextAlign.JUSTIFIED -> TextAlign.Justify
        ReaderTextAlign.RIGHT -> TextAlign.End
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- LIVE PREVIEW BOX ---
        item {
            Text(
                text = "Live Reader Preview",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = readerColors.background,
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, readerColors.surface, RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = readerSettings.horizontalMarginDp.dp,
                            vertical = readerSettings.verticalMarginDp.dp
                        )
                ) {
                    Text(
                        text = "Chapter 42: The Starlight Library",
                        fontFamily = currentFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = (readerSettings.fontSizeSp + 3).sp,
                        color = readerColors.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    val sampleText = if (readerSettings.paragraphIndentation) {
                        "    The ancient grimoire radiated a faint luminescent glow upon the weathered oak table. Whispers of long-forgotten incantations echoed through the quiet archive halls."
                    } else {
                        "The ancient grimoire radiated a faint luminescent glow upon the weathered oak table. Whispers of long-forgotten incantations echoed through the quiet archive halls."
                    }

                    Text(
                        text = sampleText,
                        fontFamily = currentFontFamily,
                        fontWeight = currentFontWeight,
                        fontSize = readerSettings.fontSizeSp.sp,
                        lineHeight = (readerSettings.fontSizeSp * readerSettings.lineSpacingMultiplier).sp,
                        letterSpacing = readerSettings.letterSpacingSp.sp,
                        textAlign = currentTextAlign,
                        color = readerColors.text
                    )
                }
            }
        }

        // --- THEMES ---
        item {
            Text(
                text = "Reader Theme",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReaderTheme.entries.forEach { theme ->
                    val isSelected = readerSettings.theme == theme
                    val tColors = getReaderColors(theme, accentColor)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = tColors.background,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                repository.updateReaderSettings(readerSettings.copy(theme = theme))
                            }
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) accentColor else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(10.dp)
                            )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = when (theme) {
                                    ReaderTheme.LIGHT -> "Light"
                                    ReaderTheme.WARM -> "Warm"
                                    ReaderTheme.SEPIA -> "Sepia"
                                    ReaderTheme.DARK -> "Dark"
                                    ReaderTheme.AMOLED -> "AMOLED"
                                },
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = tColors.text
                            )
                        }
                    }
                }
            }
        }

        // --- TYPOGRAPHY & TEXT CONTROLS ---
        item {
            Text(
                text = "Typography & Spacing",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Font Family
                    Column {
                        Text("Font Family", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val allFonts = FontType.entries
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                allFonts.take(2).forEach { font ->
                                    FilterChip(
                                        selected = readerSettings.fontType == font,
                                        onClick = { repository.updateReaderSettings(readerSettings.copy(fontType = font)) },
                                        label = {
                                            Text(
                                                text = font.displayName,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.fillMaxWidth(),
                                                textAlign = TextAlign.Center
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                allFonts.drop(2).forEach { font ->
                                    FilterChip(
                                        selected = readerSettings.fontType == font,
                                        onClick = { repository.updateReaderSettings(readerSettings.copy(fontType = font)) },
                                        label = {
                                            Text(
                                                text = font.displayName,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.fillMaxWidth(),
                                                textAlign = TextAlign.Center
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Font Size
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Font Size", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("${readerSettings.fontSizeSp} sp", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Slider(
                            value = readerSettings.fontSizeSp.toFloat(),
                            onValueChange = { repository.updateReaderSettings(readerSettings.copy(fontSizeSp = it.toInt())) },
                            valueRange = 14f..32f,
                            steps = 17
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Font Weight
                    Column {
                        Text("Font Weight", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ReaderFontWeight.entries.forEach { weight ->
                                FilterChip(
                                    selected = readerSettings.fontWeight == weight,
                                    onClick = { repository.updateReaderSettings(readerSettings.copy(fontWeight = weight)) },
                                    label = {
                                        Text(
                                            text = weight.displayName,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Text Alignment
                    Column {
                        Text("Text Alignment", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ReaderTextAlign.entries.forEach { align ->
                                FilterChip(
                                    selected = readerSettings.textAlign == align,
                                    onClick = {
                                        repository.updateReaderSettings(
                                            readerSettings.copy(
                                                textAlign = align,
                                                isJustified = (align == ReaderTextAlign.JUSTIFIED)
                                            )
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = align.displayName,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Line Spacing
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Line Spacing", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text(String.format(Locale.US, "%.1fx", readerSettings.lineSpacingMultiplier), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Slider(
                            value = readerSettings.lineSpacingMultiplier,
                            onValueChange = { repository.updateReaderSettings(readerSettings.copy(lineSpacingMultiplier = it)) },
                            valueRange = 1.2f..2.4f,
                            steps = 11
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Paragraph Spacing
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Paragraph Spacing", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("${readerSettings.paragraphSpacingDp} dp", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Slider(
                            value = readerSettings.paragraphSpacingDp.toFloat(),
                            onValueChange = { repository.updateReaderSettings(readerSettings.copy(paragraphSpacingDp = it.toInt())) },
                            valueRange = 4f..28f,
                            steps = 11
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Margins
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Horizontal Margin", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("${readerSettings.horizontalMarginDp} dp", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Slider(
                            value = readerSettings.horizontalMarginDp.toFloat(),
                            onValueChange = { repository.updateReaderSettings(readerSettings.copy(horizontalMarginDp = it.toInt())) },
                            valueRange = 8f..40f,
                            steps = 15
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Paragraph Indentation Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("First-Line Indentation", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("Indent the first line of each paragraph", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = readerSettings.paragraphIndentation,
                            onCheckedChange = { repository.updateReaderSettings(readerSettings.copy(paragraphIndentation = it)) }
                        )
                    }
                }
            }
        }

        // --- READING BEHAVIOR & HARDWARE ---
        item {
            Text(
                text = "Reading Behavior & Screen",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Reading Mode
                    Column {
                        Text("Default Reading Mode", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ReadingMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = readerSettings.readingMode == mode,
                                    onClick = { repository.updateReaderSettings(readerSettings.copy(readingMode = mode)) },
                                    label = {
                                        Text(
                                            text = if (mode == ReadingMode.CONTINUOUS) "Continuous Scroll" else "Paginated",
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center,
                                            fontSize = 12.sp,
                                            maxLines = 1
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Keep Screen On
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Keep Screen On", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("Prevent display sleep while actively reading", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = readerSettings.keepScreenOn,
                            onCheckedChange = { repository.updateReaderSettings(readerSettings.copy(keepScreenOn = it)) }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Status Bar Insets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Show Mini Status Bar", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("Display clock & battery indicator at the top of reader", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = readerSettings.showStatusBar,
                            onCheckedChange = { repository.updateReaderSettings(readerSettings.copy(showStatusBar = it)) }
                        )
                    }
                }
            }
        }

        // --- TEXT-TO-SPEECH (TTS) ---
        item {
            Text(
                text = "Text-to-Speech (TTS)",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Default Speech Rate", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("${String.format(Locale.US, "%.2fx", readerSettings.ttsSpeechRate)}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Slider(
                            value = readerSettings.ttsSpeechRate,
                            onValueChange = { repository.updateReaderSettings(readerSettings.copy(ttsSpeechRate = it)) },
                            valueRange = 0.5f..2.5f,
                            steps = 7
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { rate ->
                                FilterChip(
                                    selected = (readerSettings.ttsSpeechRate - rate).let { Math.abs(it) < 0.05f },
                                    onClick = { repository.updateReaderSettings(readerSettings.copy(ttsSpeechRate = rate)) },
                                    label = {
                                        Text(
                                            text = "${rate}x",
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Speech Pitch", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("${String.format(Locale.US, "%.1fx", readerSettings.ttsPitch)}", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Slider(
                            value = readerSettings.ttsPitch,
                            onValueChange = { repository.updateReaderSettings(readerSettings.copy(ttsPitch = it)) },
                            valueRange = 0.5f..1.5f,
                            steps = 9
                        )
                    }
                }
            }
        }

        // --- TEXT REPLACEMENT RULES ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Text Replacement Rules",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Auto-replace repetitive OCR errors, name variants, or typos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showAddRuleDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Rule", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (termReplacements.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No active replacement rules. Tap + to add terms to auto-clean while reading.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(termReplacements, key = { it.id }) { rule ->
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "\"${rule.original}\" → \"${rule.replacement}\"",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }

                        Switch(
                            checked = rule.isEnabled,
                            onCheckedChange = { checked ->
                                repository.toggleTermReplacementRule(rule.id, checked)
                            }
                        )

                        IconButton(onClick = { repository.deleteTermReplacementRule(rule.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Rule", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    if (showAddRuleDialog) {
        AlertDialog(
            onDismissRequest = { showAddRuleDialog = false },
            title = { Text("Add Replacement Rule") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newRuleFind,
                        onValueChange = { newRuleFind = it },
                        label = { Text("Text to find") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newRuleReplace,
                        onValueChange = { newRuleReplace = it },
                        label = { Text("Replace with") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newRuleFind.isNotBlank()) {
                            repository.addTermReplacementRule(
                                TermReplacementRule(
                                    original = newRuleFind,
                                    replacement = newRuleReplace
                                )
                            )
                            newRuleFind = ""
                            newRuleReplace = ""
                            showAddRuleDialog = false
                        }
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddRuleDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// =============================================================================
// 3. SOURCES SETTINGS PAGE
// =============================================================================
@Composable
private fun SourcesSettingsPage() {
    val sources by SourceManager.sources.collectAsState()
    val activeSourceId by SourceManager.activeSourceId.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Novel Catalog Sources",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Select active scraper plugin to browse and download web novels",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(sources, key = { it.id }) { source ->
            val isActive = source.id == activeSourceId
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { SourceManager.setActiveSource(source.id) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = source.name,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            if (isActive) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Text(
                                        text = "ACTIVE",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${source.description} (${source.language.uppercase()})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    RadioButton(
                        selected = isActive,
                        onClick = { SourceManager.setActiveSource(source.id) }
                    )
                }
            }
        }
    }
}

// =============================================================================
// 4. DOWNLOADS SETTINGS PAGE
// =============================================================================
@Composable
private fun DownloadSettingsPage(repository: NovelRepository) {
    val downloadSettings by repository.downloadSettings.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Network & Data Usage",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Only Download on Wi-Fi", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("Prevent cellular data usage when downloading chapters", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = downloadSettings.wifiOnly,
                            onCheckedChange = { repository.updateDownloadSettings(downloadSettings.copy(wifiOnly = it)) }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Download Next Chapter", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("Silently prefetch next chapter while reading current one", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = downloadSettings.autoDownloadNext,
                            onCheckedChange = { repository.updateDownloadSettings(downloadSettings.copy(autoDownloadNext = it)) }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Simultaneous Downloads", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("${downloadSettings.simultaneousDownloads} threads", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Slider(
                            value = downloadSettings.simultaneousDownloads.toFloat(),
                            onValueChange = { repository.updateDownloadSettings(downloadSettings.copy(simultaneousDownloads = it.toInt())) },
                            valueRange = 1f..5f,
                            steps = 3
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================
// 5. STORAGE & CACHE SETTINGS PAGE
// =============================================================================
@Composable
private fun StorageSettingsPage(repository: NovelRepository) {
    val context = LocalContext.current
    val libraryNovels by repository.libraryNovels.collectAsState()
    var cacheSizeBytes by remember { mutableStateOf(repository.getCacheSizeBytes()) }
    var storageUsageBytes by remember { mutableStateOf(repository.getStorageUsageBytes()) }

    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var showDeleteAllDownloadsConfirm by remember { mutableStateOf(false) }
    var novelToDeleteDownloads by remember { mutableStateOf<Novel?>(null) }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024f * 1024f * 1024f))
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
            else -> "$bytes B"
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Storage Overview",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("App Storage Usage", style = MaterialTheme.typography.bodyMedium)
                        Text(formatBytes(storageUsageBytes), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Temporary Cache", style = MaterialTheme.typography.bodyMedium)
                            Text("Image thumbnails & API response cache", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(formatBytes(cacheSizeBytes), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }

        item {
            Text(
                text = "Cleanup Actions",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Clear Image & Web Cache", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("Safe. Does NOT delete downloaded offline chapters.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { showClearCacheConfirm = true }) {
                            Text("Clear")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Delete All Offline Downloads", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error))
                            Text("Permanently removes all saved chapter text files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = { showDeleteAllDownloadsConfirm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }

        // Per-novel download manager
        item {
            Text(
                text = "Downloaded Novels",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        val downloadedNovels = libraryNovels.filter { it.downloadedChaptersCount > 0 }
        if (downloadedNovels.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No downloaded chapters found. Chapters downloaded from the Downloads or Reader screen will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(downloadedNovels, key = { it.id }) { novel ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(novel.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${novel.downloadedChaptersCount} chapters stored offline", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { novelToDeleteDownloads = novel }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete downloads", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text("Clear Temporary Cache?") },
            text = { Text("This will clear temporary cover images and response data. Your library and downloaded chapters are safe.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val freed = repository.clearCache()
                        cacheSizeBytes = repository.getCacheSizeBytes()
                        storageUsageBytes = repository.getStorageUsageBytes()
                        showClearCacheConfirm = false
                        Toast.makeText(context, "Freed ${formatBytes(freed)}", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Clear Cache")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteAllDownloadsConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDownloadsConfirm = false },
            title = { Text("Delete All Offline Downloads?") },
            text = { Text("This will delete all saved chapter text files from your device. You will need internet connection to read them again.") },
            confirmButton = {
                Button(
                    onClick = {
                        repository.deleteAllDownloadedContent()
                        storageUsageBytes = repository.getStorageUsageBytes()
                        showDeleteAllDownloadsConfirm = false
                        Toast.makeText(context, "All downloads deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDownloadsConfirm = false }) { Text("Cancel") }
            }
        )
    }

    novelToDeleteDownloads?.let { novel ->
        AlertDialog(
            onDismissRequest = { novelToDeleteDownloads = null },
            title = { Text("Delete Downloads for ${novel.title}?") },
            text = { Text("Are you sure you want to delete all offline chapters for this novel?") },
            confirmButton = {
                Button(
                    onClick = {
                        repository.deleteNovelDownloads(novel.id)
                        storageUsageBytes = repository.getStorageUsageBytes()
                        novelToDeleteDownloads = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { novelToDeleteDownloads = null }) { Text("Cancel") }
            }
        )
    }
}

// =============================================================================
// 6. NOTIFICATIONS SETTINGS PAGE
// =============================================================================
@Composable
private fun NotificationsSettingsPage(repository: NovelRepository) {
    val notificationSettings by repository.notificationSettings.collectAsState()
    val notifications by repository.notifications.collectAsState()

    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Preferences",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Library Chapter Updates", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("Alert when new chapters are detected for books in your library", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = notificationSettings.notifyOnNovelUpdate,
                            onCheckedChange = { repository.updateNotificationSettings(notificationSettings.copy(notifyOnNovelUpdate = it)) }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Download Completed Alerts", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("Notify when offline chapter batch downloads finish", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = notificationSettings.notifyOnDownload,
                            onCheckedChange = { repository.updateNotificationSettings(notificationSettings.copy(notifyOnDownload = it)) }
                        )
                    }
                }
            }
        }

        // Notification History Log
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Notification History",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (notifications.isNotEmpty()) {
                    TextButton(onClick = { repository.clearNotifications() }) {
                        Text("Clear All")
                    }
                }
            }
        }

        if (notifications.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No notifications yet. New chapter releases and download progress will be logged here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(notifications, key = { it.id }) { item ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                            Text(dateFormat.format(Date(item.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(item.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

// =============================================================================
// 7. READING HISTORY PAGE
// =============================================================================
@Composable
private fun ReadingHistoryPage(
    repository: NovelRepository,
    onReadChapter: (Novel, String, Int) -> Unit
) {
    val libraryNovels by repository.libraryNovels.collectAsState()
    val historyNovels = remember(libraryNovels) {
        libraryNovels
            .filter { it.lastReadTimestamp > 0L }
            .sortedByDescending { it.lastReadTimestamp }
    }

    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    val groupedHistory = remember(historyNovels) {
        val groups = linkedMapOf<String, MutableList<Novel>>()
        val now = Calendar.getInstance()
        val entryCal = Calendar.getInstance()

        for (novel in historyNovels) {
            entryCal.timeInMillis = novel.lastReadTimestamp
            val header = when {
                now.get(Calendar.YEAR) == entryCal.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == entryCal.get(Calendar.DAY_OF_YEAR) -> "TODAY"

                now.get(Calendar.YEAR) == entryCal.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) - 1 == entryCal.get(Calendar.DAY_OF_YEAR) -> "YESTERDAY"

                else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(novel.lastReadTimestamp)).uppercase()
            }
            groups.getOrPut(header) { mutableListOf() }.add(novel)
        }
        groups
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (historyNovels.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Text(
                        text = "No reading history recorded yet. Open a chapter from your Library or Browse to start reading.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            groupedHistory.forEach { (dateHeader, novelsInGroup) ->
                item(key = "header_$dateHeader") {
                    Text(
                        text = dateHeader,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        ),
                        color = Color(0xFFF59E0B), // Amber accent color
                        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                    )
                }

                items(novelsInGroup, key = { it.id }) { novel ->
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val chId = novel.lastReadChapterId ?: "${novel.id}_ch_1"
                                val chIdx = if (novel.lastReadChapterIndex > 0) novel.lastReadChapterIndex else 1
                                onReadChapter(novel, chId, chIdx)
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NovelCoverImage(
                                coverUrl = novel.coverUrl,
                                title = novel.title,
                                modifier = Modifier
                                    .size(width = 46.dp, height = 66.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = novel.title,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.5.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Spacer(modifier = Modifier.height(3.dp))

                                val chapterLabel = if (!novel.lastReadChapterTitle.isNullOrBlank()) {
                                    novel.lastReadChapterTitle ?: ""
                                } else if (novel.lastReadChapterIndex > 0) {
                                    "Chapter ${novel.lastReadChapterIndex}"
                                } else {
                                    "Chapter 1"
                                }
                                val timeLabel = timeFormat.format(Date(novel.lastReadTimestamp))

                                Text(
                                    text = "$chapterLabel • $timeLabel",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(
                                onClick = { repository.clearNovelReadingHistory(novel.id) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove from history",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// 8. GENERAL SETTINGS PAGE
// =============================================================================
@Composable
private fun GeneralSettingsPage(repository: NovelRepository) {
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "About",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Application Name", style = MaterialTheme.typography.bodyMedium)
                        Text("Novel Reader", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Version", style = MaterialTheme.typography.bodyMedium)
                        Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Application ID", style = MaterialTheme.typography.bodyMedium)
                        Text("com.example", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Architecture", style = MaterialTheme.typography.bodyMedium)
                        Text("Offline-First Android Compose", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            Text(
                text = "Preferences Reset",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Reset All Preferences", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error))
                        Text("Restore reader typography, colors, downloads, and app settings to defaults. Library novels are preserved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = { showResetDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Reset")
                    }
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Preferences to Default?") },
            text = { Text("This will restore default font sizes, theme settings, downloads config, and notification preferences. Your library novels will NOT be deleted.") },
            confirmButton = {
                Button(
                    onClick = {
                        repository.resetAppPreferences()
                        showResetDialog = false
                        Toast.makeText(context, "Preferences reset to default", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            }
        )
    }
}
