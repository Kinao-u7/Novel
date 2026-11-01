package com.example.data.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubParserTest {

    @Test
    fun extractMetadata_plainTag() {
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Renegade Immortal</dc:title>
                <dc:creator>Er Gen</dc:creator>
                <dc:language>en</dc:language>
                <dc:description>Wang Lin is a very smart boy with loving parents.</dc:description>
              </metadata>
            </package>
        """.trimIndent()

        val metadata = EpubParser.extractMetadata(opf)
        assertEquals("Renegade Immortal", metadata.title)
        assertEquals("Er Gen", metadata.author)
        assertEquals("en", metadata.language)
        assertEquals("Wang Lin is a very smart boy with loving parents.", metadata.description)
    }

    @Test
    fun extractMetadata_tagWithIdAttributeAndEntities() {
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title id="pub-title" dir="ltr" xml:lang="en">Coiling Dragon &#8217;s Legacy &amp; Journey</dc:title>
                <dc:creator id="author-id">I Eat Tomatoes</dc:creator>
                <dc:language id="lang-code">zh-CN</dc:language>
                <dc:description id="book-desc">Linley&#8217;s journey through the Magical Beasts Mountain Range &amp; beyond.</dc:description>
              </metadata>
            </package>
        """.trimIndent()

        val metadata = EpubParser.extractMetadata(opf)
        assertEquals("Coiling Dragon ’s Legacy & Journey", metadata.title)
        assertEquals("I Eat Tomatoes", metadata.author)
        assertEquals("zh-CN", metadata.language)
        assertEquals("Linley’s journey through the Magical Beasts Mountain Range & beyond.", metadata.description)
    }

    @Test
    fun extractMetadata_multiLineWithCdataAndWhitespace() {
        val opf = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title id="title">
                  <![CDATA[
                    A Will Eternal:
                    Bai Xiaochun&#8217;s Tale &amp; Legends
                  ]]>
                </dc:title>
                <dc:creator id="creator">
                  <![CDATA[
                    Er Gen
                  ]]>
                </dc:creator>
                <dc:language>
                  en-US
                </dc:language>
                <dc:description>
                  <![CDATA[
                    One will to create the oceans.
                    One will to summon the mulberry fields.
                  ]]>
                </dc:description>
              </metadata>
            </package>
        """.trimIndent()

        val metadata = EpubParser.extractMetadata(opf)
        assertEquals("A Will Eternal: Bai Xiaochun’s Tale & Legends", metadata.title)
        assertEquals("Er Gen", metadata.author)
        assertEquals("en-US", metadata.language)
        assertEquals("One will to create the oceans. One will to summon the mulberry fields.", metadata.description)
    }

    @Test
    fun findFallbackTitle_fallsBackToFirstChapterTitle() {
        val chapterHtml = """
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head>
              <title>The First Step to Immortality &amp; Dao</title>
            </head>
            <body>
              <h1>Prologue</h1>
              <p>In a small village nestled among the misty mountains...</p>
            </body>
            </html>
        """.trimIndent()

        val entries = mapOf(
            "OEBPS/chapter1.xhtml" to chapterHtml.toByteArray(Charsets.UTF_8)
        )

        val fallback = EpubParser.findFallbackTitle(entries, "OEBPS/", listOf("chapter1.xhtml"))
        assertEquals("The First Step to Immortality & Dao", fallback)
    }

    @Test
    fun findFallbackTitle_fallsBackToTocNcxDocTitle() {
        val ncx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <docTitle>
                <text>Lord of the Mysteries: Book 1</text>
              </docTitle>
            </ncx>
        """.trimIndent()

        val entries = mapOf(
            "toc.ncx" to ncx.toByteArray(Charsets.UTF_8)
        )

        val fallback = EpubParser.findFallbackTitle(entries, "", emptyList())
        assertEquals("Lord of the Mysteries: Book 1", fallback)
    }

    @Test
    fun cleanXmlContent_unescapesDecimalAndHexNumericEntities() {
        val raw = "Bai&#8217;s sword &amp; shield &#x2014; &quot;unmatched&quot;"
        val cleaned = EpubParser.cleanXmlContent(raw)
        assertEquals("Bai’s sword & shield — \"unmatched\"", cleaned)
    }
}
