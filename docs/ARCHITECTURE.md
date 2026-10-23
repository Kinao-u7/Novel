# Architecture Blueprint (authoritative)

This file is the source of truth for the rebuild of the Novel Reader app. Read it, `docs/ARCHITECTURE_RULES.md` and `docs/CARRYOVER.md` before every stage. If code and this file disagree, follow this file and tell the user.

## 1. Context

- Nobody uses the app yet: no data migration, no backward compatibility. A fresh install may wipe old data.
- Goal: a solid foundation (Room, whole-object settings, type-safe navigation with ViewModels, WorkManager downloads, permanent storage (downloads, EPUB) plus an untracked reader cache). Features stay the same.
- Keep as-is for now: scrapers and parsers (`WtrLabExtractor`, `WebChapterScraper`, `WebScrapeScripts`, `EpubParser`), the theme, `CommonUi`, and the screen layouts. Fixes for those are listed in `docs/CARRYOVER.md` (Stage 7).
- Environment rule: do not install JDKs or SDKs and do not run Gradle in the AI sandbox. Compilation is verified by GitHub Actions. Verify by reading the code.

## 2. Package structure

```
com.example/
  NovelApplication.kt          // creates AppContainer (no disk or DB work in onCreate)
  MainActivity.kt
  di/        AppContainer.kt, ViewModelFactory.kt          // manual DI
  data/
    local/db/        NovelDatabase (lazy holder), entity/, dao/, converters
    local/preferences/  SettingsRepository, SettingsModels   // JSON file, not DataStore
    local/storage/   StorageResolver, ChapterStorage, ChapterCache (untracked reader cache), staging cleanup
    remote/          source/, webscrape/                      // kept as-is
    epub/            EpubParser                               // kept as-is
    backup/          BackupManager
    repository/      NovelRepository, ChapterRepository, ReadingStatsRepository, DownloadRepository
  download/          DownloadQueueWorker, DownloadEngine
  ui/  navigation/, components/, theme/, screens/<screen>/ (Screen + ViewModel)
```

No separate domain-model layer. Room entities plus UI state classes.

## 3. Room data model

Never use `OnConflictStrategy.REPLACE` on tables with cascading foreign keys. Use insert-ignore plus update.

### novels
`id` TEXT PK · `title` · `author` · `description` · `cover_url` NULL · `source_id` · `source_name` · `source_url` NULL (normalized: trimmed, lowercase host, no trailing slash) · `slug` NULL · `locale` NULL · `raw_id` NULL · `status` · `genres_json` · `in_library` BOOL · `is_epub` BOOL · `total_chapters` INT · `rating` REAL **NULL** (null = unrated, never a fake default) · `last_read_chapter_id` NULL · `last_read_timestamp` INT (0 until the user really reads) · `created_at` INT.
Indexes: `(in_library)`, `(source_id, raw_id)`, `(source_url)`.
No stored downloaded counter: downloaded counts are derived from `chapters.storage_tier`.

### chapters
Composite PK `(novel_id, id)`. FK `novel_id → novels(id) ON DELETE CASCADE`.
`title` · `chapter_index` INT (1-based) · `release_date` TEXT · `word_count` INT · `is_read` BOOL · `read_paragraph_index` INT · `read_char_offset` INT · `storage_tier` TEXT (`NONE`, `DOWNLOADED`, `EPUB`) · `storage_location` TEXT (`INTERNAL` or a SAF root token) · `file_hash` TEXT (SHA-256 of the chapter id, used as the file name).
Indexes: `(novel_id, chapter_index)`, `(is_read, novel_id)`, `(novel_id, storage_tier)`.
Chapter refresh = `INSERT OR IGNORE` new rows, then `UPDATE` only `title`, `chapter_index`, `word_count`, `release_date`. Read state, position, tier, location, hash and bookmarks are never overwritten.

### bookmarks
`id` TEXT PK (UUID) · `novel_id` (FK cascade, indexed) · `chapter_id` · `chapter_title` · `paragraph_index` · `char_offset` · `excerpt` · `note` · `created_at`.

### download_queue
Composite PK `(novel_id, chapter_id)`, FK `novel_id` cascade. `status` (`QUEUED`, `DOWNLOADING`, `PAUSED`, `FAILED`, `COMPLETED`) · `queued_at` · `retry_count` · `error_message` NULL. Index `(status, queued_at)`.

### reading_sessions
**No foreign key** (history and streaks survive novel deletion). `id` INT autoincrement · `novel_id` · `novel_title` (snapshot) · `chapter_id` · `start_time` INT · `duration_ms` INT (foreground active time only) · `words_read` INT · `date_key` TEXT `YYYY-MM-DD`. Index `(date_key)`.

### term_replacements
`id` TEXT PK · `novel_id` NULL (null = global) · `pattern` · `replacement` · `is_regex` · `match_case` · `is_enabled`.

### notifications
`id` TEXT PK · `title` · `message` · `type` (`info`, `download`, `update`) · `timestamp` · `is_read`.

### custom_sources
`id` TEXT PK · `name` · `url` · `language` · `description` · `is_enabled`.

### requested_sources
`id` TEXT PK · `site_name` · `url` · `requested_at`.

### Room configuration
`exportSchema = true` with `room.schemaLocation`. `fallbackToDestructiveMigration()` is development only: remove it and add real migrations before any release. The database is built lazily; nothing opens it in `Application.onCreate`.

Browse novels: a novel opened from Browse is persisted with `in_library = 0`. On startup, in the background, purge `in_library = 0` rows that have no read state, bookmarks or downloads.

## 4. File layout (under `filesDir`, cache under `cacheDir`)

```
filesDir/
  covers/{novelId}.jpg                  permanent
  epubs/{novelId}/{sha256}.json         permanent EPUB chapter text, never deleted by cache or download cleanup
  original_epubs/{novelId}.epub         original files (needed for backup restore)
  downloads/{novelId}/{sha256}.json     permanent downloads when storage_location = INTERNAL
  staging/                              temporary restore extraction

cacheDir/ (Android may clear it)
  chapters/{novelId}/{sha256}.json      transient untracked reader cache (size cap)
```

- Chapter file names are SHA-256 hashes of the chapter id (ids can be URLs).
- Stage 4a status: StorageResolver, ChapterStorage and ChapterRepository operate on INTERNAL storage only (`filesDir`). Custom SAF folder writes and reads are deferred to Stage 4b.
- A custom SAF folder is a real location, not a mirror: downloads go to `NovelReader/downloads/{novelId}/{sha256}.json` under the SAF tree, and `chapters.storage_location` records where each file lives. Changing the folder never orphans old files. If permission is revoked, new writes fall back to `INTERNAL`. Cache the SAF directory handle, never call `findFile` per chapter.

## 5. Settings (already implemented in Stage 1a)

`SettingsRepository` stores each settings group as one serialized JSON object (`ignoreUnknownKeys`, `coerceInputValues`, `encodeDefaults`), written atomically, with a `.corrupted` backup. Source of truth for fields is `SettingsModels.kt`. Decisions: one field per concept (`textAlign` replaces `isJustified`, `horizontalMarginDp` replaces `marginType`, `wifiOnly` replaces `allowMobileData`); defaults are Sepia reader theme, accent `#DEB887`, serif font, `wifiOnly = true`, concurrency 2, throttle delay 2.0s with jitter, `cacheSizeCapMb = 250`, default toolbar of at most 5 actions. `AppSettings` holds `libraryViewMode`, `librarySort`, `lastSeenChangelogVersion`, `setupStoragePromptShown`. Reset preserves `customDownloadLocationUri` and its display name.

## 6. Layers

UI → ViewModels → Repositories/engines → Room, `StorageResolver`/`ChapterStorage`, `SettingsRepository`, sources/parsers. ViewModels and repositories hold no `Context` or UI classes. All I/O runs on `Dispatchers.IO`.

## 7. Navigation

Navigation Compose 2.8+ with `@Serializable` routes carrying IDs only: `Library`, `Browse`, `Downloads`, `Stats`, `NovelDetail(novelId)`, `Reader(novelId, chapterIndex)` (index, because chapter ids are URLs), `Settings(subPage)`, `WebBrowser(initialUrl)`. Each screen has a ViewModel using `SavedStateHandle`. Never pass `Novel` objects between screens.

## 8. Storage tiers, cache and downloads

- `storage_tier` (`NONE`, `DOWNLOADED`, `EPUB`) says whether permanent content exists. Reading never marks a chapter downloaded.
- Reader cache is untracked: chapters read online are written (temp file, then rename) to `cacheDir/chapters/{novelId}/{sha256}.json`. Room does not record them. Cap: `cacheSizeCapMb`. After a write, if the folder exceeds the cap, delete the least recently modified files until under it; a cache read refreshes the file's modified time. One mutex serializes trimming. A missing or unreadable cache file is a miss: delete it and refetch.
- Reader order: `EPUB`, then `DOWNLOADED`, then cache, then network, with single-flight per chapter. A reader fetch and a download of the same chapter share one in-flight fetch (single-flight).
- Downloads: written to the download location; tier set to `DOWNLOADED` with a compare-and-set (expected previous `NONE` or `DOWNLOADED`). Disk first: if a valid file already exists at the target, only set the tier. If the chapter is in the cache, copy it to the download location (no network) and delete the cache copy.
- Delete downloads resets `DOWNLOADED` rows to `NONE` and deletes files, never `EPUB`; the cache is untouched. Clear cache deletes the cache folder only. Removing a novel from the app deletes its cache folder (`cacheDir/chapters/{novelId}`).
- Startup cleans only `staging/` (files older than 10 minutes). No cache reconciliation. `CacheReconciler` is replaced by a minimal staging cleanup.
- Downloads: one WorkManager `CoroutineWorker` (foreground, `dataSync`) drains `download_queue` ordered by `queued_at`. `NetworkType.UNMETERED` when `wifiOnly`. Concurrency from settings (default 2) with a WebView pool of the same size. Throttle with delay and jitter. Handle timeouts, HTTP 429 and network errors, with retry.

## 9. Backup format

A zip: `manifest.json` (schema version, app version, timestamp, counts), `data.json` (Room dump), `settings.json`, `covers/`, `original_epubs/`.
Restore: preview from the manifest, then user chooses **Replace all** or **Merge** (newer read state wins). Extract files to `staging/`, import in one `database.withTransaction`, move files only after the commit succeeds. Reset `DOWNLOADED` tier to `NONE` (content is not in the backup), re-parse EPUBs from `original_epubs/`, drop `customDownloadLocationUri` if its permission is no longer granted, validate zip entry paths (zip-slip).

## 10. Reading stats

Sessions count foreground reading only, pause when the app is backgrounded or after 3 minutes idle, ignore position jumps (TOC, slider seek) when counting words, and clamp words per minute to 1000. Words come from paragraph and character progress deltas, not from chapter load.

## 11. Libraries

Room (runtime, ktx, compiler via KSP), Navigation Compose, lifecycle ViewModel Compose, kotlinx.serialization JSON, WorkManager, and for tests JUnit, Robolectric, AndroidX test core and coroutines-test. Set `testOptions.unitTests.isIncludeAndroidResources = true`. Never add JDK or SDK paths to `gradle.properties` or `local.properties`. CI uses Java 21. Robolectric is pinned to sdk=34 via app/src/test/resources/robolectric.properties. Do not change either.

## 12. Stages

Every stage ends with: the app compiling and launching, tests passing, and a summary (files changed, what was NOT changed, temporary adapters and the stage that deletes each, manual test checklist). Stop after each stage.

1. **1a Settings** (done): `SettingsRepository`, serializable settings models.
2. **1b Room database.** Split in two:
   - **1b-1:** entities, DAOs, database, converters, Robolectric tests. Not wired into the app.
   - **1b-2:** `NovelRepository` uses Room (library, chapter metadata, read state, bookmarks, term replacements, custom sources, notifications). Delete `LibraryManager`. Thin temporary adapters for old code that still needs it.
3. **2a** `AppContainer`, `NovelApplication` and the type-safe `NavHost`.
4. **4** Storage engine: `StorageResolver`, `ChapterStorage`, `ChapterCache` (untracked reader cache), cache-folder tests. Delete `StorageManager`.
5. **2b** ViewModels: Library, Detail, Downloads, Settings (and the remove-from-library confirm with a delete-files option).
6. **3** Reader and real stats: `ReaderViewModel`, every `ReaderSettings` field visibly wired, the Reader checklist from `docs/CARRYOVER.md`.
7. **5** Download engine (WorkManager, notification permission, foreground service type, WebView pool). Delete `DownloadManager`.
8. **6** Backup and restore (format above).
9. **7a to 7h** Per-screen carry-over fixes from `docs/CARRYOVER.md`: Library, Detail and options, Browse, WebBrowser, Downloads, Settings/History/Stats/Profile/What's New, Theme and design system, EPUB parser and scraper scripts. Also delete `ConfirmNovelScreen.kt` and the first-run dummy seeding.

## 13. Amendments (all approved)

- **a.** The app compiles and runs after every stage; replaced managers are bridged by documented temporary adapters, deleted in the stage that replaces their last consumer.
- **b.** Browse novels persist with `in_library = 0` and are purged on startup when unused (section 3).
- **c.** `POST_NOTIFICATIONS` request and the `dataSync` foreground service manifest entries belong to Stage 5.
- **d.** In Stage 3 every `ReaderSettings` field must visibly work in the Reader and be editable in the reader sheet and in Settings.
- **e.** Stats safeguards (section 10).
- **f.** Json options as in section 5. Room `exportSchema = true`.
- **g.** Backup and security rules (section 9).
- **h.** `Application.onCreate` does no disk or database work; the UI renders immediately and collects Flows.
- **i.** Stage 7 follows `docs/CARRYOVER.md`.
- **j.** Robolectric test configuration (section 11).
- **k.** The old UI has been moved to `legacy/ui` (reference only, not compiled); new UI screens and ViewModels are rewritten from scratch in subsequent stages. Tests are the verification until then.
- **l.** Indexes: novels UNIQUE (source_id, raw_id) (NULL allowed; a blank raw_id is
   normalized to null); chapters (is_read, novel_id). term_replacements.sort_order INT
   default 0; queries ORDER BY (novel_id IS NULL), sort_order, id; the reader keeps the
   DAO order.
- **m.** A DOWNLOADED row whose file is missing is reset only together with a successful
   refetch, written back as DOWNLOADED in its original location. A read IOException on a
   DOWNLOADED file throws ChapterReadException and never changes the tier. A corrupt cache
   file is deleted and refetched. A DOWNLOADED file is deleted only when its content is
   unparseable. EPUB rows are never reset (EpubFileMissing).
- **n.** Queue: only QUEUED/DOWNLOADING/PAUSED block enqueueing; COMPLETED rows are cleared only
   when no QUEUED, DOWNLOADING or PAUSED rows remain and when downloads are deleted; worker status
   writes are guarded by the expected status; the time-limit follow-up uses APPEND_OR_REPLACE.
- **o.** SAF: directory creation is serialized; writes use a temp file (octet-stream MIME)
   then rename, falling back to "wt"; size is computed on demand and cached.
- **p.** upsertSafe updates source-owned fields only and returns the canonical id; callers use
   it. Notification types come from the NotificationTypes constants (data.model).
- **q.** An unknown source throws SourceUnavailable (no active-source fallback).
   getChapterList always rethrows CancellationException and other fetch errors.
- **r.** novels.rating NULL = unrated; the domain model uses Float?; the UI hides null (and 0f).
- **s.** Current package layout differs from section 2: data/download/ (not download/),
   data/source/ (not data/remote/source/), BackupManager and SettingsRepository live
   under data/repository/. Reconcile in Stage 7h or update section 2.
- **t.** The reader cache is untracked (no Room column, no tier), and Stage 4 is amended
   accordingly (LRU tests become cache-folder tests).

## 14. Rules

All rules in `docs/ARCHITECTURE_RULES.md` apply to every stage.
