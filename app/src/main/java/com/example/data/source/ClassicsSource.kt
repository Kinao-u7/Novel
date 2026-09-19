package com.example.data.source

import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.Novel

class ClassicsSource : NovelSource {
    override val id: String = "classics_light"
    override val name: String = "Public Domain Classics"
    override val language: String = "EN"
    override val version: String = "1.0.0"
    override val description: String = "Timeless public domain light literature, science fiction, and adventure novels."
    override val isLocal: Boolean = false

    private val novels = listOf(
        Novel(
            id = "cl_time_machine",
            title = "The Time Machine",
            author = "H. G. Wells",
            coverUrl = "https://images.unsplash.com/photo-1506744038136-46273834b3fb?w=500&auto=format&fit=crop&q=80",
            description = "An English scientist and gentleman inventor known simply as the Time Traveller journeys eight hundred thousand years into the distant future, encountering the gentle Eloi and the subterranean, monstrous Morlocks.",
            genres = listOf("Sci-Fi", "Classic", "Time Travel", "Dystopian"),
            status = "Completed",
            sourceId = id,
            sourceName = name,
            totalChapters = 5,
            rating = 4.6f
        ),
        Novel(
            id = "cl_alice",
            title = "Alice's Adventures in Wonderland",
            author = "Lewis Carroll",
            coverUrl = "https://images.unsplash.com/photo-1512820790803-83ca734da794?w=500&auto=format&fit=crop&q=80",
            description = "A young girl named Alice falls down a rabbit hole into a subterranean fantasy realm populated by peculiar, anthropomorphic creatures, nonsensical riddles, and the Queen of Hearts.",
            genres = listOf("Fantasy", "Classic", "Adventure", "Whimsical"),
            status = "Completed",
            sourceId = id,
            sourceName = name,
            totalChapters = 6,
            rating = 4.7f
        ),
        Novel(
            id = "cl_holmes",
            title = "A Study in Scarlet",
            author = "Arthur Conan Doyle",
            coverUrl = "https://images.unsplash.com/photo-1481627834876-b7833e8f5570?w=500&auto=format&fit=crop&q=80",
            description = "The immortal meeting of Dr. John Watson and consulting detective Sherlock Holmes, leading to their first investigation at 221B Baker Street concerning a baffling murder in an abandoned Brixton mansion.",
            genres = listOf("Mystery", "Detective", "Classic", "Crime"),
            status = "Completed",
            sourceId = id,
            sourceName = name,
            totalChapters = 5,
            rating = 4.8f
        )
    )

    override suspend fun getPopularNovels(page: Int): List<Novel> = novels

    override suspend fun searchNovels(query: String, page: Int): List<Novel> {
        if (query.isBlank()) return novels
        val q = query.lowercase()
        return novels.filter {
            it.title.lowercase().contains(q) ||
            it.author.lowercase().contains(q) ||
            it.genres.any { g -> g.lowercase().contains(q) }
        }
    }

    override suspend fun getNovelDetails(novelId: String): Novel {
        return novels.firstOrNull { it.id == novelId } ?: novels.first()
    }

    override suspend fun getChapterList(novelId: String): List<Chapter> {
        val novel = getNovelDetails(novelId)
        val titles = when (novelId) {
            "cl_time_machine" -> listOf(
                "Introduction & The Fourth Dimension",
                "The Machine & The Journey Ahead",
                "The Golden Age of the Eloi",
                "A Night in the Dark Forest",
                "The Morlocks Below"
            )
            "cl_alice" -> listOf(
                "Down the Rabbit-Hole",
                "The Pool of Tears",
                "A Caucus-Race and a Long Tale",
                "The Rabbit Sends in a Little Bill",
                "Advice from a Caterpillar",
                "Pig and Pepper"
            )
            else -> listOf(
                "Mr. Sherlock Holmes",
                "The Science of Deduction",
                "The Lauriston Garden Mystery",
                "What John Rance Had to Tell",
                "Our Advertisement Brings a Visitor"
            )
        }
        return titles.mapIndexed { index, title ->
            Chapter(
                id = "${novelId}_ch_${index + 1}",
                novelId = novelId,
                title = "Chapter ${index + 1}: $title",
                chapterIndex = index + 1,
                releaseDate = "Classic",
                isDownloaded = false,
                isRead = false,
                wordCount = 1900 + (index * 60)
            )
        }
    }

    override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent {
        val idx = chapterId.substringAfterLast("_ch_").toIntOrNull() ?: 1
        val chapterList = getChapterList(novelId)
        val title = chapterList.getOrNull(idx - 1)?.title ?: "Chapter $idx"

        val paragraphs = when (novelId) {
            "cl_time_machine" -> listOf(
                "The Time Traveller was expounding a recondite matter to us. His grey eyes shone and twinkled, and his usually pale face was flushed and animated.",
                "The fire burnt brightly, and the soft radiance of the incandescent lights in the lilies of silver caught the bubbles that flashed and passed in our glasses.",
                "\"You must follow me carefully,\" he began. \"I shall have to controvert one or two ideas that are almost universally accepted. The geometry, for instance, that you were taught in the schools is founded on a misconception.\"",
                "\"Is not that rather a large thing to expect us to begin upon?\" said Filby, an argumentative person with red hair.",
                "\"I do not mean to ask you to accept anything without reasonable ground for it. You will soon admit as much as I need from you. You know of course that a mathematical line, a line of thickness nil, has no real existence?\"",
                "\"That is so,\" admitted the Medical Man.",
                "\"Nor has a mathematical plane. These things are mere abstractions. Well, nor has a cube having only length, breadth, and thickness a real existence.\"",
                "\"There I object,\" said Filby. \"Of course a solid body may exist. All real things—\"",
                "\"So most people think. But wait a moment. Can an instantaneous cube exist? Can a cube exist that has no duration in time whatsoever?\"",
                "Filby became pensive. \"Clearly,\" the Time Traveller proceeded, \"any real body must have extension in four directions: it must have Length, Breadth, Thickness, and—Duration.\""
            )
            "cl_alice" -> listOf(
                "Alice was beginning to get very tired of sitting by her sister on the bank, and of having nothing to do.",
                "Once or twice she had peeped into the book her sister was reading, but it had no pictures or conversations in it, \"and what is the use of a book,\" thought Alice, \"without pictures or conversations?\"",
                "So she was considering in her own mind (as well as she could, for the hot day made her feel very sleepy and stupid), whether the pleasure of making a daisy-chain would be worth the trouble of getting up and picking the daisies, when suddenly a White Rabbit with pink eyes ran close by her.",
                "There was nothing so very remarkable in that; nor did Alice think it so very much out of the way to hear the Rabbit say to itself, \"Oh dear! Oh dear! I shall be late!\"",
                "When the Rabbit actually took a watch out of its waistcoat-pocket, and looked at it, and then hurried on, Alice started to her feet, for it flashed across her mind that she had never before seen a rabbit with either a waistcoat-pocket, or a watch to take out of it.",
                "Burning with curiosity, she ran across the field after it, and fortunately was just in time to see it pop down a large rabbit-hole under the hedge."
            )
            else -> listOf(
                "In the year 1878 I took my degree of Doctor of Medicine of the University of London, and proceeded to Netley to go through the course prescribed for surgeons in the army.",
                "Having completed my studies there, I was duly attached to the Fifth Northumberland Fusiliers as Assistant Surgeon. The campaign brought honors and promotion to many, but for me it brought nothing but misfortune and disaster.",
                "I was removed from my brigade and attached to the Berkshires, with whom I served at the fatal battle of Maiwand. There I was struck on the shoulder by a Jezail bullet, which shattered the bone and grazed the subclavian artery.",
                "Worn with pain, and weak from prolonged hardships, I was removed to the base hospital at Peshawar.",
                "I returned to England with ruined health, but with an unmistakable impulse to seek out affordable lodgings in London. It was at the Criterion bar that I met young Stamford, who introduced me to a peculiar, observant gentleman named Sherlock Holmes."
            )
        }

        return ChapterContent(
            chapterId = chapterId,
            novelId = novelId,
            title = title,
            paragraphs = paragraphs,
            isDownloaded = false
        )
    }
}
