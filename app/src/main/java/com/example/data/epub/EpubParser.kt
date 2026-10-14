package com.example.data.epub

import android.content.Context
import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.Novel
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class ParsedEpub(
    val novel: Novel,
    val chapters: List<Chapter>,
    val chapterContents: Map<String, ChapterContent>,
    val coverBytes: ByteArray? = null
)

object EpubParser {

    private const val MAX_CHAPTER_SIZE_BYTES = 5 * 1024 * 1024 // 5 MB bound

    fun parseEpub(context: Context, inputStream: InputStream, fileName: String): ParsedEpub {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(inputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val buffer = ByteArrayOutputStream()
                    val temp = ByteArray(8192)
                    var len: Int
                    while (zis.read(temp).also { len = it } > 0) {
                        buffer.write(temp, 0, len)
                    }
                    entries[entry.name] = buffer.toByteArray()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // 1. Locate OPF file from META-INF/container.xml
        val containerXml = entries["META-INF/container.xml"]?.toString(Charsets.UTF_8)
        var opfPath = "OEBPS/content.opf"
        if (containerXml != null) {
            val rootFileMatch = Pattern.compile("full-path=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(containerXml)
            if (rootFileMatch.find()) {
                opfPath = rootFileMatch.group(1) ?: opfPath
            }
        }

        // Check if opfPath exists in entries (handle leading slash or relative path)
        var opfBytes = entries[opfPath]
        if (opfBytes == null) {
            val altKey = entries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
            if (altKey != null) {
                opfPath = altKey
                opfBytes = entries[altKey]
            }
        }

        val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""
        val opfContent = opfBytes?.toString(Charsets.UTF_8) ?: ""

        // 2. Extract Metadata
        var title = extractXmlTag(opfContent, "dc:title")
        if (title.isBlank()) title = extractXmlTag(opfContent, "title")
        if (title.isBlank()) title = fileName.removeSuffix(".epub").replace("_", " ")

        var author = extractXmlTag(opfContent, "dc:creator")
        if (author.isBlank()) author = extractXmlTag(opfContent, "creator")
        if (author.isBlank()) author = "Unknown Author"

        var description = extractXmlTag(opfContent, "dc:description")
        if (description.isBlank()) description = extractXmlTag(opfContent, "description")
        if (description.isBlank()) description = "Imported EPUB light novel from device storage."

        // 3. Manifest items: id -> href
        // Match <item tag attributes directly without nested quantifiers or DOTALL backtracking
        val manifestPattern = Pattern.compile("<item\\s+([^>]+)", Pattern.CASE_INSENSITIVE)
        val manifestMatcher = manifestPattern.matcher(opfContent)
        val manifest = mutableMapOf<String, String>() // id -> href
        val manifestMediaType = mutableMapOf<String, String>()
        var coverHref: String? = null

        while (manifestMatcher.find()) {
            val itemAttr = manifestMatcher.group(1) ?: ""
            val idMatch = Pattern.compile("id=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(itemAttr)
            val hrefMatch = Pattern.compile("href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(itemAttr)
            val mediaMatch = Pattern.compile("media-type=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(itemAttr)
            val propMatch = Pattern.compile("properties=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(itemAttr)

            if (idMatch.find() && hrefMatch.find()) {
                val id = idMatch.group(1) ?: ""
                val href = hrefMatch.group(1) ?: ""
                manifest[id] = href

                val mediaType = if (mediaMatch.find()) mediaMatch.group(1) ?: "" else ""
                manifestMediaType[id] = mediaType

                val prop = if (propMatch.find()) propMatch.group(1) ?: "" else ""
                if (prop.contains("cover-image", ignoreCase = true) || id.contains("cover", ignoreCase = true)) {
                    coverHref = href
                }
            }
        }

        // 4. Extract Cover image bytes if found (saved by caller to covers/{novelId}.jpg)
        var coverBytes: ByteArray? = null
        val resolvedCoverHref = coverHref ?: entries.keys.firstOrNull { 
            (it.contains("cover", ignoreCase = true) || it.contains("titlepage", ignoreCase = true)) &&
            (it.endsWith(".jpg", ignoreCase = true) || it.endsWith(".jpeg", ignoreCase = true) || it.endsWith(".png", ignoreCase = true))
        }

        if (resolvedCoverHref != null) {
            val key = if (entries.containsKey(resolvedCoverHref)) resolvedCoverHref else opfDir + resolvedCoverHref
            val imageBytes = entries[key] ?: entries[resolvedCoverHref]
            if (imageBytes != null && imageBytes.isNotEmpty()) {
                coverBytes = imageBytes
            }
        }

        // 5. Spine itemrefs
        val spinePattern = Pattern.compile("<itemref\\s+[^>]*idref=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        val spineMatcher = spinePattern.matcher(opfContent)
        val spineIds = mutableListOf<String>()
        while (spineMatcher.find()) {
            val idref = spineMatcher.group(1)
            if (idref != null) spineIds.add(idref)
        }

        // If spine was empty, fallback to manifest html files
        val orderedHrefs = if (spineIds.isNotEmpty()) {
            spineIds.mapNotNull { manifest[it] }
        } else {
            manifest.values.filter { it.endsWith(".xhtml", ignoreCase = true) || it.endsWith(".html", ignoreCase = true) }
        }

        val novelId = "epub_${System.currentTimeMillis()}"
        val chapters = mutableListOf<Chapter>()
        val chapterContents = mutableMapOf<String, ChapterContent>()

        var chapterIdx = 1
        for (href in orderedHrefs) {
            val fullHref = if (entries.containsKey(href)) href else (opfDir + href)
            val xhtmlBytes = entries[fullHref] ?: entries[href] ?: continue
            // Bound chapter size to skip chapters over 5 MB
            if (xhtmlBytes.size > MAX_CHAPTER_SIZE_BYTES) {
                continue
            }
            val rawHtml = xhtmlBytes.toString(Charsets.UTF_8)

            val parsedChapter = parseChapterHtml(rawHtml, chapterIdx)
            if (parsedChapter.paragraphs.isEmpty() || parsedChapter.paragraphs.all { it.isBlank() }) {
                continue // Skip empty cover pages / title wrappers without substantial text
            }

            val chapterId = "${novelId}_ch_${chapterIdx}"
            val chapter = Chapter(
                id = chapterId,
                novelId = novelId,
                title = parsedChapter.title.ifBlank { "Chapter $chapterIdx" },
                chapterIndex = chapterIdx,
                releaseDate = "Imported",
                isDownloaded = true,
                isRead = false,
                wordCount = parsedChapter.paragraphs.sumOf { it.split("\\s+".toRegex()).size }
            )

            chapters.add(chapter)
            chapterContents[chapterId] = ChapterContent(
                chapterId = chapterId,
                novelId = novelId,
                title = chapter.title,
                paragraphs = parsedChapter.paragraphs,
                isDownloaded = true
            )
            chapterIdx++
        }

        val novel = Novel(
            id = novelId,
            title = title,
            author = author,
            coverUrl = null,
            description = cleanHtml(description),
            genres = listOf("EPUB", "Local File"),
            status = "Local Book",
            sourceId = "local_epub",
            sourceName = "Imported EPUB",
            inLibrary = true,
            totalChapters = chapters.size,
            downloadedChaptersCount = chapters.size,
            isEpub = true
        )

        return ParsedEpub(novel, chapters, chapterContents, coverBytes)
    }

    private fun extractXmlTag(xml: String, tag: String): String {
        val startTag = "<$tag"
        val endTag = "</$tag>"
        val startIdx = xml.indexOf(startTag, ignoreCase = true)
        if (startIdx == -1) return ""
        val closeBracket = xml.indexOf('>', startIdx)
        if (closeBracket == -1) return ""
        val endIdx = xml.indexOf(endTag, closeBracket, ignoreCase = true)
        if (endIdx == -1) return ""
        return cleanHtml(xml.substring(closeBracket + 1, endIdx))
    }

    private data class ParsedHtmlChapter(val title: String, val paragraphs: List<String>)

    private fun parseChapterHtml(html: String, chapterIdx: Int): ParsedHtmlChapter {
        // Extract title from <h1>, <h2>, <h3> or <title> without nested quantifiers
        var title = ""
        val hPattern = Pattern.compile("<h[1-3][^>]*>([^<]+)</h[1-3]>", Pattern.CASE_INSENSITIVE)
        val hMatcher = hPattern.matcher(html)
        if (hMatcher.find()) {
            title = cleanHtml(hMatcher.group(1) ?: "")
        } else {
            val titlePattern = Pattern.compile("<title[^>]*>([^<]+)</title>", Pattern.CASE_INSENSITIVE)
            val titleMatcher = titlePattern.matcher(html)
            if (titleMatcher.find()) {
                title = cleanHtml(titleMatcher.group(1) ?: "")
            }
        }

        // Extract body content via substring instead of regular expression to avoid catastrophic backtracking
        val bodyStart = html.indexOf("<body", ignoreCase = true)
        val bodyContent = if (bodyStart != -1) {
            val bodyTagEnd = html.indexOf('>', bodyStart)
            val bodyEnd = html.lastIndexOf("</body>", ignoreCase = true)
            if (bodyTagEnd != -1 && bodyEnd > bodyTagEnd) {
                html.substring(bodyTagEnd + 1, bodyEnd)
            } else {
                html
            }
        } else {
            html
        }

        // Replace <p>, <div>, <br> with deliberate delimiters
        val formatted = bodyContent
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n\n")
            .replace(Regex("(?i)</div>"), "\n\n")
            .replace(Regex("(?i)</h[1-6]>"), "\n\n")

        val cleanText = cleanHtml(formatted)
        val rawParagraphs = cleanText.split("\n\n")
        val paragraphs = mutableListOf<String>()

        for (p in rawParagraphs) {
            val trimmed = p.trim().replace(Regex("[ \\t]+"), " ")
            if (trimmed.length > 3) {
                paragraphs.add(trimmed)
            }
        }

        return ParsedHtmlChapter(title.ifBlank { "Chapter $chapterIdx" }, paragraphs)
    }

    private fun cleanHtml(html: String): String {
        val noTags = html.replace(Regex("<[^>]+>"), "")
        return noTags
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&#39;", "'")
            .replace("&#34;", "\"")
            .trim()
    }
}
