package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Novel
import com.example.data.repository.NovelRepository
import com.example.ui.components.EditorialSectionHeader
import com.example.ui.theme.BookSerifFontFamily

@Composable
fun ProfileScreen(
    repository: NovelRepository,
    onNavigateToSettingsSubPage: (SettingsSubPage) -> Unit,
    onNavigateToDownloads: () -> Unit,
    onOpenNovel: (Novel) -> Unit
) {
    val stats by repository.readingStats.collectAsState()
    val libraryNovels by repository.libraryNovels.collectAsState()
    val notifications by repository.notifications.collectAsState()

    // Find the most recently read novel
    val lastReadNovel = libraryNovels
        .filter { it.lastReadTimestamp > 0 }
        .maxByOrNull { it.lastReadTimestamp }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            // Profile Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoStories,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = "Reader Profile",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = BookSerifFontFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        )
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalFireDepartment,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${stats.currentStreakDays} DAY STREAK",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // Reading Summary Card
        item {
            Card(
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Reading Summary",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = BookSerifFontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        TextButton(
                            onClick = { onNavigateToSettingsSubPage(SettingsSubPage.STATS) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Full Stats", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SummaryMetricItem(
                            label = "Read Today",
                            value = "${stats.todayMinutesRead}m",
                            icon = Icons.Default.Timer,
                            modifier = Modifier.weight(1f)
                        )
                        SummaryMetricItem(
                            label = "Total Words",
                            value = if (stats.totalWordsRead >= 1000) "${stats.totalWordsRead / 1000}k" else "${stats.totalWordsRead}",
                            icon = Icons.Default.Speed,
                            modifier = Modifier.weight(1f)
                        )
                        SummaryMetricItem(
                            label = "Chapters",
                            value = "${stats.totalChaptersRead}",
                            icon = Icons.AutoMirrored.Filled.MenuBook,
                            modifier = Modifier.weight(1f)
                        )
                        SummaryMetricItem(
                            label = "Streak",
                            value = "${stats.currentStreakDays}d",
                            icon = Icons.Default.WorkspacePremium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Continue Reading shortcut if any novel has been read
        if (lastReadNovel != null) {
            item {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenNovel(lastReadNovel) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Continue",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "CONTINUE READING",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = lastReadNovel.title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = BookSerifFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!lastReadNovel.lastReadChapterTitle.isNullOrBlank()) {
                                Text(
                                    text = lastReadNovel.lastReadChapterTitle ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Quick Navigation Links
        item {
            EditorialSectionHeader(title = "Navigation Hub")
        }

        item {
            Card(
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    ProfileMenuRow(
                        icon = Icons.Default.History,
                        title = "Reading History",
                        subtitle = "Recent chapters and reading log",
                        onClick = { onNavigateToSettingsSubPage(SettingsSubPage.HISTORY) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ProfileMenuRow(
                        icon = Icons.Default.BarChart,
                        title = "Reading Stats",
                        subtitle = "Streaks, reading speed, and milestones",
                        onClick = { onNavigateToSettingsSubPage(SettingsSubPage.STATS) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ProfileMenuRow(
                        icon = Icons.Default.Notifications,
                        title = "Notifications & Updates",
                        subtitle = if (notifications.isNotEmpty()) "${notifications.size} update(s)" else "Chapter updates & reading notices",
                        badgeCount = notifications.size,
                        onClick = { onNavigateToSettingsSubPage(SettingsSubPage.NOTIFICATIONS) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ProfileMenuRow(
                        icon = Icons.Default.Tune,
                        title = "Reader Settings",
                        subtitle = "Fonts, spacing, themes, TTS, and custom toolbar",
                        onClick = { onNavigateToSettingsSubPage(SettingsSubPage.READER) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ProfileMenuRow(
                        icon = Icons.Default.CloudDownload,
                        title = "Downloads & Offline",
                        subtitle = "Offline storage and background downloads",
                        onClick = onNavigateToDownloads
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ProfileMenuRow(
                        icon = Icons.Default.Storage,
                        title = "Storage & Cache",
                        subtitle = "Cache cleanup and storage management",
                        onClick = { onNavigateToSettingsSubPage(SettingsSubPage.STORAGE) }
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    ProfileMenuRow(
                        icon = Icons.Default.Settings,
                        title = "Settings Hub",
                        subtitle = "General preferences, appearance, and sources",
                        onClick = { onNavigateToSettingsSubPage(SettingsSubPage.HUB) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryMetricItem(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = BookSerifFontFamily,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun ProfileMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badgeCount: Int = 0,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = BookSerifFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }

        if (badgeCount > 0) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text(
                    text = "$badgeCount",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

