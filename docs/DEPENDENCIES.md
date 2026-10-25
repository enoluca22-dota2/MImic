# MImic — Dependencies & Licenses

All runtime dependencies are permissively licensed (Apache-2.0 or MIT). No GPL code is
included. No proprietary (e.g. Spotify) code or assets are used.

| Dependency | Purpose | License |
|---|---|---|
| AndroidX Core / Lifecycle / Activity | platform + lifecycle | Apache-2.0 |
| Jetpack Compose (BOM) + Material 3 | UI toolkit | Apache-2.0 |
| Material Icons Extended | icon set | Apache-2.0 |
| Navigation Compose | in-app navigation | Apache-2.0 |
| AndroidX Media3 (ExoPlayer, Session, UI) | playback engine | Apache-2.0 |
| AndroidX Room (runtime/ktx/compiler) | local database | Apache-2.0 |
| AndroidX DataStore (Preferences) | settings | Apache-2.0 |
| Coil (coil-compose) | async image loading | Apache-2.0 |
| AndroidX Palette | album-art color extraction (Album Adaptive theme) | Apache-2.0 |
| OkHttp | HTTP client for the Subsonic/Navidrome music server | Apache-2.0 |
| KSP (build-time) | Room code generation | Apache-2.0 |
| Kyant Backdrop `io.github.kyant0:backdrop:1.0.6` | Liquid Glass: backdrop blur, lens refraction, rim highlight (2.9) | Apache-2.0 |
| Kyant Shapes `io.github.kyant0:shapes:1.2.0` | continuous-curvature (squircle) & capsule shapes (2.9) | Apache-2.0 |
| MaterialKolor `com.materialkolor:material-color-utilities:4.1.1` | contrast-safe album-adaptive schemes (2.9) | MIT (port of Google's Apache-2.0 material-color-utilities) |
| skydoves colorpicker-compose `1.1.3` | HSV color wheel in Theme Studio (2.9) | Apache-2.0 |
| JUnit4, AndroidX Test, Espresso (test only) | testing | EPL-1.0 / Apache-2.0 |

MImic's own source is intended to be released under the MIT License (`LICENSE`).

## Added in 4.5

| Dependency | License | Where | Why |
|---|---|---|---|
| gorilla/websocket 1.5.3 (Go) | BSD-2-Clause | `server/party` only (not in the APK) | Listen Together party server WebSockets (from BitChord's backend) |
| golang:1.27-alpine, alpine:3.22 (Docker images) | BSD-3 / MIT and others | build/run of `server/party` | container for the party server |

No new Android dependencies: the app side of Listen Together uses OkHttp (already present) and
org.json; lyrics use OkHttp and Room.

## Version pinning (2.9)
MImic builds with Kotlin 2.2.10 / Compose BOM 2026.02.01 (foundation 1.10.4, material3 1.4.0).
The newest releases of these libraries (backdrop 2.0.x, colorpicker 1.2+/1.3, MaterialKolor 5.x)
are compiled with Kotlin 2.4 metadata and Compose 1.11–1.12, which a Kotlin 2.2 compiler cannot
read. The pinned versions above are the newest that fit the current toolchain. Resolved graph was
checked: material3 stays 1.4.0, foundation 1.10.4; only the runtime kotlin-stdlib moves
2.2.10 → 2.3.10 (readable by the 2.2 compiler). Upgrading these requires a Kotlin 2.4 migration.

Only the *color-utilities* artifact of MaterialKolor is used: its `material-kolor` UI artifact
pulls JetBrains `material3 1.10.0-alpha05`, which would push AndroidX material3 to an alpha.

## Evaluated, not added
- **Haze** (Apache-2.0) — redundant: Backdrop already provides the blur for the few glass surfaces.
- **KMPalette** (MIT) — redundant: AndroidX Palette (already used) does the extraction on Android.
- **Resonate** (MIT) — studied as a design reference only; no code copied.
- No Apple assets, fonts or icons are used; typography uses Android's built-in system families.

## Design references and attribution (3.2)
No new runtime dependencies were added in 3.2. Design ideas were studied from:
- **Resonate** (https://github.com/kimhabkhun/resonate), MIT License, Copyright (c) 2026 Kimhab —
  playback-effects architecture (service-side queue persistence, a single volume equation,
  fade-through crossfade, sleep fade, equalizer on the audio session, LRC parsing approach).
  MImic's implementations are its own code; this credit is given in the spirit of the MIT license.
- **Tune** (https://github.com/v8065791/Tune) — no license published; only general feature ideas
  (reorderable/hideable tabs, grid/list toggle, sort direction) were taken from its README.
- **android/nav3-recipes** (Apache-2.0) — navigation patterns reviewed; no code used.
See docs/MIMIC_3_2.md for details.
