package com.example.novelreader.ui

import androidx.compose.ui.graphics.Color

data class Book(
    val id: String,
    val title: String,
    val author: String,
    val genres: List<String>,
    val glyph: String,
    val c1: Color,
    val c2: Color,
    val accent: Color,
    val chapters: Int,
    val chaptersRead: Int,
    val currentChapterTitle: String,
    val progress: Float,          // progress inside the current chapter, 1f = finished
    val words: String,
    val rating: Float,
    val description: String,
    val source: String,
    val inLibrary: Boolean = false,
    val isNew: Boolean = false,
) {
    val isFinished get() = progress >= 1f
    val statusLabel get() = if (isFinished) "Completed" else "Ongoing"
}

data class Category(val id: String, val name: String)

enum class SortMode(val label: String) {
    Recent("Recently read"), Title("Title A–Z"), Author("Author A–Z"), Chapters("Most chapters"), Rating("Highest rated")
}

enum class ShelfFilter(val label: String) { All("All"), Reading("Reading"), Finished("Finished"), Saved("Saved") }

enum class ViewMode { Grid, List }

private fun c(hex: Long) = Color(hex or 0xFF000000)

val sampleBooks = listOf(
    Book("jade", "The Jade Lantern Ascent", "Wen Lan", listOf("Xianxia"), "燈", c(0x17463D), c(0x071013), c(0xF3CB83),
        126, 2, "Seven Breaths Before Dawn", .42f, "410k", 4.8f,
        "A servant boy finds a lantern that has burned for nine hundred years, and a voice inside it offers to teach him how to breathe. The mountain, it turns out, has been waiting for him.", "WTR-LAB"),
    Book("sword", "Sword of the Nine Rivers", "Han Zhou", listOf("Wuxia"), "劍", c(0x4A2118), c(0x12090A), c(0xF08A6B),
        210, 57, "The Ferryman’s Price", .27f, "720k", 4.6f,
        "Nine rivers, nine schools, one blade that remembers every hand that has held it. A wandering swordsman owes a debt he cannot name.", "NovelFire"),
    Book("plum", "Empress Beneath the Plum Snow", "Su Mei", listOf("Romance"), "梅", c(0x5A2547), c(0x170913), c(0xFFC9DE),
        88, 88, "Spring, At Last", 1f, "260k", 4.9f,
        "A palace winter, a bride who was never meant to survive it, and an emperor who learns to read her silences.", "Royal Road"),
    Book("dragon", "Dragon-Scale Alchemist", "Bai Ren", listOf("Xianxia", "Fantasy"), "龍", c(0x124557), c(0x06141A), c(0x7CF2C3),
        164, 0, "", 0f, "540k", 4.5f,
        "Every pill she brews bends fate a little. Every bend leaves a scale on her skin. How many can she afford?", "WTR-LAB", inLibrary = true),
    Book("star", "The Last Star Cartographer", "Ilya Voss", listOf("Sci-fi"), "星", c(0x1F2760), c(0x080A1C), c(0x9DB4FF),
        54, 0, "", 0f, "180k", 4.4f,
        "When the stars begin to move on their own, one aging mapmaker is asked to chart a sky that will not hold still.", "NovelFire"),
    Book("moon", "Moonwell Academy", "Tamsin Reed", listOf("Fantasy"), "月", c(0x2F2D6C), c(0x0E0D24), c(0xD8D4FF),
        72, 0, "", 0f, "300k", 4.3f,
        "Scholarships are drawn from a well that does not reflect your face. First-year Nell would very much like to know why.", "Royal Road", inLibrary = true),
    Book("ash", "Ashes of the Heavenly Court", "Lu Yan", listOf("Xianxia"), "焰", c(0x573312), c(0x160C05), c(0xFFB25A),
        301, 0, "", 0f, "1.1M", 4.7f,
        "The heavens burned once. The only witness is a fallen immortal with a very bad memory and a very good grudge.", "WTR-LAB", isNew = true),
    Book("letters", "A Thousand Autumn Letters", "Mei Rowan", listOf("Romance"), "秋", c(0x60401D), c(0x1A1008), c(0xFFD9A0),
        46, 0, "", 0f, "150k", 4.6f,
        "Two strangers, one misdelivered letter, and a correspondence that outlasts three autumns.", "NovelFire", isNew = true),
)
