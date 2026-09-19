package com.example.data.source

import com.example.data.model.Chapter
import com.example.data.model.ChapterContent
import com.example.data.model.Novel

class WebNovelSource : NovelSource {
    override val id: String = "webnovel_haven"
    override val name: String = "WebNovel Vault"
    override val language: String = "EN"
    override val version: String = "1.2.0"
    override val description: String = "Curated collection of premier fantasy, progression, cultivation, and isekai web novels."
    override val isLocal: Boolean = false

    private val novels = listOf(
        Novel(
            id = "wn_shadow_slave",
            title = "Shadow Slave",
            author = "Guiltythree",
            coverUrl = "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?w=500&auto=format&fit=crop&q=80",
            description = "Growing up in poverty, Sunny never expected anything good from life. However, when he is chosen by the Nightmare Spell, he gains a miraculous yet treacherous Divine Aspect. In a fractured world caught between dreamscapes and ancient gods, Sunny must navigate deceit, nightmare creatures, and his own shadow to survive.",
            genres = listOf("Progression Fantasy", "Dark Fantasy", "Action", "Mythology"),
            status = "Ongoing",
            sourceId = id,
            sourceName = name,
            totalChapters = 12,
            rating = 4.9f
        ),
        Novel(
            id = "wn_lotm",
            title = "Lord of the Mysteries",
            author = "Cuttlefish That Loves Diving",
            coverUrl = "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=500&auto=format&fit=crop&q=80",
            description = "With the rising tide of steam power and machinery, who can come close to being a Beyonder? Shrouded in the fog of history and darkness, who is the whisper in the ear? Waking up to face a string of mysteries, Zhou Mingrui finds himself reincarnated as Klein Moretti in an alternate Victorian-era world filled with airships, potions, tarot gatherings, and ancient deities.",
            genres = listOf("Mystery", "Steampunk", "Eldritch", "Transmigration"),
            status = "Completed",
            sourceId = id,
            sourceName = name,
            totalChapters = 10,
            rating = 5.0f
        ),
        Novel(
            id = "wn_tbate",
            title = "The Beginning After the End",
            author = "TurtleMe",
            coverUrl = "https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?w=500&auto=format&fit=crop&q=80",
            description = "King Grey possessed unrivaled strength, wealth, and prestige in a world governed by martial ability. Yet, solitude lingers beneath extraordinary success. Reincarnated into a new world brimming with magic and monsters, the king has a second chance to relive his life—discovering meaning and protecting those he loves.",
            genres = listOf("Fantasy", "Reincarnation", "Magic", "Adventure"),
            status = "Ongoing",
            sourceId = id,
            sourceName = name,
            totalChapters = 8,
            rating = 4.8f
        ),
        Novel(
            id = "wn_orv",
            title = "Omniscient Reader's Viewpoint",
            author = "Sing Shong",
            coverUrl = "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=500&auto=format&fit=crop&q=80",
            description = "Kim Dokja does not consider himself the protagonist of his own life. The only thing he loves is reading an obscure web novel titled 'Three Ways to Survive in a Ruined World'. When the novel's final chapter is posted, reality suddenly shatters, transforming into the very apocalypse he spent a decade reading about. Only Dokja knows the path ahead.",
            genres = listOf("Apocalypse", "Action", "Psychological", "System"),
            status = "Completed",
            sourceId = id,
            sourceName = name,
            totalChapters = 10,
            rating = 4.9f
        ),
        Novel(
            id = "wn_mol",
            title = "Mother of Learning",
            author = "nobody103",
            coverUrl = "https://images.unsplash.com/photo-1516962215378-7fa2e137ae93?w=500&auto=format&fit=crop&q=80",
            description = "Zorian Kazinski is an introverted, hardworking mage student at the Royal Academy of Magical Arts. When a mysterious invasion suddenly destroys the city during the summer festival, Zorian is killed—only to wake up in his dorm room one month earlier. Trapped in a time loop, he must master magic, forge unlikely alliances, and unravel the world's greatest secret.",
            genres = listOf("Time Loop", "Magic Academy", "Mystery", "Progression"),
            status = "Completed",
            sourceId = id,
            sourceName = name,
            totalChapters = 12,
            rating = 4.9f
        ),
        Novel(
            id = "wn_coiling_dragon",
            title = "Coiling Dragon",
            author = "I Eat Tomatoes",
            coverUrl = "https://images.unsplash.com/photo-1509198397868-475647b2a1e5?w=500&auto=format&fit=crop&q=80",
            description = "Linley Baruch is a young scion of a once-legendary clan of Dragonblood Warriors in the Yulan Continent. Stumbling upon an ancient, ring-shaped artifact in his clan's ancestral ruins, Linley begins an epic journey of elemental affinity, swordplay, and godlike cultivation across celestial planes.",
            genres = listOf("Xianxia", "Cultivation", "Martial Arts", "Adventure"),
            status = "Completed",
            sourceId = id,
            sourceName = name,
            totalChapters = 8,
            rating = 4.7f
        )
    )

    override suspend fun getPopularNovels(page: Int): List<Novel> = novels

    override suspend fun searchNovels(query: String, page: Int): List<Novel> {
        if (query.isBlank()) return novels
        val q = query.lowercase()
        return novels.filter {
            it.title.lowercase().contains(q) ||
            it.author.lowercase().contains(q) ||
            it.genres.any { g -> g.lowercase().contains(q) } ||
            it.description.lowercase().contains(q)
        }
    }

    override suspend fun getNovelDetails(novelId: String): Novel {
        return novels.firstOrNull { it.id == novelId } ?: novels.first()
    }

    override suspend fun getChapterList(novelId: String): List<Chapter> {
        val novel = getNovelDetails(novelId)
        return (1..novel.totalChapters).map { idx ->
            Chapter(
                id = "${novelId}_ch_$idx",
                novelId = novelId,
                title = "Chapter $idx: ${getChapterTitle(novelId, idx)}",
                chapterIndex = idx,
                releaseDate = "Recent",
                isDownloaded = false,
                isRead = false,
                wordCount = 1850 + (idx * 42)
            )
        }
    }

    override suspend fun getChapterContent(novelId: String, chapterId: String): ChapterContent {
        val idx = chapterId.substringAfterLast("_ch_").toIntOrNull() ?: 1
        val title = "Chapter $idx: ${getChapterTitle(novelId, idx)}"
        val paragraphs = generateRichChapterParagraphs(novelId, idx)
        return ChapterContent(
            chapterId = chapterId,
            novelId = novelId,
            title = title,
            paragraphs = paragraphs,
            isDownloaded = false
        )
    }

    private fun getChapterTitle(novelId: String, idx: Int): String {
        return when (novelId) {
            "wn_shadow_slave" -> listOf(
                "Nightmare Awakening", "A Treacherous Gift", "Whispers in the Dark",
                "The First Trial", "Echoes of the Past", "A Shadow Awakens",
                "The Crimson Shore", "Carving a Path", "Under the Blood Sky",
                "The Tyrant's Domain", "Blade of Shadows", "A Fragile Dawn"
            ).getOrElse(idx - 1) { "Echo of Destiny $idx" }

            "wn_lotm" -> listOf(
                "Crimson Fog", "The Fool's Tarot", "Beyonder Secrets",
                "Sequence 9: Seer", "A Cup of Black Tea", "The Divination Club",
                "Shadows Over Backlund", "The Hornacis Whisper", "The Sealed Artifact",
                "A Gathering of Stars"
            ).getOrElse(idx - 1) { "Mystery of the Abyss $idx" }

            "wn_tbate" -> listOf(
                "Reborn in Light", "Mana Awakening", "The Forest of Elshire",
                "A Royal Acquaintance", "The Swordmaster's Teachings", "Tessia's Secret",
                "Beast Glades Expedition", "Echoes of War"
            ).getOrElse(idx - 1) { "The Radiant Path $idx" }

            "wn_orv" -> listOf(
                "The Starting Scenario", "Bridge of Broken Stars", "A Coin for Survival",
                "Constellation's Gaze", "Demon of Salvation", "The Prophet's Path",
                "Throne of Legends", "Reader vs Author", "The Outer Gods", "A New Narrative"
            ).getOrElse(idx - 1) { "Scenario Clear $idx" }

            "wn_mol" -> listOf(
                "Morning Bell", "Aranea in the Ruins", "Simulated Combat",
                "The Red Robe", "Loop Seven", "Mind Magic Fundamentals",
                "The Cyoria Incident", "Shaping Mana", "A Sudden Departure",
                "Echoes of Memory", "Soul Sight", "Breaking the Gate"
            ).getOrElse(idx - 1) { "Iterative Loop $idx" }

            else -> "The Awakening of Power Part $idx"
        }
    }

    private fun generateRichChapterParagraphs(novelId: String, idx: Int): List<String> {
        return when (novelId) {
            "wn_shadow_slave" -> listOf(
                "The cold stone beneath his fingertips felt unforgiving and damp. Sunny opened his eyes to an oppressive, endless gray twilight that seemed to swallow all warmth from the air.",
                "A faint ringing echoed in his ears, followed by the dispassionate, resonant chime of the Spell.",
                "[Aspirant Sunny, welcome to the First Nightmare.]",
                "He pushed himself upward with trembling arms. His breath curled like silver wisps in the freezing wind. Around him, towering obsidian ruins stretched toward an abyssal horizon, carved with incomprehensible geometric patterns that hurt the eyes if gazed upon too long.",
                "\"Great,\" Sunny muttered, his teeth chattering uncontrollably. \"Of all the pleasant vacation spots across the Dream Realm, I get dropped straight into an open-air tomb.\"",
                "He took stock of his belongings. A torn coat, worn leather boots, and a dull hunting knife tucked into his belt. Not exactly the arsenal of an epic hero. But then again, Sunny had never harbored any ridiculous fantasies about being a hero. In the outskirts, heroes were just dead men who made too much noise.",
                "Suddenly, his own shadow on the frosted floor flickered. It tilted its head slightly, as if mocking his cautious posture.",
                "Sunny blinked, rubbing his temples. \"Now you're judging me too?\"",
                "From the cracked arches fifty yards ahead, a guttural growl broke the dead silence. Two pairs of crimson eyes ignited in the pitch darkness, burning with ravenous hunger.",
                "Sunny tightened his grip on the rusted hilt. His heart hammered like a war drum against his ribs, but beneath the raw fear, an icy focus washed over his mind. Survival was the only law that mattered.",
                "With a silent inhalation, he lowered his center of gravity and stepped into the shadow."
            )

            "wn_lotm" -> listOf(
                "The sharp scent of crimson iron and burning gunpowder filled Zhou Mingrui's senses before his eyes even adjusted to the dim candlelight.",
                "He reached out reflexively, feeling the cold, smooth barrel of a revolver resting on the solid mahogany desk beside him. A mirror with an ornate bronze frame stood nearby, reflecting a pale young man with dark brown hair, deep brown eyes, and a neat white shirt stained with crimson near the temple.",
                "Pain surged through his skull like a thousand needle pricks, bringing with it a deluge of fragmented memories.",
                "Klein Moretti. Graduate of the Department of History at Hoy University. The city of Tingen. The Kingdom of Loen.",
                "\"I... I transmigrated?\" he whispered, his voice dry and raspy.",
                "A soft, rhythmic ticking caught his attention. On the edge of the desk lay a silver pocket watch engraved with vines and stars. Through the open lace curtains, the night sky was dominated not by a pale moon, but by a colossal, ethereal crimson moon that bathed the gas-lit streets below in an otherworldly scarlet glow.",
                "Something was deeply wrong with this world. He could hear faint, incomprehensible whispers brushing against the periphery of his consciousness, like tides surging from an ancient deep.",
                "Klein took a deep, steadying breath. As an ordinary keyboard warrior from the twenty-first century, fear was inevitable. But curiosity—and the primal instinct to survive—was stronger.",
                "He closed his eyes, counted four steps in a counter-clockwise square, and prepared to touch the grey fog above the spirit world."
            )

            "wn_mol" -> listOf(
                "The shrill, mechanical clatter of the brass alarm clock snapped Zorian out of sleep with ruthless efficiency.",
                "He groaned, pulling the blanket over his face, but the rhythmic ticking was unyielding. Outside his bedroom window, the birds of Cirin chirped cheerfully, completely indifferent to his misery.",
                "\"Zorian! Breakfast is getting cold!\" his mother's voice shouted from down the stairs, followed closely by the patter of his little sister's running footsteps.",
                "Zorian rubbed his eyes, sitting up. The cool morning air sent a shiver through his shoulders. Today was the first day of his third year at the Royal Academy of Magical Arts in Cyoria.",
                "He gathered his notebooks, checking his leather satchel. Wards, arithmetic spell arrays, shaping exercises, and alchemy reagents—all meticulously organized.",
                "He had no idea that within twenty-four days, a catastrophic explosion would rip through the skies of Cyoria, tearing open the dimensional veil and pulling him into a repeating thirty-day prison.",
                "For now, he simply slipped into his boots, picked up his practice wand, and stepped out into what felt like just another ordinary morning."
            )

            else -> listOf(
                "The morning mist hung low over the ancient cobblestones, shrouding the towering spires of the city in quiet mystery.",
                "Every breath tasted of adventure and untamed elemental mana. Travelers gathered around the notice board, debating the latest expeditions into the uncharted wilderness.",
                "In the center of the tavern, a solitary youth sat by the hearth, turning the weathered pages of a leather-bound grimoire.",
                "\"Strength isn't given by birthright,\" he murmured softly, feeling the latent resonance of his core awaken. \"It is carved, stroke by stroke, through perseverance.\"",
                "Outside, the cathedral bells chimed twelve times, signaling the start of a journey that would echo across generations."
            )
        }
    }
}
