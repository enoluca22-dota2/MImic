# MImic — security test results (4.7.0-beta.4, 2026-11-09)

Only tests that actually ran are marked PASS. Nothing here was run on a physical phone.

## Automated

| Test | Result |
|---|---|
| Unit tests, full edition (`testFullDebugUnitTest`) | **PASS** — 315 tests, 3 skipped, 0 failures (was 301) |
| Unit tests, standard edition (`testStandardDebugUnitTest`) | **PASS** — 197 tests, 1 skipped, 0 failures (was 183) |
| of which `SecurityRegressionTest` | **PASS** — 14/14 in each edition |
| Android Lint (`lintFullDebug`, `lintFullRelease` in the release dry run) | **PASS** — 0 errors (warning `InsecureBaseConfiguration` is the intended HTTP-for-home-servers rule) |
| Release dry run (`scripts/release.sh`) | **PASS** — tests, lint, signed build, apksigner certificate = pinned, package/versionCode/versionName for all 5 APKs |
| Security gate — Gitleaks (63 commits, all branches + working tree) | **PASS** — 0 leaks after the reviewed allowlist (2 public YouTube identifiers) |
| Security gate — OSV (345 resolved release dependencies) | **PASS** — 23 advisories, all reviewed (allowlist), 0 blocking; was 32 advisories / 19 HIGH before the upgrades |
| Security gate — merged release manifests (full + standard) | **PASS** — no debuggable/testOnly, network config present, only reviewed exported components |
| Security gate — APK signers | **PASS** — every APK signed only by C03C5107…4F88087D, package `com.example.mimic`, not debuggable |
| CycloneDX SBOM | generated (`build/security/sbom.cdx.json`) |
| GitHub workflows on `main` @ b55236f: Security, CI, CodeQL | **PASS** — all three succeeded; CodeQL raised 16 alerts, triaged in SECURITY_REPORT.md |

## Emulator (YTD_Test_4K, reported as emulator-5554; signed release x86_64 APK)

| Test | Result | Evidence |
|---|---|---|
| Upgrade install over 4.7.0-beta.3 (`adb install -r`) | **PASS** | versionCode 400700036 → 400700037; firstInstallTime 2026-10-26 unchanged; 35 phone songs and the paused track still there |
| Application launch | **PASS** | Home renders; no crash in logcat |
| Music playback | **PASS** | media session PLAYING, position advancing |
| Background playback | **PASS** | still PLAYING after HOME; foreground media notification present |
| Notification / media-button controls (as Bluetooth sends them) | **PASS** | MEDIA_PAUSE → PAUSED, MEDIA_PLAY → PLAYING (queue had one song, so "next" was not verifiable) |
| Theme switching | **PASS** | Classic Music Dark → Midnight Blue → Classic Music Dark; survives force-stop + restart |
| Update check (GitHub over the new HTTPS-only rule) | **PASS** | answered "MImic is up to date" (beta.3 was the newest release then) |
| Rejection of a tampered APK | **PASS** | 1 byte flipped: apksigner "DOES NOT VERIFY"; Android: INSTALL_PARSE_FAILED_NO_CERTIFICATES (v2 digest mismatch); installed app unchanged |
| Rejection of a downgrade | **PASS** | real signed beta.3 APK: INSTALL_FAILED_VERSION_DOWNGRADE |
| Malformed / malicious links | **PASS** | `mimic://party/../../x?server=javascript:…`, `…?server=file:///…`, junk SEND text, `youtube.com/watch?v=%00%2e%2e%2f`: app stays up (same pid), no crash in logcat |
| Private components from outside the app | **PASS** | both FileProviders: SecurityException "not exported"; DownloadService: "not exported"; a forged installer broadcast to UpdateInstallReceiver had no effect (App Updates showed no failure) |
| Rejection of an APK signed with a different key | NOT TESTED on device — creating a second signing key was ruled out; covered by unit tests (`an_apk_signed_with_another_key_is_rejected`, `an_installed_copy_with_a_different_key_is_reported_not_overwritten`) |
| Fresh installation | NOT TESTED — would need uninstalling MImic (forbidden: it erases data) or a clean emulator |
| Search, downloads, offline playback | NOT TESTED — the emulator was stopped by the OS for low memory before these ran |
| Navidrome connection, reconnection, credential persistence | BLOCKED — the emulator was not signed in to Navidrome, the local test-credential file `server/navi-creds.txt` does not exist and passwords are not requested in chat; Navidrome (Docker) was not running |
| In-app update 4.7.0-beta.3 → 4.7.0-beta.4 on an emulator | NOT TESTED — no emulator running after the low-memory stop |
| Physical Samsung / Xiaomi / Redmi phones | NOT TESTED |

## Session 2026-11-10 (published 4.7.0-beta.4; emulators started one at a time, reduced RAM)

| Test | Result | Evidence |
|---|---|---|
| **In-app update 4.7.0-beta.3 → 4.7.0-beta.4** (Pixel_10a, existing updater, Beta channel) | **PASS** | offered "MImic v4.7.0-beta.4", x86_64 asset 81 MB; log "Update v4.7.0-beta.4 verified (sha256 + package + versionCode + ABI + pinned certificate)"; Android "Update this app?" → Update; versionCode 400700037; firstInstallTime 2026-10-17 unchanged |
| Data kept after the update | **PASS** | before = after: 5 songs shown · 1 hidden; folders ArtTest 3, LyricsTest 1, YTD 2; offline downloads 132.3 MB; theme MImic Liquid Glass; "5 on this phone · 5 downloaded" |
| Offline playback of existing downloads (Pixel_10a, airplane mode) | **PASS** | "First Light" PLAYING, position advancing, airplane_mode_on=1 |
| Published beta.4 x86_64 APK on YTD_Test_4K | **PASS** | SHA-256 4fba6b3f… = SHA256SUMS.txt; `adb install -r` keeps data |
| Online search (YouTube Music → YouTube fallback) | **PASS** | "Daft Punk Around the World": artist + songs "from YouTube" (catalogue unavailable from Albania, as documented) |
| Online streaming | **PASS** | "Around The World (Official Music Video Remastered)" PLAYING |
| YouTube Music in-app playlist search | **BLOCKED** (region) | "Nothing on YouTube Music matches" — YouTube Music's catalogue answers no results from Albania; region not spoofed by policy |
| YouTube playlist link, 15 songs (`youtube.com/playlist?list=PLcrOcEdE8R92CQCXk5ocYQbZ4E4uY1m_O`, Gorillaz – Demon Days) | **PASS** | "15 tracks · 15 of 15 selected · 15 songs loaded" |
| Selecting 13 of 15 | **PASS** | unticked 04 "O Green World" and 08 "Every Planet We Reach" → "13 of 15 selected" |
| Actual downloads (MP3) | **PASS** | 13 queued → 13 MP3 files in Music/MImic in 140 s (1.2–6.5 MB each); the 2 unticked songs absent; downloader Completed 33 → 46, Failed 0 |
| Offline playback of a new download (airplane mode) | **PASS** | "Gorillaz - Feel Good Inc." found in My library, PLAYING offline |
| YouTube Music playlist link (`music.youtube.com/playlist?list=PLIQqWhTQWxVs-iB4fIPYtvaO3o4J0ChQB`) | **PASS** | "15 tracks · 15 of 15 selected · 15 songs loaded" (analysis only, not downloaded) |
| Player layout (full Now Playing screen) | **PASS** | artwork, title/artist, favourite, seek bar with times, shuffle/previous/pause/next/repeat, lyrics/speed/sleep/queue — screenshot checked |
| Player controls | **PASS** | on-screen pause → PAUSED, play → PLAYING, seek to ~70 % → 187 s, previous → 0:00 (second tap; first tap landed during the seek animation) |
| `main` (540b10d, PoToken WebView change) signed build, online playback | **PASS** | "Clint Eastwood" PLAYING, no crash; the PoToken WebView itself was not started in that run (NOT VERIFIED on device) |
| Navidrome sign-in, streaming, reconnection, credential persistence | **BLOCKED** | needs your Navidrome password: run `! powershell -ExecutionPolicy Bypass -File server\set-creds.ps1` locally and start Docker Desktop |
| Physical phones | NOT TESTED | |
