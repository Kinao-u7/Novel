package com.example.data.source.webscrape

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.model.ChapterContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.util.ArrayDeque
import java.util.Collections
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

object WebChapterScraper {

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    const val MAX_POOL_CAP = 2
    @Volatile
    private var maxPooledWebViews = 2

    private val activeWebViewSemaphore = Semaphore(2)

    fun configurePoolCapacity(simultaneousDownloads: Int) {
        maxPooledWebViews = min(simultaneousDownloads, MAX_POOL_CAP).coerceAtLeast(1)
    }

    private val webViewPool = ArrayDeque<WebView>()
    private val activeWebViews = Collections.synchronizedSet(HashSet<WebView>())

    private fun obtainWebView(context: Context): WebView {
        synchronized(webViewPool) {
            while (webViewPool.isNotEmpty()) {
                val candidate = webViewPool.removeFirst()
                try {
                    candidate.stopLoading()
                    activeWebViews.add(candidate)
                    return candidate
                } catch (_: Exception) {
                    // Stale or invalid WebView instance
                }
            }
        }
        val created = WebView(context.applicationContext ?: context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = DEFAULT_USER_AGENT
                cacheMode = WebSettings.LOAD_DEFAULT
                blockNetworkImage = true // Speeds up text extraction
            }
            CookieManager.getInstance().setAcceptCookie(true)
        }
        activeWebViews.add(created)
        return created
    }

    private fun recycleWebView(webView: WebView?) {
        if (webView == null) return
        activeWebViews.remove(webView)
        try {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.loadUrl("about:blank")
            synchronized(webViewPool) {
                if (webViewPool.size < maxPooledWebViews) {
                    webViewPool.addLast(webView)
                    return
                }
            }
            webView.destroy()
        } catch (_: Exception) {
            try {
                webView.destroy()
            } catch (_: Exception) {}
        }
    }

    /**
     * Releases all cached and active WebViews, clearing with about:blank before destroy.
     * Called when the download queue becomes empty, cancelled, or the worker finishes.
     */
    fun releaseWebViews() {
        val toRelease = mutableListOf<WebView>()
        synchronized(webViewPool) {
            synchronized(activeWebViews) {
                if (webViewPool.isEmpty() && activeWebViews.isEmpty()) {
                    return
                }
                while (webViewPool.isNotEmpty()) {
                    toRelease.add(webViewPool.removeFirst())
                }
                toRelease.addAll(activeWebViews)
                activeWebViews.clear()
            }
        }
        Handler(Looper.getMainLooper()).post {
            for (wv in toRelease) {
                try {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.destroy()
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Extracts chapter content from a webpage URL using a pooled background WebView instance.
     * Executes entirely headless without visible UI.
     */
    suspend fun extractChapterText(
        context: Context,
        url: String,
        novelId: String = "",
        chapterId: String = url,
        titleFallback: String = "Chapter"
    ): ChapterContent {
        activeWebViewSemaphore.acquire()
        return try {
            withContext(Dispatchers.Main) {
                withTimeout(25_000L) {
            suspendCancellableCoroutine { continuation ->
                var webView: WebView? = null
                val handler = Handler(Looper.getMainLooper())

                try {
                    val wv = obtainWebView(context)
                    webView = wv

                    wv.webViewClient = object : WebViewClient() {
                        private var hasExtracted = false

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                if (!hasExtracted && continuation.isActive) {
                                    hasExtracted = true
                                    recycleWebView(wv)
                                    val desc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                        error?.description?.toString() ?: "Web loading error"
                                    } else {
                                        "Web loading error"
                                    }
                                    continuation.resumeWithException(IOException("Main frame load failed: $desc"))
                                }
                            }
                        }

                        @Suppress("DEPRECATION")
                        override fun onReceivedError(
                            view: WebView?,
                            errorCode: Int,
                            description: String?,
                            failingUrl: String?
                        ) {
                            super.onReceivedError(view, errorCode, description, failingUrl)
                            if (failingUrl == url && !hasExtracted && continuation.isActive) {
                                hasExtracted = true
                                recycleWebView(wv)
                                continuation.resumeWithException(IOException("Main frame load failed: $description"))
                            }
                        }

                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            super.onPageFinished(view, finishedUrl)
                            if (hasExtracted) return

                            // Delay slightly for client-side JS rendering (Next.js / Vue / React)
                            handler.postDelayed({
                                if (hasExtracted || !continuation.isActive) return@postDelayed
                                hasExtracted = true

                                view?.evaluateJavascript(WebScrapeScripts.CHAPTER_CONTENT_SCRAPE_JS) { rawJson ->
                                    try {
                                        if (rawJson.isNullOrBlank() || rawJson == "null") {
                                            recycleWebView(wv)
                                            continuation.resumeWithException(
                                                IllegalStateException("No content returned from chapter page.")
                                            )
                                            return@evaluateJavascript
                                        }

                                        // evaluateJavascript wraps the string in quotes or JSON string
                                        val unescapedJson = if (rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                                            org.json.JSONTokener(rawJson).nextValue().toString()
                                        } else {
                                            rawJson
                                        }

                                        val json = JSONObject(unescapedJson)
                                        val title = json.optString("title").ifBlank { titleFallback }
                                        val pArr = json.optJSONArray("paragraphs")
                                        val paragraphs = mutableListOf<String>()
                                        if (pArr != null) {
                                            for (i in 0 until pArr.length()) {
                                                val p = pArr.optString(i, "").trim()
                                                if (p.isNotBlank()) paragraphs.add(p)
                                            }
                                        }
                                        val wordCount = json.optInt("wordCount", 0)

                                        if (paragraphs.isEmpty() || wordCount < 30) {
                                            recycleWebView(wv)
                                            continuation.resumeWithException(
                                                IllegalStateException(
                                                    "Extracted chapter text was too short ($wordCount words). The site may require authentication or block automated extraction."
                                                )
                                            )
                                        } else {
                                            recycleWebView(wv)
                                            continuation.resume(
                                                ChapterContent(
                                                    chapterId = chapterId,
                                                    novelId = novelId,
                                                    title = title,
                                                    paragraphs = paragraphs,
                                                    isDownloaded = true
                                                )
                                            )
                                        }
                                    } catch (e: Exception) {
                                        recycleWebView(wv)
                                        continuation.resumeWithException(e)
                                    }
                                }
                            }, 800)
                        }
                    }

                    continuation.invokeOnCancellation {
                        try {
                            recycleWebView(wv)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    wv.loadUrl(url)
                } catch (e: Exception) {
                    recycleWebView(webView)
                    continuation.resumeWithException(e)
                }
            }
        }
    } finally {
        activeWebViewSemaphore.release()
    }
}
}
