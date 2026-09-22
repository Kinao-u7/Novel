package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.ClipDescription
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.Novel
import com.example.data.repository.NovelRepository
import com.example.data.source.webscrape.ScrapedChapter
import com.example.data.source.webscrape.ScrapedNovelData
import com.example.data.source.webscrape.ScrapeDiagnosticsManager
import com.example.data.source.webscrape.WebScrapeScripts
import com.example.data.source.webscrape.WtrLabExtractor
import com.example.ui.components.NovelCoverImage
import com.example.ui.theme.BookSerifFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private fun resolveAbsoluteUrl(baseUrl: String, relativeUrl: String?): String? {
    if (relativeUrl.isNullOrBlank()) return null
    val trimmed = relativeUrl.trim()
    return try {
        when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("data:") -> trimmed
            else -> {
                val base = java.net.URI(baseUrl)
                base.resolve(trimmed).toString()
            }
        }
    } catch (e: Exception) {
        if (trimmed.startsWith("//")) "https:$trimmed"
        else trimmed
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebBrowserScreen(
    initialUrl: String = "https://wtr-lab.com",
    repository: NovelRepository? = null,
    onBack: () -> Unit,
    onNovelSaved: ((Novel) -> Unit)? = null,
    onScrapedNovel: ((ScrapedNovelData) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val addressBarFocusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var loadProgress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    // Address bar input state with text and selection
    var urlInputValue by remember {
        mutableStateOf(TextFieldValue(initialUrl, TextRange(0, initialUrl.length)))
    }
    var isAddressBarFocused by remember { mutableStateOf(false) }

    // Scraping overlay & bottom sheet state
    var showAddNovelSheet by remember { mutableStateOf(false) }
    var sheetUrlInput by remember { mutableStateOf(initialUrl) }
    var isScraping by remember { mutableStateOf(false) }
    var scrapeErrorMessage by remember { mutableStateOf<String?>(null) }
    var scrapedNovelData by remember { mutableStateOf<ScrapedNovelData?>(null) }

    fun navigateTo(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return

        val targetUrl = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else if (trimmed.contains(".") && !trimmed.contains(" ")) {
            "https://$trimmed"
        } else {
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
        }

        currentUrl = targetUrl
        sheetUrlInput = targetUrl
        urlInputValue = TextFieldValue(targetUrl, TextRange(targetUrl.length))
        focusManager.clearFocus()
        keyboardController?.hide()
        webViewInstance?.loadUrl(targetUrl)
    }

    // Hardware back navigation
    BackHandler {
        if (showAddNovelSheet) {
            showAddNovelSheet = false
        } else if (isAddressBarFocused) {
            focusManager.clearFocus()
            keyboardController?.hide()
        } else if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        } else {
            onBack()
        }
    }

    // Trigger JS scraping on current or custom URL with pre-scroll & TOC trigger
    fun runScrapingPass(customUrl: String? = null) {
        val webView = webViewInstance ?: return

        val targetUrl = if (!customUrl.isNullOrBlank()) customUrl.trim() else currentUrl.trim()
        Log.d("NOVEL_SCRAPE_DIAG", "================== NOVEL EXTRACTION PASS INITIATED ==================")
        Log.d("NOVEL_SCRAPE_DIAG", "Target URL: $targetUrl (current WebView URL: $currentUrl)")
        ScrapeDiagnosticsManager.startNewRun(context, targetUrl)

        if (!customUrl.isNullOrBlank() && customUrl.trim() != currentUrl.trim()) {
            navigateTo(customUrl)
        }

        isScraping = true
        scrapeErrorMessage = null

        val isWtrLab = targetUrl.contains("wtr-lab.com", ignoreCase = true) || currentUrl.contains("wtr-lab.com", ignoreCase = true)

        val processScrapedJson: (String?) -> Unit = { rawJson ->
            scope.launch {
                isScraping = false
                try {
                    if (rawJson.isNullOrBlank() || rawJson == "null") {
                        Log.d("NOVEL_SCRAPE_DIAG", "[ERROR] evaluateJavascript returned null or blank response.")
                        ScrapeDiagnosticsManager.log(context, "[ERROR] evaluateJavascript returned null or blank response.")
                        ScrapeDiagnosticsManager.finishRun(context, "FAILED - evaluateJavascript returned null or blank")
                        scrapeErrorMessage = "No novel details could be extracted from this page."
                        return@launch
                    }

                    val unescaped = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                        org.json.JSONTokener(rawJson).nextValue().toString()
                    } else {
                        rawJson
                    }

                    val json = JSONObject(unescaped)

                    // Output all diagnostic logs collected by JavaScript
                    Log.d("NOVEL_SCRAPE_DIAG", "====================== NOVEL EXTRACTION REPORT START ======================")
                    Log.d("NOVEL_SCRAPE_DIAG", "Report Target URL: $currentUrl")
                    ScrapeDiagnosticsManager.log(context, "====================== NOVEL EXTRACTION REPORT START ======================")
                    ScrapeDiagnosticsManager.log(context, "Report Target URL: $currentUrl")

                    val diagLogsArr = json.optJSONArray("_diagnosticLogs")
                    if (diagLogsArr != null) {
                        for (dIdx in 0 until diagLogsArr.length()) {
                            val logMsg = diagLogsArr.optString(dIdx, "")
                            Log.d("NOVEL_SCRAPE_DIAG", logMsg)
                            ScrapeDiagnosticsManager.log(context, logMsg)
                        }
                    }

                    // Output site structure diagnostics (HTML of container, JSON-LD tag, __NEXT_DATA__)
                    val siteDiag = json.optJSONObject("_siteStructureDiag")
                    if (siteDiag != null) {
                        val nextExists = siteDiag.optBoolean("nextDataExists", false)
                        val nextKeysArr = siteDiag.optJSONArray("nextDataKeys")
                        val nextKeysStr = if (nextKeysArr != null) {
                            (0 until nextKeysArr.length()).map { nextKeysArr.optString(it) }.joinToString(", ")
                        } else "none"
                        Log.d("NOVEL_SCRAPE_DIAG", "--- SITE STRUCTURE DIAGNOSTICS ---")
                        Log.d("NOVEL_SCRAPE_DIAG", "[SITE_STRUCTURE] window.__NEXT_DATA__ exists: $nextExists | keys: [$nextKeysStr]")
                        ScrapeDiagnosticsManager.log(context, "--- SITE STRUCTURE DIAGNOSTICS ---")
                        ScrapeDiagnosticsManager.log(context, "[SITE_STRUCTURE] window.__NEXT_DATA__ exists: $nextExists | keys: [$nextKeysStr]")

                        val headJsonLd = siteDiag.optString("headJsonLdOuterHtml", "none")
                        Log.d("NOVEL_SCRAPE_DIAG", "[SITE_STRUCTURE] Head <script type=\"application/ld+json\"> tag outerHTML:")
                        logDiagChunked("HEAD_JSON_LD", headJsonLd)
                        ScrapeDiagnosticsManager.log(context, "[SITE_STRUCTURE] Head <script type=\"application/ld+json\"> tag outerHTML:\n$headJsonLd")

                        val containerHtml = siteDiag.optString("chapterContainerOuterHtml", "none")
                        Log.d("NOVEL_SCRAPE_DIAG", "[SITE_STRUCTURE] Candidate chapter container outerHTML:")
                        logDiagChunked("CHAPTER_CONTAINER", containerHtml)
                        ScrapeDiagnosticsManager.log(context, "[SITE_STRUCTURE] Candidate chapter container outerHTML:\n$containerHtml")
                    }
                    Log.d("NOVEL_SCRAPE_DIAG", "====================== NOVEL EXTRACTION REPORT END ========================")
                    ScrapeDiagnosticsManager.log(context, "====================== NOVEL EXTRACTION REPORT END ========================")

                    var title = WtrLabExtractor.cleanWtrLabTokens(json.optString("title", "").trim())
                    var author = WtrLabExtractor.cleanWtrLabTokens(json.optString("author", "Unknown Author").trim())
                    val sourceUrl = json.optString("sourceUrl", currentUrl).ifBlank { currentUrl }
                    var coverUrl = json.optString("coverUrl", "").takeIf { it.isNotBlank() }
                    coverUrl = resolveAbsoluteUrl(sourceUrl, coverUrl)
                    var description = WtrLabExtractor.cleanWtrLabTokens(json.optString("description", "").trim())
                    var extractedRating: Float? = json.optDouble("rating", 0.0).toFloat().takeIf { it > 0f }

                    val genresList = mutableListOf<String>()
                    val gArr = json.optJSONArray("genres")
                    if (gArr != null) {
                        for (i in 0 until gArr.length()) {
                            val g = gArr.optString(i, "").trim()
                            if (g.isNotBlank() && !genresList.contains(g)) genresList.add(g)
                        }
                    }

                    val chaptersList = mutableListOf<ScrapedChapter>()
                    val chArr = json.optJSONArray("chapters")
                    if (chArr != null) {
                        for (i in 0 until chArr.length()) {
                            val cObj = chArr.optJSONObject(i) ?: continue
                            val chTitle = cObj.optString("title", "").trim()
                            val chUrl = cObj.optString("url", "").trim()
                            val chOrder = cObj.optInt("order", i + 1)
                            if (chUrl.isNotBlank()) {
                                chaptersList.add(
                                    ScrapedChapter(
                                        title = chTitle.ifBlank { "Chapter ${i + 1}" },
                                        url = chUrl,
                                        order = chOrder
                                    )
                                )
                            }
                        }
                    }

                    // WTR-Lab Specific fallback/enhancement:
                    // If WTR-Lab is detected, ensure metadata and chapters are completely loaded
                    val isWtrLabDetected = isWtrLab || json.optBoolean("isWtrLab", false)
                    var detectedRawId: String? = null
                    var detectedLocale: String? = null

                    if (isWtrLabDetected) {
                        val rawId = json.optString("rawId", "")
                        val chapterCount = json.optInt("chapterCount", 0)
                        val locale = json.optString("locale", "en").ifBlank { "en" }
                        val serieSlug = json.optString("serieSlug", "")
                        detectedRawId = rawId.takeIf { it.isNotBlank() }
                        detectedLocale = locale.takeIf { it.isNotBlank() }

                        // If rawNextData is available and fields need filling
                        val rawNextData = json.optString("rawNextData", "")
                        if (rawNextData.isNotBlank() && (title.isBlank() || genresList.isEmpty() || chaptersList.isEmpty())) {
                            val parsedMeta = WtrLabExtractor.extractMetadataFromNextData(rawNextData, currentUrl)
                            if (parsedMeta != null) {
                                if (title.isBlank()) title = parsedMeta.title
                                if (author.isBlank() || author == "Unknown Author") author = parsedMeta.author
                                if (description.isBlank()) description = parsedMeta.synopsis
                                if (coverUrl.isNullOrBlank()) coverUrl = resolveAbsoluteUrl(sourceUrl, parsedMeta.coverUrl)
                                if (genresList.isEmpty()) genresList.addAll(parsedMeta.genres)
                                if (detectedRawId.isNullOrBlank() && parsedMeta.rawId.isNotBlank()) {
                                    detectedRawId = parsedMeta.rawId
                                }
                                if (detectedLocale.isNullOrBlank() && parsedMeta.locale.isNotBlank()) {
                                    detectedLocale = parsedMeta.locale
                                }
                                parsedMeta.rating?.let { extractedRating = it }
                            }
                        }

                        // If chapters were not retrieved in JS (e.g. synchronous XHR restricted in WebView),
                        // execute direct HTTP GET in Kotlin using WtrLabExtractor
                        if (chaptersList.isEmpty() && rawId.isNotBlank()) {
                            Log.d("NOVEL_SCRAPE_DIAG", "[WTR-LAB] Fetching chapters via Kotlin OkHttp API for raw_id: $rawId...")
                            ScrapeDiagnosticsManager.log(context, "[WTR-LAB] Fetching chapters via Kotlin OkHttp API for raw_id: $rawId (count: $chapterCount)...")

                            val cookies = CookieManager.getInstance().getCookie("https://wtr-lab.com")
                            val userAgent = webView.settings.userAgentString

                            val fetchedChapters = withContext(Dispatchers.IO) {
                                WtrLabExtractor.fetchChaptersFromApi(
                                    rawId = rawId,
                                    chapterCount = chapterCount,
                                    locale = locale,
                                    serieSlug = serieSlug,
                                    userAgent = userAgent,
                                    cookies = cookies,
                                    referer = currentUrl
                                )
                            }
                            if (fetchedChapters.isNotEmpty()) {
                                chaptersList.clear()
                                chaptersList.addAll(fetchedChapters)
                                ScrapeDiagnosticsManager.log(context, "[WTR-LAB] Successfully fetched ${fetchedChapters.size} chapters via Kotlin OkHttp API!")
                            }
                        }
                    }

                    val data = ScrapedNovelData(
                        title = title.ifBlank { pageTitle.ifBlank { "Untitled Web Novel" } },
                        author = author.ifBlank { "Unknown Author" },
                        coverUrl = coverUrl,
                        description = description,
                        genres = genresList,
                        chapters = chaptersList,
                        sourceUrl = sourceUrl,
                        rawId = detectedRawId,
                        locale = detectedLocale,
                        rating = extractedRating
                    )

                    Log.d("NovelScraper", "Extracted novel: '${data.title}', ${chaptersList.size} chapters, ${genresList.size} genres")
                    scrapedNovelData = data
                    ScrapeDiagnosticsManager.finishRun(context, "SUCCESS - Extracted '${data.title}', ${chaptersList.size} chapters, ${genresList.size} genres")
                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.d("NOVEL_SCRAPE_DIAG", "[EXCEPTION] ${e.message}")
                    ScrapeDiagnosticsManager.log(context, "[EXCEPTION] ${e.message}")
                    ScrapeDiagnosticsManager.finishRun(context, "FAILED WITH EXCEPTION - ${e.message}")
                    scrapeErrorMessage = "Extraction failed: ${e.localizedMessage ?: "Unknown error"}"
                }
            }
        }

        if (isWtrLab) {
            // For WTR-Lab: No DOM scrolling or TOC clicking needed. Execute extraction immediately!
            Log.d("NOVEL_SCRAPE_DIAG", "[WTR-LAB] Detected WTR-Lab URL. Skipping DOM scroll heuristics, running direct Next.js & API extraction...")
            ScrapeDiagnosticsManager.log(context, "[WTR-LAB] Detected WTR-Lab URL. Skipping DOM scroll heuristics, running direct Next.js & API extraction...")
            webView.evaluateJavascript(WebScrapeScripts.NOVEL_DETAILS_SCRAPE_JS) { rawJson ->
                processScrapedJson(rawJson)
            }
        } else {
            // Step 1: Programmatically trigger any TOC/accordion and scroll to load all chapters for other websites
            val prepareJs = """
                (function() {
                    try {
                        console.log('[NOVEL_SCRAPE_DIAG] prepareJs executing: clicking TOC / expand buttons...');
                        var buttons = document.querySelectorAll('button, [role="tab"], [role="button"], a.nav-link, a.tab, div.tab, span.tab, [data-tab*="chapter"]');
                        var clicked = 0;
                        for (var b = 0; b < buttons.length; b++) {
                            var text = (buttons[b].innerText || '').trim().toLowerCase();
                            if (text === 'chapters' || text === 'all chapters' || text === 'table of contents' || text === 'toc' || text.indexOf('all chapters') >= 0 || text === 'chapter list') {
                                buttons[b].click();
                                clicked++;
                            }
                        }
                        console.log('[NOVEL_SCRAPE_DIAG] prepareJs clicked ' + clicked + ' buttons, scrolling to page bottom...');
                        window.scrollTo(0, document.body.scrollHeight || document.documentElement.scrollHeight);
                    } catch(e) {
                        console.log('[NOVEL_SCRAPE_DIAG] prepareJs error: ' + e.toString());
                    }
                })();
            """.trimIndent()

            webView.evaluateJavascript(prepareJs) {
                // Step 2: Wait 600ms for lazy rendered DOM elements, then perform deep scrape
                scope.launch {
                    delay(600)
                    Log.d("NOVEL_SCRAPE_DIAG", "Executing NOVEL_DETAILS_SCRAPE_JS...")
                    ScrapeDiagnosticsManager.log(context, "Executing NOVEL_DETAILS_SCRAPE_JS...")
                    webView.evaluateJavascript(WebScrapeScripts.NOVEL_DETAILS_SCRAPE_JS) { rawJson ->
                        processScrapedJson(rawJson)
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Top Header with Address Bar & Navigation Controls (Proper status bar padding)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Exit Browser Button
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("browser_back_btn")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Close Browser",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Functional, fully-editable Address Bar with Amber Accents
                        OutlinedTextField(
                            value = urlInputValue,
                            onValueChange = { newValue ->
                                urlInputValue = newValue
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .focusRequester(addressBarFocusRequester)
                                .onFocusChanged { focusState ->
                                    isAddressBarFocused = focusState.isFocused
                                    if (focusState.isFocused) {
                                        val len = urlInputValue.text.length
                                        urlInputValue = urlInputValue.copy(selection = TextRange(0, len))
                                    }
                                }
                                .testTag("browser_address_bar"),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.5.sp
                            ),
                            shape = RoundedCornerShape(24.dp),
                            placeholder = {
                                Text("Search or type URL...", fontSize = 12.5.sp, maxLines = 1)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (currentUrl.startsWith("https://")) Icons.Default.Lock else Icons.Default.Public,
                                    contentDescription = null,
                                    tint = if (currentUrl.startsWith("https://")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Quick Paste Icon Button
                                    if (isAddressBarFocused || urlInputValue.text.isBlank()) {
                                        IconButton(
                                            onClick = {
                                                val clip = clipboardManager.getText()?.text
                                                if (!clip.isNullOrBlank()) {
                                                    urlInputValue = TextFieldValue(clip, TextRange(clip.length))
                                                    navigateTo(clip)
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentPaste,
                                                contentDescription = "Paste from Clipboard",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    // Clear button when user typed something
                                    if (urlInputValue.text.isNotBlank() && isAddressBarFocused) {
                                        IconButton(
                                            onClick = {
                                                urlInputValue = TextFieldValue("", TextRange.Zero)
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Clear Address Bar",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }

                                    // Go / Navigate Button
                                    if (isAddressBarFocused) {
                                        IconButton(
                                            onClick = {
                                                navigateTo(urlInputValue.text)
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = "Go",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    navigateTo(urlInputValue.text)
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                            )
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        // Refresh / Stop Button
                        IconButton(
                            onClick = {
                                if (isLoading) {
                                    webViewInstance?.stopLoading()
                                } else {
                                    webViewInstance?.reload()
                                }
                            },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = if (isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                contentDescription = if (isLoading) "Stop Loading" else "Reload Page",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Browser Controls Sub-bar (Back, Forward, Page Info)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { webViewInstance?.goBack() },
                                enabled = canGoBack,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back Page",
                                    tint = if (canGoBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
                                )
                            }

                            IconButton(
                                onClick = { webViewInstance?.goForward() },
                                enabled = canGoForward,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "Forward Page",
                                    tint = if (canGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            FilledTonalButton(
                                onClick = {
                                    ScrapeDiagnosticsManager.shareDiagnosticsFile(context)
                                },
                                modifier = Modifier
                                    .height(28.dp)
                                    .testTag("share_diagnostics_button"),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Share Diag",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        if (pageTitle.isNotBlank()) {
                            Text(
                                text = pageTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).padding(horizontal = 8.dp)
                            )
                        }
                    }

                    // Progress Indicator with Amber Accent
                    if (isLoading) {
                        LinearProgressIndicator(
                            progress = { loadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    } else {
                        Spacer(modifier = Modifier.height(2.5.dp))
                    }
                }
            }

            // WebView Area
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                factory = { ctx ->
                    val wv = WebView(ctx)
                    wv.apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            cacheMode = WebSettings.LOAD_DEFAULT
                            userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                        }

                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(wv, true)
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url?.toString()
                                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                                    return false
                                }
                                return false
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                val effectiveUrl = view?.url ?: url
                                if (!effectiveUrl.isNullOrBlank()) {
                                    currentUrl = effectiveUrl
                                    if (!isAddressBarFocused) {
                                        urlInputValue = TextFieldValue(effectiveUrl, TextRange(effectiveUrl.length))
                                    }
                                }
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                val effectiveUrl = view?.url ?: url
                                if (!effectiveUrl.isNullOrBlank()) {
                                    currentUrl = effectiveUrl
                                    urlInputValue = TextFieldValue(effectiveUrl, TextRange(effectiveUrl.length))
                                    isAddressBarFocused = false
                                }
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                val effectiveUrl = view?.url ?: url
                                if (!effectiveUrl.isNullOrBlank()) {
                                    currentUrl = effectiveUrl
                                    if (!isAddressBarFocused) {
                                        urlInputValue = TextFieldValue(effectiveUrl, TextRange(effectiveUrl.length))
                                    }
                                }
                                pageTitle = view?.title ?: ""
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }
                        }

                        setOnTouchListener { _, _ ->
                            if (isAddressBarFocused) {
                                focusManager.clearFocus()
                                isAddressBarFocused = false
                            }
                            false
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                val msg = consoleMessage?.message() ?: ""
                                if (msg.contains("NOVEL_SCRAPE_DIAG")) {
                                    Log.d("NOVEL_SCRAPE_DIAG", "[CONSOLE] $msg")
                                    ScrapeDiagnosticsManager.log(context, "[CONSOLE] $msg")
                                }
                                return super.onConsoleMessage(consoleMessage)
                            }

                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                super.onProgressChanged(view, newProgress)
                                loadProgress = newProgress / 100f
                                if (newProgress >= 100) {
                                    isLoading = false
                                }
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                super.onReceivedTitle(view, title)
                                if (!title.isNullOrBlank()) {
                                    pageTitle = title
                                }
                            }
                        }

                        webViewInstance = this
                        loadUrl(initialUrl)
                    }
                },
                update = {
                    // Handled dynamically
                }
            )
        }

        // Floating Action Button: "Add to Library" (Warm Amber Accent, bottom-right with navigation bar padding)
        FloatingActionButton(
            onClick = {
                sheetUrlInput = currentUrl
                showAddNovelSheet = true
                runScrapingPass(currentUrl)
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(16.dp)
                .testTag("add_to_library_fab"),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.BookmarkAdd,
                    contentDescription = "Add to Library"
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Add to Library",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }

        // Compact Add-Novel Modal Bottom Sheet Overlay
        if (showAddNovelSheet) {
            ModalBottomSheet(
                onDismissRequest = { showAddNovelSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                dragHandle = { BottomSheetDefaults.DragHandle() }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    // Header: "Add Novel" with Serif Display Typography
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Add Novel",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = BookSerifFontFamily,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(
                            onClick = { showAddNovelSheet = false },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Editable URL Field with Paste & Go Buttons
                    OutlinedTextField(
                        value = sheetUrlInput,
                        onValueChange = { sheetUrlInput = it },
                        label = { Text("Novel or Chapter URL", fontSize = 12.sp) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        val clip = clipboardManager.getText()?.text
                                        if (!clip.isNullOrBlank()) {
                                            sheetUrlInput = clip
                                            navigateTo(clip)
                                            runScrapingPass(clip)
                                        }
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Paste",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        if (sheetUrlInput.isNotBlank()) {
                                            navigateTo(sheetUrlInput)
                                            runScrapingPass(sheetUrlInput)
                                        }
                                    },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "Go",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Compact Confirmation Preview Area
                    if (isScraping) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Extracting novel details & chapters...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    } else if (scrapeErrorMessage != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "Extraction Notice",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = scrapeErrorMessage ?: "Could not extract details.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = { runScrapingPass(sheetUrlInput.ifBlank { currentUrl }) },
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                                    ) {
                                        Text("Retry Extraction", fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = { ScrapeDiagnosticsManager.shareDiagnosticsFile(context) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Share Diagnostics Log", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    } else if (scrapedNovelData != null) {
                        val data = scrapedNovelData!!
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Cover Thumbnail
                                NovelCoverImage(
                                    coverUrl = data.coverUrl,
                                    title = data.title,
                                    modifier = Modifier
                                        .width(60.dp)
                                        .height(84.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = data.title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontFamily = BookSerifFontFamily,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = data.author,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.MenuBook,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = "${data.chapters.size} chapters found",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = if (data.chapters.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    if (data.genres.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = data.genres.take(3).joinToString(", "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        // Inline warning if 0 chapters found
                        if (data.chapters.isEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "0 chapters detected. Navigate to the novel's table of contents or index page to scrape all chapters.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Share diagnostics option in sheet
                    TextButton(
                        onClick = { ScrapeDiagnosticsManager.shareDiagnosticsFile(context) },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share Diagnostics Log", fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Prominent "Add to Library" Button
                    Button(
                        onClick = {
                            val data = scrapedNovelData ?: return@Button
                            val resolvedCover = resolveAbsoluteUrl(data.sourceUrl, data.coverUrl)
                            if (repository != null) {
                                val savedNovel = repository.saveWebScrapedNovel(
                                    scrapedData = data,
                                    customTitle = data.title,
                                    customAuthor = data.author,
                                    customSynopsis = data.description,
                                    customTags = data.genres,
                                    customCoverUrl = resolvedCover
                                )
                                showAddNovelSheet = false
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "Added \"${savedNovel.title}\" to Library",
                                        actionLabel = "Open",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        onNovelSaved?.invoke(savedNovel)
                                    }
                                }
                            } else if (onScrapedNovel != null) {
                                showAddNovelSheet = false
                                onScrapedNovel.invoke(data.copy(coverUrl = resolvedCover))
                            }
                        },
                        enabled = !isScraping && scrapedNovelData != null && scrapedNovelData!!.title.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("sheet_add_to_library_btn"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(imageVector = Icons.Default.BookmarkAdd, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Add to Library",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webViewInstance?.stopLoading()
                webViewInstance?.destroy()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

private fun logDiagChunked(prefix: String, text: String) {
    if (text.isBlank() || text == "none") {
        Log.d("NOVEL_SCRAPE_DIAG", "  $prefix: none")
        return
    }
    val chunkSize = 1000
    var i = 0
    var part = 1
    val totalParts = (text.length + chunkSize - 1) / chunkSize
    while (i < text.length) {
        val end = (i + chunkSize).coerceAtMost(text.length)
        Log.d("NOVEL_SCRAPE_DIAG", "  $prefix [Part $part/$totalParts]: ${text.substring(i, end)}")
        i += chunkSize
        part++
    }
}
