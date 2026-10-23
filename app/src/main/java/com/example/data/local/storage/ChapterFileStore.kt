package com.example.data.local.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.data.model.ChapterContent
import com.example.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thrown when a storage location (such as an unmounted SD card or revoked SAF folder)
 * cannot be reached, preserving the chapter's storage tier without resetting it to NONE.
 */
class StorageUnavailableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Interface abstracting all chapter file I/O operations (internal disk vs SAF).
 * Enables unit tests to use fake implementations without requiring Android SAF APIs.
 */
interface ChapterFileStore {
    suspend fun read(novelId: String, chapterId: String, tier: String, location: String): ChapterContent?
    suspend fun writeDownload(content: ChapterContent, location: String): Boolean
    suspend fun writeEpub(content: ChapterContent): File?
    suspend fun deleteChapter(novelId: String, chapterId: String, tier: String, location: String): Boolean
    suspend fun deleteNovelDownloads(novelId: String, locations: Set<String>): Boolean
    suspend fun deleteAllDownloads(locations: Set<String>): Boolean
    suspend fun removeNovelCompletely(novelId: String, isEpub: Boolean, locations: Set<String>): Unit
    suspend fun getDownloadsSizeBytes(): Long
    suspend fun getEpubsSizeBytes(): Long
    fun isLocationReachable(location: String): Boolean
    fun isLocationWritable(location: String): Boolean
}

/**
 * Standard implementation handling internal storage files under filesDir.
 */
class InternalChapterFileStore(
    val resolver: StorageResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ChapterFileStore {

    override suspend fun read(novelId: String, chapterId: String, tier: String, location: String): ChapterContent? = withContext(ioDispatcher) {
        val file = when (tier) {
            "DOWNLOADED" -> resolver.getDownloadChapterFile(novelId, chapterId)
            "EPUB" -> resolver.getEpubChapterFile(novelId, chapterId)
            else -> return@withContext null
        }
        if (!file.exists() || !file.isFile || file.length() == 0L) {
            return@withContext null
        }
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw ChapterReadException(novelId, chapterId, e)
        }
        try {
            deserialize(text, isDownloaded = (tier == "DOWNLOADED" || tier == "EPUB"))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            file.delete()
            null
        }
    }

    override suspend fun writeDownload(content: ChapterContent, location: String): Boolean = withContext(ioDispatcher) {
        val file = resolver.getDownloadChapterFile(content.novelId, content.chapterId)
        atomicWrite(file, serialize(content.copy(isDownloaded = true)))
        true
    }

    override suspend fun writeEpub(content: ChapterContent): File? = withContext(ioDispatcher) {
        val file = resolver.getEpubChapterFile(content.novelId, content.chapterId)
        atomicWrite(file, serialize(content.copy(isDownloaded = true)))
        file
    }

    override suspend fun deleteChapter(novelId: String, chapterId: String, tier: String, location: String): Boolean = withContext(ioDispatcher) {
        val file = when (tier) {
            "DOWNLOADED" -> resolver.getDownloadChapterFile(novelId, chapterId)
            "EPUB" -> resolver.getEpubChapterFile(novelId, chapterId)
            else -> return@withContext false
        }
        if (file.exists()) file.delete() else false
    }

    override suspend fun deleteNovelDownloads(novelId: String, locations: Set<String>): Boolean = withContext(ioDispatcher) {
        val dir = resolver.getNovelDownloadsDir(novelId)
        if (dir.exists()) dir.deleteRecursively() else false
    }

    override suspend fun deleteAllDownloads(locations: Set<String>): Boolean = withContext(ioDispatcher) {
        val dir = resolver.getDownloadsDir()
        if (dir.exists()) dir.deleteRecursively() else false
    }

    override suspend fun removeNovelCompletely(novelId: String, isEpub: Boolean, locations: Set<String>): Unit {
        withContext(ioDispatcher) {
            val coverFile = resolver.getCoverFile(novelId)
            if (coverFile.exists()) coverFile.delete()

            if (isEpub) {
                val originalEpub = resolver.getOriginalEpubFile(novelId)
                if (originalEpub.exists()) originalEpub.delete()
                val epubDir = resolver.getNovelEpubDir(novelId)
                if (epubDir.exists()) epubDir.deleteRecursively()
            } else {
                deleteNovelDownloads(novelId, locations)
            }
        }
    }

    override suspend fun getDownloadsSizeBytes(): Long = withContext(ioDispatcher) {
        getFolderSize(resolver.getDownloadsDir())
    }

    override suspend fun getEpubsSizeBytes(): Long = withContext(ioDispatcher) {
        getFolderSize(resolver.getEpubsDir()) + getFolderSize(resolver.getOriginalEpubsDir())
    }

    override fun isLocationReachable(location: String): Boolean = location == "INTERNAL"

    override fun isLocationWritable(location: String): Boolean = location == "INTERNAL"

    private fun atomicWrite(targetFile: File, contentString: String) {
        val parent = targetFile.parentFile ?: throw IOException("Missing parent dir for $targetFile")
        if (!parent.exists()) {
            parent.mkdirs()
        }
        val tempFile = File(
            parent,
            "${targetFile.name}.${System.currentTimeMillis()}.${UUID.randomUUID().toString().take(6)}.tmp"
        )
        try {
            tempFile.writeText(contentString, Charsets.UTF_8)
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun getFolderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.listFiles()?.forEach { file ->
            total += if (file.isDirectory) getFolderSize(file) else file.length()
        }
        return total
    }

    companion object {
        fun serialize(content: ChapterContent): String {
            val json = JSONObject().apply {
                put("chapterId", content.chapterId)
                put("novelId", content.novelId)
                put("title", content.title)
                put("paragraphs", JSONArray(content.paragraphs))
            }
            return json.toString()
        }

        fun deserialize(text: String, isDownloaded: Boolean): ChapterContent {
            val json = JSONObject(text)
            val pArr = json.getJSONArray("paragraphs")
            val paragraphs = ArrayList<String>(pArr.length())
            for (i in 0 until pArr.length()) {
                paragraphs.add(pArr.getString(i))
            }
            return ChapterContent(
                chapterId = json.getString("chapterId"),
                novelId = json.getString("novelId"),
                title = json.getString("title"),
                paragraphs = paragraphs,
                isDownloaded = isDownloaded
            )
        }
    }
}

/**
 * SAF implementation handling chapter files stored under a custom SAF tree directory:
 * NovelReader/downloads/{novelId}/{sha256}.json
 *
 * Implements cached handles for tree roots, novel directories, and file listings (listFiles loaded ONCE).
 */
class SafChapterFileStore(
    private val context: Context,
    private val resolver: StorageResolver,
    private val settingsRepository: SettingsRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ChapterFileStore {
    // Tree URI string -> Root DocumentFile
    private val treeRoots = ConcurrentHashMap<String, DocumentFile>()
    // "$treeUri|$novelId" -> Novel Folder DocumentFile
    private val novelFolders = ConcurrentHashMap<String, DocumentFile>()
    // "$treeUri|$novelId" -> Map of fileName -> DocumentFile
    private val novelFileListings = ConcurrentHashMap<String, ConcurrentHashMap<String, DocumentFile>>()

    override fun isLocationReachable(location: String): Boolean {
        if (!location.startsWith("SAF:")) return false
        val treeUriStr = location.removePrefix("SAF:")
        return try {
            val uri = Uri.parse(treeUriStr)
            val root = getRootDoc(treeUriStr, uri) ?: return false
            root.exists() && root.canRead()
        } catch (e: Exception) {
            false
        }
    }

    override fun isLocationWritable(location: String): Boolean {
        if (!location.startsWith("SAF:")) return false
        val treeUriStr = location.removePrefix("SAF:")
        return try {
            val uri = Uri.parse(treeUriStr)
            val hasPerm = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isWritePermission
            }
            if (!hasPerm) return false
            val root = getRootDoc(treeUriStr, uri) ?: return false
            root.exists() && root.canWrite()
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun read(novelId: String, chapterId: String, tier: String, location: String): ChapterContent? = withContext(ioDispatcher) {
        if (!isLocationReachable(location)) {
            throw StorageUnavailableException("SAF folder is unavailable: $location")
        }
        val treeUriStr = location.removePrefix("SAF:")
        val novelDir = getNovelFolder(treeUriStr, novelId, createIfMissing = false)
            ?: return@withContext null // Folder reachable, novel folder not present -> file missing

        val listing = getNovelListing(treeUriStr, novelId, novelDir)
        val fileName = resolver.sha256FileName(chapterId)
        val docFile = listing[fileName] ?: return@withContext null // File missing in reachable folder

        if (!docFile.exists() || docFile.length() == 0L || docFile.name.orEmpty().endsWith(".tmp")) {
            return@withContext null
        }

        val text = try {
            context.contentResolver.openInputStream(docFile.uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: return@withContext null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw ChapterReadException(novelId, chapterId, e)
        }

        try {
            InternalChapterFileStore.deserialize(text, isDownloaded = true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Corrupt file in reachable folder: remove from listing and delete
            try { docFile.delete() } catch (_: Exception) {}
            listing.remove(fileName)
            null
        }
    }

    override suspend fun writeCache(content: ChapterContent): File? = null

    override suspend fun writeDownload(content: ChapterContent, location: String): Boolean = withContext(ioDispatcher) {
        if (!isLocationWritable(location)) {
            throw StorageUnavailableException("SAF location is unwritable: $location")
        }
        val treeUriStr = location.removePrefix("SAF:")
        val novelDir = getNovelFolder(treeUriStr, content.novelId, createIfMissing = true)
            ?: throw StorageUnavailableException("Failed to create novel folder in SAF: $location")

        val listing = getNovelListing(treeUriStr, content.novelId, novelDir)
        val fileName = resolver.sha256FileName(content.chapterId)
        val tempFileName = "$fileName.tmp"

        val jsonString = InternalChapterFileStore.serialize(content.copy(isDownloaded = true))
        val bytes = jsonString.toByteArray(Charsets.UTF_8)

        // Delete any leftover temp file first
        listing.remove(tempFileName)?.delete()
        novelDir.findFile(tempFileName)?.delete()

        val tempDoc = novelDir.createFile("application/octet-stream", tempFileName)
            ?: throw IOException("Failed to create SAF temp file: $tempFileName")

        try {
            context.contentResolver.openOutputStream(tempDoc.uri, "wt")?.use { os ->
                os.write(bytes)
                os.flush()
            } ?: throw IOException("Failed to open output stream for SAF temp file: $tempFileName")

            // Delete old target if present
            val oldTarget = listing.remove(fileName) ?: novelDir.findFile(fileName)
            oldTarget?.delete()

            // Attempt rename
            val renamed = tempDoc.renameTo(fileName)
            if (renamed) {
                listing[fileName] = tempDoc
            } else {
                // If renameTo fails, write directly to target with "wt" and delete temp file
                val directDoc = novelDir.createFile("application/json", fileName)
                    ?: throw IOException("Failed to create direct SAF file: $fileName")
                context.contentResolver.openOutputStream(directDoc.uri, "wt")?.use { os ->
                    os.write(bytes)
                    os.flush()
                } ?: throw IOException("Failed to write directly to SAF target file: $fileName")
                listing[fileName] = directDoc
                tempDoc.delete()
            }
            true
        } catch (e: Exception) {
            tempDoc.delete()
            if (e is CancellationException) throw e
            throw e
        }
    }

    override suspend fun writeEpub(content: ChapterContent): File? = null

    override suspend fun deleteChapter(novelId: String, chapterId: String, tier: String, location: String): Boolean = withContext(ioDispatcher) {
        if (!isLocationReachable(location)) return@withContext false
        val treeUriStr = location.removePrefix("SAF:")
        val novelDir = getNovelFolder(treeUriStr, novelId, createIfMissing = false) ?: return@withContext false
        val listing = getNovelListing(treeUriStr, novelId, novelDir)
        val fileName = resolver.sha256FileName(chapterId)
        val docFile = listing.remove(fileName) ?: return@withContext false
        docFile.delete()
    }

    suspend fun deleteChapter(novelId: String, chapterId: String, location: String): Boolean {
        return deleteChapter(novelId, chapterId, "DOWNLOADED", location)
    }

    override suspend fun deleteNovelDownloads(novelId: String, locations: Set<String>): Boolean = withContext(ioDispatcher) {
        var anyDeleted = false
        for (loc in locations) {
            if (loc.startsWith("SAF:")) {
                if (deleteNovelDownloads(novelId, loc)) {
                    anyDeleted = true
                }
            }
        }
        anyDeleted
    }

    suspend fun deleteNovelDownloads(novelId: String, location: String): Boolean = withContext(ioDispatcher) {
        if (!isLocationReachable(location)) return@withContext false
        val treeUriStr = location.removePrefix("SAF:")
        val cacheKey = "$treeUriStr|$novelId"
        val novelDir = getNovelFolder(treeUriStr, novelId, createIfMissing = false)
        novelFolders.remove(cacheKey)
        novelFileListings.remove(cacheKey)
        if (novelDir != null && novelDir.exists()) {
            novelDir.delete()
        } else {
            false
        }
    }

    override suspend fun deleteNovelCache(novelId: String): Boolean = false

    override suspend fun deleteAllDownloads(locations: Set<String>): Boolean = withContext(ioDispatcher) {
        val treeUris = locations.filter { it.startsWith("SAF:") }.map { it.removePrefix("SAF:") }.toSet()
        for (treeUriStr in treeUris) {
            try {
                val uri = Uri.parse(treeUriStr)
                val root = getRootDoc(treeUriStr, uri) ?: continue
                val novelReaderDir = root.findFile("NovelReader") ?: continue
                val downloadsDir = novelReaderDir.findFile("downloads") ?: continue
                downloadsDir.delete()
            } catch (_: Exception) {}
        }
        novelFolders.clear()
        novelFileListings.clear()
        true
    }

    suspend fun deleteAllDownloadsSaf(treeUris: Set<String>): Boolean {
        return deleteAllDownloads(treeUris.map { "SAF:$it" }.toSet())
    }

    override suspend fun removeNovelCompletely(novelId: String, isEpub: Boolean, locations: Set<String>): Unit {
        withContext(ioDispatcher) {
            deleteNovelDownloads(novelId, locations)
        }
    }

    private var cachedSafDownloadsSize: Long = 0L
    private var lastSizeCalculationTimestamp: Long = 0L
    private val isCalculatingSize = AtomicBoolean(false)
    private val dirCreationLock = Any()

    val isCalculatingDownloadsSize: Boolean get() = isCalculatingSize.get()

    override suspend fun getDownloadsSizeBytes(): Long = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        if (lastSizeCalculationTimestamp > 0L && (now - lastSizeCalculationTimestamp < 60_000L)) {
            return@withContext cachedSafDownloadsSize
        }
        isCalculatingSize.set(true)
        try {
            var total = 0L
            val configuredUri = settingsRepository?.downloadSettings?.value?.customDownloadLocationUri
                ?: try {
                    SettingsRepository.getInstance(context).downloadSettings.value.customDownloadLocationUri
                } catch (_: Exception) {
                    null
                }
            val urisToScan = mutableSetOf<String>()
            if (!configuredUri.isNullOrBlank()) {
                urisToScan.add(configuredUri)
            }
            urisToScan.addAll(treeRoots.keys)

            for (treeUriStr in urisToScan) {
                try {
                    val uri = Uri.parse(treeUriStr)
                    val root = getRootDoc(treeUriStr, uri) ?: continue
                    val novelReaderDir = root.findFile("NovelReader") ?: continue
                    val downloadsDir = novelReaderDir.findFile("downloads") ?: continue
                    for (novelDir in downloadsDir.listFiles()) {
                        if (novelDir.isDirectory) {
                            for (file in novelDir.listFiles()) {
                                if (file.isFile && !file.name.orEmpty().endsWith(".tmp")) {
                                    total += file.length()
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            cachedSafDownloadsSize = total
            lastSizeCalculationTimestamp = System.currentTimeMillis()
            total
        } finally {
            isCalculatingSize.set(false)
        }
    }

    override suspend fun getEpubsSizeBytes(): Long = 0L

    private fun getRootDoc(treeUriStr: String, uri: Uri): DocumentFile? {
        return treeRoots.getOrPut(treeUriStr) {
            DocumentFile.fromTreeUri(context, uri) ?: return null
        }
    }

    private fun getNovelFolder(treeUriStr: String, novelId: String, createIfMissing: Boolean): DocumentFile? = synchronized(dirCreationLock) {
        val cacheKey = "$treeUriStr|$novelId"
        novelFolders[cacheKey]?.let {
            if (it.exists()) return it else novelFolders.remove(cacheKey)
        }

        val uri = Uri.parse(treeUriStr)
        val root = getRootDoc(treeUriStr, uri) ?: return null
        if (!root.exists() || !root.canRead()) return null

        var novelReaderDir = root.findFile("NovelReader")
        if (novelReaderDir == null || !novelReaderDir.isDirectory) {
            if (!createIfMissing) return null
            novelReaderDir = root.createDirectory("NovelReader") ?: return null
        }

        var downloadsDir = novelReaderDir.findFile("downloads")
        if (downloadsDir == null || !downloadsDir.isDirectory) {
            if (!createIfMissing) return null
            downloadsDir = novelReaderDir.createDirectory("downloads") ?: return null
        }

        var novelDir = downloadsDir.findFile(novelId)
        if (novelDir == null || !novelDir.isDirectory) {
            if (!createIfMissing) return null
            novelDir = downloadsDir.createDirectory(novelId) ?: return null
        }

        novelFolders[cacheKey] = novelDir
        novelDir
    }

    private fun getNovelListing(treeUriStr: String, novelId: String, novelDir: DocumentFile): ConcurrentHashMap<String, DocumentFile> {
        val cacheKey = "$treeUriStr|$novelId"
        return novelFileListings.getOrPut(cacheKey) {
            val map = ConcurrentHashMap<String, DocumentFile>()
            val files = novelDir.listFiles()
            for (file in files) {
                val name = file.name
                if (name != null) {
                    map[name] = file
                }
            }
            map
        }
    }
}

/**
 * Composite store coordinating internal files and SAF locations.
 */
class DefaultChapterFileStore(
    val internalStore: InternalChapterFileStore,
    val safStore: SafChapterFileStore
) : ChapterFileStore {

    override suspend fun read(novelId: String, chapterId: String, tier: String, location: String): ChapterContent? {
        return if (location.startsWith("SAF:")) {
            safStore.read(novelId, chapterId, tier, location)
        } else {
            internalStore.read(novelId, chapterId, tier, location)
        }
    }

    override suspend fun writeDownload(content: ChapterContent, location: String): Boolean {
        return if (location.startsWith("SAF:")) {
            safStore.writeDownload(content, location)
        } else {
            internalStore.writeDownload(content, location)
        }
    }

    override suspend fun writeEpub(content: ChapterContent): File? {
        return internalStore.writeEpub(content)
    }

    override suspend fun deleteChapter(novelId: String, chapterId: String, tier: String, location: String): Boolean {
        return if (location.startsWith("SAF:")) {
            safStore.deleteChapter(novelId, chapterId, tier, location)
        } else {
            internalStore.deleteChapter(novelId, chapterId, tier, location)
        }
    }

    override suspend fun deleteNovelDownloads(novelId: String, locations: Set<String>): Boolean {
        val internalDeleted = internalStore.deleteNovelDownloads(novelId, locations)
        val safDeleted = safStore.deleteNovelDownloads(novelId, locations)
        return internalDeleted || safDeleted
    }

    override suspend fun deleteAllDownloads(locations: Set<String>): Boolean {
        val anyDeleted = internalStore.deleteAllDownloads(locations)
        val safUris = locations.filter { it.startsWith("SAF:") }.toSet()
        if (safUris.isNotEmpty()) {
            safStore.deleteAllDownloads(safUris)
        }
        return anyDeleted
    }

    override suspend fun removeNovelCompletely(novelId: String, isEpub: Boolean, locations: Set<String>): Unit {
        internalStore.removeNovelCompletely(novelId, isEpub, locations)
        safStore.removeNovelCompletely(novelId, isEpub, locations)
    }

    override suspend fun getDownloadsSizeBytes(): Long =
        internalStore.getDownloadsSizeBytes() + safStore.getDownloadsSizeBytes()

    override suspend fun getEpubsSizeBytes(): Long = internalStore.getEpubsSizeBytes()

    override fun isLocationReachable(location: String): Boolean {
        return if (location.startsWith("SAF:")) {
            safStore.isLocationReachable(location)
        } else {
            internalStore.isLocationReachable(location)
        }
    }

    override fun isLocationWritable(location: String): Boolean {
        return if (location.startsWith("SAF:")) {
            safStore.isLocationWritable(location)
        } else {
            internalStore.isLocationWritable(location)
        }
    }
}
