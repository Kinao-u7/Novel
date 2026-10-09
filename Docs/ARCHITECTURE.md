# Architecture Blueprint & Execution Plan

This document establishes the target architecture for the Novel Reader application. It governs the ongoing modernization and rebuild across all stages.

---

## 1. Context & Objectives

- **Clean-slate architecture**: No historical migration or legacy schema backward compatibility is required. A fresh install wipes existing prototype data.
- **Goal**: Provide a rock-solid, crash-resilient foundation:
  - Strongly typed, structured persistence via Room and DataStore/JSON.
  - Predictable state management with unidirectional data flow (MVI/MVVM with StateFlow) and Lifecycle-aware ViewModels.
  - Safe, cached, tiered storage for chapters (cache vs downloaded, SAF awareness, tamper-safe IDs).
  - Background-safe download engine utilizing WorkManager and Foreground Services.
  - Type-safe, ID-driven Navigation with full process-recreation survival.
- **Preserved modules**:
  - Scrapers and parsers (`WtrLabExtractor`, `WebChapterScraper`, `WebScrapeScripts`, `EpubParser`).
  - Theme, design tokens, `CommonUi` components, and established screen layouts.

---

## 2. Rebuilt Components & Strategy

### 2.1 Navigation & State Handling
- Type-safe navigation (Jetpack Navigation 2.8+ with `kotlinx.serialization` or URL-safe encoded chapter/source IDs).
- Screens receive entity IDs only; data loads from repositories via ViewModels with `SavedStateHandle`.
- Back stack managed predictably with standard navigation actions; no local state held across screens in `remember`.

### 2.2 Persistence & Database (Room)
- Room Database with `exportSchema = true` and configured `room.schemaLocation`.
- Core entities:
  - `novels`: id, title, author, cover_url, description, genres, status, source_id, source_name, in_library, last_read_chapter_id, last_read_chapter_title, last_read_chapter_index, total_chapters, downloaded_count, rating, last_read_timestamp, is_epub, raw_id, locale, slug, source_url.
  - `chapters`: composite primary key `(novel_id, id)`. Columns: novel_id, id, title, chapter_index, release_date, is_downloaded, is_read, word_count, storage_location (`INTERNAL` or SAF root ID), last_read_paragraph, last_read_offset, updated_at.
  - `bookmarks`: id, novel_id, chapter_id, chapter_title, paragraph_index, character_offset, excerpt, note, timestamp.
  - `download_queue`: composite primary key `(novel_id, chapter_id)`, priority, status, enqueued_at.
  - `reading_sessions`: id, novel_id, novel_title_snapshot, start_time, duration_seconds, words_read. (No foreign key cascade from novel to preserve reading history).
  - `custom_sources`: id, name, url, language, description, created_at.
  - `requested_sources`: id, url, requested_at.
- Cascading updates: Never use `REPLACE` on tables with cascading foreign keys. Chapter refresh executes insert-ignore followed by updates of metadata columns only, leaving read status, scroll positions, storage locations, and bookmarks intact.

### 2.3 Chapter Storage & Caching
- **Tiered chapter storage**:
  - `cache/`: Transient chapter contents downloaded during reading. Automatically trimmed; never counted as downloaded.
  - `downloads/`: Explicitly downloaded chapters. Counted in UI badges.
  - `epubs/` & `original_epubs/`: Imported EPUB chapters and raw archives. Protected from all deletion routines.
  - `SAF`: User-selected external storage tree.
- Stable storage addressing: File names generated via SHA-256 stable hash of chapter id (`ch_{hash}.json`), avoiding fragile index-based naming.
- `CacheReconciler`: Safely cleans orphan temp files in `cache/` and `staging/` older than 10 minutes. Never touches `downloads/`, `epubs/`, or SAF roots. Runs lazily on cache misses and after restores.

### 2.4 Settings Architecture
- Managed by `SettingsRepository` with `kotlinx.serialization` JSON:
  - Single settings object serialization with `Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }`.
  - Adding a field with a default requires no migration or schema boilerplate.
  - Distinct models: `ReaderSettings`, `ThemeSettings`, `DownloadSettings`, `NotificationSettings`.
  - Unified concepts: No duplicate fields (e.g. `marginType` vs `horizontalMarginDp`, `isJustified` vs `textAlign`).
  - Preserved defaults: Sepia reader theme, Amber Gold accent `#DEB887`, justified text, normal margins.

---

## 3. Implementation Stages

1. **Stage 1a — Settings Architecture**:
   - `kotlinx.serialization` plugin and dependencies.
   - `SettingsRepository` backed by atomic file-based JSON persistence with Flow.
   - Field-by-field model consolidation and temporary adapter layer for `PreferencesManager`.
2. **Stage 1b — Navigation & Screen Scaffolding**:
   - Type-safe navigation routes with ID arguments.
   - ViewModel foundations with `SavedStateHandle`.
3. **Stage 2a — Room Database Foundation**:
   - Room entities, DAOs, Database, schema export, and in-memory test harness.
4. **Stage 4 — Chapter Storage Engine**:
   - Tiered filesystem manager, stable chapter hashing, SAF resolver, `CacheReconciler`.
5. **Stage 2b — Repository Layer**:
   - `NovelRepository`, `ChapterRepository`, `SourceRepository`, startup purge of unread `in_library = 0` browse records.
6. **Stage 3 — Reader & Reading Stats**:
   - `ReaderViewModel`, `ChapterStorage` integration, complete end-to-end wiring of all `ReaderSettings` attributes with visible reader effects.
   - Reading stats session recorder with 3-minute idle timeout, TOC/jump exclusion, and maximum WPM threshold.
7. **Stage 5 — Download Engine & Background Work**:
   - WorkManager / Foreground Service with `dataSync` type, throttling WebView pool (max 2 parallel).
8. **Stage 6 — Backup & Restore Engine**:
   - Zip archive import/export with zip-slip verification, persistable SAF validation, post-restore reconciliation.
9. **Stage 7 — Screen & UI Refinements (7a through 7h)**:
   - 7a Library, 7b Detail & Options, 7c Browse, 7d WebBrowser, 7e Downloads, 7f Settings/History/Stats/Profile, 7g Theme & Design System, 7h EPUB Parser & Scraper scripts.

---

## 4. Approved Amendments

- **a. Zero-Downtime Builds & Temporary Adapters**: The app must compile and run cleanly at the conclusion of every stage. When a stage replaces a manager, remaining legacy code routes through new repositories via thin temporary adapters, documented and removed in the subsequent stage that replaces the consumer.
- **b. Browse Novels Persistence**: When opened from Browse, a novel is persisted with `in_library = 0`. On app launch, unbookmarked, unread, undownloaded novels with `in_library = 0` are purged.
- **c. Foreground Service & Permissions**: Move `POST_NOTIFICATIONS` permission request and `dataSync` foreground service manifest entry into Stage 5.
- **d. Reader Settings End-to-End Wiring**: In Stage 3, every single `ReaderSettings` field must have an observable visual effect in `ReaderScreen` and be fully editable in both the reader sheet and global Settings.
- **e. Reading Stats Safeguards**: 3-minute idle session timeout, discard word counts on non-reading position jumps (slider seek, TOC jump), and clamp WPM to a realistic maximum (1000 WPM).
- **f. Serialization & Room Configuration**: `Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }` for settings. Room configured with `exportSchema = true` and `room.schemaLocation`.
- **g. Backup & Security Rules**: Drop `customDownloadLocationUri` on restore if permission is revoked. Strict path validation against directory traversal (zip-slip).
- **h. Main-Thread Hygiene**: `Application.onCreate` performs zero disk or database I/O. Room is initialized lazily; UI renders immediately and collects Flows.
- **i. Carry-over Polish**: Incorporate 7g (theme and design system) and 7h (EPUB streaming and scraper fixes) into Stage 7. Report resolved issue IDs from `Docs/CARRYOVER.md` at the end of each stage.
- **j. Testing Configuration**: Enable `testOptions.unitTests.isIncludeAndroidResources = true` for Robolectric.

---

## 5. Architectural Rules & Invariants

*(Adopted verbatim from `Docs/ARCHITECTURE_RULES.md`)*

### Data safety
1. **Deleting never touches protected data.** Delete-downloads, clear-cache and delete-all must never remove EPUB content, original `.epub` files, covers or the chapter list of any novel.
2. **Reading is not downloading.** Reading a chapter writes only to the cache tier. It never marks the chapter as downloaded, and download counts always come from the database, not from a separate counter.
3. **No `REPLACE` on tables with cascading foreign keys.** Chapter refresh uses insert-ignore plus an update of metadata columns only, so read state, positions, storage tier and bookmarks survive.
4. **Removing a novel asks first.** Show a confirm dialog with an option to also delete files. Reading stats and history survive removal.
5. **Writes are safe.** Write to a temp file and rename, run multi-table changes in a transaction, and never overwrite good data after a failed load.

### No fake data
6. **No placeholder or demo data anywhere.** No default stats, streaks, ratings, "welcome" notifications or seeded novels. Unknown values are null and the UI hides them.
7. **New novels are not "read".** `last_read_timestamp` is set only when the user actually reads.

### Wiring
8. **Every feature or setting is wired end to end:** a model field with a default, persistence, the UI that edits it, the code that consumes it (it must visibly work), backup and restore, and a test or manual check. If any step is missing, say so instead of shipping it.
9. **One field per concept.** No duplicate fields for the same setting (for example margin type and margin dp).
10. **Persistence of settings is by whole object.** Adding a field with a default must not need other code changes.

### Threading and performance
11. **No file, SAF, JSON, database or network work on the main thread,** and none inside composition. `Application.onCreate` does no disk or database work.
12. **Long-running work never lives in a composable scope.** Downloads, scraping and deletes run in the app scope, WorkManager or a ViewModel.
13. **Never swallow exceptions.** Handle them or surface them in UI state. Always rethrow `CancellationException`.
14. **Reading position is a paragraph plus a character offset,** saved debounced and off the main thread, only when it changes. Never store a page index.
15. **Compose hygiene:** stable keys in lists, do not read fast-changing state (clock, battery, scroll) at a screen root, and sliders commit on release, not on every tick.

### State and navigation
16. **Routes carry IDs only.** Screens load data from the database through a ViewModel. Never pass `Novel` objects around or hold copies in `remember`.
17. **UI state survives rotation and process recreation** (ViewModel plus `SavedStateHandle`, or `rememberSaveable` for local text fields).
18. **One resume target function** decides which chapter "Continue" opens. Never build a chapter ID by guessing.

### UI consistency
19. **No hardcoded colors, shapes, URLs or user agents.** Use theme colors and `MaterialTheme.shapes`. Buttons go through the shared button components, which take their shape from the theme. Repeated values live in one constant.
20. **Reuse before adding.** No copy-pasted composables or functions. Delete dead code, unused parameters and unused imports in every file you touch.
21. **Destructive actions confirm** (or offer undo) and report the result.
22. **Accessibility basics:** touch targets of at least 48dp, content descriptions only when meaningful, text of at least 12sp.

### Security
23. **WebView loads only http and https.** Handle render-process crashes and load errors.
24. **FileProvider paths are narrow** (a cache subfolder only).
25. **Validate archive entries** (zip-slip) before extracting. Never restore a folder permission that is no longer granted.

### Done means
- `./gradlew assembleDebug` passes, the app launches and `./gradlew test` is green.
- The summary lists the files changed, what was NOT changed, any temporary adapters added, and anything noticed but left alone.
- A manual test checklist is provided for the user.
