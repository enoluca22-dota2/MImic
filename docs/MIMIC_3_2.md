# MImic 3.2 — UX study, REA analysis, open-source study, and what changed

Milestone: versionCode 18 / versionName 3.2, starting from 3.1 (`703237c`, safety tag
`checkpoint-3.1`). Package `com.example.mimic` and signing identity unchanged; Room stays at v6
(no migration was needed — new state lives in DataStore).

---

## 1. Spotify UX study: a design reference only

**Method and limits.** Spotify is **not installed on the test emulator**, and no screenshots or
recordings were supplied. So this map draws on Spotify's widely known, publicly visible
behavior as a product-design reference, not on a capture made for this study. Nothing was
extracted from Spotify: no code, assets, traffic, APIs, tokens or lyrics. MImic uses its own
names, layout and visuals. If you can supply screenshots, the "Observed" column can be re-checked
against them.

| Area | Publicly observable pattern | MImic 3.2 equivalent (original implementation) | Inferred, not verified |
|---|---|---|---|
| Home | Compact 2-column grid of recent items at the top | **Quick Access**: Liked Songs, Downloaded, recent albums, playlists (2 × up to 4) | How Spotify ranks its tiles |
| Home | "Jump back in" / recently played rows | **Continue Listening** (resume card with progress + recently played albums) and **Recently Played** (songs) | — |
| Home | Personalized mixes | **Smart Mixes**: plain rules on your own history, each with a one-line reason | Spotify's recommendation model (not copied, not attempted) |
| Navigation | Re-tapping Home goes to the root; re-tapping again scrolls to the top | `Plan.ScrollToTop` + `LocalTabReselect` (Home, Search, Library) | — |
| Search | Live results grouped by type, result-type chips, recent searches | Ranked, accent-insensitive search; All/Songs/Albums/Artists chips; on-device recent searches | Spotify's ranking signals |
| Library | Filter chips, sort, grid/list | Reorderable and hideable tabs, sort + direction, albums/artists grid/list, source filters kept | — |
| Library | Multi-select actions | Long-press or Select → Play / Queue / Playlist / Like / Download | — |
| Now Playing | Large artwork; swipe to skip; like; shuffle/repeat; queue access | Swipe artwork; Lyrics · Speed · Sleep · Queue action row; error and buffering line | — |
| Queue | Now playing + Next up, drag to reorder, remove, clear | Same structure; drag handles; Clear Up Next with confirmation; save queue as playlist | — |
| Playlists | Create, rename, add, reorder, remove, play, shuffle | Rename + delete confirmation + Shuffle added (reorder/remove existed) | — |
| Lyrics | Synced lyrics view | From **your own files only**: embedded USLT/LYRICS or an added `.lrc` | Spotify's lyrics come from a licensed provider; nothing like that is used |

---

## 2. REA analysis

* **Install:** `rea` 6.7.0 (npm global), user-scope MCP server `rea`. JADX engine
  `jadx-headless-mcp-0.7.1-all.jar` (artifact SHA-256 `6e5eacf5…ff75`). JDK: Android Studio JBR
  (JDK 25; the Java 8 on PATH doesn't work). Windows x64. The CLI needs `JAVA_HOME` and
  `REA_JADX_MCP_JAR` exported in each shell.
* **Authorized target:** MImic's own 3.1 debug APK (`sha256 1725dbc2…098a`).

Commands run, with real results:

| Command | Finding |
|---|---|
| `rea inspect-android-package app-debug.apk` | package `com.example.mimic`, versionName 3.1 / code 17, min 24 / target 37, 27 245 classes, 942 resources; permissions incl. `READ_MEDIA_AUDIO`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `INTERNET`, `RECEIVE_BOOT_COMPLETED` (WorkManager), `NFC` (from a dependency) |
| `rea search-android-classes … com.example.mimic.player` | `CatalogState`, `CloudDataSource`, `MimicDataSourceFactory`, `NowPlaying`, `PlaybackService`, `PlayerUiState`, `PlayerViewModel` + compiler-generated `PlayerViewModel$…` lambda/coroutine classes |
| `rea inspect-android-method … PlaybackService onCreate` | Decompiled Java matches the Kotlin source line for line: `ExoPlayer.Builder → setMediaSourceFactory(DefaultMediaSourceFactory(MimicDataSourceFactory)) → setAudioAttributes(…, true) → setHandleAudioBecomingNoisy(true)` then `MediaSession.Builder` |
| `rea inspect-android-method … TopLevelNavigation plan` | The pure navigation decision decompiles to one readable ternary |
| `rea search-android-classes … com.example.mimic.ui.home` | Only 3 classes for the entire Home screen (`HomeScreenKt`, `ComposableSingletons$HomeScreenKt`, one lambda class) |

**What compiling does to the code (visible in the output):**
* Constants are inlined: `C.AUDIO_CONTENT_TYPE_MUSIC` → `2`, `C.USAGE_MEDIA` → `1`. Names are lost; values remain.
* Kotlin null-safety becomes explicit `Intrinsics.checkNotNull…` calls.
* Compose `@Composable` functions are rewritten with a hidden `Composer` parameter and their
  lambdas compiled via invokedynamic/singletons. A whole screen collapses into a few classes, and
  the UI tree, modifiers and state are hard to recover in readable form.
* Coroutines and Flow operators become state-machine classes (`…$emit$1`).

**Cannot be recovered reliably:** Compose layout structure and visual design, original
local-variable names inside lambdas, comments and intent, inlined constant names, anything a
release build would strip with R8. MImic release builds have optimization disabled, so they
would decompile similarly.

**Not done:** a second, open-source APK (e.g. a Resonate build) wasn't built and inspected.
Resonate was studied from its source instead (section 3), which is more reliable for learning.

---

## 3. Open-source code studied

| Project | License (checked via GitHub API + LICENSE file) | Used how |
|---|---|---|
| [kimhabkhun/resonate](https://github.com/kimhabkhun/resonate) | **MIT** | Read `PlaybackService`, `PlaybackEffectsController`, `EqualizerController`, `LrcParser`, `LyricsRepository`, `ReplayGain`. Ideas adopted, **code written independently for MImic**: service-side queue persistence; one volume equation (ReplayGain × crossfade × sleep fade); fade-through crossfade on a single player; sleep fade over the last 20 s; equalizer attached to the audio session; `AudioDeviceCallback` resume-on-connect; "skip a bad file and keep going". Resonate uses jaudiotagger (LGPL); MImic instead got a small original ID3v2/FLAC tag reader, so no new dependency. |
| [v8065791/Tune](https://github.com/v8065791/Tune) | **No license** (GitHub reports none; no LICENSE file) | **Ideas only**, from its README: reorderable/hideable tabs, grid/list toggle, explicit ascending/descending sort. No code was read for reuse or copied. |
| [android/nav3-recipes](https://github.com/android/nav3-recipes) | **Apache-2.0** | Multiple-back-stack / top-level patterns reviewed. MImic stays on Navigation Compose 2.8: its tab rules (`TopLevelNavigation`) already give one-tap Home and per-tab restore, so migrating offered no demonstrated benefit. |

---

## 4. What 3.2 adds

**P1 — Home and navigation:** Quick Access, Continue Listening (resume card + recent albums),
Recently Played, Smart Mixes tiles (2×2 cover collage + reason); re-tapping a tab on its root
scrolls to the top; status-bar scrim; the queue + position are saved by the service and restored
paused on next launch (IDs only, never stream URLs, which carry tokens); play history is recorded
in the service (counts with the UI closed; resuming mid-song isn't a new play); one unplayable song
is skipped (at most 3 in a row) instead of ending the queue; the Navidrome catalog reloads by
itself when the network returns.

**P2 — Library and search:** tab order/visibility, grid/list, album/artist sort, reverse order
(all persisted); multi-select with bulk actions; ranked, accent-insensitive search with type chips
and recent searches.

**P3 — Now Playing, queue, playlists:** swipe artwork; Lyrics/Speed/Sleep/Queue row; marquee for
long titles; artwork shrinks on small screens; optional haptic seek; lyrics sheet (synced
highlight, tap to seek, add/remove `.lrc`); queue sheet with drag reorder, clear (with confirmation)
and save-as-playlist; playlist rename, shuffle, delete confirmation.

**P4 — Smart Mixes and stats:** Recently Played, Most Played, Favorite Artists Mix (round-robin by
artist, just-played last), Never Played, Forgotten Favorites, Shuffle Discovery (weighted toward
rarely played, max 3 per artist, daily seed), Least Played. Each is a deterministic rule, not AI,
and nothing leaves the phone. Listening stats: plays, estimated time, distinct songs, streak,
plays/day chart, top artists and songs.

**P5 — Audio (Settings → Playback & audio):** gapless (ExoPlayer default, always on), fade-through
crossfade 0–12 s, skip silence, ReplayGain off/track/album (tags in local/downloaded files,
attenuation only), the phone's equalizer + bass boost, resume on headphones connect, pause on
disconnect (always), sleep timer in the service with a 20 s fade-out and an "end of this song"
option, playback speed 0.5–2× with keep-pitch.

---

## 5. Device-dependent and not-covered items

* **Equalizer / bass boost** use Android's audio effects. Availability and sound vary by phone
  (the emulator offers 5 bands). The screen says so when the phone has no equalizer.
* **ReplayGain** reads tags only from files on the phone (on-device or downloaded). Streamed
  Navidrome/cloud songs play unchanged. OpenSubsonic `replayGain` fields aren't used yet.
* **Lyrics:** ID3v2.3/2.4 USLT and FLAC Vorbis `LYRICS`/`UNSYNCEDLYRICS`. **Not read:** M4A `©lyr`,
  SYLT, APE tags, and same-name `.lrc` files next to the song. On Android 13+ scoped storage
  doesn't let a music app read non-audio files, so `.lrc` files are added via the file picker.
* **Crossfade** is a fade-out/fade-in on one player, not an overlap. That's deliberate: it can't
  double-play or corrupt queue transitions.
* **Bluetooth resume after the app was killed** (Media3 playback resumption) isn't implemented.
  Bluetooth/lock-screen controls work while the session exists.

---

## 6. Verification (emulator Pixel_10a, Android 16 / API 37)

* JVM unit tests: **130 / 130 pass** (117 → 130; new: queue snapshot, recent albums, smart mixes,
  ranked search, library config, tag reader, LRC parser, volume math, listening stats).
* Instrumented tests: **12 / 12 pass**.
* Installed over 3.1 with `adb install -r` (no uninstall). Before → after: downloads 5 → 5,
  favorites 5 → 5 (identical rows), play history kept, DB `user_version` 6.
* Exercised in the real UI: Home one tap from deep Settings; second tap scrolls to top; Quick
  Access, Continue Listening, Smart Mix opened from Home; streaming a Navidrome song, Next, Pause,
  force-stop → relaunch restores the same song at 5 979 ms (paused); resume from the Home card;
  resuming isn't counted as a new play (history 157 → 157); Library sort reverse, album list
  view, tab reorder + hide surviving restart; multi-select 2 songs → new playlist (DB checked);
  Search ranking, chips, recent searches; synced embedded lyrics (generated test MP3 with
  USLT + `REPLAYGAIN_TRACK_GAIN -6.02 dB`); speed 1.5× (`speed=1.5` in media session); sleep "end of
  this song" paused at 39.9 s without advancing; ReplayGain logged `-6.02 → ×0.50`; equalizer 5
  bands shown; artwork swipe → next song; queue drag reorder, save queue (392 songs), clear Up Next
  (with confirmation); background playback after Home; airplane mode: downloaded song plays and
  the queue restores on cold start; catalog auto-reload after reconnect (387 Navidrome songs);
  720×1280 small-screen layout (fixed an overflow found here); no crashes / ANRs in logcat.
* **Not verified:** real Google Drive / OneDrive sign-in (still waiting on your app registrations;
  see `CLOUD_SETUP.md`), a physical Xiaomi/Redmi phone, real Bluetooth hardware, a measured
  artwork-request count under a throttled network (the image-loading code is unchanged from
  2.8.1, where it was measured; new Home tiles use the same stable URLs).
