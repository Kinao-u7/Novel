package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.ClipDescription
import android.graphics.Bitmap
import android.view.ViewGroup
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.data.source.webscrape.ScrapedChapter
import com.example.data.source.webscrape.ScrapedNovelData
import com.example.data.source.webscrape.WebScrapeScripts
import kotlinx.coroutines.launch
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebBrowserScreen(
    initialUrl: String = "https://wtr-lab.com",
    onBack: () -> Unit,
    onScrapedNovel: (ScrapedNovelData) -> Unit
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

    // Scraping overlay / state
    var isScraping by remember { mutableStateOf(false) }
    var scrapeErrorMessage by remember { mutableStateOf<String?>(null) }

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
        urlInputValue = TextFieldValue(targetUrl, TextRange(targetUrl.length))
        focusManager.clearFocus()
        keyboardController?.hide()
        webViewInstance?.loadUrl(targetUrl)
    }

    // Hardware back navigation
    BackHandler {
        if (isAddressBarFocused) {
            focusManager.clearFocus()
            keyboardController?.hide()
        } else if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        } else {
            onBack()
        }
    }

    // Function to trigger JS scraping on current page
    fun runScrapingPass() {
        val webView = webViewInstance ?: return
        isScraping = true
        scrapeErrorMessage = null

        webView.evaluateJavascript(WebScrapeScripts.NOVEL_DETAILS_SCRAPE_JS) { rawJson ->
            isScraping = false
            try {
                if (rawJson.isNullOrBlank() || rawJson == "null") {
                    scrapeErrorMessage = "No information could be extracted from this page."
                    return@evaluateJavascript
                }

                val unescaped = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                    org.json.JSONTokener(rawJson).nextValue().toString()
                } else {
                    rawJson
                }

                val json = JSONObject(unescaped)
                val title = json.optString("title", "").trim()
                val author = json.optString("author", "Unknown Author").trim()
                val coverUrl = json.optString("coverUrl", "").takeIf { it.isNotBlank() }
                val description = json.optString("description", "").trim()
                val sourceUrl = json.optString("sourceUrl", currentUrl).ifBlank { currentUrl }

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

                val scrapedData = ScrapedNovelData(
                    title = title.ifBlank { pageTitle.ifBlank { "Untitled Web Novel" } },
                    author = author.ifBlank { "Unknown Author" },
                    coverUrl = coverUrl,
                    description = description,
                    genres = genresList,
                    chapters = chaptersList,
                    sourceUrl = sourceUrl
                )

                onScrapedNovel(scrapedData)
            } catch (e: Exception) {
                e.printStackTrace()
                scrapeErrorMessage = "Failed to parse page data: ${e.localizedMessage ?: "Unknown error"}"
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Top Header with Address Bar & Navigation Controls
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
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

                        // Functional, fully-editable Address Bar
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
                                        // Select entire text when focused so user can replace or paste immediately
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
                                    // Quick Paste Icon Button: pastes clipboard directly into the field
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
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
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

                    // Progress Indicator
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
                                    return false // Let WebView load it
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                if (!url.isNullOrBlank()) {
                                    currentUrl = url
                                    if (!isAddressBarFocused) {
                                        urlInputValue = TextFieldValue(url, TextRange(url.length))
                                    }
                                }
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                if (!url.isNullOrBlank()) {
                                    currentUrl = url
                                    if (!isAddressBarFocused) {
                                        urlInputValue = TextFieldValue(url, TextRange(url.length))
                                    }
                                }
                                pageTitle = view?.title ?: ""
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
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
                    // Update if needed
                }
            )
        }

        // Floating Action Button: "Add to Library" (Bottom-right, accent circular FAB)
        FloatingActionButton(
            onClick = { runScrapingPass() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("add_to_library_fab"),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape
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

        // Scraping Loading Overlay
        if (isScraping) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Extracting Novel Info...",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Analyzing title, author, cover, synopsis, and chapter table of contents.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Scrape Error Dialog
        if (scrapeErrorMessage != null) {
            AlertDialog(
                onDismissRequest = { scrapeErrorMessage = null },
                title = { Text("Extraction Error") },
                text = { Text(scrapeErrorMessage ?: "") },
                confirmButton = {
                    TextButton(onClick = { scrapeErrorMessage = null }) {
                        Text("OK")
                    }
                }
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
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
