@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.ui.screens.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.LibrarySort
import com.example.data.model.LibraryViewMode
import com.example.ui.components.AppSheet
import com.example.ui.components.Cover
import com.example.ui.components.DesignSearchField
import com.example.ui.components.NewCategoryDialog
import com.example.ui.components.Pill
import com.example.ui.components.SectionLabel
import com.example.ui.components.SquareIcon
import com.example.ui.theme.AppTheme
import java.time.LocalTime

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
) {
    var showSearch by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showNewCat by remember { mutableStateOf(false) }
    val c = AppTheme.colors
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            actions.onMessageShown()
        }
    }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        snackbarHost = {
            SnackbarHost(snackbarHostState, Modifier.padding(bottom = 16.dp))
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(
                start = 18.dp,
                top = top + 18.dp,
                end = 18.dp,
                bottom = pad.calculateBottomPadding() + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    TopBar(
                        displayName = state.displayName,
                        filterActive = state.filterActive,
                        onSearch = { showSearch = !showSearch },
                        onFilter = { showFilter = true },
                    )
                    AnimatedVisibility(showSearch) {
                        DesignSearchField(
                            query = state.searchQuery,
                            onQueryChange = actions.onSearchChange,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                    if (state.categoriesEnabled) {
                        CategoryChips(
                            categories = state.categories,
                            selectedCategoryId = state.selectedCategoryId,
                            onSelect = actions.onSelectCategory,
                            onNew = { showNewCat = true },
                        )
                    }
                    state.continueReading?.let { cr ->
                        SectionLabel(
                            stringResource(R.string.library_continue_reading),
                            Modifier.padding(top = 26.dp, bottom = 12.dp),
                        )
                        HeroCard(cr = cr, onResume = { actions.onResume(cr.novelId) })
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 26.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        SectionLabel(stringResource(R.string.library_your_shelf))
                        ViewToggle(state.viewMode, actions.onViewModeChange)
                    }
                }
            }

            if (state.showEmptyLibrary) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.library_empty_title),
                            color = c.tx,
                            fontFamily = AppTheme.fonts.heading,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                        )
                        Text(
                            stringResource(R.string.library_empty_subtitle),
                            color = c.mu,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            } else if (state.showNoResults) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.library_no_results),
                            color = c.mu,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        OutlinedButton(
                            onClick = actions.onResetFilters,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, c.ln),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(stringResource(R.string.library_reset_filters), color = c.ac)
                        }
                    }
                }
            } else if (state.viewMode == LibraryViewMode.GRID) {
                items(state.novels, key = { it.id }) { novel ->
                    GridBook(novel) { actions.onNovelClick(novel.id) }
                }
            } else {
                items(state.novels, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { novel ->
                    ListBook(novel) { actions.onNovelClick(novel.id) }
                }
            }
        }
    }

    if (showFilter) {
        FilterSheet(
            sort = state.sort,
            filter = state.filter,
            onSortChange = actions.onSortChange,
            onFilterChange = actions.onFilterChange,
            onResetFilters = actions.onResetFilters,
            onDismiss = { showFilter = false },
        )
    }

    if (showNewCat) {
        NewCategoryDialog(
            onDismiss = { showNewCat = false },
            onCreate = { actions.onCreateCategory(it) },
        )
    }
}

@Composable
private fun TopBar(
    displayName: String,
    filterActive: Boolean,
    onSearch: () -> Unit,
    onFilter: () -> Unit,
) {
    val c = AppTheme.colors
    val f = AppTheme.fonts
    val hour = remember { LocalTime.now().hour }
    val greeting = when {
        hour < 12 -> stringResource(R.string.library_greeting_morning)
        hour < 18 -> stringResource(R.string.library_greeting_afternoon)
        else -> stringResource(R.string.library_greeting_evening)
    }
    val name = displayName.ifBlank { stringResource(R.string.library_default_reader_name) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                "$greeting, $name".uppercase(),
                color = c.go,
                fontFamily = f.heading,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
            )
            Text(
                stringResource(R.string.library_title),
                color = c.tx,
                fontFamily = f.heading,
                fontWeight = FontWeight.Medium,
                fontSize = 28.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SquareIcon(Icons.Rounded.Search, stringResource(R.string.library_search_desc), onSearch)
            SquareIcon(Icons.Rounded.Tune, stringResource(R.string.library_filter_sort_desc), onFilter, dot = filterActive)
        }
    }
}

@Composable
private fun CategoryChips(
    categories: List<CategoryItem>,
    selectedCategoryId: String?,
    onSelect: (String?) -> Unit,
    onNew: () -> Unit,
) {
    LazyRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Pill(stringResource(R.string.library_filter_all), selectedCategoryId == null) {
                onSelect(null)
            }
        }
        items(categories, key = { it.id }) { cat ->
            Pill(cat.name, selectedCategoryId == cat.id) {
                onSelect(cat.id)
            }
        }
        item {
            Pill(stringResource(R.string.library_new_category), false, dashed = true, onClick = onNew)
        }
    }
}

@Composable
private fun HeroCard(cr: ContinueReading, onResume: () -> Unit) {
    val c = AppTheme.colors
    val f = AppTheme.fonts
    val shape = RoundedCornerShape(22.dp)

    val chapterSub = if (cr.chapterTitle.isNullOrBlank()) {
        "Chapter ${cr.chapterNumber}"
    } else {
        "Chapter ${cr.chapterNumber} · ${cr.chapterTitle}"
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.cd)
            .border(1.dp, c.ln, shape)
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(
                        listOf(c.ac.copy(alpha = 0.2f), Color.Transparent),
                        Offset(size.width - 40.dp.toPx(), 40.dp.toPx()),
                        100.dp.toPx(),
                    ),
                    radius = 100.dp.toPx(),
                    center = Offset(size.width - 40.dp.toPx(), 40.dp.toPx()),
                )
            }
            .clickable(onClickLabel = "Resume reading", onClick = onResume)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Cover(
            title = cr.title,
            coverUrl = cr.coverUrl,
            modifier = Modifier.width(92.dp),
            glyphSize = 52.sp,
            titleSize = 11.sp,
        )
        Column(Modifier.weight(1f).align(Alignment.CenterVertically)) {
            Text(
                cr.title,
                color = c.tx,
                fontFamily = f.heading,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                lineHeight = 22.sp,
            )
            Text(
                chapterSub,
                color = c.mu,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            LinearProgressIndicator(
                progress = { cr.progress },
                color = c.ac,
                trackColor = c.ln,
                strokeCap = StrokeCap.Round,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(5.dp),
            )
            Text(
                "${cr.readCount} of ${cr.totalChapters} chapters",
                color = c.mu,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Box(
                modifier = Modifier
                    .padding(top = 14.dp)
                    .defaultMinSize(minHeight = 48.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    stringResource(R.string.library_resume),
                    color = c.on,
                    fontFamily = f.ui,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(c.ac)
                        .padding(horizontal = 38.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ViewToggle(view: LibraryViewMode, onChange: (LibraryViewMode) -> Unit) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .clip(shape)
            .background(c.cd)
            .border(1.dp, c.ln, shape)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(
            LibraryViewMode.GRID to Icons.Rounded.GridView,
            LibraryViewMode.LIST to Icons.AutoMirrored.Rounded.ViewList,
        ).forEach { (m, icon) ->
            Box(
                Modifier
                    .size(48.dp, 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (view == m) c.ln else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onChange(m) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    if (m == LibraryViewMode.GRID) stringResource(R.string.library_grid_view) else stringResource(R.string.library_list_view),
                    tint = if (view == m) c.tx else c.mu,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun GridBook(b: LibraryNovelItem, onClick: () -> Unit) {
    val c = AppTheme.colors
    val f = AppTheme.fonts
    Column(
        Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Cover(title = b.title, coverUrl = b.coverUrl, modifier = Modifier.fillMaxWidth())
        Text(
            b.title,
            color = c.tx,
            fontFamily = f.heading,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "${b.author} · ${b.totalChapters} ch",
            color = c.mu,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ListBook(b: LibraryNovelItem, onClick: () -> Unit) {
    val c = AppTheme.colors
    val f = AppTheme.fonts
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cover(
            title = b.title,
            coverUrl = b.coverUrl,
            modifier = Modifier.width(64.dp),
            glyphSize = 28.sp,
            showTitle = false,
        )
        Column(Modifier.weight(1f)) {
            Text(
                b.title,
                color = c.tx,
                fontFamily = f.heading,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${b.author} · Ch ${b.readCount}/${b.totalChapters}",
                color = c.mu,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun FilterSheet(
    sort: LibrarySort,
    filter: LibraryFilter,
    onSortChange: (LibrarySort) -> Unit,
    onFilterChange: (LibraryFilter) -> Unit,
    onResetFilters: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AppTheme.colors
    val f = AppTheme.fonts
    AppSheet(onDismiss) {
        Text(
            stringResource(R.string.library_filter_sort_title),
            color = c.tx,
            fontFamily = f.heading,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
        )
        SectionLabel(stringResource(R.string.library_sort_by), Modifier.padding(top = 20.dp, bottom = 6.dp))

        LibrarySort.entries.forEach { m ->
            val label = when (m) {
                LibrarySort.RECENTLY_READ -> stringResource(R.string.library_sort_recently_read)
                LibrarySort.TITLE -> stringResource(R.string.library_sort_title)
                LibrarySort.AUTHOR -> stringResource(R.string.library_sort_author)
                LibrarySort.TOTAL_CHAPTERS -> stringResource(R.string.library_sort_chapters)
                LibrarySort.RATING -> stringResource(R.string.library_sort_rating)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { onSortChange(m) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, Modifier.weight(1f), color = if (sort == m) c.ac else c.tx)
                if (sort == m) {
                    Icon(Icons.Rounded.Check, null, tint = c.ac, modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider(color = c.ln)
        }

        SectionLabel(stringResource(R.string.library_show), Modifier.padding(top = 20.dp, bottom = 8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryFilter.entries.forEach { itFilter ->
                val label = when (itFilter) {
                    LibraryFilter.ALL -> stringResource(R.string.library_filter_all)
                    LibraryFilter.READING -> stringResource(R.string.library_filter_reading)
                    LibraryFilter.FINISHED -> stringResource(R.string.library_filter_finished)
                }
                Pill(label, filter == itFilter) { onFilterChange(itFilter) }
            }
        }

        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onResetFilters,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.dp, c.ln),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.tx),
            ) {
                Text(
                    stringResource(R.string.library_reset_button),
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.ac, contentColor = c.on),
            ) {
                Text(
                    stringResource(R.string.library_done_button),
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
