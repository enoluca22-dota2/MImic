# Security

## Reporting

Please report vulnerabilities privately via GitHub → Security → "Report a vulnerability" on this
repository, not in public issues.

## What MImic protects, and how

| Area | Measure |
|---|---|
| Navidrome password | AES-256-GCM with a non-exportable Android Keystore key; only ciphertext is stored. |
| Logs | Never contain tokens, passwords, download URLs or account names (status codes only). |
| Casting | While a Cast session runs, the phone serves only the queued songs on the local network, each under a random 128-bit token; Navidrome songs are fetched by the phone and relayed, so the server's login token never reaches the TV. The server stops when casting ends. Audio crosses the LAN unencrypted (Cast receivers fetch over plain HTTP). |
| Sharing | Only local files are shared: phone songs by their MediaStore URI, offline downloads through a FileProvider limited to the downloads folder; the receiving app gets temporary read access to that one file. Stream URLs (which carry the server login) are never shared. |
| Downloads | Files are written through MediaStore / the Storage Access Framework; names are sanitized. |
| App updates | HTTPS to GitHub hosts only (every redirect checked); SHA-256 against `SHA256SUMS.txt`; package name, higher versionCode, minSdk and CPU checked; the APK must be signed with the **certificate pinned in the app** and the installed app's certificate. Android's installer always asks the user to confirm. Release notes are shown as plain text. |
| Secrets in git | `keystore.properties`, keystores, server credentials and `.env` are gitignored. Release signing happens only on the maintainer's machine. Gitleaks scans the full history in CI and before every release. |
| Backups | Android cloud backup / device transfer skip every sign-in (Navidrome store, YouTube Music session, WebView cookies, party token); a restore on a new phone asks you to sign in again. |
| Network | System certificate authorities only (no user-added CAs, no pinning of self-hosted servers). GitHub, Google, YouTube Music and LRCLIB are HTTPS-only; plain HTTP remains possible only for home servers and radio streams. |
| Supply chain | Dependencies checked against OSV before every release (reviewed exceptions in `scripts/security/osv-allowlist.txt`), CycloneDX SBOM, CodeQL, Dependabot, GitHub Actions pinned to commits. Full audit: `docs/security/`. |

## Known limitations

- The release signing key is the maintainer's long-standing debug keystore (kept so existing
  installs keep updating). It is backed up offline and never leaves the maintainer's machine.
- Cleartext HTTP is allowed for LAN Navidrome servers and radio streams (network security config);
  use HTTPS (e.g. Tailscale Serve) for remote access.
- The downloader's yt-dlp updates itself from GitHub over HTTPS without a signature check, and
  youtubedl-android bundles older commons-compress / commons-io / Jackson (reviewed as not
  reachable; see `docs/security/SECURITY_REPORT.md`).
