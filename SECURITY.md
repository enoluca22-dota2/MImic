# Security

## Reporting

Please report vulnerabilities privately via GitHub → Security → "Report a vulnerability" on this
repository, not in public issues.

## What MImic protects, and how

| Area | Measure |
|---|---|
| Navidrome password | AES-256-GCM with a non-exportable Android Keystore key; only ciphertext is stored. |
| Google Drive | No tokens stored by MImic: Google Play services issues and renews them (`drive.readonly`). |
| OneDrive | MSAL public client, authorization code + PKCE, no client secret; MSAL's encrypted token cache. The Entra client ID is a public identifier. |
| Logs | Never contain tokens, passwords, download URLs or account names (status codes only). |
| Cloud files | Read-only: MImic never uploads, renames, moves, shares or deletes cloud files. |
| Downloads | Files are written through MediaStore / the Storage Access Framework; names are sanitized. |
| App updates | HTTPS to GitHub hosts only (every redirect checked); SHA-256 against `SHA256SUMS.txt`; package name, higher versionCode, minSdk and CPU checked; the APK must be signed with the **certificate pinned in the app** and the installed app's certificate. Android's installer always asks the user to confirm. Release notes are shown as plain text. |
| Secrets in git | `keystore.properties`, keystores, `cloud.properties`, server credentials and `.env` are gitignored. Release signing happens only on the maintainer's machine. |

## Known limitations

- The release signing key is the maintainer's long-standing debug keystore (kept so existing
  installs keep updating). It is backed up offline and never leaves the maintainer's machine.
- Cleartext HTTP is allowed for LAN Navidrome servers (`usesCleartextTraffic`); use HTTPS (e.g.
  Tailscale) for remote access.
