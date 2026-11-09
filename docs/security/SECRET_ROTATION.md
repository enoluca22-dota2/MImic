# MImic — secret rotation procedure

Never paste secrets into issues, chat, commits or logs. If one is exposed, rotate first, then
clean up. Do not rewrite public Git history without deciding it deliberately (forks and caches
keep copies anyway — rotation is what actually protects you).

| Secret | Where it lives | How to rotate | Effect on the app |
|---|---|---|---|
| Navidrome password | Navidrome server; on the phone only as Keystore-encrypted ciphertext | Change it in Navidrome (Settings → Users), then MImic → Account → Switch account → sign in | Downloads and library are kept; only the sign-in changes |
| YouTube Music session | Phone only (EncryptedSharedPreferences + WebView cookies) | Google Account → Security → Your devices → sign out the device; then MImic → YouTube Music → sign in again | Liked songs/playlists come back after sign-in |
| Listen Together party token | Phone (app-private prefs) and the party server | Leave the party; restart the party server (`server/party`) to invalidate all tokens | Next party gets a fresh token |
| Firebase config (`firebase.properties`) | Laptop only, gitignored; values are public identifiers baked into the APK | Restrict the API key in Google Cloud Console (Android app + certificate SHA-1); create a new key there and update `firebase.properties`, rebuild | Account sync keeps working with the new key |
| GitHub token (`gh` CLI) | Windows credential manager (keyring) | `gh auth refresh` or revoke at github.com → Settings → Applications/Tokens, then `gh auth login` | Release publishing uses the new token |
| Party server / Navidrome `.env` | Laptop only (`server/.env`, gitignored) | Edit `.env`, `docker compose up -d` | None on the phone |
| **Release signing key** | `~/.android/debug.keystore` (+ offline backup) | **Do not rotate casually.** A new key cannot update existing installs (Android rejects it; MImic's updater refuses it — pinned certificate). Only with a planned migration: APK Signature Scheme v3 key rotation (`apksigner rotate` + lineage), a release that pins both certificates, then the switch. Requires the owner's explicit decision. | Without a migration every phone would have to uninstall (losing data) |

## If a secret was committed

1. Rotate it (table above). 2. Remove it from the working tree and add the file to `.gitignore`.
3. Run `gitleaks git . --redact --config .gitleaks.toml` to find every occurrence.
4. Decide whether to rewrite history (only with the owner's approval; tags and releases are affected).
