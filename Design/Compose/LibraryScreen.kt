@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.novelreader.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.time.LocalTime

/** Entry point: library + slide-in details page + toast host. */
@Composable
fun LibraryRoute(vm: LibraryViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    NovelTheme(s.theme) {
        val c = AppTheme.colors
        val snack = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val toast: (String) -> Unit = { m ->
            scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(m, duration = SnackbarDuration.Short) }
        }
        val open = s.books.firstOrNull { it.id == s.openBookId }
        var last by remember { mutableStateOf<Book?>(null) }
        LaunchedEffect(open) { if (open != null) last = open }
        val shown = open ?: last

        Box(
            Modifier.fillMaxSize().background(c.bg).drawBehind {
                drawRect(Brush.radialGradient(listOf(c.glow, Color.Transparent), Offset(size.width / 2, 0f), size.width * .9f))
            },
        ) {
            LibraryScreen(s, vm, toast)
            AnimatedVisibility(open != null, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
                shown?.let { DetailsScreen(it, vm, toast) }
            }
            BackHandler(open != null) { vm.closeBook() }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)) { data ->
                Text(
                    data.visuals.message, color = c.ac, fontSize = 12.sp, fontFamily = AppTheme.fonts.ui,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(c.cd)
                        .border(1.dp, c.ac, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 11.dp),
                )
            }
        }
    }
}

@Composable
fun LibraryScreen(s: LibraryUiState, vm: LibraryViewModel, toast: (String) -> Unit) {
    var showSearch by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showNewCat by remember { mutableStateOf(false) }
    val c = AppTheme.colors
    val books = s.visible
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Scaffold(containerColor = Color.Transparent, bottomBar = { BottomNav() }) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(18.dp, top + 18.dp, 18.dp, pad.calculateBottomPadding() + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    TopBar(s.filterActive, { showSearch = !showSearch }, { showFilter = true })
                    AnimatedVisibility(showSearch) {
                        OutlinedTextField(
                            s.query, vm::setQuery, singleLine = true, shape = RoundedCornerShape(14.dp),
                            placeholder = { Text("Search titles or authors", color = c.mu) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = c.cd, unfocusedContainerColor = c.cd,
                                focusedBorderColor = c.ac, unfocusedBorderColor = c.ln,
                                focusedTextColor = c.tx, unfocusedTextColor = c.tx, cursorColor = c.ac,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        )
                    }
                    CategoryChips(s, vm::selectCategory) { showNewCat = true }
                    SectionLabel("Continue reading", Modifier.padding(top = 26.dp, bottom = 12.dp))
                    HeroCard(s.heroBook) { toast("Resuming ${s.heroBook.title}…") /* hand off to your reader */ }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 26.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        SectionLabel("Your shelf")
                        ViewToggle(s.view, vm::setView)
                    }
                }
            }
            if (books.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Nothing here yet. Try another category or filter.", color = c.mu, modifier = Modifier.padding(vertical = 30.dp))
                }
            } else if (s.view == ViewMode.Grid) {
                items(books, key = { it.id }) { b -> GridBook(b) { vm.openBook(b.id) } }
            } else {
                items(books, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { b -> ListBook(b) { vm.openBook(b.id) } }
            }
        }
    }

    if (showFilter) FilterSheet(s, vm) { showFilter = false }
    if (showNewCat) NewCategoryDialog({ showNewCat = false }) { vm.addCategory(it); toast("Created “${it.trim()}”") }
}

@Composable
private fun TopBar(filterActive: Boolean, onSearch: () -> Unit, onFilter: () -> Unit) {
    val c = AppTheme.colors; val f = AppTheme.fonts
    val hour = remember { LocalTime.now().hour }
    val greeting = when { hour < 12 -> "Good morning"; hour < 18 -> "Good afternoon"; else -> "Good evening" }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("$greeting, Lin Yue".uppercase(), color = c.go, fontFamily = f.heading, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 2.sp)
            Text("Your Library", color = c.tx, fontFamily = f.heading, fontWeight = FontWeight.Medium, fontSize = 28.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SquareIcon(Icons.Rounded.Search, "Search", onSearch)
            SquareIcon(Icons.Rounded.Tune, "Filter and sort", onFilter, dot = filterActive)
        }
    }
}

@Composable
fun SquareIcon(icon: ImageVector, desc: String, onClick: () -> Unit, dot: Boolean = false) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(13.dp)
    Box(
        Modifier.size(42.dp).clip(shape).border(1.dp, c.ln, shape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = c.tx, modifier = Modifier.size(20.dp))
        if (dot) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(8.dp).background(c.ac, CircleShape))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, color = AppTheme.colors.mu, fontFamily = AppTheme.fonts.heading,
        fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.8.sp)
}

@Composable
private fun CategoryChips(s: LibraryUiState, onSelect: (String) -> Unit, onNew: () -> Unit) {
    val all = listOf(Category("all", "All")) + s.categories
    LazyRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(all, key = { it.id }) { Pill(it.name, it.id == s.selectedCategory) { onSelect(it.id) } }
        item { Pill("+ New", false, dashed = true, onClick = onNew) }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, dashed: Boolean = false, onClick: () -> Unit) {
    val c = AppTheme.colors
    val color = if (selected) c.ac else c.mu
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.heightIn(min = 36.dp).clip(shape)
            .then(if (dashed) Modifier.drawBehind {
                drawRoundRect(c.ln, cornerRadius = CornerRadius(20.dp.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            } else Modifier.border(1.dp, if (selected) c.ac else c.ln, shape))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color, fontSize = 12.sp, fontFamily = AppTheme.fonts.ui) }
}

@Composable
private fun HeroCard(b: Book, onClick: () -> Unit) {
    val c = AppTheme.colors; val f = AppTheme.fonts
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.cd).border(1.dp, c.ln, shape)
            .drawBehind {
                drawCircle(Brush.radialGradient(listOf(c.ac.copy(alpha = .2f), Color.Transparent), Offset(size.width - 40.dp.toPx(), 40.dp.toPx()), 100.dp.toPx()),
                    radius = 100.dp.toPx(), center = Offset(size.width - 40.dp.toPx(), 40.dp.toPx()))
            }
            .clickable(onClickLabel = "Resume reading", onClick = onClick).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Cover(b, Modifier.width(92.dp), glyphSize = 52.sp, titleSize = 11.sp)
        Column(Modifier.weight(1f).align(Alignment.CenterVertically)) {
            Text(b.title, color = c.tx, fontFamily = f.heading, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 22.sp)
            Text("Chapter ${b.chaptersRead} · ${b.currentChapterTitle}", color = c.mu, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            LinearProgressIndicator(
                progress = { b.progress }, color = c.ac, trackColor = c.ln, strokeCap = StrokeCap.Round,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(5.dp),
            )
            Text("${(b.progress * 100).toInt()}% of this chapter · ${b.chaptersRead} of ${b.chapters}", color = c.mu, fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp))
            Text(
                "RESUME", color = c.on, fontFamily = f.ui, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.5.sp,
                modifier = Modifier.padding(top = 14.dp).clip(RoundedCornerShape(9.dp)).background(c.ac).padding(horizontal = 38.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ViewToggle(view: ViewMode, onChange: (ViewMode) -> Unit) {
    val c = AppTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.clip(shape).background(c.cd).border(1.dp, c.ln, shape).padding(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(ViewMode.Grid to Icons.Rounded.GridView, ViewMode.List to Icons.AutoMirrored.Rounded.ViewList).forEach { (m, icon) ->
            Box(
                Modifier.size(30.dp, 26.dp).clip(RoundedCornerShape(8.dp)).background(if (view == m) c.ln else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onChange(m) },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, if (m == ViewMode.Grid) "Grid view" else "List view", tint = if (view == m) c.tx else c.mu, modifier = Modifier.size(15.dp)) }
        }
    }
}

/** Book cover drawn from the book's colours + glyph (same look as the HTML .cv). */
@Composable
fun Cover(
    b: Book, modifier: Modifier = Modifier, tag: String? = null,
    glyphSize: androidx.compose.ui.unit.TextUnit = 42.sp, titleSize: androidx.compose.ui.unit.TextUnit = 10.sp, showTitle: Boolean = true,
) {
    val shape = RoundedCornerShape(topStart = 3.dp, topEnd = 6.dp, bottomEnd = 6.dp, bottomStart = 3.dp)
    val f = AppTheme.fonts
    Box(
        modifier.aspectRatio(2f / 3f).shadow(10.dp, shape, ambientColor = Color.Black, spotColor = Color.Black).clip(shape)
            .background(Brush.linearGradient(listOf(b.c1, b.c2)))
            .drawBehind {
                drawRect(Color(0x24FFFFFF), size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
                drawCircle(Brush.radialGradient(listOf(b.accent.copy(alpha = .55f), Color.Transparent), Offset(size.width / 2, size.height * .34f), size.width * .28f),
                    radius = size.width * .28f, center = Offset(size.width / 2, size.height * .34f))
            },
    ) {
        Text(
            b.glyph, color = b.accent, fontFamily = f.brush, fontSize = glyphSize,
            style = TextStyle(shadow = Shadow(b.accent, blurRadius = 24f)),
            modifier = Modifier.fillMaxWidth().layout { m, cons ->
                val p = m.measure(cons)
                layout(p.width, cons.maxHeight) { p.place(0, (cons.maxHeight * .34f - p.height / 2).toInt()) }
            },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (showTitle) Column(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 6.dp, bottom = 9.dp)) {
            Box(Modifier.size(22.dp, 2.dp).background(b.accent))
            Text(b.title, color = Color(0xFFF4EFE4), fontFamily = f.heading, fontWeight = FontWeight.SemiBold, fontSize = titleSize,
                lineHeight = titleSize * 1.25f, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                style = TextStyle(shadow = Shadow(Color(0x99000000), blurRadius = 8f)))
        }
        if (tag != null) Text(
            tag, color = AppTheme.colors.go, fontFamily = f.ui, fontWeight = FontWeight.Bold, fontSize = 8.sp, letterSpacing = 1.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xAA000000)).padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

private fun Book.tag(): String? = if (isNew) "NEW" else if (inLibrary) "SAVED" else null

@Composable
private fun GridBook(b: Book, onClick: () -> Unit) {
    val c = AppTheme.colors; val f = AppTheme.fonts
    Column(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Cover(b, Modifier.fillMaxWidth(), tag = b.tag())
        Text(b.title, color = c.tx, fontFamily = f.heading, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 15.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text("${b.author} · ${b.chapters} ch", color = c.mu, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ListBook(b: Book, onClick: () -> Unit) {
    val c = AppTheme.colors; val f = AppTheme.fonts
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Cover(b, Modifier.width(64.dp), glyphSize = 28.sp, showTitle = false)
        Column(Modifier.weight(1f)) {
            Text(b.title, color = c.tx, fontFamily = f.heading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${b.author} · Ch ${b.chaptersRead}/${b.chapters}", color = c.mu, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BottomNav() {
    val c = AppTheme.colors
    val items = listOf("Library" to Icons.AutoMirrored.Rounded.MenuBook, "Discover" to Icons.Rounded.Explore, "Cultivate" to Icons.Rounded.Adjust, "Profile" to Icons.Rounded.Person)
    NavigationBar(containerColor = c.bg, contentColor = c.mu) {
        items.forEachIndexed { i, (label, icon) ->
            NavigationBarItem(
                selected = i == 0, onClick = { /* navigate */ },
                icon = { Icon(icon, label) },
                label = { Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .6.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = c.ac, selectedTextColor = c.ac, unselectedIconColor = c.mu, unselectedTextColor = c.mu,
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}

// ---------- Sheets & dialogs ----------

@Composable
fun AppSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = AppTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = c.cd, contentColor = c.tx,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) { Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 24.dp).navigationBarsPadding(), content = content) }
}

@Composable
private fun FilterSheet(s: LibraryUiState, vm: LibraryViewModel, onDismiss: () -> Unit) {
    val c = AppTheme.colors; val f = AppTheme.fonts
    AppSheet(onDismiss) {
        Text("Filter & sort", color = c.tx, fontFamily = f.heading, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
        SectionLabel("Sort by", Modifier.padding(top = 20.dp, bottom = 6.dp))
        SortMode.entries.forEach { m ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { vm.setSort(m) }, verticalAlignment = Alignment.CenterVertically) {
                Text(m.label, Modifier.weight(1f), color = if (s.sort == m) c.ac else c.tx)
                if (s.sort == m) Icon(Icons.Rounded.Check, null, tint = c.ac, modifier = Modifier.size(18.dp))
            }
            HorizontalDivider(color = c.ln)
        }
        SectionLabel("Show", Modifier.padding(top = 20.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ShelfFilter.entries.forEach { Pill(it.label, s.filter == it) { vm.setFilter(it) } }
        }
        SectionLabel("Appearance", Modifier.padding(top = 20.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { Pill(it.label, s.theme == it) { vm.setTheme(it) } }
        }
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(vm::resetFilters, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(13.dp),
                border = BorderStroke(1.dp, c.ln), colors = ButtonDefaults.outlinedButtonColors(contentColor = c.tx)) { Text("RESET", letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold) }
            Button(onDismiss, Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.ac, contentColor = c.on)) { Text("DONE", letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun NewCategoryDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = AppTheme.colors.cd,
        title = { Text("New category") },
        text = { OutlinedTextField(name, { name = it.take(24) }, singleLine = true, placeholder = { Text("Category name") }) },
        confirmButton = { TextButton({ if (name.isNotBlank()) { onCreate(name); onDismiss() } }) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
