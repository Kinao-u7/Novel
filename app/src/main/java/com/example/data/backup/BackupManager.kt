package com.example.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.data.epub.EpubParser
import com.example.data.local.db.NovelDatabase
import com.example.data.local.db.dao.BookmarkDao
import com.example.data.local.db.dao.ChapterDao
import com.example.data.local.db.dao.CustomSourceDao
import com.example.data.local.db.dao.NovelDao
import com.example.data.local.db.dao.ReadingSessionDao
import com.example.data.local.db.dao.TermReplacementDao
import com.example.data.local.storage.ChapterStorage
import com.example.data.local.storage.StorageResolver
import com.example.data.model.AppSettings
import com.example.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupManager(
    private val context: Context,
    private val database: NovelDatabase,
    private val novelDao: NovelDao,
    private val chapterDao: ChapterDao,
    private val bookmarkDao: BookmarkDao,
    private val termReplacementDao: TermReplacementDao,
    private val customSourceDao: CustomSourceDao,
    private val readingSessionDao: ReadingSessionDao,
    private val settingsRepository: SettingsRepository,
    private val storageResolver: StorageResolver,
    private val chapterStorage: ChapterStorage,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun suggestedFileName(nowMillis: Long = System.currentTimeMillis()): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US)
        return "xian-backup-${formatter.format(Date(nowMillis))}.zip"
    }

    suspend fun readManifest(input: InputStream): BackupManifest = withContext(ioDispatcher) {
        try {
            ZipInputStream(BufferedInputStream(input)).use { zis ->
                val entry = zis.nextEntry ?: throw BackupException.NotABackup
                if (entry.name != "manifest.json") {
                    throw BackupException.NotABackup
                }
                val baos = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var len: Int
                while (zis.read(buffer).also { len = it } > 0) {
                    baos.write(buffer, 0, len)
                }
                val manifestJson = baos.toString(Charsets.UTF_8.name())
                val manifest = json.decodeFromString<BackupManifest>(manifestJson)
                if (manifest.schemaVersion > 1) {
                    throw BackupException.TooNew(manifest.schemaVersion)
                }
                manifest
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackupException.Corrupt("Failed to read manifest: ${e.message}")
        }
    }

    suspend fun createBackup(
        output: OutputStream,
        categories: Set<BackupCategory>,
        onProgress: (BackupProgress) -> Unit = {}
    ): BackupSummary = withContext(ioDispatcher) {
        // Resolve effective categories (dropping categories whose dependencies are missing)
        val effectiveCategories = categories.filter { cat ->
            cat.dependsOn().all { it in categories }
        }.toSet()

        onProgress(BackupProgress("Gathering data", 0, 100))

        val libraryNovels = if (BackupCategory.LIBRARY in effectiveCategories) {
            novelDao.getAllLibraryNovels()
        } else emptyList()

        val libraryNovelIds = libraryNovels.map { it.id }.toSet()

        val chapters = if (BackupCategory.LIBRARY in effectiveCategories) {
            libraryNovels.flatMap { chapterDao.getChapters(it.id) }
        } else emptyList()

        val bookmarks = if (BackupCategory.BOOKMARKS in effectiveCategories) {
            bookmarkDao.getAllBookmarks().filter { it.novelId in libraryNovelIds }
        } else emptyList()

        val termReplacements = if (BackupCategory.TERM_REPLACEMENTS in effectiveCategories) {
            termReplacementDao.getAll().filter { it.novelId == null || it.novelId in libraryNovelIds }
        } else emptyList()

        val customSources = if (BackupCategory.CUSTOM_SOURCES in effectiveCategories) {
            customSourceDao.getAllSources()
        } else emptyList()

        val readingSessions = if (BackupCategory.STATS in effectiveCategories) {
            readingSessionDao.getAllSessions().filter { it.novelId in libraryNovelIds }
        } else emptyList()

        val settings = if (BackupCategory.SETTINGS in effectiveCategories) {
            settingsRepository.settings.value
        } else null

        val coverFiles = if (BackupCategory.COVERS in effectiveCategories) {
            libraryNovels.mapNotNull { novel ->
                val file = storageResolver.getCoverFile(novel.id)
                if (file.exists() && file.length() > 0) novel.id to file else null
            }
        } else emptyList()

        val epubFiles = if (BackupCategory.EPUB_FILES in effectiveCategories) {
            libraryNovels.filter { it.isEpub }.mapNotNull { novel ->
                val file = storageResolver.getOriginalEpubFile(novel.id)
                if (file.exists() && file.length() > 0) novel.id to file else null
            }
        } else emptyList()

        val backupNovels = libraryNovels.map { it.toBackup() }
        val backupChapters = chapters.map { it.toBackup() }
        val backupBookmarks = bookmarks.map { it.toBackup() }
        val backupTerms = termReplacements.map { it.toBackup() }
        val backupSources = customSources.map { it.toBackup() }
        val backupSessions = readingSessions.map { it.toBackup() }

        val backupData = BackupData(
            novels = backupNovels,
            chapters = backupChapters,
            bookmarks = backupBookmarks,
            termReplacements = backupTerms,
            customSources = backupSources,
            readingSessions = backupSessions
        )

        val settingsJsonBytes = settings?.let {
            json.encodeToString(AppSettings.serializer(), it).toByteArray(Charsets.UTF_8)
        }
        val dataJsonBytes = json.encodeToString(BackupData.serializer(), backupData).toByteArray(Charsets.UTF_8)
        val coversBytes = coverFiles.sumOf { it.second.length() }
        val epubsBytes = epubFiles.sumOf { it.second.length() }
        val totalUncompressedBytes = (settingsJsonBytes?.size ?: 0) + dataJsonBytes.size + coversBytes + epubsBytes

        val counts = mutableMapOf<String, Int>()
        if (BackupCategory.LIBRARY in effectiveCategories) {
            counts["novels"] = backupNovels.size
            counts["chapters"] = backupChapters.size
        }
        if (BackupCategory.BOOKMARKS in effectiveCategories) counts["bookmarks"] = backupBookmarks.size
        if (BackupCategory.TERM_REPLACEMENTS in effectiveCategories) counts["termReplacements"] = backupTerms.size
        if (BackupCategory.CUSTOM_SOURCES in effectiveCategories) counts["customSources"] = backupSources.size
        if (BackupCategory.STATS in effectiveCategories) counts["readingSessions"] = backupSessions.size
        if (BackupCategory.COVERS in effectiveCategories) counts["covers"] = coverFiles.size
        if (BackupCategory.EPUB_FILES in effectiveCategories) counts["epubFiles"] = epubFiles.size

        val manifest = BackupManifest(
            schemaVersion = 1,
            appVersion = "1.0.0",
            createdAtMillis = System.currentTimeMillis(),
            categories = effectiveCategories.map { it.name },
            counts = counts,
            totalBytes = totalUncompressedBytes
        )
        val manifestBytes = json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)

        var totalZipBytesWritten = 0L
        val countingOut = object : FilterOutputStream(output) {
            override fun write(b: Int) {
                super.write(b)
                totalZipBytesWritten++
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                super.write(b, off, len)
                totalZipBytesWritten += len
            }
        }

        // Ordered writing:
        // 1. manifest.json
        // 2. settings.json
        // 3. data.json
        // 4. covers/<novelId>.jpg
        // 5. original_epubs/<novelId>.epub
        ZipOutputStream(BufferedOutputStream(countingOut)).use { zos ->
            onProgress(BackupProgress("Writing manifest", 1, 5))
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifestBytes)
            zos.closeEntry()

            if (settingsJsonBytes != null) {
                onProgress(BackupProgress("Writing settings", 2, 5))
                zos.putNextEntry(ZipEntry("settings.json"))
                zos.write(settingsJsonBytes)
                zos.closeEntry()
            }

            onProgress(BackupProgress("Writing database", 3, 5))
            zos.putNextEntry(ZipEntry("data.json"))
            zos.write(dataJsonBytes)
            zos.closeEntry()

            var coverIndex = 0
            for ((novelId, file) in coverFiles) {
                coverIndex++
                onProgress(BackupProgress("Writing covers", coverIndex, coverFiles.size.coerceAtLeast(1)))
                zos.putNextEntry(ZipEntry("covers/$novelId.jpg"))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }

            var epubIndex = 0
            for ((novelId, file) in epubFiles) {
                epubIndex++
                onProgress(BackupProgress("Writing EPUBs", epubIndex, epubFiles.size.coerceAtLeast(1)))
                zos.putNextEntry(ZipEntry("original_epubs/$novelId.epub"))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            zos.finish()
        }

        BackupSummary(
            novels = backupNovels.size,
            chapters = backupChapters.size,
            bookmarks = backupBookmarks.size,
            bytes = totalZipBytesWritten
        )
    }

    suspend fun restore(
        openInput: () -> InputStream,
        categories: Set<BackupCategory>,
        mode: RestoreMode,
        onProgress: (BackupProgress) -> Unit = {}
    ): RestoreSummary = withContext(ioDispatcher) {
        val manifest = readManifest(openInput())

        if (storageResolver.filesDir.usableSpace < manifest.totalBytes * 1.2) {
            throw BackupException.NotEnoughSpace
        }

        val manifestCategories = manifest.categories.mapNotNull { name ->
            try {
                BackupCategory.valueOf(name)
            } catch (_: Exception) { null }
        }.toSet()

        val requestedCategories = categories.filter { it in manifestCategories }.toSet()
        val effectiveCategories = requestedCategories.filter { cat ->
            cat.dependsOn().all { it in requestedCategories }
        }.toSet()

        val stagingDir = File(storageResolver.getStagingDir(), UUID.randomUUID().toString())
        stagingDir.mkdirs()

        try {
            // Extract files safely
            onProgress(BackupProgress("Extracting archive", 0, 100))
            var entryCount = 0
            var cumulativeBytes = 0L

            ZipInputStream(BufferedInputStream(openInput())).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        entryCount++
                        if (entryCount > ZipSafety.MAX_ENTRIES) {
                            throw BackupException.TooLarge
                        }

                        ZipSafety.assertSafeEntry(entry.name)

                        val targetFile = File(stagingDir, entry.name)
                        targetFile.parentFile?.mkdirs()

                        FileOutputStream(targetFile).use { fos ->
                            val written = ZipSafety.safeCopy(zis, fos, entry.name, cumulativeBytes)
                            cumulativeBytes += written
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // Parse data.json if any DB category is selected
            val dataFile = File(stagingDir, "data.json")
            val backupData = if (dataFile.exists()) {
                val dataJson = dataFile.readText(Charsets.UTF_8)
                json.decodeFromString<BackupData>(dataJson)
            } else {
                BackupData()
            }

            var novelsAdded = 0
            var novelsUpdated = 0
            var chaptersMarkedRead = 0
            var bookmarksAdded = 0
            val backupIdToCanonicalId = mutableMapOf<String, String>()
            val newlyCreatedNovelIds = mutableSetOf<String>()

            // Room Database Transaction
            onProgress(BackupProgress("Restoring database", 50, 100))
            database.withTransaction {
                if (mode == RestoreMode.REPLACE) {
                    if (BackupCategory.LIBRARY in effectiveCategories) {
                        novelDao.deleteAll()
                        readingSessionDao.deleteAll()
                    }
                    if (BackupCategory.TERM_REPLACEMENTS in effectiveCategories) {
                        termReplacementDao.deleteAll()
                    }
                    if (BackupCategory.CUSTOM_SOURCES in effectiveCategories) {
                        customSourceDao.deleteAll()
                    }
                }

                // 1. Novels
                if (BackupCategory.LIBRARY in effectiveCategories) {
                    for (bn in backupData.novels) {
                        val novelEntity = bn.toEntity()
                        if (mode == RestoreMode.REPLACE) {
                            novelDao.insertIgnore(novelEntity)
                            backupIdToCanonicalId[bn.id] = novelEntity.id
                            newlyCreatedNovelIds.add(novelEntity.id)
                            novelsAdded++
                        } else {
                            val normalizedRawId = bn.rawId?.trim()?.ifEmpty { null }
                            val existing = if (normalizedRawId != null) {
                                novelDao.getNovelBySourceAndRawId(bn.sourceId, normalizedRawId)
                                    ?: novelDao.getNovelById(bn.id)
                            } else {
                                novelDao.getNovelById(bn.id)
                            }

                            if (existing == null) {
                                novelDao.insertIgnore(novelEntity)
                                backupIdToCanonicalId[bn.id] = novelEntity.id
                                newlyCreatedNovelIds.add(novelEntity.id)
                                novelsAdded++
                            } else {
                                backupIdToCanonicalId[bn.id] = existing.id
                                // For existing novel in MERGE: never change its metadata or cover, only set in_library = 1
                                novelDao.setInLibrary(existing.id, true)
                                novelsUpdated++
                            }
                        }
                    }

                    // 2. Chapters
                    for (bc in backupData.chapters) {
                        val canonicalNovelId = backupIdToCanonicalId[bc.novelId] ?: bc.novelId
                        if (novelDao.getNovelById(canonicalNovelId) == null) continue

                        val chapterEntity = bc.toEntity(
                            targetNovelId = canonicalNovelId,
                            hash = storageResolver.sha256(bc.id)
                        )

                        if (mode == RestoreMode.REPLACE) {
                            chapterDao.insertIgnore(chapterEntity)
                            if (bc.isRead) chaptersMarkedRead++
                        } else {
                            val existing = chapterDao.getChapter(canonicalNovelId, bc.id)
                            if (existing == null) {
                                chapterDao.insertIgnore(chapterEntity)
                                if (bc.isRead) chaptersMarkedRead++
                            } else {
                                // Union read state: if backup chapter isRead == true, mark read; never unmark read
                                val shouldMarkRead = existing.isRead || bc.isRead
                                val (pIndex, cOffset) = if (existing.readParagraphIndex == 0 && existing.readCharOffset == 0) {
                                    bc.readParagraphIndex to bc.readCharOffset
                                } else {
                                    existing.readParagraphIndex to existing.readCharOffset
                                }

                                if (shouldMarkRead != existing.isRead ||
                                    pIndex != existing.readParagraphIndex ||
                                    cOffset != existing.readCharOffset
                                ) {
                                    chapterDao.updateReadingProgress(
                                        novelId = canonicalNovelId,
                                        id = existing.id,
                                        isRead = shouldMarkRead,
                                        paragraphIndex = pIndex,
                                        charOffset = cOffset
                                    )
                                    if (!existing.isRead && shouldMarkRead) {
                                        chaptersMarkedRead++
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Bookmarks
                if (BackupCategory.BOOKMARKS in effectiveCategories) {
                    for (bm in backupData.bookmarks) {
                        val canonicalNovelId = backupIdToCanonicalId[bm.novelId] ?: bm.novelId
                        if (novelDao.getNovelById(canonicalNovelId) == null) continue

                        val entity = bm.toEntity(canonicalNovelId)
                        val inserted = bookmarkDao.insertIgnore(entity)
                        if (inserted != -1L) bookmarksAdded++
                    }
                }

                // 4. Term Replacements
                if (BackupCategory.TERM_REPLACEMENTS in effectiveCategories) {
                    for (tr in backupData.termReplacements) {
                        val canonicalNovelId = tr.novelId?.let { backupIdToCanonicalId[it] ?: it }
                        if (canonicalNovelId != null && novelDao.getNovelById(canonicalNovelId) == null) continue

                        val entity = tr.toEntity(canonicalNovelId)
                        termReplacementDao.insertIgnore(entity)
                    }
                }

                // 5. Custom Sources
                if (BackupCategory.CUSTOM_SOURCES in effectiveCategories) {
                    for (cs in backupData.customSources) {
                        customSourceDao.insertIgnore(cs.toEntity())
                    }
                }

                // 6. Reading Sessions
                if (BackupCategory.STATS in effectiveCategories) {
                    for (rs in backupData.readingSessions) {
                        val canonicalNovelId = backupIdToCanonicalId[rs.novelId] ?: rs.novelId
                        if (novelDao.getNovelById(canonicalNovelId) == null) continue

                        readingSessionDao.insert(rs.toEntity(canonicalNovelId))
                    }
                }
            }

            // Operations AFTER commit
            val warnings = mutableListOf<String>()

            // 7. EPUB Files & Chapter Rebuild
            if (BackupCategory.EPUB_FILES in effectiveCategories) {
                onProgress(BackupProgress("Rebuilding EPUBs", 80, 100))
                for (novel in backupData.novels.filter { it.isEpub }) {
                    val canonicalId = backupIdToCanonicalId[novel.id] ?: novel.id
                    val stagedEpub = File(stagingDir, "original_epubs/${novel.id}.epub")

                    if (stagedEpub.exists()) {
                        val targetEpub = storageResolver.getOriginalEpubFile(canonicalId)
                        targetEpub.parentFile?.mkdirs()
                        stagedEpub.copyTo(targetEpub, overwrite = true)

                        try {
                            val parsed = targetEpub.inputStream().use {
                                EpubParser.parseEpub(context, it, targetEpub.name)
                            }
                            for ((_, content) in parsed.chapterContents) {
                                val canonicalContent = content.copy(novelId = canonicalId)
                                chapterStorage.writeToEpub(canonicalContent)
                            }

                            // Match backup read state and positions by chapterIndex
                            val backupChaptersByIndex = backupData.chapters
                                .filter { it.novelId == novel.id }
                                .associateBy { it.chapterIndex }

                            for (ch in parsed.chapters) {
                                val backupCh = backupChaptersByIndex[ch.chapterIndex]
                                if (backupCh != null && (backupCh.isRead || backupCh.readParagraphIndex > 0 || backupCh.readCharOffset > 0)) {
                                    chapterDao.updateReadingProgress(
                                        novelId = canonicalId,
                                        id = ch.id,
                                        isRead = backupCh.isRead,
                                        paragraphIndex = backupCh.readParagraphIndex,
                                        charOffset = backupCh.readCharOffset
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            warnings.add("Failed to rebuild EPUB for ${novel.title}: ${e.message}")
                        }
                    } else {
                        warnings.add("Original EPUB file missing in backup for ${novel.title}")
                    }
                }
            }

            // 8. Covers
            if (BackupCategory.COVERS in effectiveCategories) {
                onProgress(BackupProgress("Restoring covers", 90, 100))
                for (novel in backupData.novels) {
                    val canonicalId = backupIdToCanonicalId[novel.id] ?: novel.id
                    val stagedCover = File(stagingDir, "covers/${novel.id}.jpg")

                    if (stagedCover.exists()) {
                        val shouldApplyCover = (mode == RestoreMode.REPLACE) || (canonicalId in newlyCreatedNovelIds)
                        if (shouldApplyCover) {
                            val targetCover = storageResolver.getCoverFile(canonicalId)
                            targetCover.parentFile?.mkdirs()
                            stagedCover.copyTo(targetCover, overwrite = true)

                            novelDao.updateDetails(
                                id = canonicalId,
                                title = novel.title,
                                author = novel.author,
                                description = novel.description,
                                coverUrl = targetCover.absolutePath
                            )
                        }
                    }
                }
            }

            // 9. Settings
            var settingsApplied = false
            if (BackupCategory.SETTINGS in effectiveCategories) {
                onProgress(BackupProgress("Applying settings", 95, 100))
                val stagedSettings = File(stagingDir, "settings.json")
                if (stagedSettings.exists()) {
                    try {
                        val loaded = json.decodeFromString<AppSettings>(stagedSettings.readText(Charsets.UTF_8))
                        val customUriStr = loaded.downloads.customDownloadLocationUri
                        val isPermissionValid = if (!customUriStr.isNullOrBlank()) {
                            try {
                                val uri = Uri.parse(customUriStr)
                                SettingsRepository.persistFolderPermission(context, uri)
                            } catch (_: Exception) {
                                false
                            }
                        } else {
                            false
                        }

                        val sanitized = if (!isPermissionValid) {
                            loaded.copy(
                                downloads = loaded.downloads.copy(
                                    customDownloadLocationUri = null,
                                    customDownloadLocationDisplayName = null
                                )
                            )
                        } else {
                            loaded
                        }

                        settingsRepository.updateReaderSettings(sanitized.reader)
                        settingsRepository.updateThemeSettings(sanitized.theme)
                        settingsRepository.updateDownloadSettings(sanitized.downloads)
                        settingsRepository.updateNotificationSettings(sanitized.notifications)
                        settingsRepository.updateLibrarySettings(sanitized.library)
                        settingsRepository.updateCultivationSettings(sanitized.cultivation)
                        settingsRepository.updateBackupSettings(sanitized.backup)
                        settingsRepository.updateProfileSettings(sanitized.profile)
                        settingsRepository.updateBottomBarSettings(sanitized.bottomBar)
                        settingsRepository.updateDiscoverSettings(sanitized.discover)
                        settingsRepository.flush()
                        settingsApplied = true
                    } catch (e: Exception) {
                        warnings.add("Failed to restore settings: ${e.message}")
                    }
                }
            }

            onProgress(BackupProgress("Complete", 100, 100))

            RestoreSummary(
                novelsAdded = novelsAdded,
                novelsUpdated = novelsUpdated,
                chaptersMarkedRead = chaptersMarkedRead,
                bookmarks = bookmarksAdded,
                settingsApplied = settingsApplied,
                warnings = warnings
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException.Corrupt("Restore failed: ${e.message}")
        } finally {
            try {
                stagingDir.deleteRecursively()
            } catch (_: Exception) {}
        }
    }
}
