package com.example.data.source.webscrape

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.model.ChapterContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object WebChapterScraper {

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    /**
     * Extracts chapter content from a webpage URL using a background WebView instance.
     * Executes entirely headless without visible UI.
     */
    suspend fun extractChapterText(
        context: Context,
        url: String,
        novelId: String = "",
        chapterId: String = url,
        titleFallback: String = "Chapter"
    ): ChapterContent = withContext(Dispatchers.Main) {
        withTimeout(25_000L) {
            suspendCancellableCoroutine { continuation ->
                var webView: WebView? = null
                val handler = Handler(Looper.getMainLooper())

                try {
                    webView = WebView(context.applicationContext).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            userAgentString = DEFAULT_USER_AGENT
                            cacheMode = WebSettings.LOAD_DEFAULT
                            blockNetworkImage = true // Speeds up text extraction
                        }

                        CookieManager.getInstance().setAcceptCookie(true)

                        webViewClient = object : WebViewClient() {
                            private var hasExtracted = false

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
                                                continuation.resumeWithException(
                                                    IllegalStateException(
                                                        "Extracted chapter text was too short ($wordCount words). The site may require authentication or block automated extraction."
                                                    )
                                                )
                                            } else {
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
                                            continuation.resumeWithException(e)
                                        }
                                    }
                                }, 800)
                            }
                        }
                    }

                    continuation.invokeOnCancellation {
                        try {
                            webView?.stopLoading()
                            webView?.destroy()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    webView.loadUrl(url)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }
}
