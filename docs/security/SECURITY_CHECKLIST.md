# MImic — 40-control security checklist

Status values: PASS, FAIL, NOT APPLICABLE, NOT VERIFIED. "Baseline" = before this work
(4.7.0-beta.3); "Now" = 4.7.0-beta.4. Machine-readable copy: `docs/security/controls.json`
(regenerate this table with `python scripts/security/dashboard.py --markdown`).

| # | Area | Control | Baseline | Now | Severity | Changed in |
|---|---|---|---|---|---|---|
| 1 | A | Embedded passwords, API keys, tokens, private URLs in current source | PASS | **PASS** | - | - |
| 2 | A | Secrets in Git history | PASS | **PASS** | - | - |
| 3 | A | .gitignore covers sensitive files | PASS | **PASS** | - | 4.7.0-beta.4 |
| 4 | A | Release keystore and passwords excluded from Git | PASS | **PASS** | - | - |
| 5 | A | GitHub Actions secret exposure | PASS | **PASS** | - | - |
| 6 | A | Credential exposure in docs, screenshots, logs, release assets | FAIL | **FAIL** | Low | 4.7.0-beta.4 |
| 7 | A | Dependency licenses and attribution | PASS | **PASS** | - | - |
| 8 | A | Documented secret-rotation procedure | PASS | **PASS** | - | 4.7.0-beta.4 |
| 9 | B | Dangerous / unnecessary permissions | PASS | **PASS** | - | - |
| 10 | B | Exported activities, services, receivers, providers | PASS | **PASS** | - | 4.7.0-beta.4 |
| 11 | B | Deep links, intents and PendingIntents | PASS | **PASS** | - | - |
| 12 | B | WebView JavaScript, navigation and file access | PASS | **PASS** | - | 4.7.0-beta.4 |
| 13 | B | Production debugging and development settings | PASS | **PASS** | - | - |
| 14 | B | Backup and data-extraction policy | FAIL | **PASS** | Medium | 4.7.0-beta.4 |
| 15 | B | Clipboard and sensitive screen exposure | PASS | **PASS** | - | - |
| 16 | B | Native libraries and dynamically loaded code | FAIL | **FAIL** | Medium | - |
| 17 | B | ProGuard / R8 release hardening | NOT APPLICABLE | **NOT APPLICABLE** | - | - |
| 18 | B | User-controlled input and content URIs | PASS | **PASS** | - | 4.7.0-beta.4 |
| 19 | C | Android Keystore encryption | PASS | **PASS** | - | - |
| 20 | C | Navidrome credentials protected at rest | PASS | **PASS** | - | - |
| 21 | C | Credentials kept out of logs and analytics | PASS | **PASS** | - | - |
| 22 | C | Token and session handling | PASS | **PASS** | - | - |
| 23 | C | TLS certificate and hostname validation | PASS | **PASS** | - | 4.7.0-beta.4 |
| 24 | C | HTTP exceptions and network security configuration | FAIL | **PASS** | Low | 4.7.0-beta.4 |
| 25 | C | Credentials in background services and notifications | PASS | **PASS** | - | - |
| 26 | C | Disconnect, logout and credential clearing | PASS | **PASS** | - | - |
| 27 | D | Remote download URLs and redirect destinations | PASS | **PASS** | - | - |
| 28 | D | Path traversal and unsafe filenames | PASS | **PASS** | - | 4.7.0-beta.4 |
| 29 | D | Download size, content type and integrity | FAIL | **PASS** | Low | 4.7.0-beta.4 |
| 30 | D | Temporary files and offline caches | PASS | **PASS** | - | - |
| 31 | D | Media metadata and artwork parsing | PASS | **PASS** | - | - |
| 32 | D | GitHub release download integrity | PASS | **PASS** | - | - |
| 33 | D | APK signing identity verified before installation | PASS | **PASS** | - | 4.7.0-beta.4 |
| 34 | D | Update downgrade and package-substitution attacks | PASS | **PASS** | - | - |
| 35 | E | Navidrome/Subsonic authentication and server responses | PASS | **PASS** | - | - |
| 36 | E | YouTube and other online integrations | PASS | **PASS** | - | - |
| 37 | E | Vulnerable and outdated dependencies | FAIL | **FAIL** | Low | 4.7.0-beta.4 |
| 38 | E | GitHub Actions and release permissions | PASS | **PASS** | - | 4.7.0-beta.4 |
| 39 | E | Privacy-safe error reporting and suspicious-event diagnostics | PASS | **PASS** | - | - |
| 40 | E | Automated security regression tests | PASS | **PASS** | - | 4.7.0-beta.4 |


## A. Repository and secrets

### 1. Embedded passwords, API keys, tokens, private URLs in current source — **PASS**
- Evidence: Gitleaks 8.30.1 working-tree scan: 2 hits, both YouTube's public web-client key and BotGuard request key in PoTokenWebView.kt (public identifiers from YouTube's own web player / upstream NewPipe). 0 after the reviewed allowlist (.gitleaks.toml). Manual grep: no passwords, tokens or keystore values in source.
- Remediation / note: Keep the .gitleaks.toml allowlist narrow (file + line pattern).

### 2. Secrets in Git history — **PASS**
- Evidence: gitleaks git --log-opts=--all over all 63 commits on every branch: only the same 2 public YouTube identifiers. git log --name-only: keystores, keystore.properties, firebase.properties, cloud.properties, google-services.json and server/.env were never committed.
- Remediation / note: None needed. History is not rewritten.

### 3. .gitignore covers sensitive files — **PASS**
- Evidence: .gitignore excludes keystore.properties, *.jks, *.keystore, firebase.properties, google-services.json, cloud.properties, server/.env, server/navi-creds.txt, server/navidrome-data, local.properties, dist/, *.apk/*.aab. Added *.p12 / *.pfx.
- Changed in: 4.7.0-beta.4

### 4. Release keystore and passwords excluded from Git — **PASS**
- Evidence: Signing key is ~/.android/debug.keystore outside the repo; passwords only in the gitignored keystore.properties; neither ever appears in history (control 2). CI check 'No signing material tracked in git' added.

### 5. GitHub Actions secret exposure — **PASS**
- Evidence: Workflows use no secrets; default token read-only (repository setting + 'permissions: contents: read'); checkout uses persist-credentials: false; no pull_request_target; signing and publishing never run in Actions.

### 6. Credential exposure in docs, screenshots, logs, release assets — **FAIL**
- Evidence: docs/REMOTE_ACCESS.md published the Tailscale tailnet name, MagicDNS host name, account prefix and Tailscale IP. Redacted to placeholders in the current tree. No screenshots are tracked (only icon images). Release assets are APKs, SHA256SUMS.txt and notes from CHANGELOG (no private data). The old values remain in earlier commits of the public repository.
- Remediation / note: Fixed in the tree. Owner decision: leave history as is (tailnet addresses are reachable only by your own Tailscale devices) or rewrite public history.
- Changed in: 4.7.0-beta.4

### 7. Dependency licenses and attribution — **PASS**
- Evidence: THIRD_PARTY_NOTICES.md and docs/DEPENDENCIES.md list licenses; BitChord-derived files keep their GPL headers. This change adds no new shipped library (version bumps only). Gitleaks, OSV and CodeQL run only in CI/locally and are not shipped.

### 8. Documented secret-rotation procedure — **PASS**
- Evidence: New docs/security/SECRET_ROTATION.md: Navidrome password, Firebase config, GitHub token, party server, YouTube session; the signing key is explicitly NOT rotated without a planned migration.
- Changed in: 4.7.0-beta.4


## B. Android application security

### 9. Dangerous / unnecessary permissions — **PASS**
- Evidence: READ_MEDIA_AUDIO (READ_EXTERNAL_STORAGE up to API 32), FOREGROUND_SERVICE (+MEDIA_PLAYBACK; DATA_SYNC in full), POST_NOTIFICATIONS, INTERNET, ACCESS_NETWORK_STATE, REQUEST_INSTALL_PACKAGES (updater; the user confirms every install), RECEIVE_BOOT_COMPLETED (full: resume downloads), WRITE_EXTERNAL_STORAGE up to API 28. No location, contacts, camera, microphone, SMS or accessibility.

### 10. Exported activities, services, receivers, providers — **PASS**
- Evidence: Exported: MainActivity (launcher, invite and share links) and PlaybackService (Media3 session for Bluetooth/Auto/system controls). Both FileProviders are exported=false. Library components are exported only behind system permissions, plus 3 reviewed Firebase/Play-services entries. New scripts/security/check_manifest.py fails on any unreviewed exported component in the merged release manifests; SecurityRegressionTest pins the app's own list.
- Changed in: 4.7.0-beta.4

### 11. Deep links, intents and PendingIntents — **PASS**
- Evidence: All PendingIntents are FLAG_IMMUTABLE except the PackageInstaller status callback, which must be mutable and is explicit (component + package; receiver not exported). mimic://party invites only show a card and joining needs a tap; hostile server values are dropped (tests). YouTube/TikTok VIEW filters are never the default handler; shared text must contain a URL.

### 12. WebView JavaScript, navigation and file access — **PASS**
- Evidence: PoToken WebView: blockNetworkLoads=true; its JavaScript interface is reachable only by its own local page. YouTube Music sign-in WebView: JavaScript is required by Google sign-in, no JavaScript interface; file and content access now explicitly off. No onReceivedSslError override.
- Changed in: 4.7.0-beta.4

### 13. Production debugging and development settings — **PASS**
- Evidence: Release APKs are not debuggable and not testOnly (aapt2 badging, checked by the release gate); the online DebugLog sink is never installed; Log.* lines carry status codes and class names only.

### 14. Backup and data-extraction policy — **PASS** (baseline FAIL)
- Evidence: allowBackup=true with empty rules sent everything to Google cloud backup, including the YouTube Music session (EncryptedSharedPreferences, or plain prefs on its fallback path), WebView cookies, the Listen Together token and the Navidrome store. Fixed: backup_rules.xml (Android 6-11) and data_extraction_rules.xml (cloud backup + device transfer, Android 12+) exclude all sign-in stores; settings, library, playlists and themes are still backed up.
- Remediation / note: Effect: after restoring onto a NEW phone the user signs in again (the Navidrome password could never be decrypted there anyway).
- Changed in: 4.7.0-beta.4

### 15. Clipboard and sensitive screen exposure — **PASS**
- Evidence: The clipboard receives only public links (music.youtube.com/watch, party invite without token) and diagnostics that pass DiagnosticText.clean + OnlineDiagnostics.redact. Password fields are masked. FLAG_SECURE is not used: no secret is ever displayed.

### 16. Native libraries and dynamically loaded code — **FAIL**
- Evidence: Native python/ffmpeg come from youtubedl-android inside the signed APK. yt-dlp (Python code) self-updates from GitHub releases (youtubedl-android YoutubeDLUpdater) over HTTPS with system CAs, but without signature verification. Plain HTTP to github.com/githubusercontent.com is now impossible (network security config).
- Remediation / note: Accepted risk unless you decide otherwise: verifying yt-dlp's GPG signature in-app is a larger change; turning auto-update off would break downloads as YouTube changes.

### 17. ProGuard / R8 release hardening — **NOT APPLICABLE**
- Evidence: R8 is off on purpose (reflection-heavy NewPipe/Rhino/yt-dlp code). MImic is open source (public GPL repository), so obfuscation would hide nothing; it is not a security control for this app.

### 18. User-controlled input and content URIs — **PASS**
- Evidence: Server and party URLs are normalised (http/https only, no query/fragment); invite and shared links are parsed defensively; content URIs come only from the app's own FileProviders with per-URI read grants; new tests cover hostile links.
- Changed in: 4.7.0-beta.4


## C. Authentication and encryption

### 19. Android Keystore encryption — **PASS**
- Evidence: CredentialCipher: AES-256-GCM, non-exportable Android Keystore key, random IV, failure-tolerant. YouTube session: EncryptedSharedPreferences (Keystore-wrapped AES-256-SIV/GCM) that repairs a stale keyset.

### 20. Navidrome credentials protected at rest — **PASS**
- Evidence: Only ciphertext is stored (password_enc); a legacy plaintext password is migrated on first run; plaintext is written only if the Keystore is unusable (rare OEM fallback, documented). Now excluded from backups.

### 21. Credentials kept out of logs and analytics — **PASS**
- Evidence: No analytics or crash SDK. Every Log.* call reviewed: no passwords, tokens, cookies or stream URLs. Diagnostic exports are redacted (OnlineDiagnostics.redact, DiagnosticText.clean; unit-tested).

### 22. Token and session handling — **PASS**
- Evidence: Subsonic salted-token auth (t = md5(password+salt), fresh salt per request); the password itself is never sent. Party token in app-private prefs, never logged, now excluded from backup. YouTube cookies encrypted; sign-out clears them.
- Remediation / note: On a plain-HTTP network a sniffer could replay a t/s pair: use HTTPS (Tailscale Serve) for remote access.

### 23. TLS certificate and hostname validation — **PASS**
- Evidence: No custom TrustManager, HostnameVerifier, sslSocketFactory or onReceivedSslError anywhere. The network security config trusts system CAs only (user-installed CAs cannot intercept).
- Changed in: 4.7.0-beta.4

### 24. HTTP exceptions and network security configuration — **PASS** (baseline FAIL)
- Evidence: Before: usesCleartextTraffic=true for everything. Now: network_security_config.xml keeps HTTP for home Navidrome servers and HTTP radio streams (required), but makes github.com, api.github.com, *.githubusercontent.com, *.googleapis.com, accounts.google.com, music/www.youtube.com and lrclib.net HTTPS-only. Lint's InsecureBaseConfiguration warning is acknowledged (by design).
- Changed in: 4.7.0-beta.4

### 25. Credentials in background services and notifications — **PASS**
- Evidence: Notifications show song titles and progress only; WorkManager inputs carry video ids, never tokens; Cast uses a LAN server with random 128-bit per-song tokens so the Navidrome token never reaches the TV.

### 26. Disconnect, logout and credential clearing — **PASS**
- Evidence: Full sign-out clears the whole server store; 'Switch account' removes user and password but keeps the address; YouTube Music sign-out clears Google cookies; leaving a party removes its token.


## D. Music, downloads and updates

### 27. Remote download URLs and redirect destinations — **PASS**
- Evidence: Updater: HTTPS + GitHub host allowlist enforced on every redirect hop (network interceptor). Navidrome downloads go only to the configured server. Media links (yt-dlp) are user-initiated by design.

### 28. Path traversal and unsafe filenames — **PASS**
- Evidence: Navidrome download names: only [A-Za-z0-9._-], never '.', '..' or hidden, at most 120 characters (new DownloadRepository.safeFileName + tests; ordinary ids unchanged). Online downloads: yt_<videoId> restricted the same way. Downloader titles: FileNaming.sanitize (unit-tested).
- Changed in: 4.7.0-beta.4

### 29. Download size, content type and integrity — **PASS** (baseline FAIL)
- Evidence: Updater: new size cap (the release-stated size, at most 400 MB), ZIP magic check, SHA-256 against SHA256SUMS.txt, parseable APK. Media downloads are user-initiated and parsed by Media3/tagging (no size cap; a bad file just fails to play).
- Changed in: 4.7.0-beta.4

### 30. Temporary files and offline caches — **PASS**
- Evidence: All in app-private storage (filesDir/cacheDir); .part files deleted on failure or cancel; update files cleaned at start-up; sharing only through FileProviders limited to specific folders.

### 31. Media metadata and artwork parsing — **PASS**
- Evidence: Parsing by Android/Media3/Coil decoders; metadata shown as plain Compose text (no HTML rendering); malformed server JSON handled by tested fail-safe parsers. Not fuzzed.

### 32. GitHub release download integrity — **PASS**
- Evidence: HTTPS + host allowlist; SHA256SUMS.txt is treated as a corruption check only (same source as the APK); authenticity comes from the pinned signing certificate. The release script re-downloads and verifies every published asset.

### 33. APK signing identity verified before installation — **PASS**
- Evidence: The APK must be signed by exactly the pinned certificate C03C5107...087D AND match the installed app's signer; the checks now live in UpdateVerification and are covered by unit tests (wrong key, extra signer, no signer, malformed pin, installed-key mismatch).
- Changed in: 4.7.0-beta.4

### 34. Update downgrade and package-substitution attacks — **PASS**
- Evidence: Package must equal com.example.mimic and versionCode must be strictly higher (equal = reinstall, refused); the release name's versionCode is also compared before download. Unit-tested. Android's installer always asks the user.


## E. Network, supply chain and monitoring

### 35. Navidrome/Subsonic authentication and server responses — **PASS**
- Evidence: Salted-token auth; responses parsed defensively (status checks, null-safe JSON); clients have connect/read timeouts; certificate validation is the system's.

### 36. YouTube and other online integrations — **PASS**
- Evidence: HTTPS-only (now enforced for youtube/googleapis); session encrypted; PoToken WebView offline; resolver logs redacted before export; YouTube region never spoofed.

### 37. Vulnerable and outdated dependencies — **FAIL**
- Evidence: OSV scan of 345 resolved release dependencies: 32 advisories found. 9 fixed by raising jsoup to 1.23.2, Guava to 33.7.2-android, protobuf-javalite to 3.25.8 and Jackson from 2.11.1 to 2.13.5. 23 remain, all inside youtubedl-android (full edition): commons-compress 1.12, commons-io 2.5, Jackson 2.13.5. Reviewed as not reachable (bundled archives only; no polymorphic typing, async parser, etc.) and listed with reasons in scripts/security/osv-allowlist.txt; the gate blocks any new unreviewed HIGH/CRITICAL.
- Remediation / note: Owner decision: the remaining fixes need Android 8+ (minSdk 26) or replacing youtubedl-android.
- Changed in: 4.7.0-beta.4

### 38. GitHub Actions and release permissions — **PASS**
- Evidence: All actions pinned to reviewed commit SHAs (Dependabot proposes updates), read-only token, persist-credentials false, Gradle wrapper validation, security checks separate from publishing; publishing is local with the maintainer's key only. Branch protection on main blocks force-push and deletion.
- Changed in: 4.7.0-beta.4

### 39. Privacy-safe error reporting and suspicious-event diagnostics — **PASS**
- Evidence: No telemetry by design. Download Diagnostics and Online diagnostics are local and redacted; rejected updates (untrusted host, checksum, signature, downgrade) are shown to the user by name and logged with public values only.

### 40. Automated security regression tests — **PASS**
- Evidence: New SecurityRegressionTest (14 tests, both editions), manifest checker, Gitleaks/OSV gate in scripts/release.sh, GitHub workflows security.yml (PR/main/weekly) and codeql.yml.
- Changed in: 4.7.0-beta.4
