@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.example.novelreader.ui

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class Menu { None, Main, Category, Edit }

@Composable
fun DetailsScreen(book: Book, vm: LibraryViewModel, toast: (String) -> Unit) {
    val c = AppTheme.colors; val f = AppTheme.fonts
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(Menu.None) }
    var expanded by remember { mutableStateOf(false) }
    var oldestFirst by remember { mutableStateOf(true) }

    val start = if (book.chaptersRead > 0) maxOf(1, book.chaptersRead - 1) else 1
    val end = minOf(book.chapters, start + 7)
    val chapters = (start..end).toList().let { if (oldestFirst) it else it.reversed() }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
            item { Header(book) }
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Stats(book) { vm.toggleLibrary(book.id); toast(if (book.inLibrary) "Removed from your library" else "Added to your library") }
                    Text(book.description, color = c.tx, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 18.dp),
                        maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                    TextButton({ expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (expanded) "Show less" else "Read more", color = c.ac, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        book.genres.forEach {
                            Text(it, color = c.tx, fontSize = 12.sp, modifier = Modifier.border(1.dp, c.ln, RoundedCornerShape(5.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Chapters · ${book.chapters}", Modifier.weight(1f), color = c.tx, fontFamily = f.heading, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                        IconButton({ oldestFirst = !oldestFirst }) {
                            Icon(Icons.AutoMirrored.Rounded.Sort, if (oldestFirst) "Sort: oldest first" else "Sort: newest first", tint = c.tx,
                                modifier = Modifier.size(24.dp).graphicsLayer { rotationX = if (oldestFirst) 0f else 180f })
                        }
                    }
                }
            }
            items(chapters.size) { i ->
                val n = chapters[i]
                ChapterRow(n, book, Modifier.padding(horizontal = 20.dp)) { toast("Opening chapter $n") }
            }
            item {
                TextButton({ toast("Full chapter list") }, Modifier.fillMaxWidth()) { Text("View all ${book.chapters} chapters", color = c.ac, fontWeight = FontWeight.SemiBold) }
            }
        }

        OverlayButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 14.dp, top = 12.dp)) { vm.closeBook() }
        OverlayButton(Icons.Rounded.MoreVert, "More options", Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(end = 14.dp, top = 12.dp)) { menu = Menu.Main }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(c.bg).navigationBarsPadding().padding(18.dp, 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button({ toast("Opening ${book.title}…") }, Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = c.ac, contentColor = c.on)) {
                Text((if (book.chaptersRead > 0) "Resume · Ch ${book.chaptersRead}" else "Start reading").uppercase(), fontFamily = f.ui, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.7.sp)
            }
            OutlinedIconButton({ toast("Downloading chapters…") }, Modifier.size(54.dp), shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, c.ln)) {
                Icon(Icons.Rounded.Download, "Download", tint = c.tx)
            }
        }
    }

    // ----- three-dots menu -----
    if (menu == Menu.Main) AppSheet({ menu = Menu.None }) {
        Text(book.title, color = c.tx, fontFamily = f.heading, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, modifier = Modifier.padding(bottom = 8.dp))
        val close = { menu = Menu.None }
        MenuRow(Icons.Rounded.Edit, "Edit details") { menu = Menu.Edit }
        MenuRow(Icons.Rounded.DoneAll, "Mark all as read") { vm.setAllRead(book.id, true); close(); toast("Marked all as read") }
        MenuRow(Icons.Rounded.RadioButtonUnchecked, "Mark all as unread") { vm.setAllRead(book.id, false); close(); toast("Marked all as unread") }
        MenuRow(Icons.Rounded.Folder, "Add to category") { menu = Menu.Category }
        MenuRow(Icons.Rounded.Share, "Share") {
            close()
            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${book.title} by ${book.author}") }
            ctx.startActivity(Intent.createChooser(send, null))
        }
        MenuRow(Icons.Rounded.Language, "Open in browser") { close(); toast("Opening in browser") /* open source URL */ }
    }
    if (menu == Menu.Category) AppSheet({ menu = Menu.None }) {
        Text("Add to category", color = c.tx, fontFamily = f.heading, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
        FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.categories.forEach { cat ->
                Pill(cat.name, state.categoryMap[book.id].orEmpty().contains(cat.id)) { vm.toggleBookCategory(book.id, cat.id) }
            }
        }
        Button({ menu = Menu.None }, Modifier.fillMaxWidth().padding(top = 24.dp).height(52.dp), shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.ac, contentColor = c.on)) { Text("DONE", fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp) }
    }
    if (menu == Menu.Edit) {
        var title by remember { mutableStateOf(book.title) }
        var author by remember { mutableStateOf(book.author) }
        AlertDialog(
            onDismissRequest = { menu = Menu.None }, containerColor = c.cd, title = { Text("Edit details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                    OutlinedTextField(author, { author = it }, label = { Text("Author") }, singleLine = true)
                }
            },
            confirmButton = { TextButton({ vm.editDetails(book.id, title, author); menu = Menu.None; toast("Details updated") }) { Text("Save") } },
            dismissButton = { TextButton({ menu = Menu.None }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(b: Book) {
    val c = AppTheme.colors; val f = AppTheme.fonts
    Box(Modifier.fillMaxWidth().clipToBounds().background(Brush.linearGradient(listOf(b.c1, b.c2)))) {
        Text(b.glyph, fontFamily = f.brush, fontSize = 300.sp, color = b.accent.copy(alpha = .16f),
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 30.dp, y = 10.dp).blur(7.dp))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(70.dp).background(Brush.verticalGradient(listOf(Color.Transparent, c.bg))))
        Row(
            Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 64.dp, bottom = 34.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Cover(b, Modifier.width(132.dp), glyphSize = 72.sp, titleSize = 14.sp)
            Column(Modifier.weight(1f).padding(bottom = 22.dp)) {
                Text(b.title, color = Color(0xFFF4EFE4), fontFamily = f.heading, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 26.sp)
                MetaLine(Icons.Rounded.Create, b.author, Modifier.padding(top = 12.dp))
                MetaLine(Icons.Rounded.Schedule, "${b.statusLabel} • ${b.source}", Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun MetaLine(icon: ImageVector, text: String, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = Color(0xFFE3DDD0), modifier = Modifier.size(16.dp))
        Text(text, color = Color(0xFFE3DDD0), fontSize = 14.sp)
    }
}

@Composable
private fun Stats(b: Book, onLibrary: () -> Unit) {
    val c = AppTheme.colors; val f = AppTheme.fonts
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        listOf(b.chapters.toString() to "chapters", b.chaptersRead.toString() to "chapters read").forEach { (n, label) ->
            Column(Modifier.weight(1f).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(n, color = c.tx, fontFamily = f.heading, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(label, color = c.mu, fontSize = 11.sp)
            }
        }
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Switch, onClick = onLibrary).padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // only the icon fills; the box keeps the page background
            Icon(if (b.inLibrary) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Library", tint = if (b.inLibrary) c.go else c.tx, modifier = Modifier.size(32.dp))
            Text(if (b.inLibrary) "In library" else "Add to library", color = c.mu, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ChapterRow(n: Int, b: Book, modifier: Modifier, onClick: () -> Unit) {
    val c = AppTheme.colors
    val current = n == b.chaptersRead
    val read = n < b.chaptersRead
    Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Chapter $n", color = if (current) c.ac else if (read) c.mu else c.tx, fontWeight = FontWeight.Bold)
                if (current && b.currentChapterTitle.isNotEmpty()) Text(b.currentChapterTitle, color = c.mu, fontSize = 12.sp)
            }
            if (read) Icon(Icons.Rounded.Check, "Read", tint = c.ac, modifier = Modifier.size(16.dp))
            if (current) Text("Current", color = c.ac, fontSize = 12.sp)
        }
        HorizontalDivider(color = c.ln)
    }
}

@Composable
private fun OverlayButton(icon: ImageVector, desc: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier.size(42.dp).clip(shape).background(Color(0x66000000)).border(1.dp, Color(0x30FFFFFF), shape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = AppTheme.colors
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(role = Role.Button, onClick = onClick), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = c.mu, modifier = Modifier.size(20.dp))
            Text(label, color = c.tx, fontSize = 15.sp)
        }
        HorizontalDivider(color = c.ln)
    }
}
