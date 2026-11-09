# MImic — security baseline (2026-11-09)

Recorded **before** any security change, from the real project state. No assumption that the app
was insecure; existing protections were identified first so nothing was duplicated.

## Project facts

| Item | Value |
|---|---|
| Location | `C:\Users\Eno\AndroidStudioProjects\MImic` (Git, remote `origin` = github.com/enoluca22-dota2/MImic, **public**) |
| Branch / last commit | `main` @ `ff8a2c4` (4.7.0-beta.3 report); working tree: only IDE files (`.idea/*`, `.claude/`) modified/untracked — left untouched |
| Checkpoint | tag `checkpoint/pre-security-hardening` → `ff8a2c4`; work on branch `security/hardening` |
| Application id | `com.example.mimic` (both editions: `full`, `standard`) |
| Version | 4.7.0-beta.3 / versionCode 400700036 (installed and published) |
| SDK | minSdk 24, targetSdk 37, compileSdk (AGP default); Kotlin + Jetpack Compose |
| Signing | release signed locally with `~/.android/debug.keystore` via gitignored `keystore.properties`; certificate SHA-256 `C03C5107…4F88087D`, pinned in the app (`RELEASE_CERT_SHA256`) |
| Release flow | `scripts/release.sh` (tests → lint → signed build → apksigner/aapt2 checks → SHA256SUMS → `gh release`); the in-app updater reads GitHub Releases |
| CI | `.github/workflows/ci.yml`: unit tests (both editions), lint, debug build; read-only token; actions referenced by tag (not pinned) |
| GitHub settings | secret scanning ON, push protection ON, Dependabot alerts OFF, Dependabot security updates OFF, private vulnerability reporting OFF, branch protection on `main` (no force-push / deletion), default workflow token read-only |
| Build/tests at baseline | full 301 tests (3 skipped), standard 183 (1 skipped), 0 failures; lint 0 errors (4.7.0-beta.3 release run) |
| Emulators | none running at the start (both stopped earlier by the OS for low memory); YTD_Test_4K = feature tests, Pixel_10a (emulator-5554) = updater tests |

## Security already in place (kept, not duplicated)

- Navidrome password: AES-256-GCM with a non-exportable Android Keystore key; legacy plaintext migrated (`CredentialCipher`, `ServerCredentialsRepository`).
- YouTube Music session: EncryptedSharedPreferences with self-repair of stale keysets.
- Subsonic salted-token authentication (password never sent).
- Updater: HTTPS + GitHub host allowlist on every redirect; ZIP magic; SHA-256 vs `SHA256SUMS.txt`; package, higher versionCode, minSdk, ABI; **pinned release certificate + installed signer**; Android installer always asks the user.
- Logs carry status codes only; diagnostics redacted (`DiagnosticText.clean`, `OnlineDiagnostics.redact`); no analytics/telemetry SDK.
- Only `MainActivity` and the Media3 `PlaybackService` exported; FileProviders private with per-URI grants; PendingIntents immutable (installer callback explicit).
- No custom TrustManager / HostnameVerifier / `onReceivedSslError` anywhere.
- PoToken WebView offline (`blockNetworkLoads`); download names sanitised.
- Secrets gitignored and never committed (verified with Gitleaks over the full history).

## Findings and priorities

| Priority | Finding | Control |
|---|---|---|
| 1 (Medium) | `allowBackup=true` with empty rules: YouTube session, WebView cookies, party token and Navidrome store went to Google cloud backup | 14 |
| 2 (Medium) | yt-dlp code self-updates from GitHub without signature verification | 16 |
| 3 (High→Low after review) | 32 dependency advisories (19 HIGH) in jsoup, Guava, protobuf-javalite, and youtubedl-android's Jackson / commons-compress / commons-io | 37 |
| 4 (Low) | Cleartext allowed for every host, including GitHub/Google/LRCLIB | 24 |
| 5 (Low) | Public doc `docs/REMOTE_ACCESS.md` named the Tailscale tailnet, host, account prefix and IP | 6 |
| 6 (Low) | Update download had no upper size limit | 29 |
| 7 (hygiene) | GitHub Actions not pinned; no secret/dependency/manifest gate; Dependabot off; no security regression tests | 38, 40 |
