package com.example.ui.screens.reader

import com.example.data.local.storage.ChapterReadException
import com.example.data.local.storage.StorageUnavailableException
import com.example.data.model.Chapter
import com.example.data.model.ReaderSettings
import com.example.data.model.ReaderTextAlign
import com.example.data.model.TermReplacementRule
import com.example.data.repository.ChapterRowMissing
import com.example.data.repository.EpubFileMissing
import com.example.data.source.SourceUnavailable
import java.io.IOException

object ReaderMapping {

    fun mapToReaderDisplay(reader: ReaderSettings): ReaderDisplay = ReaderDisplay(
        theme = reader.theme,
        fontType = reader.fontType,
        fontSizeSp = reader.fontSizeSp,
        lineSpacing = reader.lineSpacingMultiplier,
        paragraphSpacingDp = reader.paragraphSpacingDp,
        justified = reader.textAlign == ReaderTextAlign.JUSTIFIED,
        horizontalMarginDp = reader.horizontalMarginDp,
        keepScreenOn = reader.keepScreenOn,
        volumeKeysScroll = reader.volumeKeysScroll
    )

    fun mapToReaderSettings(current: ReaderSettings, display: ReaderDisplay): ReaderSettings = current.copy(
        theme = display.theme,
        fontType = display.fontType,
        fontSizeSp = display.fontSizeSp,
        lineSpacingMultiplier = display.lineSpacing,
        paragraphSpacingDp = display.paragraphSpacingDp,
        textAlign = if (display.justified) ReaderTextAlign.JUSTIFIED else ReaderTextAlign.LEFT,
        horizontalMarginDp = display.horizontalMarginDp,
        keepScreenOn = display.keepScreenOn,
        volumeKeysScroll = display.volumeKeysScroll
    )

    fun mapToReaderContents(
        chapters: List<Chapter>,
        currentChapterIndex: Int
    ): List<ReaderContentsItem> {
        return chapters.map { ch ->
            ReaderContentsItem(
                index = ch.chapterIndex,
                number = ch.chapterIndex,
                title = ch.title,
                isRead = ch.isRead,
                isCurrent = ch.chapterIndex == currentChapterIndex,
                isDownloaded = ch.isDownloaded
            )
        }
    }

    fun applyTermReplacements(
        paragraphs: List<String>,
        rules: List<TermReplacementRule>
    ): List<String> {
        val active = rules.filter { it.isEnabled && it.original.isNotEmpty() }
        if (active.isEmpty()) return paragraphs
        return paragraphs.map { p ->
            var text = p
            for (rule in active) {
                text = text.replace(rule.original, rule.replacement)
            }
            text
        }
    }

    fun mapReaderError(e: Throwable): String = when (e) {
        is ChapterRowMissing -> "Chapter not found in library"
        is EpubFileMissing -> "EPUB file is missing"
        is ChapterReadException -> "Failed to read chapter file"
        is StorageUnavailableException -> "Storage location is unavailable"
        is SourceUnavailable -> "Novel source is unavailable"
        is IOException -> "Network or storage error loading chapter"
        else -> e.message ?: "Failed to load chapter"
    }
}
