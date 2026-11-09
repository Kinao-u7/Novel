package com.example.ui.screens.reader

import com.example.data.local.storage.ChapterReadException
import com.example.data.local.storage.StorageUnavailableException
import com.example.data.model.Chapter
import com.example.data.model.FontType
import com.example.data.model.ReaderSettings
import com.example.data.model.ReaderTextAlign
import com.example.data.model.ReaderTheme
import com.example.data.model.TermReplacementRule
import com.example.data.repository.ChapterRowMissing
import com.example.data.repository.EpubFileMissing
import com.example.data.source.SourceUnavailable
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderMappingTest {

    @Test
    fun testDisplayMapping_roundtrip() {
        val originalSettings = ReaderSettings(
            theme = ReaderTheme.DARK,
            fontType = FontType.MONOSPACE,
            fontSizeSp = 22,
            lineSpacingMultiplier = 1.8f,
            paragraphSpacingDp = 16,
            textAlign = ReaderTextAlign.JUSTIFIED,
            horizontalMarginDp = 24,
            keepScreenOn = true,
            volumeKeysScroll = true,
            saveReadingPosition = true,
            ttsSpeechRate = 1.25f
        )

        val display = ReaderMapping.mapToReaderDisplay(originalSettings)
        assertEquals(ReaderTheme.DARK, display.theme)
        assertEquals(FontType.MONOSPACE, display.fontType)
        assertEquals(22, display.fontSizeSp)
        assertEquals(1.8f, display.lineSpacing, 0.001f)
        assertEquals(16, display.paragraphSpacingDp)
        assertTrue(display.justified)
        assertEquals(24, display.horizontalMarginDp)
        assertTrue(display.keepScreenOn)
        assertTrue(display.volumeKeysScroll)

        // Modify display and map back
        val modifiedDisplay = display.copy(
            theme = ReaderTheme.WARM,
            fontSizeSp = 20,
            justified = false
        )
        val updatedSettings = ReaderMapping.mapToReaderSettings(originalSettings, modifiedDisplay)
        assertEquals(ReaderTheme.WARM, updatedSettings.theme)
        assertEquals(20, updatedSettings.fontSizeSp)
        assertEquals(ReaderTextAlign.LEFT, updatedSettings.textAlign)
        // Untouched fields must remain preserved
        assertTrue(updatedSettings.saveReadingPosition)
        assertEquals(1.25f, updatedSettings.ttsSpeechRate, 0.001f)
    }

    @Test
    fun testContentsMapping() {
        val chapters = listOf(
            Chapter(id = "ch_1", novelId = "n1", title = "Chapter 1", chapterIndex = 1, isRead = true, isDownloaded = true),
            Chapter(id = "ch_2", novelId = "n1", title = "Chapter 2", chapterIndex = 2, isRead = false, isDownloaded = false)
        )

        val items = ReaderMapping.mapToReaderContents(chapters, currentChapterIndex = 2)
        assertEquals(2, items.size)

        val item1 = items[0]
        assertEquals(1, item1.index)
        assertEquals(1, item1.number)
        assertEquals("Chapter 1", item1.title)
        assertTrue(item1.isRead)
        assertFalse(item1.isCurrent)
        assertTrue(item1.isDownloaded)

        val item2 = items[1]
        assertEquals(2, item2.index)
        assertEquals(2, item2.number)
        assertEquals("Chapter 2", item2.title)
        assertFalse(item2.isRead)
        assertTrue(item2.isCurrent)
        assertFalse(item2.isDownloaded)
    }

    @Test
    fun testTermReplacements() {
        val paragraphs = listOf(
            "Elder Lin entered the Spirit Hall.",
            "He saluted Master Wang."
        )
        val rules = listOf(
            TermReplacementRule(id = "1", original = "Lin", replacement = "Zhang", isEnabled = true),
            TermReplacementRule(id = "2", original = "Wang", replacement = "Zhao", isEnabled = false),
            TermReplacementRule(id = "3", original = "Spirit", replacement = "Qi", isEnabled = true)
        )

        val result = ReaderMapping.applyTermReplacements(paragraphs, rules)
        assertEquals("Elder Zhang entered the Qi Hall.", result[0])
        assertEquals("He saluted Master Wang.", result[1])
    }

    @Test
    fun testErrorMapping() {
        assertEquals("Chapter not found in library", ReaderMapping.mapReaderError(ChapterRowMissing("n1", "c1")))
        assertEquals("EPUB file is missing", ReaderMapping.mapReaderError(EpubFileMissing("n1", "c1")))
        assertEquals("Failed to read chapter file", ReaderMapping.mapReaderError(ChapterReadException("n1", "c1", java.io.IOException("corrupt"))))
        assertEquals("Storage location is unavailable", ReaderMapping.mapReaderError(StorageUnavailableException("unreachable")))
        assertEquals("Novel source is unavailable", ReaderMapping.mapReaderError(SourceUnavailable("src1")))
        assertEquals("Network or storage error loading chapter", ReaderMapping.mapReaderError(IOException("Connection reset")))
        assertEquals("Custom error", ReaderMapping.mapReaderError(IllegalStateException("Custom error")))
    }
}
