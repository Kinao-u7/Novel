# Rules (don't repeat the old bugs)

Append this to `docs/ARCHITECTURE.md`. These rules apply to every stage and every prompt. Each one exists because the old code broke it.

## Data safety

1. **Deleting never touches protected data.** Delete-downloads, clear-cache and delete-all must never remove EPUB content, original `.epub` files, covers or the chapter list of any novel.
2. **Reading is not downloading.** Reading a chapter writes only to the cache tier. It never marks the chapter as downloaded, and download counts always come from the database, not from a separate counter.
3. **No `REPLACE` on tables with cascading foreign keys.** Chapter refresh uses insert-ignore plus an update of metadata columns only, so read state, positions, storage tier and bookmarks survive.
4. **Removing a novel asks first.** Show a confirm dialog with an option to also delete files. Reading stats and history survive removal.
5. **Writes are safe.** Write to a temp file and rename, run multi-table changes in a transaction, and never overwrite good data after a failed load.

## No fake data

6. **No placeholder or demo data anywhere.** No default stats, streaks, ratings, "welcome" notifications or seeded novels. Unknown values are null and the UI hides them.
7. **New novels are not "read".** `last_read_timestamp` is set only when the user actually reads.

## Wiring

8. **Every feature or setting is wired end to end:** a model field with a default, persistence, the UI that edits it, the code that consumes it (it must visibly work), backup and restore, and a test or manual check. If any step is missing, say so instead of shipping it.
9. **One field per concept.** No duplicate fields for the same setting (for example margin type and margin dp).
10. **Persistence of settings is by whole object.** Adding a field with a default must not need other code changes.

## Threading and performance

11. **No file, SAF, JSON, database or network work on the main thread,** and none inside composition. `Application.onCreate` does no disk or database work.
12. **Long-running work never lives in a composable scope.** Downloads, scraping and deletes run in the app scope, WorkManager or a ViewModel.
13. **Never swallow exceptions.** Handle them or surface them in UI state. Always rethrow `CancellationException`.
14. **Reading position is a paragraph plus a character offset,** saved debounced and off the main thread, only when it changes. Never store a page index.
15. **Compose hygiene:** stable keys in lists, do not read fast-changing state (clock, battery, scroll) at a screen root, and sliders commit on release, not on every tick.

## State and navigation

16. **Routes carry IDs only.** Screens load data from the database through a ViewModel. Never pass `Novel` objects around or hold copies in `remember`.
17. **UI state survives rotation and process recreation** (ViewModel plus `SavedStateHandle`, or `rememberSaveable` for local text fields).
18. **One resume target function** decides which chapter "Continue" opens. Never build a chapter ID by guessing.

## UI consistency

19. **No hardcoded colors, shapes, URLs or user agents.** Use theme colors and `MaterialTheme.shapes`. Buttons go through the shared button components, which take their shape from the theme. Repeated values live in one constant.
20. **Reuse before adding.** No copy-pasted composables or functions. Delete dead code, unused parameters and unused imports in every file you touch.
21. **Destructive actions confirm** (or offer undo) and report the result.
22. **Accessibility basics:** touch targets of at least 48dp, content descriptions only when meaningful, text of at least 12sp.

## Security

23. **WebView loads only http and https.** Handle render-process crashes and load errors.
24. **FileProvider paths are narrow** (a cache subfolder only).
25. **Validate archive entries** (zip-slip) before extracting. Never restore a folder permission that is no longer granted.

## Done means

- `./gradlew assembleDebug` passes, the app launches and `./gradlew test` is green.
- The summary lists the files changed, what was NOT changed, any temporary adapters added, and anything noticed but left alone.
- A manual test checklist is provided for the user.
