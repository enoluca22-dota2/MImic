#!/usr/bin/env python3
"""Checks a MERGED AndroidManifest.xml (app + libraries) against MImic's security policy.

Usage: check_manifest.py <merged AndroidManifest.xml> [--json out.json]

Fails (exit 1) when:
  * the application is debuggable or testOnly, or has no networkSecurityConfig;
  * a component is exported that is not in EXPECTED_EXPORTED (a new exported component must be
    reviewed and added here on purpose);
  * an exported provider exists at all, or a provider grants URI permissions while exported;
  * the package is not com.example.mimic.
"""
import json
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"

# Reviewed exported components. App: launcher (+ share/VIEW filters) and the Media3 session that
# Bluetooth, Android Auto and the system media controls must reach. Libraries: entries guarded by a
# system-only permission (DUMP / BIND_JOB_SERVICE / Google Play services) or required by them.
EXPECTED_EXPORTED = {
    "com.example.mimic.MainActivity": "launcher, Listen Together invites, share/open links (full)",
    "com.example.mimic.player.PlaybackService": "MediaSessionService for media controls / Bluetooth / Auto",
}
# Library components reviewed and accepted as shipped by their SDK (re-review on SDK updates).
REVIEWED_LIBRARY = {
    # Firebase Auth's OAuth / reCAPTCHA redirect handlers. They act only on the result of a flow the
    # app itself started (MImic signs in with a Google ID token, so neither flow is ever started).
    "com.google.firebase.auth.internal.GenericIdpActivity": "Firebase Auth SDK, reviewed 2026-11-09",
    "com.google.firebase.auth.internal.RecaptchaActivity": "Firebase Auth SDK, reviewed 2026-11-09",
    # Bound only by Google Play services (signature-level REVOCATION_NOTIFICATION permission).
    "com.google.android.gms.auth.api.signin.RevocationBoundService": "Play services auth, reviewed 2026-11-09",
}
# Library components may be exported only when protected by one of these permissions.
SYSTEM_PERMISSIONS = {
    "android.permission.DUMP",
    "android.permission.BIND_JOB_SERVICE",
    "com.google.android.c2dm.permission.SEND",
    "android.permission.BIND_REMOTEVIEWS",
}


def main():
    path = sys.argv[1]
    out = sys.argv[sys.argv.index("--json") + 1] if "--json" in sys.argv else None
    root = ET.parse(path).getroot()
    problems, exported = [], []
    if root.get("package") not in (None, "com.example.mimic"):
        problems.append(f"package is {root.get('package')}")
    app = root.find("application")
    if app.get(A + "debuggable") == "true":
        problems.append("application is debuggable")
    if app.get(A + "testOnly") == "true":
        problems.append("application is testOnly")
    if not app.get(A + "networkSecurityConfig"):
        problems.append("no networkSecurityConfig")
    for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
        for el in app.findall(tag):
            name = el.get(A + "name")
            has_filter = el.find("intent-filter") is not None
            flag = el.get(A + "exported")
            is_exported = flag == "true" or (flag is None and has_filter)
            if not is_exported:
                continue
            perm = el.get(A + "permission")
            exported.append({"type": tag, "name": name, "permission": perm})
            if name in EXPECTED_EXPORTED or name in REVIEWED_LIBRARY:
                continue
            if tag == "provider":
                problems.append(f"exported provider {name}")
            elif perm in SYSTEM_PERMISSIONS:
                continue
            else:
                problems.append(f"unreviewed exported {tag} {name} (permission={perm})")
    report = {"manifest": path, "exported": exported, "problems": problems, "ok": not problems}
    if out:
        with open(out, "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=2)
    for e in exported:
        mark = "expected" if e["name"] in EXPECTED_EXPORTED else "reviewed-library" if e["name"] in REVIEWED_LIBRARY else ("system-permission" if e["permission"] in SYSTEM_PERMISSIONS else "REVIEW")
        print(f"  exported {e['type']:8} {e['name']}  [{mark}]")
    if problems:
        print("MANIFEST CHECK FAILED:")
        for p in problems:
            print("  - " + p)
        return 1
    print("manifest check passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
