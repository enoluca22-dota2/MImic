# MImic 4.0 — "Ultimate" integration notes

## Projects

| | path | package | notes |
|---|---|---|---|
| MImic (main app) | `C:\Users\Eno\AndroidStudioProjects\MImic` | `com.example.mimic` | kept: ID, signing (debug keystore), all data |
| ENAGELYUCA (source of the downloader) | clone of `github.com/enoluca22-dota2/YTD` at `C:\Users\Eno\AndroidStudioProjects\ENAGELYUCA` (the old `Desktop\YTD-A` working copy no longer exists) | `com.enoluca.ytd` | not modified; its installed app and data are untouched |

Backups before integration: tag `checkpoint-3.3` (31bcfc3), `MImic-backups/mimic-3.3-2026-10-19.bundle`
(all refs), `MImic-backups/mimic-release-signing-debug.keystore`. Work happened on branch
`integration/mimic-ultimate`, then `main`.

## What was merged, and what wasn't

| ENAGELYUCA part | In MImic 4.0 | Why |
|---|---|---|
| core/, data/{analyzer,model,platform,provider,repository}, download/ (engine, service, notifications, progress, ffmpeg MP3, yt-dlp updater) | **ported** to `app/src/full/…/downloader` (package renamed, otherwise the same code) | the working, tested download stack |
| Home/Downloads UI | **rebuilt** as MImic's Downloads tab (same ViewModel logic, MImic's design system) | one visual language |
| updater (update/) | **adapted** into `app/src/main/…/update` | added pinned certificate, channels, redirect allow-list, org.json |
| own player, playback service, library indexer, radio | **not merged** | MImic already has one Media3 service, library and queue; two would conflict (notifications, audio focus) |
| unit tests (95) | **ported** to `app/src/testFull` | same behavior checks |

Two ENAGELYUCA bugs were found and fixed while merging: yt-dlp's self-update could rewrite the
binary while a link was being analyzed ("bad local file header"), and `destroyForcibly()` (API 26)
was called on MImic's API 24 minimum.

## Data and identity

- **MimicDatabase** (Room v6) is unchanged — no migration needed, all user data kept (verified:
  3.3 → 4.0 installed in place on the emulator, `firstInstallTime` unchanged, favorites/playlists/
  downloads/continue-listening present).
- **mimic_downloader.db** (new, v1): the download queue (`downloads`) and `history`, full edition
  only. Separate on purpose: MimicDatabase already has a `downloads` table with a different meaning
  (offline copies of Navidrome/cloud songs). Future changes need a Migration — never destructive.
- **Files**: audio → `Music/MImic`, video → `Movies/MImic` through MediaStore. Downloaded music is
  then an ordinary phone song: same MediaStore id → same MImic song id, so favorites/history/
  playlists work and nothing is duplicated. Existing ENAGELYUCA downloads in `Music/ENAGELYUCA`
  appear in MImic's library automatically (MediaStore); ENAGELYUCA's private history database
  cannot be read by another app and was not imported.
- **Song ids by source**: phone = MediaStore id; Navidrome = `navidrome:<id>`-derived; cloud =
  `onedrive|gdrive:<account>:<item>` (62-bit hash); downloader = MediaStore id (it *is* a phone file).

## One player, one download manager

- Music (including downloaded audio) plays through MImic's single `PlaybackService` (Media3).
- Downloaded video plays in a short-lived ExoPlayer inside a full-screen dialog (a surface is
  needed); MImic pauses the music first. No second service or media notification.
- Media downloads use the ported `DownloadEngine` + `DownloadService` (dataSync foreground service,
  only while jobs run). MImic's older offline-copy downloader for Navidrome/cloud songs stays as is.

## Editions

`full` (default; downloader + youtubedl-android, GPL-3.0) and `standard` (no downloader, no GPL
code, no yt-dlp). Same application ID. The downloader is not suitable for Google Play listing.

## Verification status (2026-10-19)

| Feature | Status |
|---|---|
| Downloader: share link → formats → MP3 / MP4 → library → MImic player / in-app video | **verified** on a 4 KB-page API 36 emulator (Blender's CC-BY "Big Buck Bunny") |
| Downloader on 16 KB-page devices | analysis/transfer work; ffmpeg (MP3/M4A fix-up/merge) can't load — youtubedl-android's ffmpeg is 4 KB-aligned; clear error shown |
| 3.3 → 4.0 in-place update, data kept | **verified** (emulator) |
| Icon in launcher | **verified** (emulator) |
| Google Drive real sign-in | **NOT verified** — needs your phone + your Google Cloud OAuth client |
| OneDrive real sign-in | **NOT verified** — needs your Entra app registration |
| In-app update end-to-end | see the release notes / final report of this session |
| Physical phone | **not tested** (no device connected) |
