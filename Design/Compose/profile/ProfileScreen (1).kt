package com.example.ui.screens.profile

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.theme.Xian
import com.example.ui.theme.XianColors
import com.example.ui.theme.XianFonts
import com.example.ui.theme.XianTheme
import com.example.ui.theme.xianBackdrop
import kotlinx.coroutines.delay

/**
 * Settings / Profile screen. Stateless: everything comes from [state], everything goes out through [actions].
 * Wrap in XianTheme(...) higher up. [contentPadding] is for a host bottom bar (nav-bar insets are handled here).
 */
@Composable
fun ProfileScreen(
    state: ProfileUiState,
    actions: ProfileActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val c = Xian.colors
    var searchOpen by rememberSaveable { mutableStateOf(state.searchQuery.isNotEmpty()) }
    var editName by rememberSaveable { mutableStateOf(false) }
    var pendingKey by rememberSaveable { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val k = pendingKey; pendingKey = null
        if (k != null && uri != null) actions.onPicked(k, uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val k = pendingKey; pendingKey = null
        if (k != null && uri != null) actions.onPicked(k, uri)
    }
    val onActionRow: (ActionRow) -> Unit = { r ->
        when (r.picker) {
            PickerKind.FOLDER -> { pendingKey = r.key; folderPicker.launch(null) }
            PickerKind.BACKUP_FILE -> { pendingKey = r.key; filePicker.launch(arrayOf("*/*")) }
            PickerKind.NONE -> actions.onAction(r.key)
        }
    }

    val open = state.categories.firstOrNull { it.id == state.openCategoryId }
    var lastOpen by remember { mutableStateOf<SettingsCategory?>(null) }
    LaunchedEffect(open) { if (open != null) lastOpen = open }

    BackHandler(enabled = state.openCategoryId != null || searchOpen) {
        if (state.openCategoryId != null) actions.onCloseCategory()
        else { searchOpen = false; actions.onSearchChange("") }
    }

    Box(modifier.fillMaxSize().xianBackdrop(c)) {
        SettingsList(
            state, actions, onActionRow, contentPadding, searchOpen,
            onToggleSearch = { searchOpen = !searchOpen; if (!searchOpen) actions.onSearchChange("") },
            onEditProfile = { editName = true }
        )
        AnimatedVisibility(
            visible = open != null,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it }
        ) {
            (open ?: lastOpen)?.let { CategoryPage(it, actions, onActionRow, contentPadding) }
        }
        Toast(state.message, actions.onMessageShown, contentPadding)
    }

    state.confirm?.let { ConfirmSheet(it, actions) }
    if (editName) NameDialog(state.header.displayName, { editName = false }) { actions.onSaveName(it); editName = false }
}

@Composable
private fun SettingsList(
    state: ProfileUiState, actions: ProfileActions, onActionRow: (ActionRow) -> Unit,
    contentPadding: PaddingValues, searchOpen: Boolean, onToggleSearch: () -> Unit, onEditProfile: () -> Unit
) {
    val c = Xian.colors
    val bars = WindowInsets.systemBars.asPaddingValues()
    val searching = state.searchQuery.isNotBlank()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp, end = 18.dp,
            top = bars.calculateTopPadding() + 14.dp,
            bottom = bars.calculateBottomPadding() + contentPadding.calculateBottomPadding() + 30.dp
        )
    ) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("Preferences")
                    Text("Settings", color = c.tx, fontFamily = XianFonts.heading, fontWeight = FontWeight.Medium, fontSize = 28.sp, modifier = Modifier.semantics { heading() })
                }
                SquareButton(if (searchOpen) XIcons.Close else XIcons.Search, if (searchOpen) "Close search" else "Search settings", onToggleSearch)
            }
        }
        item(key = "search") {
            AnimatedVisibility(searchOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                SearchField(state.searchQuery, actions.onSearchChange, Modifier.padding(top = 14.dp))
            }
        }
        if (!searching) item(key = "me") { ProfileHeaderCard(state.header, onEditProfile) }

        when {
            state.isLoading -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 56.dp), Alignment.Center) {
                    CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
            }
            searching && state.categories.isEmpty() -> item(key = "empty") {
                Text("No settings match “${state.searchQuery.trim()}”.", color = c.mu, fontFamily = XianFonts.ui, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 46.dp, horizontal = 10.dp))
            }
            searching -> state.categories.forEach { cat ->
                item(key = "h_${cat.id}") {
                    Row(Modifier.padding(start = 4.dp, top = 26.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        XIcon(XIcons.forCategory(cat.id), c.accent, 16.dp)
                        SectionLabel(cat.title)
                    }
                }
                item(key = "g_${cat.id}") {
                    Column { cat.rows.forEachIndexed { i, r -> SettingRowItem(r, i == 0, actions, onActionRow) } }
                }
            }
            else -> SettingsGroup.values().forEach { g ->
                val cats = state.categories.filter { it.group == g }
                if (cats.isNotEmpty()) {
                    item(key = "gl_$g") { SectionLabel(groupTitle(g), Modifier.padding(start = 4.dp, top = 24.dp, bottom = 10.dp)) }
                    item(key = "gg_$g") {
                        Column {
                            cats.forEachIndexed { i, cat ->
                                if (i > 0) HorizontalDivider(color = c.ln)
                                CategoryRow(cat) { actions.onOpenCategory(cat.id) }
                            }
                        }
                    }
                }
            }
        }
        item(key = "ver") {
            Column(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("XIAN READER", color = c.tx, fontFamily = XianFonts.heading, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.3.sp)
                if (state.appVersion.isNotBlank()) Text("Version ${state.appVersion}", color = c.mu, fontFamily = XianFonts.ui, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CategoryPage(cat: SettingsCategory, actions: ProfileActions, onActionRow: (ActionRow) -> Unit, contentPadding: PaddingValues) {
    val c = Xian.colors
    val bars = WindowInsets.systemBars.asPaddingValues()
    Column(
        Modifier.fillMaxSize().xianBackdrop(c).pointerInput(Unit) { detectTapGestures { } }
    ) {
        Row(
            Modifier.padding(start = 18.dp, end = 18.dp, top = bars.calculateTopPadding() + 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SquareButton(XIcons.Back, "Back", actions.onCloseCategory)
            Column {
                Text(cat.title, color = c.tx, fontFamily = XianFonts.heading, fontWeight = FontWeight.Medium, fontSize = 24.sp, modifier = Modifier.semantics { heading() })
                Text(cat.summary, color = c.mu, fontFamily = XianFonts.ui, fontSize = 12.sp)
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = bars.calculateBottomPadding() + contentPadding.calculateBottomPadding() + 30.dp)
        ) {
            itemsIndexed(cat.rows, key = { _, r -> r.key }) { i, r -> SettingRowItem(r, i == 0, actions, onActionRow) }
        }
    }
}

@Composable
private fun ProfileHeaderCard(h: ProfileHeader, onClick: () -> Unit) {
    val c = Xian.colors
    Column(Modifier.padding(top = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Edit profile", onClick = onClick).padding(start = 2.dp, end = 2.dp, top = 6.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val seal = Color(0xFFC8412F)
            Box(
                Modifier.size(54.dp).rotate(-5f)
                    .shadow(14.dp, RoundedCornerShape(9.dp), ambientColor = seal, spotColor = seal)
                    .background(seal, RoundedCornerShape(9.dp)),
                Alignment.Center
            ) { Text("道", color = Color(0xFFFFF1E6), fontFamily = XianFonts.brush, fontSize = 30.sp) }
            Column(Modifier.weight(1f)) {
                Text(h.displayName, color = c.tx, fontFamily = XianFonts.heading, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 1)
                if (h.realmLabel.isNotBlank()) Text(h.realmLabel, color = c.mu, fontFamily = XianFonts.ui, fontSize = 14.sp)
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(5.dp)).background(c.ln)) {
                    Box(Modifier.fillMaxWidth(h.realmProgress.coerceIn(0f, 1f)).height(5.dp).background(Brush.horizontalGradient(listOf(c.accent, c.go))))
                }
            }
            XIcon(XIcons.Chevron, c.mu, 16.dp)
        }
        HorizontalDivider(color = c.ln)
    }
}

@Composable
private fun CategoryRow(cat: SettingsCategory, onClick: () -> Unit) {
    val c = Xian.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 2.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(c.ln), Alignment.Center) { XIcon(XIcons.forCategory(cat.id), c.accent, 21.dp) }
        Column(Modifier.weight(1f)) {
            Text(cat.title, color = c.tx, fontFamily = XianFonts.ui, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(cat.summary, color = c.mu, fontFamily = XianFonts.ui, fontSize = 12.sp, maxLines = 1)
        }
        XIcon(XIcons.Chevron, c.mu, 16.dp)
    }
}

@Composable
private fun SquareButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = Xian.colors
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).border(1.dp, c.ln, RoundedCornerShape(13.dp))
            .clickable(onClickLabel = label, onClick = onClick),
        Alignment.Center
    ) { XIcon(icon, c.tx) }
}

@Composable
private fun Eyebrow(text: String) {
    Text(text.uppercase(), color = Xian.colors.go, fontFamily = XianFonts.heading, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 2.2.sp)
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = Xian.colors.mu, fontFamily = XianFonts.heading, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.7.sp,
        modifier = modifier.semantics { heading() })
}

private fun groupTitle(g: SettingsGroup) = when (g) {
    SettingsGroup.GENERAL -> "General"
    SettingsGroup.READING_LIFE -> "Reading life"
    SettingsGroup.DATA_INFO -> "Data & info"
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Xian.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = query, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(color = c.tx, fontSize = 14.sp, fontFamily = XianFonts.ui),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier.fillMaxWidth().focusRequester(focus),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp)).background(c.cd)
                    .border(1.dp, c.ln, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp),
                Alignment.CenterStart
            ) {
                if (query.isEmpty()) Text("Search settings", color = c.mu, fontFamily = XianFonts.ui, fontSize = 14.sp)
                inner()
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmSheet(req: ConfirmRequest, actions: ProfileActions) {
    val c = Xian.colors
    ModalBottomSheet(
        onDismissRequest = actions.onDismissConfirm,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.cd, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = {
            Box(Modifier.padding(top = 12.dp, bottom = 4.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(4.dp)).background(c.ln))
        }
    ) {
        Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 24.dp).navigationBarsPadding()) {
            Text(req.title, color = c.tx, fontFamily = XianFonts.heading, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
            Text(req.message, color = c.mu, fontFamily = XianFonts.ui, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetButton("Cancel", Modifier.weight(1f), c.tx, null, actions.onDismissConfirm)
                SheetButton(
                    req.confirmLabel, Modifier.weight(1f),
                    if (req.isDanger) Color.White else c.on, if (req.isDanger) c.rd else c.accent, actions.onConfirm
                )
            }
        }
    }
}

@Composable
private fun SheetButton(label: String, modifier: Modifier, fg: Color, bg: Color?, onClick: () -> Unit) {
    val c = Xian.colors
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier.clip(shape).then(if (bg != null) Modifier.background(bg) else Modifier.border(1.dp, c.ln, shape))
            .clickable(onClick = onClick).padding(14.dp),
        Alignment.Center
    ) { Text(label.uppercase(), color = fg, fontFamily = XianFonts.ui, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.4.sp) }
}

@Composable
private fun NameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val c = Xian.colors
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(26.dp), color = c.cd) {
            Column(Modifier.padding(20.dp)) {
                Text("Your name", color = c.tx, fontFamily = XianFonts.heading, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                Spacer(Modifier.height(14.dp))
                BasicTextField(
                    value = text, onValueChange = { text = it.take(40) }, singleLine = true,
                    textStyle = TextStyle(color = c.tx, fontSize = 16.sp, fontFamily = XianFonts.ui),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, c.ln, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp), Alignment.CenterStart) { inner() }
                    }
                )
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("Cancel", color = c.mu) }
                    TextButton({ onSave(text.trim()) }, enabled = text.isNotBlank()) { Text("Save", color = c.accent, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Toast(message: String?, onShown: () -> Unit, contentPadding: PaddingValues) {
    val c = Xian.colors
    var shown by remember { mutableStateOf("") }
    LaunchedEffect(message) {
        if (message != null) { shown = message; delay(2300); onShown() }
    }
    AnimatedVisibility(
        visible = message != null,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(bottom = contentPadding.calculateBottomPadding() + 24.dp, start = 24.dp, end = 24.dp),
        enter = fadeIn(), exit = fadeOut()
    ) {
        Text(
            shown, color = c.accent, fontFamily = XianFonts.ui, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                .clip(RoundedCornerShape(20.dp)).background(c.cd)
                .border(1.dp, c.accent, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 11.dp)
        )
    }
}

// ---------------------------------------------------------------- previews

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
                        SwatchOption("ink", "Ink", 0xFF0A1214, 0xFF7CF2C3), SwatchOption("paper", "Paper", 0xFFF6F3EC, 0xFF1F7A62),
                        SwatchOption("jade", "Jade", 0xFF06231B, 0xFF9AF6C9)
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

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, heightDp = 800)
@Composable private fun PreviewInk() = XianTheme("ink") { ProfileScreen(previewState, ProfileActions.NoOp) }

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, heightDp = 800)
@Composable private fun PreviewPaper() = XianTheme("paper") { ProfileScreen(previewState, ProfileActions.NoOp) }

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, heightDp = 800)
@Composable private fun PreviewJadeReader() =
    XianTheme("jade") { ProfileScreen(previewState.copy(openCategoryId = SettingsCategoryIds.READER), ProfileActions.NoOp) }
