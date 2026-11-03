package com.example.ui.screens.browse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R

/* ───────────────────────── Palette & fonts ───────────────────────── */

@Immutable
private data class BrowsePalette(
    val bg: Color, val text: Color, val muted: Color, val accent: Color, val gold: Color,
    val card: Color, val line: Color, val onAccent: Color, val danger: Color
)

private val LocalBrowsePalette = staticCompositionLocalOf<BrowsePalette> { error("no palette") }

@Composable
private fun rememberBrowsePalette(): BrowsePalette {
    val c = MaterialTheme.colorScheme
    return remember(c) {
        BrowsePalette(
            bg = c.background,
            text = c.onBackground,
            muted = c.onSurfaceVariant,
            accent = c.primary,
            gold = c.tertiary,
            card = c.surfaceVariant,
            line = c.outlineVariant,
            onAccent = c.onPrimary,
            danger = c.error
        )
    }
}

private val HeadingFont: FontFamily = FontFamily.Serif
private val GlyphFont: FontFamily = FontFamily.Serif

private fun strokeIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d ->
            addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
    }.build()

private val BackIcon = strokeIcon("back", "M15 5l-7 7 7 7")
private val FilterIcon = strokeIcon("filter", "M4 6h16M7 12h10M10 18h4")

/* ───────────────────────── Entry point ───────────────────────── */

@Composable
fun BrowseScreen(
    state: BrowseUiState,
    actions: BrowseActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val palette = rememberBrowsePalette()
    CompositionLocalProvider(LocalBrowsePalette provides palette) {
        BrowseContent(state, actions, modifier, contentPadding)
    }
}

@Composable
private fun BrowseContent(
    state: BrowseUiState,
    actions: BrowseActions,
    modifier: Modifier,
    contentPadding: PaddingValues
) {
    val p = LocalBrowsePalette.current
    BackHandler { actions.onBack() }

    val gridState = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 7
        }
    }
    // keyed on list size so it re-fires only after a page actually arrived
    LaunchedEffect(nearEnd, state.novels.size) {
        if (nearEnd && state.novels.isNotEmpty() && !state.isLoading && !state.isLoadingMore && state.error == null) {
            actions.onLoadMore()
        }
    }

    Box(modifier.fillMaxSize().background(p.bg), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxSize()) {
            Header(
                state, actions,
                Modifier
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp)
            )
            if (state.latestSupported) {
                ModeSegment(
                    state.mode, actions.onModeChange,
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 18.dp,
                    bottom = contentPadding.calculateBottomPadding() + 30.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                when {
                    state.isLoading -> {
                        items(9) { SkeletonCover() }
                    }
                    state.novels.isEmpty() && state.error != null -> {
                        item(span = { GridItemSpan(maxLineSpan) }) { ErrorBlock(state.error, actions.onRetry) }
                    }
                    state.novels.isEmpty() -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                stringResource(R.string.browse_empty_novels),
                                color = p.muted, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 46.dp)
                            )
                        }
                    }
                    else -> {
                        items(state.novels, key = { it.id }) { n ->
                            NovelCard(n, showRank = state.mode == BrowseMode.POPULAR) { actions.onNovelClick(n.id) }
                        }
                        if (state.isLoadingMore) items(3) { SkeletonCover() }
                        if (state.error != null) {
                            item(span = { GridItemSpan(maxLineSpan) }) { ErrorBlock(state.error, actions.onRetry) }
                        }
                    }
                }
            }
        }
    }

    if (state.showFilters && state.filtersSupported) {
        FilterSheet(
            current = state.filters,
            genres = state.availableGenres,
            onApply = actions.onApplyFilters,
            onDismiss = actions.onDismissFilters
        )
    }
}

/* ───────────────────────── Header & segment ───────────────────────── */

@Composable
private fun Header(state: BrowseUiState, actions: BrowseActions, modifier: Modifier) {
    val p = LocalBrowsePalette.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconBox(BackIcon, stringResource(R.string.browse_back), actions.onBack)
        Column(Modifier.weight(1f)) {
            Text(
                state.sourceName, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = HeadingFont, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (state.subtitle.isNotEmpty()) Text(state.subtitle, color = p.muted, fontSize = 12.sp)
        }
        if (state.filtersSupported) {
            Box {
                IconBox(FilterIcon, stringResource(R.string.browse_filter), actions.onOpenFilters)
                if (state.filters.isActive) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp)
                            .size(8.dp).clip(CircleShape).background(p.gold)
                    )
                }
            }
        }
    }
}

@Composable
private fun IconBox(icon: ImageVector, description: String, onClick: () -> Unit) {
    val p = LocalBrowsePalette.current
    val shape = RoundedCornerShape(13.dp)
    Box(
        Modifier.size(42.dp).clip(shape).border(1.dp, p.line, shape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, Modifier.size(20.dp), tint = p.text) }
}

@Composable
private fun ModeSegment(mode: BrowseMode, onChange: (BrowseMode) -> Unit, modifier: Modifier) {
    val p = LocalBrowsePalette.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(p.card).border(1.dp, p.line, shape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SegButton(stringResource(R.string.browse_mode_latest), mode == BrowseMode.LATEST) { onChange(BrowseMode.LATEST) }
        SegButton(stringResource(R.string.browse_mode_popular), mode == BrowseMode.POPULAR) { onChange(BrowseMode.POPULAR) }
    }
}

@Composable
private fun RowScope.SegButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalBrowsePalette.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.weight(1f).clip(shape)
            .background(if (selected) p.line else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) p.text else p.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/* ───────────────────────── Grid items ───────────────────────── */

@Composable
private fun NovelCard(item: BrowseNovelItem, showRank: Boolean, onClick: () -> Unit) {
    val p = LocalBrowsePalette.current
    val hue = remember(item.id) { Math.floorMod(item.id.hashCode(), 360).toFloat() }
    val c1 = remember(hue) { Color.hsv(hue, 0.55f, 0.28f) }
    val ak = remember(hue) { Color.hsv(hue, 0.35f, 0.97f) }
    val glyph = remember(item.title) { item.title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•" }
    val shape = RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp, topEnd = 10.dp, bottomEnd = 10.dp)
    val clickLabel = stringResource(R.string.browse_open_novel, item.title)

    Column(Modifier.clickable(onClickLabel = clickLabel, onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .shadow(10.dp, shape)
                .clip(shape)
                .background(Brush.linearGradient(listOf(c1, Color(0xFF071013)), start = Offset.Zero, end = Offset.Infinite))
                .drawBehind {
                    drawCircle(
                        Brush.radialGradient(
                            listOf(ak.copy(alpha = 0.5f), Color.Transparent),
                            center = Offset(size.width / 2f, size.height * 0.36f),
                            radius = size.width * 0.3f
                        ),
                        radius = size.width * 0.3f,
                        center = Offset(size.width / 2f, size.height * 0.36f)
                    )
                }
        ) {
            Text(
                glyph, color = ak, fontSize = 40.sp, fontFamily = GlyphFont,
                modifier = Modifier.align(BiasAlignment(0f, -0.28f))
            )
            if (item.coverUrl != null) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // spine highlight (inset 3px 0 0 #ffffff24)
            Box(Modifier.fillMaxHeight().width(3.dp).background(Color.White.copy(alpha = 0.14f)))
            if (showRank && item.rank != null) {
                Text(
                    "#${item.rank}", color = p.gold, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color(0xAA000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            item.title, color = p.text, fontSize = 12.sp, lineHeight = 15.6.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = HeadingFont, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
        val sub = buildString {
            append(item.author)
            if (item.rating != null) {
                if (isNotEmpty()) append(" · ")
                append("★ ").append(String.format(java.util.Locale.US, "%.1f", item.rating))
            }
        }
        if (sub.isNotEmpty()) {
            Text(sub, color = p.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SkeletonCover() {
    val p = LocalBrowsePalette.current
    val t by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerT"
    )
    Box(
        Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).drawBehind {
            val w = size.width
            val x = -w + t * 2f * w
            drawRect(
                Brush.horizontalGradient(
                    listOf(p.card, p.line, p.card),
                    startX = x, endX = x + w * 2f
                )
            )
        }
    )
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    val p = LocalBrowsePalette.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 30.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(message, color = p.muted, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.browse_retry), color = p.muted, fontSize = 13.sp,
            modifier = Modifier.clip(shape).border(1.dp, p.muted, shape)
                .clickable(role = Role.Button, onClick = onRetry)
                .padding(horizontal = 18.dp, vertical = 12.dp)
        )
    }
}

/* ───────────────────────── Filter sheet ───────────────────────── */

@Composable
private fun statusLabel(s: BrowseStatus) = when (s) {
    BrowseStatus.ANY -> stringResource(R.string.browse_status_any)
    BrowseStatus.ONGOING -> stringResource(R.string.browse_status_ongoing)
    BrowseStatus.COMPLETED -> stringResource(R.string.browse_status_completed)
}

@Composable
private fun sortLabel(s: BrowseSort) = when (s) {
    BrowseSort.DEFAULT -> stringResource(R.string.browse_sort_default)
    BrowseSort.RATING -> stringResource(R.string.browse_sort_rating)
    BrowseSort.TITLE -> stringResource(R.string.browse_sort_title)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    current: BrowseFilters,
    genres: List<String>,
    onApply: (BrowseFilters) -> Unit,
    onDismiss: () -> Unit
) {
    val p = LocalBrowsePalette.current
    // the sheet edits a draft; nothing leaves until "Show results"
    var draft by remember { mutableStateOf(current) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = p.card,
        contentColor = p.text,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, bottom = 24.dp)
        ) {
            Text(stringResource(R.string.browse_filter_title), color = p.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, fontFamily = HeadingFont)

            if (genres.isNotEmpty()) {
                SheetLabel(stringResource(R.string.browse_filter_genres), top = 16.dp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    genres.forEach { g ->
                        val on = g in draft.genres
                        FilterChip(g, on) {
                            draft = draft.copy(genres = if (on) draft.genres - g else draft.genres + g)
                        }
                    }
                }
            }

            SheetLabel(stringResource(R.string.browse_filter_status), top = if (genres.isNotEmpty()) 22.dp else 16.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BrowseStatus.entries.forEach { s ->
                    FilterChip(statusLabel(s), draft.status == s) { draft = draft.copy(status = s) }
                }
            }

            SheetLabel(stringResource(R.string.browse_filter_sort_by), top = 22.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BrowseSort.entries.forEach { s ->
                    FilterChip(sortLabel(s), draft.sort == s) { draft = draft.copy(sort = s) }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton(stringResource(R.string.browse_filter_reset), fill = null, textColor = p.text) { draft = BrowseFilters() }
                SheetButton(stringResource(R.string.browse_filter_show_results), fill = p.accent, textColor = p.onAccent) { onApply(draft) }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String, top: Dp) {
    val p = LocalBrowsePalette.current
    Text(
        text.uppercase(), color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        fontFamily = HeadingFont, letterSpacing = 0.14.em,
        modifier = Modifier.padding(top = top, bottom = 8.dp)
    )
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalBrowsePalette.current
    val shape = RoundedCornerShape(20.dp)
    Text(
        label,
        color = if (selected) p.accent else p.muted,
        fontSize = 13.sp,
        modifier = Modifier.clip(shape)
            .border(1.dp, if (selected) p.accent else p.line, shape)
            .selectable(selected = selected, role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun RowScope.SheetButton(label: String, fill: Color?, textColor: Color, onClick: () -> Unit) {
    val p = LocalBrowsePalette.current
    val shape = RoundedCornerShape(13.dp)
    Box(
        Modifier.weight(1f).clip(shape)
            .then(if (fill != null) Modifier.background(fill) else Modifier.border(1.dp, p.line, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label.uppercase(), color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.12.em)
    }
}
