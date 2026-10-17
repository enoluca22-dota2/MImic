# MImic — Design Research & Licensing Notes

This document records the **design principles** MImic draws on. It is deliberately
principle-level. No source code or assets from other apps have been copied into MImic,
and no repositories were vendored into this project.

## Scope and honesty note

The 3.0 brief asked to clone four reference players (APlayer, Namida, YounesBouhouche/
MusicPlayer, Resonate) and study them in depth. I have **not** cloned or vendored them —
cloning GPL/other-licensed apps risks contaminating MImic's license and the brief itself
forbids copying their code/assets. Instead, this file captures widely-understood,
non-copyrightable UX *patterns* common to modern music players, which MImic implements in
original code.

If you want a genuine per-repo source/license audit, that is a separate task: clone each
into a directory **outside** this project, review each `LICENSE`, and decide reuse there.

## Principles adopted (original implementations)

- **Token-based theming.** Colors/shapes/typography as data, not hard-coded per screen, so
  the whole look changes from one source of truth. → `ui/designsystem/ThemeDefinition.kt`.
- **Dark-first with multiple presets.** Deep backgrounds, one vivid accent, muted secondary
  text (~#B3B3B3 on dark). → `ThemeRegistry` (14 presets).
- **Persistent mini-player** above bottom navigation, expanding to a full Now Playing. →
  `components/Components.kt`, `NowPlayingScreen.kt`.
- **Home as horizontal carousels** (recently played, most played, favorites, recently
  added, suggested) over a dense list. → `Screens.kt`.
- **Library hub** with Liked Songs + playlists + full song list. → `LibraryScreen`.
- **Stable lazy-list keys** by stable song id for smooth scrolling. → throughout.

## Licensing posture

- MImic's own code: intended MIT (see repository `LICENSE`).
- Dependencies in use are Apache-2.0 / MIT (AndroidX, Media3, Coil, Kotlin). See
  `docs/DEPENDENCIES.md`.
- No Spotify (or other proprietary) code, API, logo, font, or brand asset is used.

## Deferred (would need real evaluation before adoption)

- MaterialKolor (artwork-derived palettes), Haze (frosted glass blur), skydoves
  colorpicker-compose (color wheel). Each is plausibly MIT/Apache but must be verified at
  the exact pinned version before adding. Not yet integrated.
