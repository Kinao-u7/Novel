package com.example.ui.screens.discover

/*
 * Gradle (versions via the Compose BOM):
 *   implementation(platform("androidx.compose:compose-bom:2024.09.00"))
 *   implementation("androidx.compose.material3:material3")          // 1.2+ (ModalBottomSheet, lambda progress)
 *   implementation("androidx.compose.foundation:foundation")
 *   implementation("io.coil-kt:coil-compose:2.7.0")                 // icons from SourceItem.iconUrl / ExtensionItem.iconUrl
 *                                                                   // (Coil 3: import coil3.compose.AsyncImage instead)
 *
 * Colors come from MaterialTheme.colorScheme, so the screen follows whatever light / dark theme the app uses:
 *   primary = Install / selected, tertiary = Update + pin, error = 18+ tag + Uninstall,
 *   onSurfaceVariant = secondary text, outlineVariant = hairlines.
 *
 * Usage:
 *   DiscoverScreen(state = uiState, actions = actions, contentPadding = innerPadding)
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun DiscoverScreen(
    state: DiscoverUiState,
    actions: DiscoverActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = WindowInsets.systemBars.asPaddingValues(),
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            actions.onMessageShown()
        }
    }
    // With extensions turned off there is only the Sources list: no tabs, no settings.
    val tab = if (state.extensionsEnabled) state.tab else DiscoverTab.SOURCES

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = contentPadding.calculateTopPadding() + 18.dp,
                bottom = contentPadding.calculateBottomPadding() + 40.dp,
            ),
        ) {
            item(key = "header") { Header(state, actions) }
            item(key = "search") { SearchField(state.isSearchOpen, state.query, actions.onQueryChange) }
            if (state.extensionsEnabled) {
                item(key = "tabs") { Tabs(tab, state.updateCount, actions.onTabChange) }
            }
            when (tab) {
                DiscoverTab.SOURCES -> sourcesContent(state, actions)
                DiscoverTab.EXTENSIONS -> extensionsContent(state, actions)
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding() + 12.dp),
        )
    }

    if (state.showSettings && state.extensionsEnabled) SettingsSheet(state, actions)
    state.pendingUninstall?.let { UninstallDialog(it, actions) }
}

/* ───────────────────────────── header, search, tabs ───────────────────────────── */

@Composable
private fun Header(state: DiscoverUiState, actions: DiscoverActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(
                "SOURCES & EXTENSIONS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
            )
            Text("Discover", style = MaterialTheme.typography.headlineMedium)
        }
        SquareIconButton(Icons.Default.Search, "Search sources", actions.onToggleSearch, highlighted = state.isSearchOpen)
        if (state.extensionsEnabled) {
            Spacer(Modifier.width(6.dp))
            SquareIconButton(Icons.Default.Settings, "Settings", actions.onOpenSettings)
        }
    }
}

@Composable
private fun SquareIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    val shape = RoundedCornerShape(13.dp)
    Box(
        Modifier
            .size(42.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .background(if (highlighted) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SearchField(open: Boolean, query: String, onQuery: (String) -> Unit) {
    AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        val shape = RoundedCornerShape(14.dp)
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .height(46.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .focusRequester(focus),
            decorationBox = { inner ->
                Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            "Search sources and extensions",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
private fun Tabs(tab: DiscoverTab, updateCount: Int, onTab: (DiscoverTab) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(4.dp),
    ) {
        TabCell("Sources", tab == DiscoverTab.SOURCES, 0, Modifier.weight(1f)) { onTab(DiscoverTab.SOURCES) }
        TabCell("Extensions", tab == DiscoverTab.EXTENSIONS, updateCount, Modifier.weight(1f)) { onTab(DiscoverTab.EXTENSIONS) }
    }
}

@Composable
private fun TabCell(label: String, selected: Boolean, badge: Int, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (badge > 0) {
            Text(
                "$badge",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiary,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.tertiary)
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
    }
}

/* ───────────────────────────── sources tab ───────────────────────────── */

private fun LazyListScope.sourcesContent(state: DiscoverUiState, actions: DiscoverActions) {
    if (state.pinned.isEmpty() && state.sourceGroups.isEmpty()) {
        item(key = "empty") { EmptyState("No installed sources match.", "Add some from the Extensions tab.") }
        return
    }
    if (state.pinned.isNotEmpty()) {
        item(key = "h_pinned") { SectionTitle("Pinned") }
        items(state.pinned, key = { "p_" + it.id }) { SourceRow(it, actions) }
    }
    state.sourceGroups.forEach { group ->
        item(key = "h_" + group.language) { SectionTitle(group.language) }
        items(group.sources, key = { "s_" + it.id }) { SourceRow(it, actions) }
    }
}

@Composable
private fun SourceRow(item: SourceItem, actions: DiscoverActions) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { actions.onOpenSource(item.id) }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceIcon(item.id, item.iconUrl)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                NameLine(item.name, item.isAdult)
                Text(
                    "${item.language} · v${item.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { actions.onTogglePin(item.id) }) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = if (item.isPinned) "Unpin ${item.name}" else "Pin ${item.name}",
                    tint = if (item.isPinned) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                )
            }
        }
        Hairline()
    }
}

/* ───────────────────────────── extensions tab ───────────────────────────── */

private fun LazyListScope.extensionsContent(state: DiscoverUiState, actions: DiscoverActions) {
    if (state.updates.isNotEmpty()) {
        item(key = "h_updates") {
            SectionTitle("Updates pending") {
                TextButton(onClick = actions.onUpdateAll) {
                    Text("UPDATE ALL", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        items(state.updates, key = { "u_" + it.id }) { ExtensionRow(it, actions) }
    }
    if (state.installed.isNotEmpty()) {
        item(key = "h_installed") { SectionTitle("Installed") }
        items(state.installed, key = { "i_" + it.id }) { ExtensionRow(it, actions) }
    }
    if (state.available.isNotEmpty()) {
        item(key = "h_available") { SectionTitle("Available") }
        items(state.available, key = { "a_" + it.id }) { ExtensionRow(it, actions) }
    }
    if (state.updates.isEmpty() && state.installed.isEmpty() && state.available.isEmpty()) {
        item(key = "empty") { EmptyState("No extensions match your language settings.") }
    }
    item(key = "add_repo") {
        Box(Modifier.fillMaxWidth().padding(top = 22.dp), contentAlignment = Alignment.Center) {
            OutlinedButton(onClick = actions.onOpenSettings, shape = RoundedCornerShape(12.dp)) {
                Text("+ Add repository", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ExtensionRow(item: ExtensionItem, actions: DiscoverActions) {
    // The size label is intentionally not shown (removed in the web design).
    val versions = if (item.status == ExtensionStatus.UPDATE_AVAILABLE)
        "v${item.installedVersion ?: "?"} → v${item.availableVersion}"
    else "v${item.installedVersion ?: item.availableVersion}"
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SourceIcon(item.id, item.iconUrl)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                NameLine(item.name, item.isAdult)
                Text(
                    "${item.language} · $versions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            ExtensionAction(item, actions)
        }
        Hairline()
    }
}

@Composable
private fun ExtensionAction(item: ExtensionItem, actions: DiscoverActions) {
    val size = Modifier.widthIn(min = 92.dp).height(38.dp)
    val shape = RoundedCornerShape(11.dp)
    val padding = PaddingValues(horizontal = 14.dp)
    val progress = item.progress
    when {
        progress != null -> Box(size, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.size(26.dp), strokeWidth = 2.6.dp)
        }
        item.status == ExtensionStatus.AVAILABLE -> Button(
            onClick = { actions.onInstall(item.id) }, modifier = size, shape = shape, contentPadding = padding,
        ) { ButtonText("INSTALL") }
        item.status == ExtensionStatus.UPDATE_AVAILABLE -> Button(
            onClick = { actions.onUpdate(item.id) }, modifier = size, shape = shape, contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
            ),
        ) { ButtonText("UPDATE") }
        else -> OutlinedButton(
            onClick = { actions.onRequestUninstall(item.id) }, modifier = size, shape = shape, contentPadding = padding,
        ) { ButtonText("INSTALLED", MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ButtonText(text: String, color: Color = Color.Unspecified) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = color)
}

/* ───────────────────────────── shared pieces ───────────────────────────── */

@Composable
private fun SectionTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(),
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.6.sp,
            fontWeight = FontWeight.SemiBold,
        )
        trailing?.invoke()
    }
}

@Composable
private fun NameLine(name: String, isAdult: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isAdult) {
            val red = MaterialTheme.colorScheme.error
            Text(
                "18+",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = red,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .border(1.dp, red, RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
}

@Composable
private fun EmptyState(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (subtitle != null) {
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Uses the real icon when there is one, otherwise a calligraphy glyph on a gradient derived from the id. */
@Composable
private fun SourceIcon(id: String, iconUrl: String?, size: Dp = 46.dp) {
    val shape = RoundedCornerShape(13.dp)
    if (iconUrl != null) {
        AsyncImage(
            model = iconUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(shape),
        )
    } else {
        val hue = (id.hashCode() and 0x7fffffff) % 360
        val top = Color.hsv(hue.toFloat(), 0.55f, 0.42f)
        val glyphColor = Color.hsv(hue.toFloat(), 0.30f, 0.97f)
        Box(
            Modifier
                .size(size)
                .clip(shape)
                .background(Brush.linearGradient(listOf(top, Color(0xFF071013))))
                .border(1.dp, Color.White.copy(alpha = 0.11f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyphFor(id), fontSize = 25.sp, color = glyphColor)
        }
    }
}

private const val GLYPHS = "蓮玉月竹星梅筆河雲山風火鶴霜"
private fun glyphFor(id: String): String = GLYPHS[(id.hashCode() and 0x7fffffff) % GLYPHS.length].toString()

/* ───────────────────────────── settings sheet & uninstall dialog ───────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsSheet(state: DiscoverUiState, actions: DiscoverActions) {
    var newUrl by remember { mutableStateOf("") }
    LaunchedEffect(state.repositories.size) { newUrl = "" }   // clear the field once a repository was added

    ModalBottomSheet(
        onDismissRequest = actions.onDismissSettings,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            SectionTitle("Languages")
            Text(
                "Choose which languages appear in Sources and Extensions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.languages.forEach { choice ->
                    FilterChip(
                        selected = choice.selected,
                        onClick = { actions.onToggleLanguage(choice.language) },
                        label = { Text(choice.language) },
                    )
                }
            }

            SectionTitle("Repositories")
            Text(
                "Extensions are loaded from these sources. Only add repositories you trust.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            state.repositories.forEachIndexed { index, repo ->
                Column {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Repository ${index + 1}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                repo.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(checked = repo.enabled, onCheckedChange = { actions.onToggleRepository(repo.url) })
                        IconButton(onClick = { actions.onRemoveRepository(repo.url) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove repository")
                        }
                    }
                    Hairline()
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = newUrl,
                    onValueChange = { newUrl = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("https://…/index.json") },
                    isError = state.repositoryError != null,
                    supportingText = state.repositoryError?.let { err -> @Composable { Text(err) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { actions.onAddRepository(newUrl.trim()) }),
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { actions.onAddRepository(newUrl.trim()) },
                    modifier = Modifier.height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("Add", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun UninstallDialog(item: ExtensionItem, actions: DiscoverActions) {
    AlertDialog(
        onDismissRequest = actions.onDismissUninstall,
        title = { Text("Remove ${item.name}?") },
        text = {
            Text("Novels you saved from this source stay in your library, but you will not be able to read new chapters until you reinstall it.")
        },
        confirmButton = {
            TextButton(onClick = actions.onConfirmUninstall) {
                Text("Uninstall", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = actions.onDismissUninstall) { Text("Cancel") } },
    )
}

/* ───────────────────────────── preview ───────────────────────────── */

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun DiscoverSourcesPreview() {
    MaterialTheme {
        DiscoverScreen(
            state = DiscoverUiState(
                extensionsEnabled = true,
                pinned = listOf(SourceItem("lotus", "Lotus Pavilion", "English", "2.4.1", null, false, true)),
                sourceGroups = listOf(
                    LanguageGroup("English", listOf(
                        SourceItem("jade", "Jade Scroll", "English", "1.9.0", null, false, false),
                        SourceItem("silk", "Silk Road Novels", "English", "0.8.3", null, true, false),
                    )),
                    LanguageGroup("中文", listOf(SourceItem("nine", "Nine Rivers Press", "中文", "2.2.0", null, false, false))),
                ),
                updates = listOf(
                    ExtensionItem("jade", "Jade Scroll", "English", "1.9.0", "2.0.0", "52 KB", null, false, ExtensionStatus.UPDATE_AVAILABLE, null),
                ),
            ),
            actions = DiscoverActions.NoOp,
        )
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun DiscoverExtensionsPreview() {
    MaterialTheme {
        DiscoverScreen(
            state = DiscoverUiState(
                tab = DiscoverTab.EXTENSIONS,
                extensionsEnabled = true,
                updates = listOf(
                    ExtensionItem("jade", "Jade Scroll", "English", "1.9.0", "2.0.0", "52 KB", null, false, ExtensionStatus.UPDATE_AVAILABLE, null),
                ),
                installed = listOf(
                    ExtensionItem("lotus", "Lotus Pavilion", "English", "2.4.1", "2.4.1", "38 KB", null, false, ExtensionStatus.INSTALLED, null),
                ),
                available = listOf(
                    ExtensionItem("silk", "Silk Road Novels", "English", null, "0.8.3", "29 KB", null, true, ExtensionStatus.AVAILABLE, null),
                    ExtensionItem("bamboo", "Cloud Bamboo Reader", "中文", null, "1.2.7", "33 KB", null, false, ExtensionStatus.AVAILABLE, 0.6f),
                ),
            ),
            actions = DiscoverActions.NoOp,
        )
    }
}
