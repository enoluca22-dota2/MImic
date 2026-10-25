# MImic — instructions for Claude Code sessions

MImic is the owner's personal Android music player (package `com.example.mimic`). It plays phone
music, a private Navidrome/OpenSubsonic server (reached over Tailscale), internet radio, and
(full edition, 4.4+) YouTube Music / YouTube online music using code ported from BitChord.

## Before changing anything
1. Read `docs/MImic-Development-Report.txt` (complete system description and current status).
2. Run `git status`, `git log --oneline -10`, `git branch -a`; never discard uncommitted work.
3. Check `CHANGELOG.md` for the current version, and `THIRD_PARTY_NOTICES.md` for licensing.

## Permanent rules
1. Inspect the repository before changing anything; continue from the real current state.
2. Preserve every working feature (Navidrome, radio, Theme Studio, Dynamic player, downloads,
   Smart Collections, backup, updater). Fix, don't replace.
3. Never change the application id `com.example.mimic` or the signing identity
   (certificate SHA-256 C03C5107…087D, the laptop's `~/.android/debug.keystore`, configured in the
   gitignored `keystore.properties`). Never generate a new key.
4. Preserve Navidrome + Tailscale integration; never modify the server's data.
5. No plugin marketplace / user-installable provider architecture; providers stay internal.
6. Google Drive and OneDrive must not return as music sources (removed in 4.1). Firebase is used
   only for the optional MImic account + sync.
7. Licensing: MImic is GPL-3.0. BitChord-derived files carry a header naming their origin; keep
   them, and list new dependencies with their licenses in `THIRD_PARTY_NOTICES.md` /
   `docs/DEPENDENCIES.md`.
8. Protect credentials: never commit keystores, `keystore.properties`, `firebase.properties`,
   `cloud.properties`, `server/.env`, cookies or tokens; never log or export them (diagnostics go
   through `DiagnosticText.clean` + `OnlineDiagnostics.redact`).
9. One playback service (`player/PlaybackService.kt`, Media3) and one download store per source
   (Navidrome: `DownloadRepository`; online: `OnlineDownloadWorker` → `DownloadRepository.recordOnline`;
   links: the full-edition `downloader/`). Don't add competing ones.
10. Database migrations are additive only (`MimicDatabase.ALL_MIGRATIONS`); add a migration test.
11. Run tests: `gradlew :app:testFullDebugUnitTest :app:testStandardDebugUnitTest :app:lintFullDebug`
    (from Git Bash use `cmd //c ".\\gradlew.bat …"` with JAVA_HOME = Android Studio's `jbr`).
    Test on the emulator `YTD_Test_4K` (4 KB pages; the Pixel_10a AVD has 16 KB pages and can't run
    ffmpeg). Install over the existing app with `adb install -r` — never uninstall or clear data.
12. Every installable release: bump `versionName` in `app/build.gradle.kts` (versionCode is derived),
    add a `CHANGELOG.md` section, run `scripts/release.sh` (dry run) then `scripts/release.sh --publish`.
13. Sign locally only (the release script does it and verifies the certificate).
14. Commit and push completed work (repo `enoluca22-dota2/MImic`, currently PUBLIC by the owner's
    choice because the in-app updater downloads release assets anonymously; don't change
    visibility without asking).
15. The GitHub Release is what the in-app updater reads; after publishing, verify the update is
    offered and installs over the previous version (emulator).
16. Copy the signed APK to the Desktop folder `MImic-Releases\MImic-v<version>-signed.apk` and check
    its SHA-256 equals the GitHub asset.
17. Update `docs/MImic-Development-Report.txt` and copy it to the Desktop as
    `MImic-Development-Report.txt` after every session.
18. Report blockers and untested features plainly (PASS / FAIL / BLOCKED / NOT TESTED). Never claim
    a physical-device or account test that didn't happen.

## Handy facts
- Editions: `full` (default; yt-dlp downloader + online music, GPL code) and `standard` (no
  downloader / online music). Same application id.
- Emulator text input: Gboard's stylus tutorial can swallow `adb shell input text`; run
  `adb shell settings put secure stylus_handwriting_enabled 0` first.
- YouTube Music's song catalogue answers "No results" from Albania; MImic then falls back to
  YouTube search (`online/YouTubeSearch.kt`). Do not spoof the region.
