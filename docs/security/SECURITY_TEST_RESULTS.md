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
| GitHub workflows `security.yml`, `codeql.yml` | NOT VERIFIED yet — they first run when pushed (results under Actions / Security) |

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
