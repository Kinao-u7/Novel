package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.ui.theme.BookSerifFontFamily
import com.example.ui.theme.EyebrowStyle
import java.io.File

@Composable
fun NovelCoverImage(
    coverUrl: String?,
    modifier: Modifier = Modifier,
    title: String = ""
) {
    val context = LocalContext.current
    val imageModel: Any? = when {
        coverUrl == null -> null
        coverUrl.startsWith("http://") || coverUrl.startsWith("https://") -> coverUrl
        else -> File(coverUrl) // Local file path for EPUB covers
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            )
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF282420),
                        Color(0xFF161513)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageModel)
                    .crossfade(true)
                    .build(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Placeholder book visual with warm tone
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Book,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

/**
 * Tag/genre pills: small rounded-rect outlined pills in muted dark tone,
 * uppercase small text wrapping across lines.
 */
@Composable
fun TagPill(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Text(
            text = text.uppercase(),
            style = EyebrowStyle.copy(fontSize = 9.sp, letterSpacing = 1.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

// Backward compatibility alias for GenreTag
@Composable
fun GenreTag(
    text: String,
    modifier: Modifier = Modifier
) {
    TagPill(text = text, modifier = modifier)
}

/**
 * Status pill (e.g. "ONGOING", "COMPLETE", "GUIDE"): a single filled pill in the amber accent
 * (or muted green for "Complete"), visually distinct from the outlined tag pills.
 */
@Composable
fun StatusPill(
    status: String,
    modifier: Modifier = Modifier
) {
    val isComplete = status.equals("Complete", ignoreCase = true) || status.equals("Completed", ignoreCase = true)
    val pillBg = if (isComplete) Color(0xFF10B981).copy(alpha = 0.18f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val pillBorder = if (isComplete) Color(0xFF10B981).copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    val pillText = if (isComplete) Color(0xFF34D399) else MaterialTheme.colorScheme.primary

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = pillBg,
        border = BorderStroke(1.dp, pillBorder)
    ) {
        Text(
            text = status.uppercase(),
            style = EyebrowStyle.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
            color = pillText,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun RatingBadge(
    rating: Float,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(11.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = String.format(java.util.Locale.US, "%.1f", rating),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                ),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Primary solid amber CTA pill-shaped button with an action icon (high visual weight).
 */
@Composable
fun PrimaryCtaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.PlayArrow,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp
            )
        )
    }
}

/**
 * Secondary outlined button, inheriting theme button shape, lower visual weight.
 */
@Composable
fun SecondaryOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 36.dp),
        enabled = enabled,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}

/**
 * Segmented control with equal-width segments in a single rounded outlined container,
 * with the selected segment getting a colored outline/border rather than a heavy fill.
 */
@Composable
fun <T> OutlinedSegmentedControl(
    items: List<T>,
    selectedItem: T,
    onSelect: (T) -> Unit,
    itemLabel: (T) -> String,
    modifier: Modifier = Modifier,
    itemIcon: ((T) -> ImageVector?)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEach { item ->
                val isSelected = item == selectedItem
                val shape = RoundedCornerShape(6.dp)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(shape)
                        .border(
                            width = if (isSelected) 1.5.dp else 0.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = shape
                        )
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else Color.Transparent
                        )
                        .clickable { onSelect(item) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        val icon = itemIcon?.invoke(item)
                        if (icon != null) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                        }
                        Text(
                            text = itemLabel(item),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 11.5.sp
                            ),
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/**
 * Section header with editorial serif title and optional count badge pill.
 */
@Composable
fun EditorialSectionHeader(
    title: String,
    badgeText: String? = null,
    count: Int? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    val displayBadge = badgeText ?: count?.toString()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = BookSerifFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (displayBadge != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = displayBadge,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
        if (action != null) {
            action()
        }
    }
}

/**
 * Empty state container with centered circular icon outline badge, serif headline, and muted description.
 */
@Composable
fun EmptyStateView(
    icon: ImageVector,
    title: String,
    message: String = "",
    description: String = message,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    val displayText = message.ifEmpty { description }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = BookSerifFontFamily,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        if (displayText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = displayText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        if (action != null) {
            Spacer(modifier = Modifier.height(20.dp))
            action()
        }
    }
}

/**
 * Minimal thin scrollbar indicator modifier for LazyColumn / chapter list.
 * Renders as a clean thin line thumb on the right edge without decorative lines or dots.
 */
fun Modifier.simpleVerticalScrollbar(
    state: LazyListState,
    width: Dp = 3.dp,
    color: Color = Color.Unspecified
): Modifier = this.drawWithContent {
    drawContent()

    val layoutInfo = state.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo
    if (totalItems > 0 && visibleItems.isNotEmpty()) {
        val firstVisibleItem = visibleItems.first().index
        val visibleItemCount = visibleItems.size

        val viewportHeight = size.height
        val scrollbarHeight = ((visibleItemCount.toFloat() / totalItems.toFloat()) * viewportHeight).coerceIn(24.dp.toPx(), viewportHeight)
        val scrollbarOffset = ((firstVisibleItem.toFloat() / totalItems.toFloat()) * (viewportHeight - scrollbarHeight)).coerceIn(0f, viewportHeight - scrollbarHeight)

        val thumbColor = if (color != Color.Unspecified) color else Color(0xFFE89A3C).copy(alpha = 0.8f)
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(size.width - width.toPx(), scrollbarOffset),
            size = Size(width.toPx(), scrollbarHeight),
            cornerRadius = CornerRadius(width.toPx() / 2, width.toPx() / 2)
        )
    }
}

/**
 * Clean slider with thin track line (no dots/stop indicators) and round circular thumb,
 * replacing the heavy default M3 slider with dots.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
) {
    val interactionSource = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        thumb = {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (enabled) accentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            )
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier.height(3.dp),
                enabled = enabled,
                colors = SliderDefaults.colors(
                    activeTrackColor = accentColor,
                    inactiveTrackColor = trackColor
                ),
                drawStopIndicator = null,
                drawTick = { _, _ -> },
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 0.dp
            )
        }
    )
}


