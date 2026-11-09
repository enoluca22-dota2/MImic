#!/usr/bin/env bash
# MImic local security gate — run by scripts/release.sh after the signed build, before publishing.
# Also usable on its own after a release build:  scripts/security/security_gate.sh
#
# Blocks the release (exit 1) on: a secret in the tree or history (Gitleaks), an unreviewed
# HIGH/CRITICAL dependency advisory (OSV), a debuggable/testOnly APK or an unreviewed exported
# component, or a release APK whose signer is not the pinned certificate.
# Writes build/security/gate.json (machine-readable) for scripts/security/dashboard.py.
set -uo pipefail
cd "$(dirname "$0")/../.."
OUT=build/security; mkdir -p "$OUT"
PIN=c03c51075a49a049387185c775626074368aa9f4220eb5ba0e74cc4a4f88087d
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}}"
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
APKSIGNER="$BT/apksigner.bat"; [[ -f "$APKSIGNER" ]] || APKSIGNER="$BT/apksigner"
AAPT2="$BT/aapt2.exe"; [[ -f "$AAPT2" ]] || AAPT2="$BT/aapt2"
GITLEAKS="$(command -v gitleaks || ls "$LOCALAPPDATA"/Microsoft/WinGet/Links/gitleaks.exe 2>/dev/null || true)"
PY="$(command -v python3 || command -v python)"

declare -A RESULT
fail=0
mark() { RESULT[$1]=$2; [[ $2 == FAIL ]] && fail=1; echo "  [$2] $1"; }

echo "==> Secrets (Gitleaks)"
if [[ -z "$GITLEAKS" ]]; then
  mark secrets FAIL; echo "      gitleaks is not installed (winget install Gitleaks.Gitleaks)"
elif "$GITLEAKS" git . --redact --no-banner --config .gitleaks.toml --log-opts=--all --report-format json --report-path "$OUT/gitleaks-history.json" >/dev/null 2>&1 \
  && "$GITLEAKS" dir . --redact --no-banner --config .gitleaks.toml --report-format json --report-path "$OUT/gitleaks-tree.json" >/dev/null 2>&1; then
  mark secrets PASS
else
  mark secrets FAIL; echo "      see $OUT/gitleaks-*.json (values redacted)"
fi

echo "==> Dependencies (OSV)"
./gradlew -q --no-configuration-cache :app:dependencies --configuration fullReleaseRuntimeClasspath > "$OUT/deps-full.txt" 2>&1
./gradlew -q --no-configuration-cache :app:dependencies --configuration standardReleaseRuntimeClasspath > "$OUT/deps-standard.txt" 2>&1
if PYTHONIOENCODING=utf8 "$PY" -I scripts/security/osv_gradle.py "$OUT/deps-full.txt" "$OUT/deps-standard.txt" --allow scripts/security/osv-allowlist.txt --json "$OUT/osv.json" --sbom "$OUT/sbom.cdx.json" > "$OUT/osv.txt" 2>&1; then
  mark dependencies PASS
else
  mark dependencies FAIL
fi
head -1 "$OUT/osv.txt" | sed 's/^/      /'

echo "==> Merged release manifests"
mf=PASS
for v in fullRelease standardRelease; do
  m=$(find app/build/intermediates -path "*merged_manifest*/$v/*" -name AndroidManifest.xml 2>/dev/null | head -1)
  if [[ -z "$m" ]]; then echo "      no merged manifest for $v (build it first)"; mf=FAIL; continue; fi
  "$PY" -I scripts/security/check_manifest.py "$m" --json "$OUT/manifest-$v.json" > "$OUT/manifest-$v.txt" 2>&1 || mf=FAIL
  tail -1 "$OUT/manifest-$v.txt" | sed "s/^/      $v: /"
done
mark manifest "$mf"

echo "==> Release APKs (dist/)"
apks=(dist/*.apk)
if [[ ! -e "${apks[0]}" ]]; then
  mark apk_signing "NOT VERIFIED"; echo "      dist/ has no APKs"
else
  ap=PASS
  for apk in "${apks[@]}"; do
    certs=$("$APKSIGNER" verify --print-certs "$apk" 2>/dev/null | sed -nE 's/.*certificate SHA-256 digest: ([0-9a-f]{64}).*/\1/p' | sort -u)
    badging=$("$AAPT2" dump badging "$apk" 2>/dev/null)
    if [[ "$certs" != "$PIN" ]]; then echo "      $apk: signer $certs"; ap=FAIL; fi
    if grep -q "application-debuggable" <<<"$badging"; then echo "      $apk: DEBUGGABLE"; ap=FAIL; fi
    if grep -q "testOnly" <<<"$badging"; then echo "      $apk: testOnly"; ap=FAIL; fi
    grep -q "package: name='com.example.mimic'" <<<"$badging" || { echo "      $apk: wrong package"; ap=FAIL; }
  done
  mark apk_signing "$ap"
fi

{
  printf '{\n  "generated": "%s",\n  "commit": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(git rev-parse --short HEAD)"
  printf '  "secrets": "%s",\n  "dependencies": "%s",\n  "manifest": "%s",\n  "apk_signing": "%s",\n' "${RESULT[secrets]}" "${RESULT[dependencies]}" "${RESULT[manifest]}" "${RESULT[apk_signing]}"
  printf '  "release_ready": %s\n}\n' "$([[ $fail == 0 ]] && echo true || echo false)"
} > "$OUT/gate.json"
echo "    -> $OUT/gate.json"
if [[ $fail != 0 ]]; then echo "SECURITY GATE FAILED — not publishing. Fix the finding, or review it and record why it doesn't apply."; exit 1; fi
echo "Security gate passed."
