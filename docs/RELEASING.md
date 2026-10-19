# Releasing MImic

Releases are GitHub Releases of <https://github.com/enoluca22-dota2/MImic>. The app's
Settings → App Updates reads them, so **every release must follow these rules** or phones won't
see (or won't accept) it.

## Rules

1. **Version** — change only `versionName` in `app/build.gradle.kts`. `versionCode` is derived:
   `X.Y.Z` → `(X·1 000 000 + Y·1 000 + Z)·100 + 99`; pre-releases `X.Y.Z-alpha.N | -beta.N | -rc.N`
   get `N`, `33+N`, `66+N` instead of 99. Every release therefore has a unique, increasing code.
   Never reuse or lower a version.
2. **Channel** — a version with a suffix (`4.1.0-beta.1`) is published as a GitHub *pre-release*
   and is only offered to the Beta channel. A plain version (`4.1.0`) is Stable.
3. **Changelog** — `CHANGELOG.md` must have a `## <versionName>` section; it becomes the release
   notes shown in the app.
4. **Signing** — only with MImic's release key (the certificate pinned as
   `RELEASE_CERT_SHA256`, SHA-256 `C0:3C:51:07:…:4F:88:08:7D`). Never generate a replacement key:
   phones would refuse the update. The key is the maintainer's debug keystore
   (`~/.android/debug.keystore`, backup in `MImic-backups/`), referenced by the gitignored
   `keystore.properties`.
5. **Assets** (created by the script):
   `MImic-vX.Y.Z-full.apk` (universal), `MImic-vX.Y.Z-full-arm64-v8a.apk`,
   `…-full-armeabi-v7a.apk`, `…-full-x86_64.apk`, `MImic-vX.Y.Z-standard.apk`, `SHA256SUMS.txt`.

## Steps

```sh
# 1. bump versionName, add the CHANGELOG section, commit (and push to main: CI runs the tests)
scripts/release.sh             # dry run: tests, lint, signed build, verification → dist/
scripts/release.sh --publish   # same, then tag vX.Y.Z, push, create the GitHub Release, verify downloads
```

The script refuses to publish when: tests or lint fail, `keystore.properties` is missing, any APK
isn't signed with the pinned certificate, an APK's package/versionCode/versionName is wrong, the
tree has uncommitted changes, or the tag/release already exists. After publishing it checks that
every asset is downloadable.

Then on a phone: Settings → App Updates → **Check for Updates** → Download Update → Install
Update (Android asks to confirm). Data is kept; no uninstall is ever needed.

## Why not sign in GitHub Actions?

Decision (2026-10-19): the signing key stays on the maintainer's machine. CI (`.github/workflows/ci.yml`)
runs unit tests, lint and a debug build on every push; publishing is a local, deliberate step.
To move signing into Actions later, add the keystore as encrypted secrets and a tag-triggered job
— never commit the key.
