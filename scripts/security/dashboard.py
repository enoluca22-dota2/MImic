#!/usr/bin/env python3
"""MImic security dashboard (local, for the developer — not part of the app).

Reads docs/security/controls.json (the 40 audited controls) and, when present, the latest gate
results in build/security/ (gate.json, osv.json, written by scripts/security/security_gate.sh).
Prints a summary and writes build/security/dashboard.html. `--markdown` prints the control table
used in docs/security/SECURITY_CHECKLIST.md. Never reads or prints secrets.
"""
import html
import json
import os
import sys
from collections import Counter

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CONTROLS = os.path.join(ROOT, "docs", "security", "controls.json")
OUT = os.path.join(ROOT, "build", "security")
AREAS = {"A": "Repository and secrets", "B": "Android application", "C": "Authentication and encryption",
         "D": "Music, downloads and updates", "E": "Network, supply chain and monitoring"}


def load(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None


def markdown(controls):
    rows = ["| # | Area | Control | Baseline | Now | Severity | Changed in |", "|---|---|---|---|---|---|---|"]
    for c in controls:
        rows.append(f"| {c['id']} | {c['area']} | {c['title']} | {c['baseline']} | **{c['status']}** | {c['severity'] or '-'} | {c['changed_in'] or '-'} |")
    return "\n".join(rows)


def main():
    doc = load(CONTROLS)
    if not doc:
        print("docs/security/controls.json missing or invalid", file=sys.stderr)
        return 2
    controls = doc["controls"]
    if "--markdown" in sys.argv:
        print(markdown(controls))
        return 0
    gate = load(os.path.join(OUT, "gate.json")) or {}
    osv = load(os.path.join(OUT, "osv.json")) or {}
    now, base = Counter(c["status"] for c in controls), Counter(c["baseline"] for c in controls)
    open_fail = [c for c in controls if c["status"] == "FAIL"]
    sev = Counter(c["severity"] for c in open_fail)
    vulns = osv.get("vulnerabilities", [])
    summary = [
        ("Controls passed", f"{now['PASS']} / {len(controls)}  (baseline {base['PASS']})"),
        ("Controls failed", f"{now['FAIL']}  (baseline {base['FAIL']})"),
        ("Not verified", str(now["NOT VERIFIED"])),
        ("Not applicable", str(now["NOT APPLICABLE"])),
        ("Open critical findings", str(sev["Critical"])),
        ("Open high-risk findings", str(sev["High"])),
        ("Dependency advisories", f"{len(vulns)} total, {sum(1 for v in vulns if v.get('allowed'))} reviewed, {osv.get('blocking', 'n/a')} blocking" if osv else "run security_gate.sh"),
        ("Secret exposure (gate)", gate.get("secrets", "run security_gate.sh")),
        ("Manifest policy (gate)", gate.get("manifest", "run security_gate.sh")),
        ("APK signing (gate)", gate.get("apk_signing", "run security_gate.sh")),
        ("Release ready (gate)", str(gate.get("release_ready", "unknown"))),
        ("Gate run", f"{gate.get('generated', '-')} @ {gate.get('commit', '-')}"),
    ]
    width = max(len(k) for k, _ in summary)
    print(f"MImic security dashboard — {doc.get('version')}")
    for k, v in summary:
        print(f"  {k.ljust(width)}  {v}")
    if open_fail:
        print("  Open findings:")
        for c in open_fail:
            print(f"    #{c['id']} [{c['severity']}] {c['title']}")

    os.makedirs(OUT, exist_ok=True)
    cells = "".join(f"<tr><th>{html.escape(k)}</th><td>{html.escape(str(v))}</td></tr>" for k, v in summary)
    rows = "".join(
        f"<tr class='{c['status'].split()[0].lower()}'><td>{c['id']}</td><td>{html.escape(AREAS[c['area']])}</td>"
        f"<td>{html.escape(c['title'])}</td><td>{c['baseline']}</td><td><b>{c['status']}</b></td>"
        f"<td>{html.escape(c['severity'] or '-')}</td><td>{html.escape(c['evidence'])}"
        + (f"<br><i>{html.escape(c['remediation'])}</i>" if c["remediation"] else "") + "</td></tr>"
        for c in controls)
    page = f"""<!doctype html><meta charset="utf-8"><title>MImic security</title>
<style>body{{font:14px system-ui,sans-serif;margin:24px;max-width:1200px}}table{{border-collapse:collapse;width:100%;margin:12px 0}}
td,th{{border:1px solid #ccc;padding:6px;vertical-align:top;text-align:left}}tr.pass td:nth-child(5){{color:#11733a}}
tr.fail td:nth-child(5){{color:#b00020}}tr.not td:nth-child(5){{color:#666}}</style>
<h1>MImic security dashboard — {html.escape(str(doc.get('version')))}</h1><table>{cells}</table>
<h2>40 controls</h2><table><tr><th>#</th><th>Area</th><th>Control</th><th>Baseline</th><th>Now</th><th>Severity</th><th>Evidence / remediation</th></tr>{rows}</table>"""
    with open(os.path.join(OUT, "dashboard.html"), "w", encoding="utf-8") as fh:
        fh.write(page)
    print(f"  -> {os.path.join('build', 'security', 'dashboard.html')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
