# Implementation Plan: Settings Backend Overhaul (Profile Design) [COMPLETED]

## Context & Objectives
Overhaul the settings backend to support the new Profile design while reusing existing settings without duplicates, maintaining backward compatibility, and keeping the UI/Gradle unchanged.

## Adjustments Implemented
1. **PreferencesManager**: Untouched.
2. **BackupManager**: Compile fix only. Export writes `autoDownloadAhead`; import reads `autoDownloadAhead` and falls back to legacy `autoDownloadNext` boolean (`true -> 3, false -> 0`). Temporary exporter/importer does not include new groups.
3. **DownloadSettings**: Replaced `autoDownloadNext` with `autoDownloadAhead: Int` (allowed 0, 1, 3, 5, 10; default 3) and `deleteAfterReading: Boolean = false`. No compatibility getter. Callers updated.
4. **SettingsRepository Updates**: Suspend update functions added for the new groups only (`updateLibrarySettings`, `updateCultivationSettings`, `updateBackupSettings`, `updateProfileSettings`). No Sync variants added.
5. **Removed Legacy Migration**: `migrateFromLegacyPrefs` and its legacy SharedPreferences mapping deleted from `SettingsRepository`.
6. **LibrarySettings**: Holds `viewMode` (`GRID`, `LIST`; replaces `isLibraryGridView`) and `sort` (`RECENTLY_READ`, `TITLE`, `PROGRESS`, `TOTAL_CHAPTERS`; default `RECENTLY_READ`), alongside `updateOnLaunch`, `showUnreadBadges`, and `defaultShelf`.
7. **Single-Place Coercion**: `sanitizeSettings` in `SettingsRepository` coerces:
   - `autoDownloadAhead` to nearest of `[0, 1, 3, 5, 10]`
   - `reminderMinutesOfDay` to `0..1439`
   - `dailyGoalMinutes` to `5..120`
   Executed on initial load and every mutation.
8. **Reset Preservation**: `resetAllSettings` and `resetAllSettingsSync` preserve:
   - `downloads.customDownloadLocationUri` & `downloads.customDownloadLocationDisplayName`
   - `profile.displayName`
   - `backup.lastBackupAtMillis`
9. **Unit Tests**: Updated `SettingsRepositoryTest` verifying defaults, round-trip, unknown keys, legacy JSON with `autoDownloadNext`, reset preservation, and coercion.
