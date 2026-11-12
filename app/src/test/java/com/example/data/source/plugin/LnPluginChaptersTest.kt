package com.example.data.source.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LnPluginChaptersTest {

    @Test
    fun testChapterOrderingAndIndex() = runBlocking<Unit> {
        val scriptDescending = """
            const plugin = {
                id: 'test_order',
                name: 'Test Order Plugin',
                async parseNovel(path) {
                    return {
                        name: 'Numbered Novel',
                        path: path,
                        chapters: [
                            { name: 'Chapter 3', path: 'ch3', chapterNumber: 3 },
                            { name: 'Chapter 2', path: 'ch2', chapterNumber: 2 },
                            { name: 'Chapter 1', path: 'ch1', chapterNumber: 1 }
                        ]
                    };
                }
            };
            module.exports = { default: plugin };
        """.trimIndent()

        val source = LnPluginSource(pluginScript = scriptDescending)
        try {
            val novelId = source.encodeNovelId("novel/ordered")
            val chapters = source.getChapterList(novelId)
            assertEquals(3, chapters.size)
            assertEquals("Chapter 1", chapters[0].title)
            assertEquals(1, chapters[0].chapterIndex)
            assertEquals("Recent", chapters[0].releaseDate)
            assertEquals("Chapter 2", chapters[1].title)
            assertEquals(2, chapters[1].chapterIndex)
            assertEquals("Chapter 3", chapters[2].title)
            assertEquals(3, chapters[2].chapterIndex)

            println("[LN-SOURCE-CHAPTERS] First chapter: ${chapters.first().title}")
        } finally {
            source.close()
        }
    }

    @Test
    fun testPreservesOrderWhenChapterNumberMissing() = runBlocking<Unit> {
        val scriptUnnumbered = """
            const plugin = {
                id: 'test_unnum',
                name: 'Test Unnumbered Plugin',
                async parseNovel(path) {
                    return {
                        name: 'Unnumbered Novel',
                        path: path,
                        chapters: [
                            { name: 'Prologue', path: 'p1' },
                            { name: 'Interlude', path: 'i1' }
                        ]
                    };
                }
            };
            module.exports = { default: plugin };
        """.trimIndent()

        val source = LnPluginSource(pluginScript = scriptUnnumbered)
        try {
            val novelId = source.encodeNovelId("novel/unnum")
            val chapters = source.getChapterList(novelId)
            assertEquals(2, chapters.size)
            assertEquals("Prologue", chapters[0].title)
            assertEquals(1, chapters[0].chapterIndex)
            assertEquals("Interlude", chapters[1].title)
            assertEquals(2, chapters[1].chapterIndex)
        } finally {
            source.close()
        }
    }

    @Test
    fun testSingleEntryCacheLifecycle() = runBlocking<Unit> {
        val scriptWithCounter = """
            globalThis.__parseNovelCalls = 0;
            const plugin = {
                id: 'test_cache',
                name: 'Test Cache Plugin',
                async parseNovel(path) {
                    globalThis.__parseNovelCalls++;
                    return {
                        name: 'Cached Novel ' + path,
                        path: path,
                        chapters: [{ name: 'Ch 1', path: 'ch1', chapterNumber: 1 }]
                    };
                }
            };
            module.exports = { default: plugin };
        """.trimIndent()

        val source = LnPluginSource(pluginScript = scriptWithCounter)
        try {
            val novelA = source.encodeNovelId("novel/A")
            val novelB = source.encodeNovelId("novel/B")

            // 1. getNovelDetails calls parseNovel (calls = 1)
            val detailsA = source.getNovelDetails(novelA)
            assertEquals(1, detailsA.totalChapters)

            // 2. Immediate getChapterList for same novel uses cache (calls stays 1)
            val chaptersA1 = source.getChapterList(novelA)
            assertEquals(1, chaptersA1.size)

            val host = source.getBridgeHost()!!
            val callsAfterFirst = host.quickJs.evaluate<Number>("globalThis.__parseNovelCalls").toInt()
            assertEquals("Cache must avoid second parseNovel call", 1, callsAfterFirst)

            // 3. Second getChapterList for same novel fetches fresh because cache was consumed (calls = 2)
            val chaptersA2 = source.getChapterList(novelA)
            assertEquals(1, chaptersA2.size)
            val callsAfterSecond = host.quickJs.evaluate<Number>("globalThis.__parseNovelCalls").toInt()
            assertEquals("Consuming cache causes subsequent call to fetch fresh", 2, callsAfterSecond)

            // 4. getNovelDetails for A then getChapterList for B doesn't reuse cache
            source.getNovelDetails(novelA)
            val callsAfterDetailsA = host.quickJs.evaluate<Number>("globalThis.__parseNovelCalls").toInt()
            assertEquals(3, callsAfterDetailsA)

            source.getChapterList(novelB)
            val callsAfterChaptersB = host.quickJs.evaluate<Number>("globalThis.__parseNovelCalls").toInt()
            assertEquals("Different novelId must fetch fresh", 4, callsAfterChaptersB)
        } finally {
            source.close()
        }
    }

    @Test
    fun testPagedChapterList() = runBlocking<Unit> {
        val scriptPaged = """
            const plugin = {
                id: 'test_paged',
                name: 'Test Paged Plugin',
                async parseNovel(path) {
                    return {
                        name: 'Paged Novel',
                        path: path,
                        totalPages: 3,
                        chapters: [{ name: 'Page 1 Ch', path: 'ch1', chapterNumber: 1 }]
                    };
                },
                async parsePage(path, page) {
                    if (page === 2) return [{ name: 'Page 2 Ch', path: 'ch2', chapterNumber: 2 }];
                    if (page === 3) return [{ name: 'Page 3 Ch', path: 'ch3', chapterNumber: 3 }];
                    return [];
                }
            };
            module.exports = { default: plugin };
        """.trimIndent()

        val source = LnPluginSource(pluginScript = scriptPaged)
        try {
            val novelId = source.encodeNovelId("novel/paged")
            val chapters = source.getChapterList(novelId)
            assertEquals(3, chapters.size)
            assertEquals("Page 1 Ch", chapters[0].title)
            assertEquals("Page 2 Ch", chapters[1].title)
            assertEquals("Page 3 Ch", chapters[2].title)
        } finally {
            source.close()
        }
    }

    @Test
    fun testChapterContentHtmlConversion() = runBlocking<Unit> {
        val messyHtml = "<div><p>Paragraph 1<br/>with soft break</p><script>alert(1)</script><style>.ad{color:red}</style><p>&nbsp;</p><p>Paragraph 2 &amp; &lt;escaped&gt;</p><img src=\"ad.jpg\"/><div>Paragraph 3</div></div>"
        val scriptChapter = """
            const plugin = {
                id: 'test_content',
                name: 'Test Content Plugin',
                async parseChapter(chapterPath) {
                    return '$messyHtml';
                }
            };
            module.exports = { default: plugin };
        """.trimIndent()

        val source = LnPluginSource(pluginScript = scriptChapter)
        try {
            val novelId = source.encodeNovelId("novel/content")
            val content = source.getChapterContent(novelId, "chapter/1")
            val expected = listOf(
                "Paragraph 1",
                "with soft break",
                "Paragraph 2 & <escaped>",
                "Paragraph 3"
            )
            assertEquals(expected, content.paragraphs)
            assertEquals("chapter/1", content.title)
            assertEquals("chapter/1", content.chapterId)
            assertEquals(novelId, content.novelId)
            assertFalse(content.isDownloaded)
        } finally {
            source.close()
        }
    }

    @Test
    fun testChapterContentResolvesRealTitleWhenCached() = runBlocking<Unit> {
        val script = """
            const plugin = {
                id: 'test_title_res',
                name: 'Title Res Plugin',
                async parseNovel(path) {
                    return {
                        name: 'Title Novel',
                        path: path,
                        chapters: [{ name: 'Prologue: The Beginning', path: 'ch/0', chapterNumber: 0 }]
                    };
                },
                async parseChapter(chapterPath) {
                    return '<p>Paragraph 1</p>';
                }
            };
            module.exports = { default: plugin };
        """.trimIndent()

        val source = LnPluginSource(pluginScript = script)
        try {
            val novelId = source.encodeNovelId("novel/title")
            source.getNovelDetails(novelId)
            val content = source.getChapterContent(novelId, "ch/0")
            assertEquals("Prologue: The Beginning", content.title)
        } finally {
            source.close()
        }
    }
}
