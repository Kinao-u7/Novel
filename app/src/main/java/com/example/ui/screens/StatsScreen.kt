package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.NovelRepository
import com.example.ui.components.EditorialSectionHeader
import com.example.ui.theme.BookSerifFontFamily

@Composable
fun StatsScreen(repository: NovelRepository) {
    val stats by repository.readingStats.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Reading Statistics",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = BookSerifFontFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Track your reading habits and milestones",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // Main Big Metric Cards (Streak & Today's Reading)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricHighlightCard(
                        title = "DAILY STREAK",
                        value = "${stats.currentStreakDays} Days",
                        subtitle = "Keep the flame burning",
                        icon = Icons.Default.LocalFireDepartment,
                        iconTint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        progress = (stats.currentStreakDays / 7f).coerceIn(0f, 1f)
                    )

                    MetricHighlightCard(
                        title = "TODAY'S READING",
                        value = "${stats.todayMinutesRead}m",
                        subtitle = "Goal: 30m / day",
                        icon = Icons.Default.Timer,
                        iconTint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        progress = (stats.todayMinutesRead / 30f).coerceIn(0f, 1f)
                    )
                }
            }

            // Secondary Stats Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricHighlightCard(
                        title = "CHAPTERS READ",
                        value = "${stats.totalChaptersRead}",
                        subtitle = "All-time completed",
                        icon = Icons.Default.AutoStories,
                        iconTint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )

                    MetricHighlightCard(
                        title = "WORDS READ",
                        value = "${stats.totalWordsRead / 1000}k",
                        subtitle = "~${stats.wordsPerMinute} wpm reading pace",
                        icon = Icons.Default.Speed,
                        iconTint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Milestone Badges
            item {
                Spacer(modifier = Modifier.height(12.dp))
                EditorialSectionHeader(title = "Milestones & Badges")
                Spacer(modifier = Modifier.height(4.dp))
            }

            item {
                MilestoneItem(
                    title = "Night Owl Reader",
                    description = "Completed 10+ reading sessions during late evening hours.",
                    isUnlocked = true
                )
            }

            item {
                MilestoneItem(
                    title = "Centurion",
                    description = "Read over 100 chapters across web novels.",
                    isUnlocked = stats.totalChaptersRead >= 100
                )
            }

            item {
                MilestoneItem(
                    title = "Offline Voyager",
                    description = "Read downloaded chapters in airplane or offline mode.",
                    isUnlocked = true
                )
            }

            item {
                MilestoneItem(
                    title = "Grand Scholar",
                    description = "Reached 500,000 total words read.",
                    isUnlocked = stats.totalWordsRead >= 500000
                )
            }
        }
    }
}

@Composable
fun MetricHighlightCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier,
    progress: Float? = null
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                )
                Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = BookSerifFontFamily,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                )
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )

            if (progress != null) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = iconTint,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
fun MilestoneItem(
    title: String,
    description: String,
    isUnlocked: Boolean
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            1.dp,
            if (isUnlocked)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            else
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isUnlocked)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isUnlocked) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, if (isUnlocked) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.WorkspacePremium,
                        contentDescription = null,
                        tint = if (isUnlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = BookSerifFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}

