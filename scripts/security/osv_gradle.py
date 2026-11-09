#!/usr/bin/env python3
"""Checks resolved Gradle dependencies against the OSV vulnerability database (osv.dev).

Input: one or more files produced by
    ./gradlew -q :app:dependencies --configuration <variant>RuntimeClasspath
Only public Maven coordinates (group:artifact:version) are sent to api.osv.dev.

Output: a JSON report (--json) and a short text summary. Exit code 1 when a vulnerability with
severity CRITICAL or HIGH is found and is not listed in the allowlist (--allow FILE: one OSV id per
line, '#' comments; every entry should say why it does not apply to MImic).
"""
import argparse
import json
import re
import sys
import urllib.request

LINE = re.compile(r"[+\x5c]--- ([\w.\-]+):([\w.\-]+):([^\s()]+)(?: -> ([^\s()]+))?")


def coordinates(paths):
    found = set()
    for path in paths:
        with open(path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                m = LINE.search(line)
                if not m:
                    continue
                group, artifact, declared, resolved = m.groups()
                version = resolved or declared
                if version.startswith("{") or not re.match(r"^[\w.\-+]+$", version):
                    continue
                found.add((group, artifact, version))
    return sorted(found)


def post(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)


def get(url):
    with urllib.request.urlopen(url, timeout=60) as resp:
        return json.load(resp)


def severity(vuln):
    label = (vuln.get("database_specific") or {}).get("severity")
    if isinstance(label, str) and label:
        return label.upper().replace("MODERATE", "MEDIUM")
    return "UNKNOWN"


def write_sbom(path, deps):
    """Minimal CycloneDX SBOM: every resolved Maven component with its package URL."""
    import datetime
    import uuid
    doc = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": f"urn:uuid:{uuid.uuid4()}",
        "version": 1,
        "metadata": {
            "timestamp": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "component": {"type": "application", "name": "MImic", "group": "com.example.mimic"},
            "tools": {"components": [{"type": "application", "name": "scripts/security/osv_gradle.py"}]},
        },
        "components": [
            {"type": "library", "group": g, "name": a, "version": v, "purl": f"pkg:maven/{g}/{a}@{v}"}
            for g, a, v in deps
        ],
    }
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("trees", nargs="+")
    ap.add_argument("--json", help="write the full report here")
    ap.add_argument("--allow", help="allowlist of OSV ids that were reviewed and do not apply")
    ap.add_argument("--sbom", help="also write a CycloneDX 1.5 SBOM (JSON) of the resolved dependencies")
    args = ap.parse_args()

    allowed = set()
    if args.allow:
        with open(args.allow, encoding="utf-8") as fh:
            allowed = {l.split("#")[0].strip() for l in fh if l.split("#")[0].strip()}

    deps = coordinates(args.trees)
    if not deps:
        print("no dependencies parsed — is the input a Gradle dependency tree?", file=sys.stderr)
        return 2
    if args.sbom:
        write_sbom(args.sbom, deps)
    results = []
    for i in range(0, len(deps), 500):
        chunk = deps[i:i + 500]
        queries = [{"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v} for g, a, v in chunk]
        answer = post("https://api.osv.dev/v1/querybatch", {"queries": queries})
        for (g, a, v), res in zip(chunk, answer.get("results", [])):
            for vuln in res.get("vulns", []) or []:
                results.append({"package": f"{g}:{a}", "version": v, "id": vuln["id"]})

    details = {}
    for vid in sorted({r["id"] for r in results}):
        try:
            details[vid] = get(f"https://api.osv.dev/v1/vulns/{vid}")
        except Exception as e:  # report the id even when its details can't be fetched
            details[vid] = {"id": vid, "summary": f"(details unavailable: {e.__class__.__name__})"}
    for r in results:
        d = details[r["id"]]
        r["severity"] = severity(d)
        r["summary"] = (d.get("summary") or d.get("details") or "").strip().splitlines()[0][:160] if (d.get("summary") or d.get("details")) else ""
        r["aliases"] = d.get("aliases", [])
        r["allowed"] = r["id"] in allowed or any(a in allowed for a in r["aliases"])

    blocking = [r for r in results if r["severity"] in ("CRITICAL", "HIGH") and not r["allowed"]]
    report = {"dependencies_checked": len(deps), "vulnerabilities": results, "blocking": len(blocking)}
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(report, fh, indent=2)
    print(f"{len(deps)} resolved dependencies checked against OSV; {len(results)} advisories; {len(blocking)} blocking")
    for r in sorted(results, key=lambda r: (r["severity"], r["package"])):
        flag = "allowed" if r["allowed"] else r["severity"]
        print(f"  [{flag}] {r['package']} {r['version']}  {r['id']}  {r['summary']}")
    return 1 if blocking else 0


if __name__ == "__main__":
    sys.exit(main())
