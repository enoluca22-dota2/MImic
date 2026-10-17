# Changelog

## 2.7.1 — Smart Collections (local, no server)
- New **Library → Smart Collections**: generated views over your own library + play history —
  **Never Played**, **Least Played**, **Forgotten Favorites**, **Random Discovery** (reproducible
  per day). Pure, deterministic logic in `data/SmartCollections.kt`; new DAO queries
  (`playedSongIds`, `leastPlayed`). Generated views only — manual playlists are never touched;
  local/remote ids stay distinct. (Spotipy-feature brief, Phase 6 — local half, server-independent.)
- 6 new JVM unit tests (33 total, 0 failures). Verified on emulator: hub renders with accurate
  live counts, opens cleanly (0 app crashes under normal navigation).
- versionCode 10 / versionName 2.7.1; applicationId + signing unchanged.
- NOTE: authenticated streaming/download/offline remain UNVERIFIED (blocked on the server
  password). No multi-user/sync started.

## 2.7 — Credential encryption + verified on-emulator client
- **Credential security:** the server password is now encrypted at rest with AES-256-GCM using a
  non-exportable Android Keystore key (`data/server/CredentialCipher.kt`); only ciphertext is
  written to DataStore. Legacy plaintext from older installs is migrated in place on first launch
  (`migratePlaintextIfNeeded`), with safe fallback if the Keystore is unavailable and no loss of
  unrelated preferences (themes/layouts live in a separate store).
- **Verified on a running emulator (Android, x86_64):** app installs + launches (no crash); local
  MediaStore scan + **real playback** (Media3 session `state=PLAYING`); background playback
  (mini-player persists across screens); Account/Sign In UI renders; **auth pipeline reaches a live
  Navidrome and correctly rejects a wrong password** ("Wrong username or password"); network-failure
  handling shows a graceful error (10s timeout, no crash). 27 unit tests pass.
- **Remote access:** documented in `docs/REMOTE_ACCESS.md` (Tailscale recommended; HTTPS proxy for
  public). Tailscale is NOT yet installed on the laptop. Worldwide access NOT claimed until tested
  from outside the home network.
- **STILL PENDING:** authenticated streaming/download/offline with the *correct* password (both via
  curl and in-app), and physical-phone testing. The only gap for streaming is the real password.
- versionCode 9 / versionName 2.7; applicationId + signing unchanged.
- Build note: `cmd //c "gradlew.bat ..."` silently no-ops in this shell (current dir excluded from
  search); use `.\gradlew.bat`. Earlier 2.6 "builds" this session had not actually compiled.

## 2.6 — Account / Sign In experience
- Settings → "Music Server" reworked into **Account**: a real Sign In screen (server address,
  username, password) with **Test Connection** (pings without saving) and **Sign In** (validates
  then persists), plus a **Stay signed in** toggle that controls auto-reconnect on launch.
- When signed in, the screen shows an **account profile** (username + server + Connected status)
  with **Browse server library**, **Switch account** (keeps server address, forgets the user),
  and **Sign out** (forgets the account entirely).
- **Offline Guest mode**: "Continue offline (Guest)" — and the screen states that on-device music
  always plays without an account. Local playback remains fully login-free.
- Remote access works today with no extra code: the server address is free-form, so a Tailscale
  address or an HTTPS URL can be entered directly (worldwide access without the laptop's LAN IP).
- NOTE (carried over, still true): live authenticated streaming + offline download have NOT been
  verified against the running server or a physical phone yet — that acceptance test is pending.
  Automatic library sync, multi-library per-account scoping, and account-data sync (brief phases
  5/7/8) are NOT started — gated until streaming + offline are verified.

## 2.5 — Music server (Navidrome/Subsonic) connection [code complete, end-to-end UNVERIFIED]
- Navidrome deployment prepared: `server/docker-compose.yml` (music mounted read-only) +
  `docs/SERVER_SETUP.md`. Not started/streamed in this environment — run it on your laptop.
- Android Subsonic client (OkHttp): salted-token auth (password never sent/logged), ping,
  album list, album songs, stream + cover URLs, file download.
- Settings → Music Server: enter address/user/password, Test & Connect, status, Disconnect
  (credentials persist in app-private DataStore; NOT yet encrypted at rest — hardening TODO).
- Browse server albums → songs → stream via the existing Media3 engine; per-song Download.
- Downloads stored in Room (migration 2→3) and listed under Library → Downloads for offline
  playback. Local MediaStore playback remains fully functional and login-free.
- Unit tests for Subsonic auth (official token vector; password absent from URL).
- NOTE: streaming and offline playback are implemented but have NOT been verified against a
  live server or physical phone here — that acceptance test is yours to run.

## 2.4 — Browsing, customization, file themes, adaptive colors
- Library browsing: Albums, Artists, Folders, and Genres (genre where metadata permits).
- Home customization: show/hide and reorder home sections; persisted.
- Navigation styles: Standard, Floating, Compact bottom bar.
- Theme import/export as real `.json` files via the system file picker (plus Share).
- "Album Adaptive" theme now genuinely derives colors from the current song's artwork
  (AndroidX Palette, extracted off the main thread, recomputed only on song/theme change).
- Settings → Home & Navigation screen. Unit tests for grouping and home-config logic.

## 2.3 — Player Layout Studio
- Three Now Playing layouts: Classic, Immersive (accent gradient, oversized art), Compact.
- Configurable artwork shape (Rounded/Circle/Square) and size (Small/Medium/Large).
- Show/hide shuffle+repeat and queue+sleep controls.
- Settings → Player Layout studio with a live miniature preview; applies instantly and
  does not interrupt playback. All settings persist in DataStore, independent of theme.
- Unit tests for artwork sizing/shape helpers.

## 2.2 — Theme Studio (editable themes)
- Custom themes persisted in Room (new `custom_themes` table; migration 1→2 preserves data).
- Theme Studio gallery: apply, duplicate & edit, edit, export, delete (built-ins protected).
- Original color editor: RGB sliders + live HEX field, per-token editing, live preview card.
- Versioned JSON export (via share sheet) and paste-to-import with strict validation
  (rejects missing fields, bad colors, oversized input; imports can't overwrite built-ins).
- Unit tests: JSON round-trip, hex parsing, validation rejections; custom-theme DAO test.

## 2.1 — Theme engine
- Token-based theme engine (`ThemeDefinition` / `ThemeColors`) driving the whole UI.
- 14 built-in theme presets (AMOLED Black, Emerald, Ocean Neon, Sunset, Rose Gold,
  Arctic Light, Purple Galaxy, Midnight Blue, Retro Vinyl, Minimal Monochrome,
  Material You, Album Adaptive, Frosted Glass, High Contrast).
- Theme Studio screen (Settings → Appearance): browse presets, apply instantly, persisted.
- Unit tests: theme uniqueness, fallback, WCAG contrast per preset.

## 2.0 — Persistence & library platform
- Room database: favorites, play history, play counts, playlists (with manual ordering).
- DataStore settings.
- Manual DI container (`AppContainer`) + `MimicApplication`.
- Home sections backed by real data; Search; Library hub with Liked Songs + playlists.
- Queue management (add to queue, play next, remove, jump).
- Sleep timer; like button; per-song actions sheet; add-to-playlist.
- Unit tests (library queries) + Room instrumented tests.
- Safe conditional release signing; version 2.0 (versionCode 2).

## 1.0 — Baseline local player
- MediaStore scan, Media3 ExoPlayer + MediaSessionService + MediaController.
- Home / Search / Library / Now Playing, mini-player, shuffle/repeat/seek.
