package com.example.data.storage

import com.example.data.local.storage.DownloadNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNamingTest {

    @Test
    fun sanitizeFolderName_stripsInvalidCharsAndControlChars() {
        val sanitized = DownloadNaming.sanitizeFolderName("Re:Zero / Starting? *Life* <In> \"Another\" |World\\")
        assertEquals("Re Zero Starting Life In Another World", sanitized)
    }

    @Test
    fun sanitizeFolderName_handlesParentTraversalAndDotsAndIllegalOnly() {
        assertEquals("Untitled", DownloadNaming.sanitizeFolderName(".."))
        assertEquals("Untitled", DownloadNaming.sanitizeFolderName("."))
        assertEquals("Untitled", DownloadNaming.sanitizeFolderName("..."))
        assertEquals("Untitled", DownloadNaming.sanitizeFolderName("? / : * \" < > |"))
        assertEquals("Untitled", DownloadNaming.sanitizeFolderName("   "))
        assertEquals("CON_", DownloadNaming.sanitizeFolderName("CON"))
    }

    @Test
    fun sanitizeFolderName_preservesEmojiAndTruncatesSafelyAt300Chars() {
        val longEmojiTitle = "\uD83D\uDD25".repeat(90) + "A".repeat(120)
        assertTrue(longEmojiTitle.length == 300)
        val sanitized = DownloadNaming.sanitizeFolderName(longEmojiTitle)
        val bytes = sanitized.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size <= DownloadNaming.MAX_SEGMENT_BYTES)
        assertFalse(sanitized.contains('\uFFFD'))
        val decoded = String(bytes, Charsets.UTF_8)
        assertEquals(sanitized, decoded)
    }

    @Test
    fun sanitizeFolderName_stripsBidiMarksAndKeepsRtlLetters() {
        val rtlWithBidi = "\u202E\u200Fرواية البداية\u2069"
        val sanitized = DownloadNaming.sanitizeFolderName(rtlWithBidi)
        assertEquals("رواية البداية", sanitized)
        assertFalse(sanitized.any { it == '\u202E' || it == '\u200F' || it == '\u2069' })
    }

    @Test
    fun resolveUniqueFolderName_appendsDeterministicHex6OnCaseInsensitiveCollision() {
        val first = DownloadNaming.resolveUniqueFolderName("Shadow Slave", "novel_1", emptySet())
        assertEquals("Shadow Slave", first)

        val second = DownloadNaming.resolveUniqueFolderName(
            "shadow slave",
            "novel_2",
            setOf(first.lowercase())
        )
        val expectedSuffix = "_${DownloadNaming.shortHash6("novel_2")}"
        assertEquals("shadow slave$expectedSuffix", second)
        assertEquals(first, DownloadNaming.resolveUniqueFolderName("Shadow Slave", "novel_1", emptySet()))
    }

    @Test
    fun resolveUniqueFolderName_twoSourcesWithSameDisplayNameGetDistinctFolders() {
        val srcA = DownloadNaming.resolveUniqueFolderName("NovelRead", "ln_en_novelread", emptySet())
        val srcB = DownloadNaming.resolveUniqueFolderName("NovelRead", "ln_es_novelread", setOf(srcA.lowercase()))
        assertEquals("NovelRead", srcA)
        assertNotEquals(srcA.lowercase(), srcB.lowercase())
        assertTrue(srcB.startsWith("NovelRead_"))
        assertEquals("NovelRead_${DownloadNaming.shortHash6("ln_es_novelread")}", srcB)
    }

    @Test
    fun resolveUniqueFolderName_novelTitleOver300CharsWithCollisionStaysWithin200Bytes() {
        val longTitle = "Very Long Fantasy Novel Title ".repeat(12)
        val first = DownloadNaming.resolveUniqueFolderName(longTitle, "id_1", emptySet())
        val second = DownloadNaming.resolveUniqueFolderName(longTitle, "id_2", setOf(first.lowercase()))
        assertTrue(first.toByteArray(Charsets.UTF_8).size <= DownloadNaming.MAX_SEGMENT_BYTES)
        assertTrue(second.toByteArray(Charsets.UTF_8).size <= DownloadNaming.MAX_SEGMENT_BYTES)
        assertTrue(second.endsWith("_${DownloadNaming.shortHash6("id_2")}"))
    }

    @Test
    fun formatChapterFileName_handlesNormalBlankAndIllegalOnlyTitles() {
        assertEquals(
            "0001 - Chapter 1 Prologue.json",
            DownloadNaming.formatChapterFileName(1, "Chapter 1: Prologue?", "ch_1")
        )
        assertEquals("0002.json", DownloadNaming.formatChapterFileName(2, "   ", "ch_2"))
        assertEquals("0003.json", DownloadNaming.formatChapterFileName(3, "?? / : *", "ch_3"))
        assertEquals("10000 - Finale.json", DownloadNaming.formatChapterFileName(10000, "Finale", "ch_10000"))
    }

    @Test
    fun formatChapterFileName_sameIndexTwiceResolvesWithHex6Suffix() {
        val ch1 = DownloadNaming.formatChapterFileName(5, "Side Story", "ch_5a", emptySet())
        val ch2 = DownloadNaming.formatChapterFileName(5, "Side Story", "ch_5b", setOf(ch1.lowercase()))
        assertEquals("0005 - Side Story.json", ch1)
        assertEquals("0005 - Side Story_${DownloadNaming.shortHash6("ch_5b")}.json", ch2)

        val blank1 = DownloadNaming.formatChapterFileName(7, "", "ch_7a", emptySet())
        val blank2 = DownloadNaming.formatChapterFileName(7, "???", "ch_7b", setOf(blank1.lowercase()))
        assertEquals("0007.json", blank1)
        assertEquals("0007_${DownloadNaming.shortHash6("ch_7b")}.json", blank2)
    }

    @Test
    fun formatChapterFileName_300CharTitleRespects200ByteCapWithCollisionSuffix() {
        val longTitle = "أهلاً".repeat(60)
        val ch1 = DownloadNaming.formatChapterFileName(12, longTitle, "ch_12a", emptySet())
        val ch2 = DownloadNaming.formatChapterFileName(12, longTitle, "ch_12b", setOf(ch1.lowercase()))
        assertTrue(ch1.endsWith(".json"))
        assertTrue(ch2.endsWith("_${DownloadNaming.shortHash6("ch_12b")}.json"))
        assertTrue(ch1.toByteArray(Charsets.UTF_8).size <= DownloadNaming.MAX_SEGMENT_BYTES)
        assertTrue(ch2.toByteArray(Charsets.UTF_8).size <= DownloadNaming.MAX_SEGMENT_BYTES)
    }

    @Test
    fun requireSafeSegment_rejectsTraversalAndControlChars() {
        listOf("", ".", "..", "a/b", "a\\b", "bad\u0000name", "bad\nname", "a".repeat(256)).forEach { bad ->
            val threw = try {
                DownloadNaming.requireSafeSegment(bad)
                false
            } catch (_: IllegalArgumentException) {
                true
            }
            assertTrue("Expected rejection for '$bad'", threw)
        }
        assertEquals("0001 - Safe.json", DownloadNaming.requireSafeSegment("0001 - Safe.json"))
    }
}
