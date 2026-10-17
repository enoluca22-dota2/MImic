# Changelog

## 2.1 (in progress) — Theme engine
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
