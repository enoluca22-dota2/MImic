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
| KSP (build-time) | Room code generation | Apache-2.0 |
| JUnit4, AndroidX Test, Espresso (test only) | testing | EPL-1.0 / Apache-2.0 |

MImic's own source is intended to be released under the MIT License (`LICENSE`).

## Not yet added (evaluated, deferred)
MaterialKolor, Haze, skydoves colorpicker-compose, ComposeDynamicTheme — must be
license-verified at a pinned version before integration. See `DESIGN_RESEARCH.md`.
