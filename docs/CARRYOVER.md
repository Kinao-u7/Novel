# Carry-over fixes (Stage 7)

These issues are **not** solved by the architecture rebuild. Each one is fixed in the Stage 7 pass for its screen, or in the Reader checklist inside Stage 3. Anything the new architecture already fixes (storage, backup, downloads, persistence, navigation, state, stats storage) is deliberately not listed.

Numbers in parentheses are IDs from the original master issue list, for traceability only.

Rule for every pass: fix only the items listed for that pass, follow `docs/ARCHITECTURE_RULES.md`, and report anything else you notice instead of changing it.

---

## Reader checklist (do inside Stage 3)

- **Toolbar (2.6):** the default has 6 actions, but the toolbar shows 4 plus a more menu when there are more than 5, so Bookmark and Display are hidden by default. Cap the customizer at 5 with a message (or let the row scroll), make the default 5 or fewer, and make "enabled" always mean "visible".
- **Auto-scroll (2.5):** needs a UI control. Use frame-based pixels per second (not 4px every 100ms). Pause on touch, while controls are shown and while TTS plays. Stop at chapter end, or advance if the user enabled it.
- **Bookmarks (2.18):** save the current paragraph and character offset, an excerpt of the visible text and the chapter title. Entries in the bookmarks sheet are tappable and jump to the saved position.
- **TTS (2.19):** read the same text that is displayed (term replacements applied), start from the visible position, and stop or advance on chapter change.
- **Term replacements (2.20):** compile rules once per chapter, not once per paragraph. Support whole-word and case options. Support global and per-novel rules.
- **Dictionary (2.16):** remove the mock dictionary. Long-press must not block normal text selection.
- **Performance (3.18):**
  - Paginate only in paged mode, off the main thread.
  - Font-size and other sliders change local state while dragging and save on release (also in Settings).
  - Do not read battery % or the clock at the root of the Reader (this recomposes the whole screen). Scope them to the small composables that show them. Honor 12/24h.
- **Insets (5.12):** replace hardcoded 40/52/58dp with status bar, display cutout and navigation bar insets. The bottom toolbar must clear the navigation bar.
- **Contents sheet (6.32):** scroll to the current chapter. The progress slider seeks by paragraph or percentage, not by coarse index.
- **Structure (6.34):** split the 2,000-line composable into focused composables. Extract the duplicated toolbar-action handler used by the toolbar and the More sheet.
- **Reader sheet (6.35):** parity with Settings (spacing, indent, weight, letter spacing, margins, keep screen on, TTS tab).
- **Chapter list errors (6.33):** surface load errors in UI state instead of swallowing them.

## 7a Library

- Show reading progress in both views: a progress bar plus unread and downloaded badges. Remove the "Ch. N read" wording (6.11).
- EPUB import (6.9): show an error message on failure, get the display name via `OpenableColumns.DISPLAY_NAME` (not `lastPathSegment`), use the `application/epub+zip` MIME type, and use a single picker launcher.
- Three entry points to the same actions (header, FAB sheet, empty state): consolidate. No button may silently do nothing when its callback is null (6.12).
- "Recently Read" must not include never-read novels. Add "Recently Added" (6.10).
- Replace the nested `LazyRow` of genre tags in list items with a plain `Row` (3.19).
- Add empty and no-results states.

## 7b Detail screen and options sheet

- Continue button (6.14): disabled until chapters load. Use one `resumeTarget(novel)` function (last read chapter, else first). Never construct a guessed chapter ID.
- Loading and errors (6.15): show an empty or error state with Retry. Refresh keeps the list and scroll position and shows inline progress.
- UI details (6.16): "Read more" only when the text is truncated, jump to the current chapter, sort icon shows direction, count is consistent while loading, a visible add/remove-library action outside the overflow menu.
- Touch targets of at least 48dp for the per-chapter download icon (6.17).
- Pass `isRead`/`isDownloaded` into chapter rows instead of copying the chapter list on every change (3.19).
- Refresh must really re-scrape web novels (WTR-Lab API) and merge new chapters with the insert-ignore plus update strategy (2.17).
- Options sheet (6.18, 6.19): confirm dialogs for Remove, Delete downloads and Clear history. Validate a blank title and the cover URL.
- `resolveNovelWebUrl` (6.20) must return the novel page, not a chapter URL. For WTR-Lab build it from locale, raw id and slug.
- Rating badge hidden when the rating is null.

## 7c Browse (Discover)

- Add Source (6.1): validate the URL (scheme and host), normalize it, and reject duplicates. `"https://"` alone must not be accepted.
- Empty states (6.2): "No custom sources yet" only when there are none. Show a separate no-results state when search or a filter hides everything.
- Filters (6.3): separate category from languages (a `languages` set per source). Do not use `contains`.
- Search box (6.4): URL detection via a real URL parse, not `contains(".")`. The keyboard Go action opens the URL.
- Delete source (6.5): confirm or undo snackbar, 48dp target.
- Request Source (6.6): do not save a bare `"https://"`. Say clearly that the request is stored locally.
- Share one source card between built-in and custom sources (6.7). Move the built-in sources list and the filtering into the ViewModel.
- One constant for the WTR-Lab base URL (6.8), used everywhere.

## 7d WebBrowser and the add-novel flow

- Scrape timing (6.24): wait for `onPageFinished` before running the scrape script, including after Paste or Go.
- Clear previous scrape data at the start of each pass. A failed pass must disable Add (6.25).
- `isScraping` ends in a `finally`, with a timeout, and an earlier pass is cancelled by a new one (6.26).
- WebView safety (6.27): handle `onRenderProcessGone` and `onReceivedError`. Allow only http and https in `shouldOverrideUrlLoading`.
- Address bar and rotation (6.28): do not overwrite text while the user is typing. Preserve history across rotation.
- Add flow (6.29): "Already in your library" with an Open action for duplicates, check `isWtrLab` by host, no main-thread saves, and gate diagnostics behind a debug option (3.17). One shared user-agent constant.
- Move the roughly 250 lines of scrape parsing out of the composable into a testable scraper class (6.30). Handle lazy and paginated chapter lists.
- Delete `ConfirmNovelScreen.kt` (dead code).

## 7e Downloads screen

- Show the active queue: per-chapter queued, downloading, failed, with pause, cancel and retry.
- "100% readable offline" only when it is actually true (6.23). Remove the hardcoded green. Use `Formatter.formatFileSize`. Show feedback after deleting.

## 7f Settings, history, notifications, What's New, profile, stats

- Settings (6.38): split the 2,500-line file into one file per page. Sub-pages go back to where the user came from. Remove unused parameters. Stats is not nested inside Settings.
- Folder picker: one helper that takes the persistable permission and checks `canWrite()`. Abort with a message on failure (1.10).
- History (4.10, 6.39): a real log built from reading sessions (not one row per novel). Use date keys for "Yesterday" (no year-boundary bug). Honor 24h time. The remove button asks for confirmation or offers undo.
- Notifications (2.15): cap the list size, no seeded fake entry, and toggles must really control something.
- What's New (2.21, 4.6): show only when the newest release code is greater than the last seen code. Do not show on a fresh install on top of the storage dialog. Validate duplicate keys, sort defensively, scrollable body, "see all releases" link.
- Profile (4.10, 6.22): "Continue reading" opens the Reader. Format word counts properly (one decimal, millions). Notification badge counts unread only. Remove the duplicate streak and the duplicate Stats entry. Drive the menu rows from a list. No wildcard imports.
- Stats screen (2.10): milestones driven by real data (no always-unlocked badges), configurable daily goal, a meaningful streak bar, no "~0 wpm", and a history chart.
- `file_paths.xml` (5.15): expose only a cache subfolder.
- `backup_rules.xml` and `data_extraction_rules.xml` (5.16): exclude chapters and folder-URI settings, or disable cloud backup.

## 7g Theme and design system

- Buttons (5.1): M3 `Button` and `OutlinedButton` use a full-round shape that ignores `MaterialTheme.shapes`. Set `shape = MaterialTheme.shapes.medium` in `PrimaryCtaButton` and `SecondaryOutlinedButton`, and route every raw Button through them.
- Shapes (5.2): replace hardcoded radii (4/6/8/10/12/14/16dp) with `MaterialTheme.shapes`.
- Color scheme (5.3, 5.4, 5.5):
  - Set all `surfaceContainer*` roles (Lowest to Highest) in every scheme.
  - `secondary` must be opaque.
  - Remove the unreachable `SYSTEM` branch.
  - Accept 6-digit RGB hex (not ARGB) in the custom color field.
  - Document or hide that Dynamic color overrides the accent, AMOLED and Sepia.
- Typography (5.6): add the italic serif display style and stop hardcoding `.sp` values. Raise sizes below 12sp.
- Hardcoded colors (5.7): remove the green values and the scrollbar amber. Use theme tokens.
- Download badge (5.8): the check color comes from `calculateOnColor`, with a meaningful content description.
- Scrollbar (5.9): thin track with a round, draggable thumb, positioned by pixels, hidden when the list fits.
- Cover images (5.10): do not force a decode size. Let Coil measure the layout.
- One `EmptyStateView` signature (5.11).
- Accessibility (5.13): 48dp touch targets, roles for clickable surfaces, selectable semantics for the segmented control.
- Shared nav bar (4.9): one loop over the tabs, no 11sp hardcoded label.
- Strings in `strings.xml`, no wildcard imports, remove unused imports (5.14).

## 7h EPUB parser and scraper scripts

- EPUB (6.40 to 6.44):
  - Stream entries instead of loading the whole zip into memory, and enforce size limits.
  - Use a real HTML parser: decode entities (`&#8217;`, `&rsquo;`, `&hellip;`).
  - Do not drop short paragraphs ("No.", "Ok.").
  - Choose the cover via `properties="cover-image"` or `<meta name="cover">`, and normalize paths (`../`, `%20`).
  - Skip non-chapter spine items (copyright, TOC) and use nav or NCX titles.
  - Count CJK words by characters.
- Scraper JS (6.45 to 6.47):
  - Replace broad selectors like `[class*="ad-"]` (it also matches "read-" and "head-" and can delete chapter text) with specific ones.
  - Detached-clone `innerText` loses line breaks: extract text from the live DOM or walk the nodes, and keep `<br>` breaks.
  - Move the 970-line details script to an asset file and share logic with the Kotlin extractor.
- WTR-Lab extractor and network (3.16, 6.48):
  - Close OkHttp responses on every path.
  - An unknown chapter count must not truncate the list at 200.
  - Fetch the chapter list in one place only.
  - Host check instead of a substring URL match.
  - One base-URL constant.
- Chapter fetch (3.15, remaining parts): do not reject chapters for being short or CJK (use character counts), detect challenge pages before extracting, and add retry with backoff.
