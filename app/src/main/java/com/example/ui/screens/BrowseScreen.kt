package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CustomNovelSource
import com.example.data.model.Novel
import com.example.data.repository.NovelRepository
import com.example.ui.theme.BookSerifFontFamily

/**
 * Built-in source catalog item representation.
 */
data class SourceItem(
    val id: String,
    val name: String,
    val url: String,
    val language: String,
    val category: String,
    val description: String,
    val badge: String? = null,
    val isTopPick: Boolean = false
)

private val BUILTIN_SOURCES = listOf(
    SourceItem(
        id = "wtr_lab",
        name = "WTR-Lab",
        url = "https://wtr-lab.com",
        language = "Chinese / Japanese / Korean → English",
        category = "Chinese",
        description = "Extensive machine-translated web novel archive with real-time translation and chapter navigation.",
        badge = "Recommended",
        isTopPick = true
    ),
    SourceItem(
        id = "royal_road",
        name = "Royal Road",
        url = "https://www.royalroad.com",
        language = "English",
        category = "English",
        description = "Premier home for original web serials, LitRPG, progression fantasy, sci-fi, and active author communities.",
        badge = "Originals",
        isTopPick = true
    ),
    SourceItem(
        id = "light_novel_pub",
        name = "Light Novel Pub",
        url = "https://lightnovelpub.vip",
        language = "English",
        category = "English",
        description = "Popular aggregator for translated Asian light novels, progression fantasy, and Eastern cultivation serials.",
        badge = "Popular",
        isTopPick = true
    ),
    SourceItem(
        id = "novel_full",
        name = "NovelFull",
        url = "https://novelfull.net",
        language = "English",
        category = "Chinese",
        description = "Massive online library of translated Eastern web novels, xianxia, wuxia, and fantasy epics.",
        badge = "High Traffic",
        isTopPick = true
    ),
    SourceItem(
        id = "standard_ebooks",
        name = "Standard Ebooks",
        url = "https://standardebooks.org",
        language = "English",
        category = "Classics",
        description = "Free, carefully formatted, and beautifully typeset public domain literature editions.",
        badge = "Public Domain",
        isTopPick = true
    ),
    SourceItem(
        id = "project_gutenberg",
        name = "Project Gutenberg",
        url = "https://www.gutenberg.org",
        language = "Multilingual",
        category = "Classics",
        description = "Over 70,000 free literary classics and historical public domain books.",
        badge = "Free Archive",
        isTopPick = false
    ),
    SourceItem(
        id = "scribble_hub",
        name = "Scribble Hub",
        url = "https://www.scribblehub.com",
        language = "English",
        category = "English",
        description = "Vibrant community hosting original romance, isekai, fantasy, and slice-of-life web fiction.",
        badge = "Community",
        isTopPick = false
    ),
    SourceItem(
        id = "syosetu",
        name = "Shousetsuka ni Narou",
        url = "https://syosetu.com",
        language = "Japanese",
        category = "Japanese",
        description = "The largest Japanese user-published web novel site and origin of popular light novels.",
        badge = "Raw Source",
        isTopPick = false
    ),
    SourceItem(
        id = "munpia",
        name = "Munpia",
        url = "https://www.munpia.com",
        language = "Korean",
        category = "Korean",
        description = "Major Korean web fiction portal, birthplace of countless famous modern hunter and regression novels.",
        badge = "Raw Source",
        isTopPick = false
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    repository: NovelRepository,
    onOpenNovel: (Novel) -> Unit = {},
    onOpenWebBrowser: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val customSources by repository.customSources.collectAsState()
    val requestedSources by repository.requestedSources.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategoryFilter by remember { mutableStateOf("All") }

    // Dialog states
    var showAddSourceDialog by remember { mutableStateOf(false) }
    var newSourceName by remember { mutableStateOf("") }
    var newSourceUrl by remember { mutableStateOf("https://") }
    var newSourceLanguage by remember { mutableStateOf("English") }

    var showRequestSourceDialog by remember { mutableStateOf(false) }
    var requestSiteName by remember { mutableStateOf("") }
    var requestSiteUrl by remember { mutableStateOf("https://") }

    val categories = listOf("All", "Top Picks", "English", "Chinese", "Japanese", "Korean", "Classics", "Custom")

    // Filtered lists
    val filteredBuiltin = remember(searchQuery, selectedCategoryFilter) {
        BUILTIN_SOURCES.filter { src ->
            val matchesCategory = when (selectedCategoryFilter) {
                "All" -> true
                "Top Picks" -> src.isTopPick
                "Custom" -> false
                else -> src.category.equals(selectedCategoryFilter, ignoreCase = true) ||
                        src.language.contains(selectedCategoryFilter, ignoreCase = true)
            }
            val matchesSearch = searchQuery.isBlank() ||
                    src.name.contains(searchQuery, ignoreCase = true) ||
                    src.description.contains(searchQuery, ignoreCase = true) ||
                    src.language.contains(searchQuery, ignoreCase = true) ||
                    src.url.contains(searchQuery, ignoreCase = true)

            matchesCategory && matchesSearch
        }
    }

    val filteredCustom = remember(searchQuery, selectedCategoryFilter, customSources) {
        if (selectedCategoryFilter != "All" && selectedCategoryFilter != "Custom") {
            emptyList()
        } else {
            customSources.filter { src ->
                searchQuery.isBlank() ||
                        src.name.contains(searchQuery, ignoreCase = true) ||
                        src.url.contains(searchQuery, ignoreCase = true) ||
                        src.language.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Screen Title: "Discover"
            item {
                Column {
                    Text(
                        text = "Discover",
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontFamily = BookSerifFontFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Explore web novel sources, archives & reading platforms",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Entry Point: "Open web browser" Button
            // Restyled to visually match the search bar: 10.dp corner radius, 52.dp height, consistent outline
            item {
                Surface(
                    onClick = { onOpenWebBrowser("https://wtr-lab.com") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("discover_open_browser_btn"),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Open In-App Web Browser",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Navigate to Browser",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Search Bar: Matches the Open Web Browser button in height, corner radius & padding
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search sources or enter URL...", fontSize = 13.5.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("discover_search_bar"),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }

            // Direct URL action if user types an address
            if (searchQuery.contains(".") && !searchQuery.contains(" ")) {
                item {
                    val directUrl = if (searchQuery.startsWith("http://") || searchQuery.startsWith("https://")) {
                        searchQuery
                    } else {
                        "https://$searchQuery"
                    }
                    Surface(
                        onClick = { onOpenWebBrowser(directUrl) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInBrowser,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Open \"$directUrl\" in browser",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Category & Language Filter Chips
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    items(categories) { category ->
                        val isSelected = selectedCategoryFilter == category
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedCategoryFilter = category },
                            label = {
                                Text(
                                    text = category,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
            }

            // Section: Top Picks (if All or Top Picks selected)
            if (selectedCategoryFilter == "All" || selectedCategoryFilter == "Top Picks") {
                val topPicks = filteredBuiltin.filter { it.isTopPick }
                if (topPicks.isNotEmpty()) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Top Picks",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    items(topPicks, key = { "top_" + it.id }) { source ->
                        SourceCard(
                            source = source,
                            onOpen = { onOpenWebBrowser(source.url) }
                        )
                    }
                }
            }

            // Section: Language & Other Builtin Sources
            val otherSources = if (selectedCategoryFilter == "All") {
                filteredBuiltin.filter { !it.isTopPick }
            } else if (selectedCategoryFilter != "Top Picks" && selectedCategoryFilter != "Custom") {
                filteredBuiltin
            } else {
                emptyList()
            }

            if (otherSources.isNotEmpty()) {
                item {
                    val sectionLabel = if (selectedCategoryFilter == "All") "More Sources & Platforms" else "$selectedCategoryFilter Sources"
                    Text(
                        text = sectionLabel,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }

                items(otherSources, key = { "src_" + it.id }) { source ->
                    SourceCard(
                        source = source,
                        onOpen = { onOpenWebBrowser(source.url) }
                    )
                }
            }

            // Section: Custom Sources
            if (selectedCategoryFilter == "All" || selectedCategoryFilter == "Custom") {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Custom Sources",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${customSources.size} added",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (filteredCustom.isEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "No custom sources added yet",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Add any web fiction portal, forum, or novel archive URL.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                } else {
                    items(filteredCustom, key = { "custom_" + it.id }) { source ->
                        CustomSourceCard(
                            source = source,
                            onOpen = { onOpenWebBrowser(source.url) },
                            onDelete = { repository.removeCustomSource(source.id) }
                        )
                    }
                }
            }

            // Action Buttons: "Add a custom source" & "Request a source"
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Add Custom Source Button
                    OutlinedButton(
                        onClick = {
                            newSourceName = ""
                            newSourceUrl = "https://"
                            newSourceLanguage = "English"
                            showAddSourceDialog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("discover_add_custom_source_btn"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddLink,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Add a custom source",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Request a Source Button
                    OutlinedButton(
                        onClick = {
                            requestSiteName = ""
                            requestSiteUrl = "https://"
                            showRequestSourceDialog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("discover_request_source_btn"),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Feedback,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Request a source",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Dialog: Add Custom Source
        if (showAddSourceDialog) {
            AlertDialog(
                onDismissRequest = { showAddSourceDialog = false },
                title = { Text("Add Custom Source", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Enter the details of the web novel site you would like to browse and scrape from.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = newSourceName,
                            onValueChange = { newSourceName = it },
                            label = { Text("Source Name") },
                            placeholder = { Text("e.g. My Novel Site") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newSourceUrl,
                            onValueChange = { newSourceUrl = it },
                            label = { Text("Website URL") },
                            placeholder = { Text("https://example.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newSourceLanguage,
                            onValueChange = { newSourceLanguage = it },
                            label = { Text("Language / Region") },
                            placeholder = { Text("e.g. English, Chinese") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newSourceName.isNotBlank() && newSourceUrl.isNotBlank()) {
                                repository.addCustomSource(
                                    name = newSourceName.trim(),
                                    url = newSourceUrl.trim(),
                                    language = newSourceLanguage.trim()
                                )
                                showAddSourceDialog = false
                                Toast.makeText(context, "Added source: $newSourceName", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = newSourceName.isNotBlank() && newSourceUrl.isNotBlank()
                    ) {
                        Text("Add Source")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddSourceDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Dialog: Request a Source
        if (showRequestSourceDialog) {
            AlertDialog(
                onDismissRequest = { showRequestSourceDialog = false },
                title = { Text("Request a Source", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Suggest a novel source or website you'd like official support for:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = requestSiteName,
                            onValueChange = { requestSiteName = it },
                            label = { Text("Site Name / Platform") },
                            placeholder = { Text("e.g. KakaoPage, Joara") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = requestSiteUrl,
                            onValueChange = { requestSiteUrl = it },
                            label = { Text("URL or Note (Optional)") },
                            placeholder = { Text("https://...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (requestSiteName.isNotBlank()) {
                                repository.requestSource(
                                    siteName = requestSiteName.trim(),
                                    url = requestSiteUrl.trim()
                                )
                                showRequestSourceDialog = false
                                Toast.makeText(context, "Thank you! Source request recorded.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = requestSiteName.isNotBlank()
                    ) {
                        Text("Submit Request")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRequestSourceDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

/**
 * Modern M3 card displaying a novel source.
 */
@Composable
private fun SourceCard(
    source: SourceItem,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .testTag("source_card_${source.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = source.name.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = source.url.removePrefix("https://").removePrefix("http://"),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (source.badge != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = source.badge,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = source.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = source.language,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Browse Source",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/**
 * Modern M3 card displaying a user-added custom source.
 */
@Composable
private fun CustomSourceCard(
    source: CustomNovelSource,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .testTag("custom_source_${source.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = source.url.removePrefix("https://").removePrefix("http://"),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete custom source",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = source.language,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Open in Browser",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
