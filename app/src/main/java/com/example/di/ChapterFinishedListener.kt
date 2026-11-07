package com.example.di

fun interface ChapterFinishedListener {
    fun onChapterFinished(novelId: String, chapterIndex: Int, wasUnread: Boolean)

    companion object {
        val NoOp = ChapterFinishedListener { _, _, _ -> }
    }
}
