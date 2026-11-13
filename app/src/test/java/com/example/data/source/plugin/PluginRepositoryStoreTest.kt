package com.example.data.source.plugin

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PluginRepositoryStoreTest {

    @get:Rule val tempFolder = TemporaryFolder()
    private lateinit var pluginsDir: File

    @Before fun setUp() {
        pluginsDir = tempFolder.newFolder("plugins")
    }

    @Test fun seededOnFirstRunAndEmptyRemainsEmptyAfterRestart() = runBlocking<Unit> {
        val store1 = PluginRepositoryStore(pluginsDir)
        val initial = store1.getAll()
        assertEquals(listOf(Repository(PluginRepositoryStore.OFFICIAL_REPOSITORY_URL, enabled = true)), initial)
        assertTrue(File(pluginsDir, "repositories.json").exists())
        assertFalse(File(pluginsDir, "repositories.json.tmp").exists())

        store1.remove(PluginRepositoryStore.OFFICIAL_REPOSITORY_URL)
        assertTrue(store1.getAll().isEmpty())

        val store2 = PluginRepositoryStore(pluginsDir)
        assertTrue(store2.getAll().isEmpty())
    }

    @Test fun addNormalizesOfficialUrlRejectsDuplicateAndNonOfficialUrls() = runBlocking<Unit> {
        val store = PluginRepositoryStore(pluginsDir)
        store.remove(PluginRepositoryStore.OFFICIAL_REPOSITORY_URL)

        val variant = "  HTTPS://RAW.GitHubUserContent.COM/LNReader/lnreader-plugins/plugins/v3.0.0/.dist/plugins.min.json/#anchor  "
        val first = store.add(variant)
        assertEquals(
            AddRepositoryResult.Added(Repository(PluginRepositoryStore.OFFICIAL_REPOSITORY_URL, enabled = true)),
            first
        )

        assertEquals(AddRepositoryResult.Duplicate, store.add(PluginRepositoryStore.OFFICIAL_REPOSITORY_URL))
        assertEquals(AddRepositoryResult.NotSupported, store.add("https://example.com/plugins.min.json"))
        val httpVersion = PluginRepositoryStore.OFFICIAL_REPOSITORY_URL.replaceFirst("https://", "http://")
        assertEquals(AddRepositoryResult.NotSupported, store.add(httpVersion))
        assertEquals(1, store.getAll().size)
    }

    @Test fun togglePersistedAndRemoveOrDisableLeavesInstalledPluginsUntouched() = runBlocking<Unit> {
        val installedFile = File(pluginsDir, "installed_plugins.json")
        val installedContent = """[{"id":"p1","name":"P1","language":"EN","version":"1.0.0","site":"https://example.com"}]"""
        installedFile.writeText(installedContent, Charsets.UTF_8)
        val scriptFile = File(pluginsDir, "p1.js").apply { writeText("// p1", Charsets.UTF_8) }

        val store = PluginRepositoryStore(pluginsDir)
        assertEquals(1, store.getAll().size)

        store.setEnabled(PluginRepositoryStore.OFFICIAL_REPOSITORY_URL, false)
        val reloaded = PluginRepositoryStore(pluginsDir)
        assertEquals(listOf(Repository(PluginRepositoryStore.OFFICIAL_REPOSITORY_URL, enabled = false)), reloaded.getAll())
        assertEquals(installedContent, installedFile.readText(Charsets.UTF_8))
        assertTrue(scriptFile.exists())

        reloaded.remove(PluginRepositoryStore.OFFICIAL_REPOSITORY_URL)
        assertTrue(PluginRepositoryStore(pluginsDir).getAll().isEmpty())
        assertEquals(installedContent, installedFile.readText(Charsets.UTF_8))
        assertTrue(scriptFile.exists())
    }

    @Test fun corruptRepositoriesJsonFallsBackToSeededDefault() = runBlocking<Unit> {
        File(pluginsDir, "repositories.json").writeText("{corrupt", Charsets.UTF_8)
        val store = PluginRepositoryStore(pluginsDir)
        assertEquals(
            listOf(Repository(PluginRepositoryStore.OFFICIAL_REPOSITORY_URL, enabled = true)),
            store.getAll()
        )
    }
}
