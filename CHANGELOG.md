# Changelog

## 4.1.0-beta.1
A redesign release: one music library (phone · Navidrome · offline), correct artwork everywhere,
a new optional Dynamic player, casting and real audio sharing. Beta until tested on a phone.

- **Removed Google Drive and OneDrive.** Their screens, sign-in, background sync and libraries
  (MSAL, Play services auth) are gone. Songs already downloaded from them stay on the phone as
  offline songs — same ids, so favorites, playlists and history still point at them.
- **Fixed: one song's cover showing on many songs.** Phone songs used Android's per-ALBUM art, and
  Android groups every untagged file in a folder (e.g. all downloads) into one "album" named after
  the folder — so they all got the same cover, also in the notification and on the lock screen.
  Every phone song now shows its own embedded artwork (album art only for real album tags,
  otherwise a neutral placeholder), cached per song and refreshed when the file changes.
- **Dynamic Now Playing** (new, optional — Settings → Player Layout, or the card in Theme Studio):
  colors extracted from the artwork drive a gradient, glow and optional blurred artwork that blend
  between songs (500–900 ms; instant with reduced motion); text keeps ≥ 4.5:1 contrast; premium
  seek bar (buffered range, drag/tap to seek, glow); full-screen synced lyrics (follow the song,
  tap a line to jump, manual scroll pauses following); Lyrics · Queue · Cast · Share · More.
  Settings: gradient, blur, animated background, intensity, artwork shape & size, glass controls,
  brightness, automatic contrast, reduce motion. Classic, Immersive, Compact and all themes are
  unchanged.
- **Cast to a TV** (Google Cast: Chromecast, Google TV, TVs with Chromecast built-in) from the
  player and the mini player. Play/pause, next/previous, seek, queue changes and TV volume work
  while casting; stopping returns playback to the phone at the same position, paused.
- **Share the audio file** from the player, a song's ⋮ menu or Downloads: Bluetooth, Quick Share,
  WhatsApp, Telegram, e-mail… Streamed-only songs offer "download first".
- **Lock-screen style view** (in the app, not Android's lock screen): Classic, Dynamic, Glass or
  Minimal, with a large clock; keeps the screen on while open.
- **Downloads tab** in every edition: offline music with storage used/free and live download
  progress; the full edition adds "From a link".
- **Mini player**: next button, real progress line, cast button, "Casting to …".
- Battery: the position updates pause while MImic isn't visible.
- Tests: 122 JVM (standard) / 217 (full); lint clean.

## 4.0.0-beta.2
- App Updates: "View all releases on GitHub" button.
- Changelog: corrected the 4.0.0-beta.1 test counts.
- Used to verify the in-app update path end to end (beta.1 → beta.2).

## 4.0.0-beta.1
First "MImic Ultimate" build: ENAGELYUCA's downloader merged in, new brand, in-app updates.
Beta: Google Drive / OneDrive sign-in still needs a real-phone test with your own app registrations.

- **Downloads tab** (full edition): paste or share a YouTube/TikTok link (also "Open with MImic"),
  see the real formats, download MP3/M4A audio or MP4 video at a chosen quality, whole playlists or
  several links at once. Persistent queue with progress, speed, ETA, pause/resume/cancel/retry,
  Wi-Fi-only and speed limit, history, duplicate warning, in-app yt-dlp update. Music is saved to
  Music/MImic and appears in your Library (existing ENAGELYUCA downloads in Music/ENAGELYUCA too);
  it plays in MImic's player, videos play in-app. Ported from ENAGELYUCA 0.3.0 (its own player,
  library and radio were not merged — MImic's are used).
- **Fixed** (inherited from ENAGELYUCA): yt-dlp's self-update could replace the binary while a link
  was being analyzed ("bad local file header"); analysis and downloads now wait for it.
- **Five tabs**: Home | Search | Library | Downloads | Settings.
- **App Updates** (Settings): Check for Updates, what's new, download progress, install via
  Android's confirmation; SHA-256 + package + versionCode + CPU + pinned signing certificate
  checks; Stable/Beta channels; optional daily check that only notifies.
- **New icon**: "M" monogram whose right leg is an eighth note, cyan → violet on midnight blue
  (chosen from three candidates; SVG masters in design/icon, built with vdtoolkit).
- **Google Drive**: a phone reported Play services INTERNAL_ERROR (code 8) from AuthorizationClient.
  MImic now retries automatically through Android's account picker + GoogleAuthUtil (same OAuth
  client), which reports concrete reasons (e.g. UNREGISTERED_ON_API_CONSOLE); background tokens use
  GoogleAuthUtil; "Copy diagnostics" on the card (APK fingerprints, Play services version, last error).
- **OneDrive**: paste the Entra Application (client) ID on the card — no rebuild needed; the card
  lists the exact package, signature hash, redirect URI and permission to register.
- **Editions**: `full` (with downloader, GitHub) and `standard` (no downloader / no GPL code).
- Versioning: versionCode = (X·1e6+Y·1e3+Z)·100 + 99 (pre-releases lower), so betas get their own codes.
- License: GPL-3.0 (the downloader uses GPL-3.0 youtubedl-android).
- Tests: 149 JVM tests (standard edition); 244 in the full edition including 95 ported
  downloader tests; 12 instrumented.

## 3.3 — New icon, Google Drive sign-in repair, OneDrive readiness
- **New launcher icon**: dark navy field, frosted-glass disc, beamed eighth note in a cyan → blue →
  violet gradient. Vector adaptive icon (background + foreground + Android 13 themed monochrome),
  PNG legacy/round icons for every density, 512 px Play Store icon (`docs/icon/`). Everything is
  generated by `tools/icon/generate_icon.py` from one geometry spec.
- **Google Drive sign-in fix**: the account chooser's result was discarded unless it was
  `RESULT_OK`, so a rejected OAuth client (or a cancel) left the user with "nothing happened". The
  result is now always parsed; every Play services status maps to a clear message (DEVELOPER_ERROR
  shows the installed APK's package + SHA-1 to register), `authorize()` times out after 30 s, the
  granted scope is checked, and Reconnect re-authorizes the same account.
- **OneDrive**: build-time `msal.audience` (consumers / common / organizations); the installed APK's
  signature hash is checked against the MSAL redirect URI before sign-in, and the setup card shows
  the exact package + hash to register.
- **Music Sources**: rounded glass cards with status chips (Not connected, Connecting…, Choose a
  folder, Syncing…, Connected, Authorization required, Connection expired, Setup required,
  Problem), account e-mail, Change folder, Reconnect, Disconnect, persistent selectable error/setup
  panels. Connection progress lives in the ViewModel, so it survives rotation and never spins
  forever.
- Tests: +7 JVM (status mapping, card states, fingerprints, MSAL audiences, bearer token dropped on
  cross-host redirects) → 137 JVM + 12 instrumented.

## 3.1 — Google Drive + OneDrive music (no laptop server required)
- **Built-in cloud sources** (not plugins): Settings → **Music Sources** with Google Drive,
  OneDrive, Navidrome (optional) and Phone music. Connect account (provider's own sign-in UI),
  choose one music folder (in-app folder browser), status, library counts, last synced, Sync now,
  Change folder, Disconnect (optionally also delete that account's downloads).
- **OneDrive**: MSAL 8.5 public client (no secret), delegated `Files.Read`, personal accounts.
  Catalog via Graph `delta` on the folder (pages followed, delta link stored, 410 → full resync).
  Streaming via `/content` → 302 to a short-lived pre-authenticated URL that receives the Range
  requests; the URL is cached in memory ~20 min, never stored or logged, re-resolved on 401/403/404/410.
- **Google Drive**: Google Identity Services AuthorizationClient, `drive.readonly` (restricted scope —
  fine for your own/test accounts; public release needs Google verification, see docs/CLOUD_SETUP.md).
  Catalog via breadth-first `files.list` + Changes API (start token taken before the listing);
  streaming via `files.get?alt=media` with a bearer header and Range requests. Drive 403
  rate-limit responses are retried like 429.
- **One identity per cloud file**: provider + account + stable item id (`onedrive:<acct>:<id>`),
  62-bit collision-resistant song ids; renames/moves update the same song (favorites, playlists,
  history and downloads keep working); two accounts never merge. Identical uploads shown once;
  unsupported / DRM formats (WMA, M4P, AIFF, …) skipped and counted.
- **Metadata**: OneDrive audio facet; otherwise embedded tags + artwork read through
  MediaMetadataRetriever over 128 KB range reads (never the whole file, 2 in parallel, Wi-Fi only by
  default); `cover.jpg`/`folder.jpg`; OneDrive thumbnails; then Artist/Album folder layout, then the
  file name ("03 - Title"). Album track order from tags/file names.
- **One player**: the existing Media3 PlaybackService gained a routing data source for
  `mimic-cloud://` URIs — seek, queue, background, notification and media-button controls are
  unchanged; errors say "Can't reach OneDrive…", "sign in again", "no longer in your folder".
- **Sync**: incremental, atomic (applied in one transaction after all pages arrive), on app start /
  Library open when stale, pull-to-refresh on Home and Library, Sync now, WorkManager every ~6 h
  (network + battery-not-low; survives reboot). Retry-After honoured, exponential backoff.
- **Downloads**: cloud songs use the existing download workflow — progress, Cancel (deletes the
  partial file), Retry, size + provider hash (Drive MD5 / OneDrive SHA-1) verification before the
  file is recorded, free-space check, optional Wi-Fi-only. Cloud deletion or disconnect never deletes
  a download.
- Home gains an **Albums** row (newest albums across sources); source filters are now All · Phone ·
  Navidrome · Google Drive · OneDrive · Downloaded (provider filters include that provider's downloads).
- Room v6 (additive `cloud_accounts`, `cloud_items`; MIGRATION_5_6). versionCode 17.
- Tests: 103 JVM (29 new: identity, mapping, sync planning, Graph/Drive paging, 410, 429/403
  Retry-After, 401 refresh, 302 resolution) + 12 instrumented (2 new: v5→v6 upgrade, verified cloud
  download survives disconnect).

## 3.0 — Music app restructure: one-tap Home, downloads on Home, organized library, real glass
- **Fixed: Home needed several Back presses** (Settings → Account → Server Library → Home). Root
  cause: every tab tap used `popUpTo(start){saveState}` + `restoreState`; Settings sub-screens were
  pushed on top of Home, and a non-inclusive popUpTo(home) files them under Home's own id, so tapping
  Home *restored* them. New `ui/navigation/TopLevelNavigation` (pure, unit-tested): Home and Settings
  always open at their root; Search/Library restore their own stack; re-tapping a tab pops to its
  root; `launchSingleTop` everywhere. The server album is now a real route (`serveralbum/{id}`), and
  folder/genre/smart sub-lists handle system Back.
- **Four primary destinations**: Home · Search · Library · Settings (Settings is a tab; Theme Studio
  and Account live under it). Pushed screens keep their tab highlighted.
- **Unified library** (`UnifiedLibrary`, single source of truth): on-device + server catalog
  (metadata via `search3`, audio only on play/download) + downloads, one identity per recording — a
  download *replaces* its streamed entry, on-device files are never merged with server tracks.
  Source filters **All / On device / Server / Downloaded** on Home, Search and Library.
- **Home dashboard**: Continue Listening (real history + now-playing resume card), Downloaded Music,
  Recently Added (with source), Favorite Songs, Favorite Artists, My Playlists, Smart Collections,
  plus the existing Jump Back In / Suggested. Honest empty states; the existing reorder/hide
  customization is kept and a saved 2.x order gains the new sections at their default positions.
- **Downloads appear on Home immediately**: the downloads table is observed as a Flow; a download is
  recorded only after the file is complete (`.part` → size check vs Content-Length → readable →
  rename). Artwork is saved locally for offline. Offline copies are preferred for playback; a
  missing file shows a recoverable state (streams if the server is reachable, re-download in ⋮).
  "Remove download" deletes only the local copy. Album "Download" is user-initiated only.
- **Library**: Songs (sort by name / artist / album / recently added / recently played), Albums
  (2-column grid, source-aware album ids — equal titles are never merged), Artists, Playlists,
  Favorites (songs / albums / artists), Downloads (all / by album / by artist), plus Genres,
  Folders and Smart Collections. **Album pages** (header, Play all, Shuffle, ♡, Download album) and
  **Artist pages** (albums, all songs, Play all, Shuffle, ♡). Grouping runs off the main thread.
- **Readable song rows**: one shared `SongRow` — 52dp art, titles wrap to two lines (min Medium
  weight), "artist · album", source icon (downloaded / server / missing), tappable ♡, ⋮; ≥48dp
  targets; follows font scaling. Missing-tag policy (`MetadataFallback`): real tags → file name →
  "Unknown Artist/Album"; "<unknown>"/"[Unknown Album]" placeholders normalized; tags never rewritten.
- **Favorites**: Room-first (Home/Library update instantly); server tracks are also starred on
  Navidrome; failures (offline) are queued per account in `pending_server_ops` and flushed on
  reconnect; server stars are imported additively. Favorite albums & artists (`favorite_groups`).
- **Liquid Glass is actually transparent now**: 2.9's readability floor forced ≥25–92% tint and the
  presets used 42–64% tint over 24–30dp blur, so glass looked like solid cards. New tuning — Clear
  (12% tint, 4dp blur, full lens), Frosted (30%, 20dp), Dark (36% dark tint, 8dp), MImic flagship
  (20%, 10dp); floor capped at 20%. Balanced keeps the lens shader. Sub-screens now scroll *under*
  the floating bars so there is real content behind the glass. Performance = tinted see-through
  (84%, no shaders). Theme Studio shows **"Rendering now: …"** — the mode really in use.
- Lists/Home observe a tick-free `nowPlaying` flow (no 500 ms recomposition during playback).
- Room **v4 → v5** (additive: 3 nullable/defaulted download columns + 2 tables), tested on a real v4
  database. versionCode 16 / versionName 3.0; applicationId and signing unchanged.
- Tests: 74 JVM unit tests (+21) and 10 instrumented tests (+2), all passing; emulator acceptance
  scripts for navigation (14/14), player (15/15), downloads, offline and glass.

## 2.9 — MImic Liquid Glass + full Theme Studio customization
Resumed from an interrupted session (laptop shutdown): the uncommitted `ThemeStyle` model, 14-preset
registry and `GlassQuality` enum were recovered intact and finished, not rewritten.
- **MImic Liquid Glass** flagship theme: dark liquid glass that re-tints itself from the playing
  album. Real backdrop blur + vibrancy (API 31+), lens refraction (API 33+, High quality), rim
  highlight and soft shadow via Kyant Backdrop. Glass is applied only to the floating pill nav,
  the mini-player and the Now Playing controls — never to list rows.
- **14 canonical presets** (Liquid Glass Clear / Frosted / Dark, AMOLED Pure Black & Purple, Classic
  Music Dark & Light, Album Adaptive, Neon Cyberpunk, Midnight Blue, Sunset Gradient, Minimal
  Monochrome, Premium Gold, Material 3 Expressive) + the six older presets kept; all ids stable.
- **Glass quality** (device setting): High / Balanced (default on API 31+) / Performance (frosted
  tint, no blur) / Off (opaque). Glass panels keep a readability floor: low blur => denser frost.
- **Theme editor** with live preview built from the real components, sections for Colors (incl.
  accent, gradient, glass tint, album-adaptive toggle, HSV color wheel), Glass (transparency, blur,
  refraction, edge highlight, shadow, light/dark glass), Shapes (card/nav radius, rounded/capsule/
  squircle buttons), Text (system font family, size, header/title/artist weights), Motion
  (on/off, intensity, speed; honors Android "Remove animations"), Navigation (pill nav, indicator).
- **Album-adaptive coloring for any theme**: seed from artwork via Coil's cache (no re-download) +
  Palette, scheme from MaterialKolor's `SchemeContent` — text contrast unit-tested ≥ 7:1 on
  loud/dark/pale/grey covers.
- **Player**: Background (Auto/Solid/Gradient/Blurred artwork) and Controls (Auto/Filled/Liquid
  Glass/Minimal); squircle artwork shape. Gradients/artwork always sit on the 2.8.4 opaque base.
- **Navigation**: new Liquid Glass and Auto (follow theme) styles; Floating on a glass theme renders
  the glass pill. Screen transitions follow the theme's motion settings.
- **Theme files v2**: `style` block; v1 files (and files without a version) import with the default
  style; future versions and hostile values are rejected/clamped. Room migration 3 → 4 adds a nullable
  `styleJson` column — existing custom themes are kept unchanged.
- Theme Studio: grouped sections, delete **with confirmation**, "Customize (copy)" for presets,
  **Restore defaults** (custom themes untouched). Settings now names custom themes correctly.
- **Fixed (pre-existing since 2.8.4):** taps on empty areas of the full-screen player fell through
  to the list underneath and started another song. Overlays now own every touch inside their bounds
  and hide the screen beneath from TalkBack. The theme editor overlay is now opaque, inset and closes
  on Back.
- Tests: 53 JVM unit tests (+15: schema v1/v2, migration, clamping, album-color contrast, style
  resolution, glass env) and 8 instrumented tests (+1: real v3 → v4 database migration), all passing.
  versionCode 15 / versionName 2.9; applicationId + signing unchanged.

## 2.8.4 — Player overlay / insets / Stop & Close (real phone UI bug report)
Repairs the broken Player Layout / Now Playing experience reported on a physical Xiaomi phone.
- **Root cause of "Now Playing over the settings page / darkened screen":** the Immersive layout's
  background was a *translucent* accent gradient (`primary.copy(alpha=0.35f)` at top), so the
  full-screen player did not occlude the screen behind it — the underlying UI bled through, the
  controls appeared to float over the bottom nav, and touches leaked. (Classic/Compact used an
  opaque color, so this was Immersive-specific; the user had Immersive selected.) Fixed by painting
  an **opaque base** behind the gradient (edge-to-edge) and insetting the content with
  `systemBarsPadding()` so the header clears the status bar and the controls clear the nav bar.
- **Root cause of headers overlapping the status bar:** pushed sub-screens (Settings, Player
  Layout, Account, Theme Studio, etc.) drew edge-to-edge with no insets (only the three tab screens
  got them from the Scaffold). Added one `InsetScreen` wrapper applying the Scaffold's innerPadding
  (top status bar + bottom mini-player/nav) to every sub-screen — titles no longer overlap the
  clock and last rows no longer hide behind the mini-player.
- **New Stop & Close action** (■ in the player top bar): `stopPlayback()` halts playback, clears the
  queue, and resets UI state, emptying the MediaSession so Android removes the media notification /
  system media controls (`state=NONE` verified). Clearing the current song also dismisses the
  full-screen player and hides the mini-player. **Minimize** (⌄ / system back) keeps playing and
  collapses to the mini-player. The "Island"/media card on Xiaomi/HyperOS is the **OS-owned**
  system media surface driven by the MediaSession — not a MImic overlay; Stop clears it (HyperOS may
  briefly keep a "recently used" card, which is OS-controlled).
- Full-screen back always collapses (BackHandler) and never reopens automatically.
- Emulator-verified: opaque Immersive overlay, header/controls clear of system bars, Stop → NONE +
  overlay closed + mini-player gone, Minimize keeps playing, layout switching Classic/Immersive/
  Compact with no trapping, settings scroll, back navigation, background playback, 0 crashes/ANR.
- Regression test `PlayerUiStateTest` (cleared/stopped state). 38 unit tests, 0 failures.
  versionCode 14 / versionName 2.8.4; applicationId + signing unchanged.
- Library: Navidrome mount finalized at `C:/Users/Eno/Music/mp3` (`:ro`), backup SQLite
  `integrity_check: ok`, **386 tracks** indexed, multiple real tracks stream (HTTP 206 audio/mpeg,
  range/seek) incl. over Tailscale. Unicode filenames (fullwidth `｜`, accents, spaces, parens) are
  handled server-side + via opaque song ids; the app never touches raw filenames.

## 2.8.1 — Scroll jank + playback-freeze repair (real phone bug report)
Fixes two issues reported on a physical phone over Tailscale (both environment-dependent, so
the earlier localhost emulator test didn't surface them):
- **Root cause of scroll jank / audio starvation:** `SubsonicClient` generated a *fresh random
  salt on every call*, so `coverArtUrl()`/`streamUrl()` returned a different URL each time they
  were invoked in a composable. Coil saw a new URL on every recomposition/scroll and re-downloaded
  every cover — invisible on localhost, a network storm over Tailscale that janked scrolling and
  starved the audio stream. Fixed by using **one stable salt per client (session)**; salted-token
  auth stays secure (the password is never derivable). Cover URLs are now also memoized per
  `coverArtId`. Regression tests: `SubsonicClientUrlTest` (URLs stable per client, differ across
  sessions, never contain the password).
- **Root cause of "freeze on song select":** when a stream fails, the app previously showed no
  feedback and appeared frozen. Added Media3 `Player.Listener` diagnostics (`onPlayerError`,
  buffering/ready state) and surfaced a **buffering spinner + a clear red error** in the mini-player.
  No more indefinite silent hang. Errors are classified without logging the auth token.
- **Diagnostic finding (server data, not app):** on this server, 385 of 387 catalogued tracks fail
  because their files no longer exist on disk — Navidrome's `/music` mount points at the Downloads
  folder and the "YT Downloads" subfolder has been emptied, so `stream` returns HTTP 200 with a
  JSON error body (`no such file or directory`). ExoPlayer can't parse JSON → it was the "fails to
  play". The one track with a real file (System Of A Down – Aerials) streams perfectly. Fix is
  server-side: restore the music files or repoint the mount, then rescan Navidrome.
- **Full-screen player could get stuck open:** added a `BackHandler` so system back collapses it,
  and `statusBarsPadding()` so the collapse chevron no longer hides under the status-bar clock.
- Verified on emulator (emulator-5554): scrolling the full 386-track list (A→B→G and back to top),
  valid-file streaming (`state=PLAYING`, position advanced, 0 errors), failed-track error now
  visible instead of a freeze, local playback, upgrade preserves data (theme, history, download).
  **Artwork-cache proof (386 covers, through a 120 ms/chunk latency + ~0.5 MB/s proxy):** each
  cover fetched exactly once; scrolling back over loaded rows = **0 extra network requests**;
  playback never starved while scrolling (track advanced, fully buffered, 0 errors); pause/resume/
  next/previous/seek all verified; download + offline playback + offline-after-restart + reconnect
  all pass. 37 unit tests pass. versionCode 12 / versionName 2.8.1; applicationId + signing unchanged.

## Acceptance — Streaming & offline playback VERIFIED (2026-10-17, emulator)
End-to-end verified on the Android emulator against the live Navidrome server (`enoluca`):
- Authenticated API: ping OK (Navidrome 1.16.1), 386-track library, search3=387, wrong-password
  rejected (code 40). Real stream = 9,749,679-byte `audio/mpeg` (ID3), download = full file.
- In-app on emulator: signed in ("Signed in · Connected") via `http://localhost:4533` (adb reverse);
  browsed server library (386 + 1 tracks); **streamed** a track (Media3 `state=PLAYING`, position
  advanced); **downloaded** it (✓); went **airplane-mode offline** and played the download (position
  advanced); **force-stopped + relaunched** and replayed it offline. No app-code crashes.
- Credential encryption at rest confirmed on-device: `music_server.preferences_pb` holds
  `password_enc` (ciphertext) — no plaintext password key and no plaintext password anywhere in
  app data. (Separately, 3 instrumented CredentialCipher tests pass.)
- Physical-phone acceptance remains PENDING (no device attached); emulator milestone complete.

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
