#!/usr/bin/env bash
# MImic release — build, verify, sign (locally) and publish one GitHub Release.
#
#   scripts/release.sh            dry run: tests + signed build + verification into dist/ (publishes nothing)
#   scripts/release.sh --publish  the same, then creates the GitHub Release vX.Y.Z and uploads the assets
#
# The version comes ONLY from versionName in app/build.gradle.kts. A version with a suffix
# (4.1.0-beta.1) becomes a GitHub pre-release = the in-app Beta channel; stable users never see it.
# Signing happens on this machine with keystore.properties (gitignored); the key never leaves it.
# Every APK must carry the pinned release certificate (RELEASE_CERT_SHA256) or the script stops.
# Requires: Git Bash, JDK (Android Studio's jbr), Android SDK build-tools, gh (logged in).
set -euo pipefail
cd "$(dirname "$0")/.."

PUBLISH=false
[[ "${1:-}" == "--publish" ]] && PUBLISH=true

die() { echo "ERROR: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1) || true
[[ -n "$BT" ]] || die "Android build-tools not found under $SDK"
APKSIGNER="$BT/apksigner.bat"; [[ -f "$APKSIGNER" ]] || APKSIGNER="$BT/apksigner"
AAPT2="$BT/aapt2.exe"; [[ -f "$AAPT2" ]] || AAPT2="$BT/aapt2"

VERSION=$(sed -nE 's/^\s*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts | head -1)
[[ -n "$VERSION" ]] || die "versionName not found in app/build.gradle.kts"
TAG="v$VERSION"
PIN=$(sed -nE 's/.*"RELEASE_CERT_SHA256", "\\"([0-9A-Fa-f]{64})\\"".*/\1/p' app/build.gradle.kts | tr 'A-F' 'a-f')
[[ ${#PIN} -eq 64 ]] || die "RELEASE_CERT_SHA256 not found in app/build.gradle.kts"
PRERELEASE=false; [[ "$VERSION" == *-* ]] && PRERELEASE=true
echo "MImic $TAG  (pre-release: $PRERELEASE, publish: $PUBLISH)"

step "Checks"
[[ -f keystore.properties ]] || die "keystore.properties missing — releases must be signed with MImic's release key (docs/RELEASING.md)"
grep -q "^## $VERSION" CHANGELOG.md || die "CHANGELOG.md has no '## $VERSION' section"
if $PUBLISH; then
  [[ -z "$(git status --porcelain -- app scripts docs CHANGELOG.md README.md)" ]] || die "uncommitted changes — commit first"
  gh release view "$TAG" >/dev/null 2>&1 && die "release $TAG already exists — bump versionName"
  git rev-parse "$TAG" >/dev/null 2>&1 && die "tag $TAG already exists — bump versionName"
fi

step "Unit tests (both editions)"
./gradlew --no-configuration-cache :app:testFullDebugUnitTest :app:testStandardDebugUnitTest

step "Lint"
./gradlew --no-configuration-cache :app:lintFullRelease

step "Signed release build"
./gradlew --no-configuration-cache :app:assembleFullRelease :app:assembleStandardRelease

step "Collect + verify"
rm -rf dist && mkdir -p dist
cp app/build/outputs/apk/full/release/app-full-universal-release.apk        "dist/MImic-$TAG-full.apk"
cp app/build/outputs/apk/full/release/app-full-arm64-v8a-release.apk       "dist/MImic-$TAG-full-arm64-v8a.apk"
cp app/build/outputs/apk/full/release/app-full-armeabi-v7a-release.apk     "dist/MImic-$TAG-full-armeabi-v7a.apk"
cp app/build/outputs/apk/full/release/app-full-x86_64-release.apk          "dist/MImic-$TAG-full-x86_64.apk"
STD=app/build/outputs/apk/standard/release/app-standard-universal-release.apk
[[ -f "$STD" ]] || STD=app/build/outputs/apk/standard/release/app-standard-release.apk
cp "$STD" "dist/MImic-$TAG-standard.apk"
# Same formula as versionCodeFor in app/build.gradle.kts (stable suffix 99; alpha N, beta 33+N, rc 66+N).
CODE=$(awk -v v="$VERSION" 'BEGIN {
  split(v, a, "-"); split(a[1], n, "."); pre = tolower(substr(v, length(a[1]) + 2))
  k = 1; if (pre != "") { m = split(pre, p, "."); if (p[m] ~ /^[0-9]+$/) k = p[m] + 0; if (k < 1) k = 1; if (k > 32) k = 32 }
  s = 99; if (pre ~ /^alpha/) s = k; else if (pre ~ /^beta/) s = 33 + k; else if (pre ~ /^rc/) s = 66 + k; else if (pre != "") s = k
  printf "%d", (n[1] * 1000000 + n[2] * 1000 + n[3]) * 100 + s }')
for apk in dist/*.apk; do
  certs=$("$APKSIGNER" verify --print-certs "$apk" | sed -nE 's/.*certificate SHA-256 digest: ([0-9a-f]{64}).*/\1/p' | sort -u)
  [[ "$certs" == "$PIN" ]] || die "$apk is not signed with the pinned release certificate (got: $certs)"
  badging=$("$AAPT2" dump badging "$apk" | head -1)
  grep -q "name='com.example.mimic'" <<<"$badging" || die "$apk has the wrong package: $badging"
  grep -q "versionCode='$CODE'" <<<"$badging" || die "$apk versionCode isn't $CODE: $badging"
  grep -q "versionName='$VERSION'" <<<"$badging" || die "$apk versionName isn't $VERSION: $badging"
  echo "OK  $(basename "$apk")  $(stat -c %s "$apk") bytes"
done
(cd dist && sha256sum *.apk > SHA256SUMS.txt)
cat dist/SHA256SUMS.txt

# Release notes = this version's CHANGELOG section (plain text; the app shows it as-is).
awk -v v="## $VERSION" 'index($0, v)==1 {on=1; next} /^## / && on {exit} on' CHANGELOG.md > dist/NOTES.md
echo >> dist/NOTES.md
echo "Install: update from Settings → App Updates in MImic, or download the APK for your phone (arm64-v8a for almost every phone; the plain -full.apk works everywhere). Verify with SHA256SUMS.txt." >> dist/NOTES.md

if ! $PUBLISH; then
  echo; echo "Dry run complete — dist/ is ready. Re-run with --publish to create $TAG on GitHub."
  exit 0
fi

step "Publish $TAG"
git tag -a "$TAG" -m "MImic $TAG"
git push origin HEAD "$TAG"
FLAGS=(); $PRERELEASE && FLAGS+=(--prerelease)
gh release create "$TAG" dist/*.apk dist/SHA256SUMS.txt --title "MImic $TAG" --notes-file dist/NOTES.md --verify-tag "${FLAGS[@]}"

step "Verify the published release"
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
gh api "repos/$REPO/releases/tags/$TAG" -q '.assets[].name' | sort
for f in dist/*.apk dist/SHA256SUMS.txt; do
  name=$(basename "$f")
  code=$(curl -sSIL -o /dev/null -w '%{http_code}' "https://github.com/$REPO/releases/download/$TAG/$name")
  [[ "$code" == "200" ]] || die "$name is not downloadable (HTTP $code)"
done
echo "All assets downloadable. Release: https://github.com/$REPO/releases/tag/$TAG"
