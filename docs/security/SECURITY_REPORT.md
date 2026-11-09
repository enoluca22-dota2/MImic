# MImic — security report (4.7.0-beta.4, 2026-11-09)

Scope: the MImic Android app (full + standard editions), its repository, GitHub settings, CI and
release process. Method: source review, Gitleaks (history + tree), OSV (resolved dependency
graph), merged-manifest analysis, Android Lint, unit tests, emulator tests. Dashboard:
`python scripts/security/dashboard.py` (writes `build/security/dashboard.html`); machine-readable:
`docs/security/controls.json` and, after a gate run, `build/security/gate.json` / `osv.json`.

## Summary

| | Baseline (4.7.0-beta.3) | Now (4.7.0-beta.4) |
|---|---|---|
| Controls passed | 33 / 40 | **36 / 40** |
| Controls failed | 6 | **3** |
| Not applicable | 1 | 1 |
| Not verified | 0 | 0 |
| Critical vulnerabilities | 0 | 0 |
| High-risk findings | 1 (dependencies: 19 HIGH advisories) | 0 unreviewed (23 reviewed advisories remain, judged not reachable) |
| Dependency advisories (OSV) | 32 | 23 reviewed, 0 blocking |
| Secret exposure | 0 secrets; tailnet details in a public doc | 0 secrets; doc redacted (old commits keep it) |
| APK signing | pinned certificate, verified | unchanged, verified by the new gate |
| Release readiness | — | gate PASS (see SECURITY_RELEASE_READINESS.md) |

## What was already secure
Keystore-encrypted Navidrome password, encrypted YouTube session, salted-token Subsonic auth, an
updater that pins the release certificate and refuses downgrades/other packages, private
FileProviders, immutable PendingIntents, no TLS overrides, redacted diagnostics, no telemetry,
secrets never committed, secret scanning + push protection, branch protection.

## What was found and fixed
1. **Cloud backup included sign-ins** (Medium) — now excluded (control 14).
2. **Cleartext allowed everywhere** (Low) — HTTPS-only for GitHub/Google/YouTube/LRCLIB, system CAs only (24).
3. **Vulnerable libraries** — jsoup, Guava, protobuf-javalite, Jackson upgraded; 32 → 23 advisories, the rest reviewed (37).
4. **Updater download unbounded** (Low) — size cap (29).
5. **Tailnet details in a public doc** (Low) — redacted (6).
6. Hardening without a prior vulnerability: safe download names, WebView file access off, identity checks unit-tested.
7. Supply chain: pinned Actions, Gitleaks/OSV/manifest gate before every release, CodeQL, Dependabot, SBOM.

## Open findings — your decision needed
- **#6 Credential exposure in docs, screenshots, logs, release assets** (Low): Fixed in the tree. Owner decision: leave history as is (tailnet addresses are reachable only by your own Tailscale devices) or rewrite public history.
- **#16 Native libraries and dynamically loaded code** (Medium): Accepted risk unless you decide otherwise: verifying yt-dlp's GPG signature in-app is a larger change; turning auto-update off would break downloads as YouTube changes.
- **#37 Vulnerable and outdated dependencies** (Low): Owner decision: the remaining fixes need Android 8+ (minSdk 26) or replacing youtubedl-android.
- **Gradle dependency verification** (`verification-metadata.xml`) was not enabled: it pins a hash
  for every one of ~350 artifacts and breaks every Android Studio/AGP/Dependabot update until it is
  regenerated. Recommended only if you accept that maintenance.
- **GitHub "non-provider patterns" secret scanning** could not be enabled (not available for this
  account plan); provider secret scanning and push protection are on.
- **R8/minification** stays off (control 17, not a security control for open-source code).

## What could not be verified
Navidrome sign-in/streaming/reconnection on the emulator (no local test credentials; Docker off),
downloads and offline playback on the emulator (emulator stopped by the OS for low memory), fresh
install (would need uninstalling), a differently-signed APK on a device (no second key by policy;
unit-tested), the in-app update on an emulator after publishing, the new GitHub workflows (run
after push), physical Samsung/Xiaomi phones.

## All 40 controls

{table}

Details and evidence per control: SECURITY_CHECKLIST.md.
