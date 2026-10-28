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

## 5. Settings

`SettingsRepository` manages app configuration stored as a single serialized JSON file (`app_settings.json` under `filesDir`), with crash resilience: atomic writes via a temporary file (`.tmp`) and rename, a fallback `.corrupted` backup, debounced disk writes (300ms) to prevent I/O thrashing, and execution strictly off the main thread (`Dispatchers.IO`). Serializer configuration uses `Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; prettyPrint = true }`.

### Architecture: Settings vs. Room
Settings stay in one JSON file (not Room, not DataStore). Contrast with Room relational tables: settings are whole-object, crash-resilient, with zero-migration schema evolution (adding fields with defaults requires no database schema migrations, and missing keys in older JSON deserialize to their defaults). Non-UI logic (repositories, download engine, workers, storage resolver) must always read the source of truth directly from `settingsRepository.settings.value.<group>`, never through the derived StateFlows, which are for UI consumers and can lag behind state updates (Rule 26, Amendment x).

### Theme & Typography Mapping
- **App Themes:** `AppThemeMode` values map directly to the design system:
  - `DARK` = Ink (default)
  - `AMOLED` = Black
  - `SEPIA` = Paper
  - `LIGHT` = White
  - `SYSTEM` = Match system theme
- **Accent Presets:**
  - Jade: `#7cf2c3` (dark) / `#1f7a62` (light)
  - Gold: `#f3cb83` (dark) / `#96691a` (light)
  - Rose: `#ff9ec4` (dark) / `#b8416f` (light)
  - Azure: `#8fd0ff` (dark) / `#1f6fa8` (light)
- **Typography:** `FontType` (`SERIF` default, `SANS`, `MONOSPACE`, `ROUNDED`).

### Settings Groups & Defaults (from `NovelModels.kt`)
`AppSettings` is composed of 8 modular settings groups (plus `bottomBar` planned per Amendment y):

1. **reader (`ReaderSettings`):**
   - `theme: ReaderTheme = ReaderTheme.SEPIA`
   - `fontSizeSp: Int = 18`
   - `fontWeight: ReaderFontWeight = ReaderFontWeight.NORMAL`
   - `lineSpacingMultiplier: Float = 1.6f`
   - `paragraphSpacingDp: Int = 12`
   - `letterSpacingSp: Float = 0f`
   - `textAlign: ReaderTextAlign = ReaderTextAlign.JUSTIFIED`
   - `horizontalMarginDp: Int = 20`
   - `verticalMarginDp: Int = 20`
   - `contentWidthPercent: Int = 100`
   - `paragraphIndentation: Boolean = false`
   - `fontType: FontType = FontType.SERIF`
   - `readingMode: ReadingMode = ReadingMode.CONTINUOUS`
   - `autoScrollSpeed: Int = 0` (0 = off, 1–5 = speed)
   - `keepScreenOn: Boolean = true`
   - `toolbarPosition: ToolbarPosition = ToolbarPosition.BOTTOM`
   - `showStatusBar: Boolean = true`
   - `enabledToolbarActions: List<String> = listOf("prev_chapter", "next_chapter", "toc", "tts", "bookmark", "display")`
   - `globalTermReplacementsEnabled: Boolean = true`
   - `ttsSpeechRate: Float = 1.0f`
   - `ttsPitch: Float = 1.0f`
   - `ttsVoice: String? = null`
   - `saveReadingPosition: Boolean = true`
   - `volumeKeysScroll: Boolean = false`

2. **theme (`ThemeSettings`):**
   - `themeMode: AppThemeMode = AppThemeMode.DARK` (Ink)
   - `accentColorHex: String = "#DEB887"`
   - `useDynamicColor: Boolean = false`

3. **downloads (`DownloadSettings`):**
   - `wifiOnly: Boolean = true`
   - `allowMobileData: Boolean = false`
   - `simultaneousDownloads: Int = 3` (cross-source concurrency, range 1–5)
   - `autoDownloadAhead: Int = 3` (coerced on load/update to nearest of 0, 1, 3, 5, 10)
   - `customDownloadLocationUri: String? = null`
   - `customDownloadLocationDisplayName: String? = null`
   - `throttlingEnabled: Boolean = true`
   - `throttleBatchSize: Int = 5`
   - `throttleDelaySeconds: Float = 2.0f`
   - `throttleRandomJitter: Boolean = true`
   - `cacheSizeCapMb: Int = 250`
   - `deleteAfterReading: Boolean = false`

4. **notifications (`NotificationSettings`):**
   - `notifyOnDownload: Boolean = true`
   - `notifyOnNovelUpdate: Boolean = true`

5. **library (`LibrarySettings`):**
   - `updateOnLaunch: Boolean = true`
   - `showUnreadBadges: Boolean = true`
   - `defaultShelf: DefaultShelf = DefaultShelf.ALL` (`ALL`, `READING`, `SAVED`)
   - `viewMode: LibraryViewMode = LibraryViewMode.GRID` (`GRID`, `LIST`)
   - `sort: LibrarySort = LibrarySort.RECENTLY_READ` (`RECENTLY_READ`, `TITLE`, `PROGRESS`, `TOTAL_CHAPTERS`)

6. **cultivation (`CultivationSettings`):**
   - `qiRewards: Boolean = true`
   - `breakthroughEffects: Boolean = true`
   - `haptics: Boolean = true`
   - `reduceMotion: Boolean = false`
   - `dailyGoalMinutes: Int = 20` (coerced on load/update to 5..120)
   - `reminderEnabled: Boolean = true`
   - `reminderMinutesOfDay: Int = 1230` (20:30 / 8:30 PM, coerced on load/update to 0..1439)

7. **backup (`BackupSettings`):**
   - `autoBackup: AutoBackup = AutoBackup.WEEKLY` (`OFF`, `DAILY`, `WEEKLY`)
   - `lastBackupAtMillis: Long = 0L`

8. **profile (`ProfileSettings`):**
   - `displayName: String = "Reader"`

9. **bottomBar (`BottomBarSettings`):**
   - Planned / to be added: configured in `settings.bottomBar.tabs` (Amendment y).

### Reset Behavior
Calling `resetAllSettings()` resets all groups to their defaults while preserving four user-configured state fields:
- `downloads.customDownloadLocationUri`
- `downloads.customDownloadLocationDisplayName`
- `profile.displayName`
- `backup.lastBackupAtMillis`

## 6. Layers

UI → ViewModels → Repositories/engines → Room, `StorageResolver`/`ChapterStorage`, `SettingsRepository`, sources/parsers. ViewModels and repositories hold no `Context` or UI classes. All I/O runs on `Dispatchers.IO`.

## 7. Navigation

Navigation Compose 2.8+ with `@Serializable` type-safe routes carrying IDs only. Each screen has a ViewModel using `SavedStateHandle`. Never pass `Novel` or domain model objects between screens.

### Primary Destination Tabs
The bottom navigation bar displays 4 of the following 5 primary tabs, customizable in settings (Amendment y):
1. **Library:** User novel library with shelves, filters, sort, and search. Pinned to the bottom bar.
2. **Discover:** Sources, browse, and extensions catalog (replaces legacy `Browse`). Swappable tab slot.
3. **Cultivate:** Reading stats, cultivation realms, Qi progress, and daily reading goals (replaces legacy `Stats`). Swappable tab slot.
4. **Downloads:** Download queue manager, active worker status, paused/completed items, and storage management. Swappable tab slot.
5. **Profile:** Reader profile and settings dashboard (`Profile(subPage)`, replaces legacy `Settings`). Pinned to the bottom bar.

### Secondary Routes
- `NovelDetail(novelId)`: Detailed novel view, metadata, chapter list, and actions.
- `Reader(novelId, chapterIndex)`: Reader interface (uses chapter index because chapter IDs can be URLs).
- `BrowseSource(sourceId)`: Source-specific browsing, search, and latest listings.
- `WebBrowser(initialUrl)`: In-app browser for web novel inspection, cloudflare solving, or source login.

## 8. Storage tiers, cache and downloads

- `storage_tier` (`NONE`, `DOWNLOADED`, `EPUB`) records whether permanent content exists. Reading never marks a chapter downloaded.
- Reader cache is untracked: chapters read online are written (temp file, then rename) to `cacheDir/chapters/{novelId}/{sha256}.json`. Room does not record them. Cap: `cacheSizeCapMb`. After a write, if the folder exceeds the cap, delete the least recently modified files until under it; a cache read refreshes the file's modified time. One mutex serializes trimming. A missing or unreadable cache file is a miss: delete it and refetch.
- Reader order: `EPUB`, then `DOWNLOADED`, then cache, then network, with single-flight per chapter. A reader fetch and a download of the same chapter share one in-flight fetch (single-flight).
- Downloads: written to the download location; tier set to `DOWNLOADED` with a compare-and-set (expected previous `NONE` or `DOWNLOADED`). Disk first: if a valid file already exists at the target, only set the tier. Downloaded chapters (`DOWNLOADED` or `EPUB` tier) are never re-downloaded. If the chapter is in the cache, copy it to the download location (no network) and delete the cache copy.
- Delete downloads resets `DOWNLOADED` rows to `NONE` and deletes files, never `EPUB`; the cache is untouched. Clear cache deletes the cache folder only. Removing a novel from the app deletes its cache folder (`cacheDir/chapters/{novelId}`).
- Startup cleans only `staging/` (files older than 10 minutes). No cache reconciliation. `CacheReconciler` is replaced by a minimal staging cleanup.
- Downloads execution & concurrency:
  - Managed by `DownloadQueueWorker` (WorkManager foreground `CoroutineWorker`, `dataSync` foreground service type) draining `download_queue` ordered by `queued_at`. `NetworkType.UNMETERED` enforced when `wifiOnly` is enabled.
  - **Source-level politeness:** strictly 1 chapter per source at a time (constant `DEFAULT_MAX_PER_SOURCE_CONCURRENCY = 1`).
  - **Cross-source concurrency:** read from settings (`simultaneousDownloads`, default 3, range 1–5).
  - **WebView pool:** at most 2 active WebViews at a time across all sources (`MAX_POOL_CAP = 2`, managed via `WebChapterScraper.configurePoolCapacity` and `Semaphore(2)`).
  - **Request spacing:** comes ONLY from throttle settings (`throttlingEnabled`, `throttleBatchSize`, `throttleDelaySeconds`, `throttleRandomJitter`). With `throttlingEnabled = false` there is NO added delay.
  - **Retries & Backoff:** retries set in-memory not-before times per chapter and per source (no queue-row column). 3 attempts max (`MAX_RETRY_COUNT = 3`) with exponential backoff (2s, 4s, 8s via `baseMs * (1 shl nextRetry)`). Retries do NOT hold the worker concurrency slot: the in-flight chapter job completes and releases its semaphore permit immediately.
  - **Typed `DownloadResult`:** Structured result class reporting `Success`, `RateLimited` (HTTP 429 with retry-after backoff), `Network` (network errors / timeouts), `Parse`, `SourceUnavailable`, `StorageFull`, `StorageUnavailable`, and `ChapterMissing`. Cancellation throws `CancellationException` and aborts immediately without saving files or failing the queue row.
  - **DownloadCoordinator:** owns the mutex (`mutex: Mutex`) coordinating worker start/stop lifecycle, registering active chapter jobs, preventing concurrent work during deletions, and managing source-level locks.
  - **Queue cleanup:** completed queue rows are cleared automatically when the queue becomes idle (no active or paused items remaining).

## 9. Backup format

A zip: `manifest.json` (schema version, app version, timestamp, counts), `data.json` (Room dump), `settings.json`, `covers/`, `original_epubs/`.
Restore: preview from the manifest, then user chooses **Replace all** or **Merge** (newer read state wins). Extract files to `staging/`, import in one `database.withTransaction`, move files only after the commit succeeds. Reset `DOWNLOADED` tier to `NONE` (content is not in the backup), re-parse EPUBs from `original_epubs/`, drop `customDownloadLocationUri` if its permission is no longer granted, validate zip entry paths (zip-slip).

## 10. Reading stats

Sessions count foreground reading only, pause when the app is backgrounded or after 3 minutes idle, ignore position jumps (TOC, slider seek) when counting words, and clamp words per minute to 1000. Words come from paragraph and character progress deltas, not from chapter load.

## 11. Libraries

Room (runtime, ktx, compiler via KSP), Navigation Compose, lifecycle ViewModel Compose, kotlinx.serialization JSON, WorkManager, and for tests JUnit, Robolectric, AndroidX test core and coroutines-test. Set `testOptions.unitTests.isIncludeAndroidResources = true`. Never add JDK or SDK paths to `gradle.properties` or `local.properties`. CI uses Java 21. Robolectric is pinned to sdk=34 via app/src/test/resources/robolectric.properties. Do not change either.

## 12. Stages

Every stage ends with: the app compiling and launching, tests passing, and a summary (files changed, what was NOT changed, temporary adapters and the stage that deletes each, manual test checklist). Stop after each stage.

### Status

#### Completed
- **1a Settings:** `SettingsRepository`, serializable settings models.
- **1b Room database:**
  - **1b-1:** entities, DAOs, `NovelDatabase`, converters, Robolectric test suite.
  - **1b-2:** `NovelRepository` uses Room (library, chapter metadata, read state, bookmarks, term replacements, custom sources, notifications). Deleted `LibraryManager`.
- **4 Storage engine:** `StorageResolver`, `ChapterStorage`, `ChapterCache` (untracked reader cache, 4a internal storage, cache-folder tests). Deleted `StorageManager`.
- **5 Download engine:**
  - **5b:** `DownloadEngine`, `DownloadCoordinator` mutex, WorkManager enqueueing.
  - **5c:** `DownloadQueueWorker` (foreground service with `dataSync`, notification permission handling, source politeness, WebView pool capacity). Deleted `DownloadManager`.
- **Settings Backend:** Modular groups (`reader`, `theme`, `downloads`, `notifications`, `library`, `cultivation`, `backup`, `profile`), atomic `.tmp` rename with `.corrupted` backup, debounced writes, direct `settings.value.<group>` reads for non-UI logic.

#### Next
- **2a Container & Navigation:** `AppContainer`, `NovelApplication`, and type-safe `NavHost` with 4 customizable tabs (Amendment y).
- **1c Small Fixes:** reading position in Room only (remove the SharedPreferences copy in PreferencesManager and the flush bug where the debounce job cancels itself); atomic web-novel save (one transaction, throws on failure, refuses 0 chapters, WtrLabExtractor chapter fetch throws instead of returning an empty list); debounced library flow; delete migrateEpubCoversIfNeeded and the misleading libraryNovels.first() startup call; delete DAO methods that set a tier without a file if unused.
- **Screens Ported from HTML Designs:** Production UI screens ported with shared theme tokens and components (Rule 27):
  - Discover (sources catalog, extensions)
  - Profile (profile settings, app preferences)
  - Library (grid/list, shelf tabs, sort/filter)
  - NovelDetail (metadata, chapter list, batch actions)
  - Reader (custom styling, gestures, TTS, TOC)
  - Cultivate (reading stats, cultivation realms, Qi rewards)
- **Stage 3 Reader & Real Stats:** `ReaderViewModel`, wiring of ReaderSettings fields exposed by design (Amendment d), cultivation stats tracking (Amendment u), and retiring `PreferencesManager`.
- **Stage 6 Backup & Restore:** Complete JSON/zip backup format, preview, replace/merge, zip-slip validation.
- **Extensions:** Isolated post-screens feature: sandboxed runtime (JS or Wasm), user-added repos (Amendment w).
- **Optional Download Simplification:** Refinement of download queue operations based on screen feedback.
- **Release Stage:** Drop `fallbackToDestructiveMigration()`, author real Room migrations, enable code shrinker (R8), and perform final production release audits.

## 13. Amendments (all approved)

- **a.** The app compiles and runs after every stage; replaced managers are bridged by documented temporary adapters, deleted in the stage that replaces their last consumer.
- **b.** Browse novels persist with `in_library = 0` and are purged on startup when unused (section 3).
- **c.** `POST_NOTIFICATIONS` request and the `dataSync` foreground service manifest entries belong to Stage 5.
- **d.** In Stage 3, wire the ReaderSettings fields exposed by the designs into the Reader and Profile/Settings; omit what the design does not use (relaxing the requirement to wire unused fields).
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
- **s.** Current package layout is accepted as the permanent structure (do not move files):
   `data/download/` (not `download/`), `data/source/` (not `data/remote/source/`), and
   `BackupManager` and `SettingsRepository` live under `data/repository/`.
- **t.** The reader cache is untracked (no Room column, no tier), and Stage 4 is amended
   accordingly (LRU tests become cache-folder tests).
- **u.** Qi is awarded at the end of a chapter on the unread->read transition only, never by "mark all read"; stored in a small Room table.
- **v.** Inline choices in the UI where space allows; sheets only for confirmations or long lists; extract a shared component only when a second screen needs it (no preemptive abstraction).
- **w.** Extensions are an isolated post-screens feature: sandboxed runtime, load JS or Wasm, user-added repos.
- **x.** Non-UI logic (repositories, download engine, workers, storage resolver) must read `settings.value.<group>`, never derived flows (which are for UI consumers and can lag behind state updates).
- **y.** Bottom bar is customizable: displays 4 of the 5 primary tabs, configured in `settings.bottomBar.tabs`. Library and Profile are pinned (always shown); the other 2 slots can be chosen from Discover, Cultivate, or Downloads.

## 14. Rules

All rules in `docs/ARCHITECTURE_RULES.md` apply to every stage.
