# Third-party notices

MImic is licensed under the GNU General Public License v3.0 (see `LICENSE`). This file lists the
code it incorporates from other projects and the libraries it ships, with their licenses.

## Code copied and modified from BitChord (GPL-3.0) — MImic 4.4

BitChord — https://github.com/kushagrasinghx/BitChord — Copyright (C) BitChord contributors,
licensed under the GNU General Public License v3.0.

The following files under `app/src/full/java/com/example/mimic/online/` are copies of BitChord
source files, modified for MImic (package renamed from `com.music.bitchord` to
`com.example.mimic.online`, trimmed, and adapted to MImic's player and storage). Each file starts
with a notice naming its original path.

| MImic file (online/…) | BitChord original | Modification |
|---|---|---|
| data/innertube/Innertube.kt, InnertubeParser.kt, PlayerClient.kt, UploadedTracks.kt | shared/…/data/innertube/ | package rename |
| data/innertube/StreamResolver.kt | shared/…/data/innertube/StreamResolver.kt | package rename; stats overlay removed; `ensureNewPipe()` added |
| data/innertube/InnerTubeXResolver.kt | shared/…/data/innertube/InnerTubeXResolver.kt | package rename |
| data/innertube/potoken/* | shared/…/potoken/*, app/…/potoken/PoTokenWebView.kt | package rename, MImic BuildConfig |
| data/YtMusicRepository.kt, LikeState.kt, LocaleTags.kt, Http.kt, TrackLog.kt, DebugLog.kt, MonotonicClock.kt | shared/…/data/ | package rename; WebDAV interceptor removed from Http; TrackLog read-back replaced by `recent()` |
| data/model/Models.kt, ArtistCredits.kt, PlaylistOrder.kt; data/sources/StreamFormat.kt, TrackMatcher.kt | shared/…/data/model, sources | package rename |
| playback/ChunkedDataSource.kt | app/…/playback/ChunkedDataSource.kt | package rename |
| download/Downloader.kt, MediaTagger.kt, Mp4Tagger.kt, Mp4Boxes.kt, Mp4Sidx.kt, WebmTagger.kt, FlacTagger.kt | app/…/download/ | package rename; lyrics embedding removed; `readNBytes` replaced (API 24) |
| auth/AuthStore.kt, AccountSessions.kt, DataSyncId.kt, EncryptedPrefs.kt, WebSession.kt, YtMusicLoginScreen.kt | app/…/auth/, shared/…/auth/ | package rename |
| `app/src/full/java/org/schabi/newpipe/extractor/utils/Utils.java` | BitChord's patched copy of NewPipeExtractor v0.26.3 Utils (GPL-3.0) | unchanged |

MImic's own glue (`OnlineMusic.kt`, `OnlineDownloadWorker.kt`, `OnlineQuality.kt`,
`OnlineDiagnostics.kt`, `YouTubeSearch.kt`, `YouTubeMusicAccount.kt`, `OnlineSettings.kt`, and
everything under `app/src/main/`) is MImic's code under GPL-3.0.

## Libraries added in 4.4

| Library | License | Edition | Why |
|---|---|---|---|
| InnerTubeX (com.github.MetrolistGroup.innertubex) v0.7.0 | GPL-3.0 (from its POM) | full | YouTube stream extraction (client catalogue, cipher, PoTokens) |
| NewPipeExtractor (TeamNewPipe) v0.26.3 | GPL-3.0 | full | stream-resolution fallback, YouTube search fallback |
| nanojson (TeamNewPipe) | MIT / Apache-2.0 | full | NewPipeExtractor dependency |
| Mozilla Rhino 1.8.1 | MPL-2.0 | full | NewPipe's player-JS evaluation |
| quickjs-kt-android 1.0.14 (dokar3) | Apache-2.0 | full | InnerTubeX's JS solver |
| jsoup 1.22.2 | MIT | full | NewPipe dependency |
| protobuf-javalite 4.35.0 | BSD-3-Clause | full | NewPipe dependency |
| findbugs jsr305 3.0.2 | BSD-3-Clause | full | annotations (NewPipe) |
| Ktor client 3.5.2, kotlinx-serialization-json 1.11.0 | Apache-2.0 | full | BitChord's Innertube HTTP / JSON |
| androidx.security:security-crypto 1.1.0-alpha06 | Apache-2.0 | full | encrypted YouTube Music session storage |
| AndroidX Credentials 1.3.0, Google ID (googleid) 1.1.1 | Apache-2.0 | both | Sign in with Google |
| Firebase Auth / Firestore (BoM 33.7.0) | Apache-2.0 SDK; uses Google Play services | both | MImic account and sync |
| desugar_jdk_libs 2.1.5 | GPL-2.0 with Classpath Exception | both | java.time on Android 7 |

Notes:
- Firebase and Credential Manager rely on Google Play services, which is proprietary. MImic uses
  it only as a system component for sign-in; the account is optional and MImic works without it.
- At runtime InnerTubeX downloads a player-configuration file from the public `ZemerTeam/zemer-cipher`
  GitHub repository, and YouTube's own player JavaScript; neither is bundled in the APK.
- LRCLIB (https://lrclib.net) is queried for lyrics; lyrics remain the property of their rights
  holders and are only cached on the phone.
- Earlier libraries (youtubedl-android GPL-3.0, Media3, Room, Coil, OkHttp, kyant backdrop, …)
  are listed in `docs/DEPENDENCIES.md`.

## Distribution (GPL-3.0)

If you give the APK to anyone else, the GPL requires that they can get the complete corresponding
source code (this repository at the matching tag), under the GPL, with these notices kept.
