# MImic — security changelog

## 4.7.0-beta.4 (2026-11-09) — security hardening

App (both editions unless noted):
- **Backup policy** (control 14): `backup_rules.xml` and `data_extraction_rules.xml` now exclude the
  Navidrome store (`datastore/music_server.preferences_pb`), the YouTube Music session
  (`bitchord_auth*.xml`), WebView cookies (`app_webview/`) and the Listen Together token from
  cloud backup and device transfer. Everything else is backed up as before.
- **Network security config** (controls 23, 24): replaces `usesCleartextTraffic="true"`. HTTP stays
  allowed for home servers and radio; GitHub, Google APIs/sign-in, YouTube Music/YouTube and
  LRCLIB are HTTPS-only; only system CAs are trusted.
- **Updater** (controls 29, 33, 34): download size cap (release-stated size, max 400 MB); identity
  checks extracted into `UpdateVerification` (unchanged behaviour) with unit tests.
- **Download file names** (control 28): `DownloadRepository.safeFileName` — never `.`/`..`/hidden,
  max 120 characters; ordinary ids unchanged so existing downloads still resolve.
- **WebView** (control 12, full): the YouTube Music sign-in page has file/content access off.
- **Dependencies** (control 37): jsoup 1.22.2→1.23.2 (full); Guava →33.7.2-android;
  protobuf-javalite →3.25.8 (standard); Jackson 2.11.1→2.13.5 (full, via youtubedl-android).

Project:
- `SecurityRegressionTest` (14 tests × 2 editions).
- `scripts/security/`: `security_gate.sh` (run by `release.sh` before publishing), `osv_gradle.py`
  (OSV check + CycloneDX SBOM), `osv-allowlist.txt` (reviewed advisories), `check_manifest.py`,
  `dashboard.py`; `.gitleaks.toml`.
- GitHub: `security.yml` (Gitleaks, OSV, dependency review, manifest/release policy, PR/main/weekly),
  `codeql.yml` (Kotlin/Java, security-extended), `dependabot.yml`; `ci.yml` actions pinned to
  commit SHAs, Gradle wrapper validation, `persist-credentials: false`.
- Repository settings: Dependabot alerts + security updates and private vulnerability reporting enabled.
- Docs: tailnet name/host/IP removed from `docs/REMOTE_ACCESS.md`; `docs/security/*`.
- `.gitignore`: `*.p12`, `*.pfx`.
