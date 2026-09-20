package com.example

import com.example.data.source.webscrape.WtrLabExtractor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WtrLabScraperTest {

    @Test
    fun `test exact WTR-Lab metadata extraction and chapter parsing for novel 98790`() {
        // Construct realistic Next.js __NEXT_DATA__ JSON according to WTR-Lab's exact schema
        val nextDataJson = JSONObject().apply {
            put("props", JSONObject().apply {
                put("pageProps", JSONObject().apply {
                    put("serie", JSONObject().apply {
                        put("raw_id", 98790)
                        put("slug", "tycoon-if-you-keep-imagining-things-im-going-to-become-a-god")
                        put("chapter_count", 107)
                        put("serie_data", JSONObject().apply {
                            put("raw_id", 98790)
                            put("chapter_count", 107)
                            put("genres", JSONArray().apply {
                                put(10) // Urban
                                put(24) // Fantasy
                            })
                            put("tags", JSONArray().apply {
                                put(101) // Business
                                put(102) // System
                            })
                            put("data", JSONObject().apply {
                                put("title", "Tycoon: If You Keep Imagining Things, I'm Going to Become a God")
                                put("author", "Dreaming of Ten Thousand Mountains")
                                put("description", "Lin Feng transmigrated and became a down-and-out young master with a special imagining system.")
                                put("image", "/images/covers/98790.jpg")
                            })
                        })
                    })
                    put("tags", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", 10)
                            put("title", "Urban")
                            put("category_id", 1)
                            put("category_name", "Genre")
                        })
                        put(JSONObject().apply {
                            put("id", 24)
                            put("title", "Fantasy")
                            put("category_id", 1)
                            put("category_name", "Genre")
                        })
                        put(JSONObject().apply {
                            put("id", 101)
                            put("title", "Business")
                            put("category_id", 2)
                            put("category_name", "Tag")
                        })
                        put(JSONObject().apply {
                            put("id", 102)
                            put("title", "System")
                            put("category_id", 2)
                            put("category_name", "Tag")
                        })
                    })
                })
            })
            put("query", JSONObject().apply {
                put("locale", "en")
                put("serie_slug", "tycoon-if-you-keep-imagining-things-im-going-to-become-a-god")
            })
        }.toString()

        val metadata = WtrLabExtractor.extractMetadataFromNextData(
            nextDataJsonStr = nextDataJson,
            pageUrl = "https://wtr-lab.com/en/serie-98790/tycoon-if-you-keep-imagining-things-im-going-to-become-a-god"
        )

        assertNotNull(metadata)
        assertEquals("Tycoon: If You Keep Imagining Things, I'm Going to Become a God", metadata!!.title)
        assertEquals("Dreaming of Ten Thousand Mountains", metadata.author)
        assertTrue(metadata.synopsis.contains("Lin Feng transmigrated"))
        assertEquals("98790", metadata.rawId)
        assertEquals(107, metadata.chapterCount)
        assertEquals("en", metadata.locale)
        assertEquals("tycoon-if-you-keep-imagining-things-im-going-to-become-a-god", metadata.serieSlug)
        assertEquals("https://wtr-lab.com/images/covers/98790.jpg", metadata.coverUrl)

        // Verify tags resolution
        assertEquals(listOf("Urban", "Fantasy", "Business", "System"), metadata.genres)

        // Now test chapters API response with all 107 chapters
        val apiChaptersJson = JSONObject().apply {
            val chaptersArr = JSONArray()
            for (i in 1..107) {
                chaptersArr.put(
                    JSONObject().apply {
                        put("id", 1000 + i)
                        put("order", i)
                        put("title", "Chapter $i: Journey to Immortality Part $i")
                        put("name", "第${i}章")
                        put("updated_at", "2024-03-01T12:00:00Z")
                    }
                )
            }
            put("chapters", chaptersArr)
        }.toString()

        val chapters = WtrLabExtractor.parseChaptersResponse(
            jsonStr = apiChaptersJson,
            locale = metadata.locale,
            serieSlug = metadata.serieSlug
        )

        assertEquals(107, chapters.size)
        assertEquals(1, chapters.first().order)
        assertEquals("Chapter 1: Journey to Immortality Part 1", chapters.first().title)
        assertEquals(
            "https://wtr-lab.com/en/tycoon-if-you-keep-imagining-things-im-going-to-become-a-god/chapter-1",
            chapters.first().url
        )

        assertEquals(107, chapters.last().order)
        assertEquals("Chapter 107: Journey to Immortality Part 107", chapters.last().title)
        assertEquals(
            "https://wtr-lab.com/en/tycoon-if-you-keep-imagining-things-im-going-to-become-a-god/chapter-107",
            chapters.last().url
        )
    }
}
