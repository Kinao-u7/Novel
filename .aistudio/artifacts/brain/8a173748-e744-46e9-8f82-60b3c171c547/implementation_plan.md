# Stage 5 Download Engine Hardening: Coordinator, CAS Tiers & Sliding Pool (Revised)

Comprehensive hardening for the novel download engine, storage tier synchronization, and concurrency architecture. This revised specification addresses critical concurrency boundaries: non-blocking coordinator lock patterns, precise worker lifecycle tracking, conditional compare-and-set tier updates, typed error handling with storage-specific space detection, in-memory source throttling, and deletion guards.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> The implementation plan incorporates the following strict concurrency rules:
>
> 1. **Lock Non-Blocking Invariant**: The `DownloadCoordinator` lock is **never** held while waiting for workers or in-flight downloads. For pause, cancel, and delete flows: acquire lock $\to$ update queue rows & capture in-flight chapter IDs $\to$ **release lock** $\to$ wait for in-flight tasks to terminate $\to$ perform disk deletions.
> 2. **Worker Tracking & Clean Resume**: `resumeQueue` does not use `ExistingWorkPolicy.REPLACE`. The coordinator tracks active worker jobs; resume waits for any prior run to stop (outside the lock) before scheduling via `KEEP` or `APPEND_OR_REPLACE`.
> 3. **Queue-Driven Totals & Non-Disruptive Cleanup**: `clearCompleted` is never called at the start of a follow-up run. Completed rows are cleared only when no `QUEUED`, `DOWNLOADING`, or `PAUSED` items remain. Progress and totals are read directly from the queue.
> 4. **Flexible Cache CAS Update**: `updateTierToCacheIfNoneOrCache` accepts existing tiers `NONE` or `CACHE` and unconditionally refreshes `last_accessed_at` (never overwriting `DOWNLOADED` or `EPUB`).
> 5. **Storage Space Semantics**: `StatFs` pre-check is applied strictly to `INTERNAL` storage. For `SAF`, pre-checks are skipped, and `ENOSPC` / "No space left on device" `IOException`s are mapped directly to `DownloadResult.StorageFull`.
> 6. **In-Memory Source Throttling**: Per-source `nextAllowedAt` timestamps are tracked in memory without altering the Room schema.
> 7. **Deletion Guard**: Novels undergoing file deletion are tracked in an in-memory thread-safe set; `enqueueChapters` rejects attempts to queue chapters for a novel while it is being deleted.

---

### 1. Overview & Core Concept

Under heavy concurrency (e.g. background downloading while reading, rapid user pause/resume toggling, or deleting a book during active downloads), standard batching and uncoordinated locks can cause deadlocks, stranded queue rows, or overwritten storage tiers.

This implementation establishes an authoritative concurrency architecture:
- **`DownloadCoordinator`**: An app-scoped coordinator synchronizing queue mutations, worker exit checks, and lifecycle events while avoiding lock starvation.
- **Compare-and-Set (CAS) Storage Tiers**: Database tier mutations conditionally guard on previous state (`WHERE storage_tier IN (...)`) so readers, downloads, and LRU evictions never clobber each other.
- **Disk-First & Self-Healing**: Validates local storage before network calls, and repairs missing `DOWNLOADED` files back to their original tier and recorded location.
- **Sliding Pool & Dynamic Dispatch**: Coroutine-based worker pool with per-source delay windows and queue-driven progress reporting.

---

### 2. Technical Architecture & Component Interaction

```
┌────────────────────────────────────────────────────────────────────────┐
│                          DownloadCoordinator                           │
│                      (App-Scoped Mutex Barrier)                        │
└──────┬───────────────────────┬──────────────────────────┬──────────────┘
       │                       │                          │
       ▼                       ▼                          ▼
┌──────────────┐       ┌──────────────┐           ┌──────────────┐
│   Enqueue    │       │ Pause/Cancel │           │ Worker Exit  │
│  Operations  │       │  & Deletes   │           │ Verification │
└──────┬───────┘       └──────┬───────┘           └──────┬───────┘
       │                      │                          │
       │                      │ Under Lock:              │
       │                      │ 1. Mark rows PAUSED/DEL  ▼
       │                      │ 2. Capture in-flight IDs Check for QUEUED
       │                      ▼                          rows under lock;
       │               RELEASE LOCK                      if empty, mark
       │                      │                          worker stopped & exit
       │                      ▼                          
       │               Outside Lock:
       │               3. Wait for in-flight tasks
       │               4. Cancel worker job
       │               5. Delete files
       │               6. Clear "deleting" guard
       ▼                                                 
┌────────────────────────────────────────────────────────────────────────┐
│                        Sliding Worker Pool                             │
│       Max Global Slots (1-5) + In-Memory Per-Source Throttling         │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│                         ChapterRepository                              │
│       Shared In-Flight Map / Leaf Mutex (novelId, chapterId)           │
│                                                                        │
│  1. Disk-First Validation ──► File exists? ──► CAS update ──► Return   │
│  2. Fetch from Source ──────► Stream ChapterContent                    │
│  3. Storage Write ──────────► Internal (StatFs check) or SAF (ENOSPC) │
│  4. Compare-and-Set Tier ───► WHERE storage_tier IN ('NONE', 'CACHE')  │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│                           Room Database                                │
│   ChapterDao: updateStorageTierCas, updateTierToCacheIfNoneOrCache     │
│   DownloadQueueDao: clearCompletedWhenQueueDrained                     │
└────────────────────────────────────────────────────────────────────────┘
```

---

### 3. Detailed Component Plan

#### 1. Coordinator Lock & Lifecycle Rules (`DownloadCoordinator`)
- Singleton `DownloadCoordinator` holding `val mutex = Mutex()`, active worker `CompletableDeferred` / `Job` tracking, active chapter job map `ConcurrentHashMap<Pair<String, String>, Job>`, and `deletingNovelIds = ConcurrentHashMap.newKeySet<String>()`.
- **Lock Invariant Rule**:
  - In `pauseQueue`, `cancelChapter`, `cancelNovel`, `cancelAll`, `deleteNovelDownloads`, and `removeNovelCompletely`:
    1. Acquire coordinator lock.
    2. Update queue rows (to `PAUSED` or delete from queue) and record affected chapter keys / novel IDs.
    3. If deleting a novel, add `novelId` to `deletingNovelIds`.
    4. **Release coordinator lock immediately.**
    5. Outside the lock: wait on active chapter jobs / worker stoppage.
    6. Perform file deletions safely (no in-flight writer can resurrect them).
    7. Remove `novelId` from `deletingNovelIds`.
- **Worker Exit Check**:
  - When the worker sliding loop finds no candidates:
    1. Acquire coordinator lock.
    2. Re-query `countByStatus("QUEUED")`. If $> 0$, keep loop running.
    3. If truly 0: check if `countActiveRemaining() == 0 && countByStatus("PAUSED") == 0`. If all done, call `clearCompleted()`.
    4. Mark worker inactive and release lock.
- **`resumeQueue` Handling**:
  - Outside the lock, await completion of any previous worker run (never call `ExistingWorkPolicy.REPLACE`).
  - Acquire lock, update `PAUSED` rows back to `QUEUED`, schedule worker with `ExistingWorkPolicy.KEEP` (or `APPEND_OR_REPLACE`), release lock.
- **Enqueue Deletion Guard**:
  - `enqueueChapters` checks `deletingNovelIds.contains(novelId)`. If present, rejects enqueueing with clear skipped reason.

#### 2. Conditional Tier Writes (Compare-and-Set)
- Update `ChapterDao`:
  - `updateStorageTierCas(novelId, id, targetTier, targetLocation, fileHash, timestamp, allowedPreviousTiers: List<String>): Int`:
    `UPDATE chapters SET storage_tier = :targetTier, storage_location = :targetLocation, file_hash = :fileHash, last_accessed_at = :timestamp WHERE novel_id = :novelId AND id = :id AND storage_tier IN (:allowedPreviousTiers)`
  - `updateTierToCacheIfNoneOrCache(novelId, id, targetLocation, fileHash, timestamp)`:
    `UPDATE chapters SET storage_tier = 'CACHE', storage_location = :targetLocation, file_hash = :fileHash, last_accessed_at = :timestamp WHERE novel_id = :novelId AND id = :id AND storage_tier IN ('NONE', 'CACHE')`
  - `updateTierToNoneIfCache(novelId, id, timestamp)`:
    `UPDATE chapters SET storage_tier = 'NONE', last_accessed_at = :timestamp WHERE novel_id = :novelId AND id = :id AND storage_tier = 'CACHE'`
  - `updateTierToDownloaded(novelId, id, targetLocation, fileHash, timestamp)`:
    `UPDATE chapters SET storage_tier = 'DOWNLOADED', storage_location = :targetLocation, file_hash = :fileHash, last_accessed_at = :timestamp WHERE novel_id = :novelId AND id = :id AND storage_tier IN ('NONE', 'CACHE', 'DOWNLOADED')`
- In `ChapterRepository`:
  - Reader fetch: invoke `updateTierToCacheIfNoneOrCache`, always updating `last_accessed_at` and preserving existing `DOWNLOADED` or `EPUB` tiers.
  - LRU Eviction: under the per-chapter leaf lock, re-read the chapter row. Reset to `NONE` only if the tier is still `CACHE`.
  - Download write: uses `updateTierToDownloaded`.
  - Leaf Lock / In-Flight Sharing: `downloadChapter` coordinates with reader fetches via the per-chapter leaf lock around fetch + write + tier update, preventing conflicting writes.

#### 3. Disk-First Validation & Self-Healing
- In `ChapterRepository.downloadChapter`:
  - **Disk-First**: Before making remote network calls, check if a valid file exists at target location (`INTERNAL` or `SAF`). If valid, execute `updateTierToDownloaded`, skip network fetch, and return `DownloadResult.Success`.
  - **Self-Healing**: If a chapter is recorded in Room as `DOWNLOADED` but reading encounters a missing file:
    - On successful refetch from source, write content back as `DOWNLOADED` to its original recorded `storageLocation` (not `CACHE`).
- Update `docs/ARCHITECTURE.md` amendment `m` to include the self-healing requirement.

#### 4. Typed Download Results & Storage Checks
- Define `DownloadResult` sealed hierarchy:
  ```kotlin
  sealed class DownloadResult {
      object Success : DownloadResult()
      data class RateLimited(val message: String, val retryAfterMs: Long? = null) : DownloadResult()
      data class Network(val message: String, val cause: Throwable? = null) : DownloadResult()
      data class Parse(val message: String, val cause: Throwable? = null) : DownloadResult()
      data class SourceUnavailable(val sourceId: String) : DownloadResult()
      data class StorageFull(val availableBytes: Long, val requiredBytes: Long) : DownloadResult()
      data class StorageUnavailable(val location: String, val message: String) : DownloadResult()
  }
  ```
- Storage space verification:
  - **INTERNAL**: Check free space before download via `StatFs(appContext.filesDir.path).availableBytes`. If $< 20\text{ MB}$, return `DownloadResult.StorageFull`.
  - **SAF**: Skip `StatFs` pre-check. During write, catch `IOException` matching `ENOSPC` ("No space left on device") and map directly to `DownloadResult.StorageFull`.
- In `DownloadQueueWorker`:
  - `RateLimited` and `Network`: backoff retry up to `MAX_RETRY_COUNT`, then set `FAILED`.
  - `Parse`, `SourceUnavailable`, `StorageFull`, `StorageUnavailable`: transition immediately to `FAILED` without retrying.
  - Record error messages into `download_queue.error_message`.
  - No `printStackTrace()` or empty catch blocks; log cleanly.

#### 5. Dynamic Sliding Pool, Per-Source Limiting & Progress
- Refactor `DownloadQueueWorker.doWork()`:
  - Sliding pool with dynamic slot acquisition (max concurrent from settings, default 2, max 5).
  - In-memory per-source rate limiting: `sourceNextAllowedAt: ConcurrentHashMap<String, Long>`.
  - When selecting items, if a source's `nextAllowedAt` is in the future, do not block the worker thread; skip to an eligible chapter from another source or delay briefly without holding a slot.
  - Progress calculation: compute progress and totals from the database queue (`countByStatus("COMPLETED")`, `countActiveRemaining()`, and total) so follow-up workers and resumed runs display accurate progress.
  - Queue cleanup: clear completed rows only when no `QUEUED`, `DOWNLOADING`, or `PAUSED` rows remain.

#### 6. Persistent SAF Size Calculation
- In `SafChapterFileStore.getDownloadsSizeBytes()`:
  - Read `settingsRepository.downloadSettings.value.customDownloadLocationUri` to locate the configured SAF root tree (instead of iterating volatile `treeRoots.keys`).
  - Calculate directory size on demand and cache with timestamp invalidation.

---

### 4. Test Suite & Verification Plan

#### Test Modernization
- **Wall-Clock Timeouts**: Replace `withTimeout` inside virtual time dispatchers with real-time timeouts or standard `runBlocking(Dispatchers.IO)`.
- **Deterministic Signaling**: Replace `delay(50)` heuristics with `CompletableDeferred<Unit>` signaling when concurrent callers join.

#### Concurrency & Race Condition Tests
1. **Eviction vs Promote**: LRU eviction running concurrently with `promoteCacheToDownload`. CAS ensures eviction does not reset `DOWNLOADED` tier.
2. **Download vs Reader Fetch**: Concurrent reader fetch and download for the same chapter. Leaf lock ensures single fetch and final tier is `DOWNLOADED`.
3. **Pause Mid-Download**: Worker downloading chapters while `pauseQueue` is called. Lock released before waiting on tasks ensures worker stops cleanly and rows transition to `PAUSED`.
4. **Enqueue While Worker Exiting**: Enqueuing right as worker loop finds an empty batch. Under coordinator lock, newly added rows are detected and processed without stranding.
5. **Delete During Download**: Calling `deleteNovelDownloads` while downloads are in flight. Lock ensures queue rows are canceled and novel is marked "deleting" before files are wiped, preventing resurrection.

---

### 5. Scope & Preservation Notice

- **Android Source Only**: No edits to web wrapper or server scripts.
- **Legacy Preservation**: No edits under `legacy/`.
- **API Preservation**: Existing public APIs on `NovelRepository` and `DownloadEngine` remain compatible.
