package com.example.ui.screens.discover

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.repository.SettingsRepository
import com.example.data.source.SourceManager
import com.example.data.source.plugin.InstalledPluginSources
import com.example.data.source.plugin.LnPluginSource
import com.example.data.source.plugin.PluginCatalogueEntry
import com.example.data.source.plugin.PluginInstallationManager
import com.example.data.source.plugin.PluginRepositoryStore
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiscoverViewModelTest {

    @get:Rule val tempFolder = TemporaryFolder()
    private lateinit var mainExecutor: ExecutorService
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    private lateinit var context: Context
    private lateinit var pluginsDir: File
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var repositoryStore: PluginRepositoryStore
    private lateinit var installationManager: PluginInstallationManager
    private lateinit var installedPluginSources: InstalledPluginSources
    private val failCatalogue = AtomicBoolean(false)
    private var viewModel: DiscoverViewModel? = null

    private fun fakeJs(id: String, title: String) =
        "function P(){this.id='$id';this.name='$id';this.popularNovels=function(){return[{name:'$title',path:'/n1'}];};}exports.default=new P();"

    private val catalogueJson = """
        [
          {"id":"upd_p","name":"Updatable Plugin","site":"https://example.com","lang":"English","version":"2.0.0","url":"https://example.com/upd_p.js","iconUrl":""},
          {"id":"keep_p","name":"UpToDate Plugin","site":"https://example.com","lang":"English","version":"1.0.0","url":"https://example.com/keep_p.js","iconUrl":""},
          {"id":"new_p","name":"Fresh Plugin","site":"https://example.com","lang":"English","version":"1.0.0","url":"https://example.com/new_p.js","iconUrl":""}
        ]
    """.trimIndent()

    @Before fun setUp() {
        mainExecutor = Executors.newSingleThreadExecutor()
        mainDispatcher = mainExecutor.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
        context = ApplicationProvider.getApplicationContext()
        pluginsDir = tempFolder.newFolder("plugins")
        File(context.filesDir, "app_settings.json").delete()
        settingsRepository = SettingsRepository.createForTest(context, Dispatchers.IO)
        repositoryStore = PluginRepositoryStore(pluginsDir)

        val httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            val (code, body) = when {
                url == PluginRepositoryStore.OFFICIAL_REPOSITORY_URL && failCatalogue.get() -> 500 to "error"
                url == PluginRepositoryStore.OFFICIAL_REPOSITORY_URL -> 200 to catalogueJson
                url.endsWith("/upd_p.js") -> 200 to fakeJs("upd_p", "Updated Novel")
                url.endsWith("/keep_p.js") -> 200 to fakeJs("keep_p", "Keep Novel")
                url.endsWith("/new_p.js") -> 200 to fakeJs("new_p", "Fresh Novel")
                else -> 404 to ""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message(if (code == 200) "OK" else "ERR")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

        installationManager = PluginInstallationManager(pluginsDir, httpClient = httpClient)
        installedPluginSources = InstalledPluginSources(installationManager, context)
    }

    @After fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        SourceManager.sources.value.map { it.id }.filter { it.startsWith(LnPluginSource.PREFIX) }
            .forEach { SourceManager.unregisterSource(it) }
        Dispatchers.resetMain()
        mainDispatcher.close()
    }

    private fun createVm() = DiscoverViewModel(
        settingsRepository, repositoryStore, installationManager, installedPluginSources,
        stringProvider = { id, args -> if (args.isEmpty()) context.getString(id) else context.getString(id, *args) }
    ).also { viewModel = it }

    private suspend fun awaitState(vm: DiscoverViewModel, predicate: (DiscoverUiState) -> Boolean): DiscoverUiState {
        var last = vm.uiState.value
        return try {
            withTimeout(5000L) { vm.uiState.first { last = it; predicate(it) } }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("Timed out. Last state: $last", e)
        }
    }

    @Test fun installedWithoutRepoAndCatalogueLoadsUpdatesAfterAddingOfficialUrl() = runBlocking<Unit> {
        installationManager.install(PluginCatalogueEntry("upd_p", "Updatable Plugin", "https://example.com", "EN", "English", "1.0.0", "https://example.com/upd_p.js"))
        installationManager.install(PluginCatalogueEntry("keep_p", "UpToDate Plugin", "https://example.com", "EN", "English", "1.0.0", "https://example.com/keep_p.js"))
        repositoryStore.remove(PluginRepositoryStore.OFFICIAL_REPOSITORY_URL)

        val vm = createVm()
        vm.onTabChange(DiscoverTab.EXTENSIONS)
        val offlineState = awaitState(vm) { it.tab == DiscoverTab.EXTENSIONS && it.installed.size == 2 }
        assertTrue(offlineState.available.isEmpty())
        assertTrue(offlineState.updates.isEmpty())
        assertTrue(offlineState.repositories.isEmpty())

        vm.onAddRepository(PluginRepositoryStore.OFFICIAL_REPOSITORY_URL)
        val onlineState = awaitState(vm) { it.updates.size == 1 && it.available.size == 1 }
        assertEquals("upd_p", onlineState.updates.single().id)
        assertEquals("2.0.0", onlineState.updates.single().availableVersion)
        assertEquals("keep_p", onlineState.installed.single().id)
        assertEquals("new_p", onlineState.available.single().id)

        vm.onUpdate("upd_p")
        val updated = awaitState(vm) { it.updates.isEmpty() && it.installed.size == 2 }
        assertEquals("2.0.0", updated.installed.first { it.id == "upd_p" }.installedVersion)
    }

    @Test fun installRegistersSourceAndUninstallRemovesAndClosesIt() = runBlocking<Unit> {
        val vm = createVm()
        vm.onTabChange(DiscoverTab.EXTENSIONS)
        awaitState(vm) { it.available.any { e -> e.id == "new_p" } }

        vm.onInstall("new_p")
        awaitState(vm) { it.installed.any { e -> e.id == "new_p" } }
        val registered = SourceManager.getSourceById("ln_new_p") as? LnPluginSource
        assertNotNull(registered)
        assertEquals("Fresh Novel", registered!!.getPopularNovels(1).single().title)
        assertNotNull(registered.getBridgeHost())

        vm.onRequestUninstall("new_p")
        val confirm = awaitState(vm) { it.pendingUninstall?.id == "new_p" }
        assertEquals("new_p", confirm.pendingUninstall?.id)

        vm.onConfirmUninstall()
        awaitState(vm) { it.installed.none { e -> e.id == "new_p" } }
        assertNull(SourceManager.getSourceById("ln_new_p"))
        assertNull(registered.getBridgeHost())
    }

    @Test fun failedCatalogueLoadShowsErrorAndRetryAndNonOfficialRepoRejected() = runBlocking<Unit> {
        failCatalogue.set(true)
        val vm = createVm()
        vm.onTabChange(DiscoverTab.EXTENSIONS)
        val errState = awaitState(vm) { it.catalogueError != null }
        assertTrue(errState.available.isEmpty())

        failCatalogue.set(false)
        vm.onRetryCatalogue()
        val okState = awaitState(vm) { it.catalogueError == null && it.available.size == 3 }
        assertEquals(3, okState.available.size)

        vm.onAddRepository("https://example.com/other.json")
        val badRepo = awaitState(vm) { it.repositoryError != null }
        assertEquals(context.getString(R.string.discover_repo_error_not_supported), badRepo.repositoryError)
    }
}
