#!/usr/bin/env python3
"""Print only the crashed app thread and its symbols from an Apple CI simulator run."""
import json
from pathlib import Path
import subprocess
import sys

scratch = Path(sys.argv[1])
products = scratch / "shared/Build/Products/Debug-iphonesimulator/StadiaTV.app"
roots = (Path.home() / "Library/Logs/DiagnosticReports", scratch / "diagnostics")
reports = set()
for root in roots:
    if root.is_dir():
        reports.update(root.rglob("*.ips"))
        reports.update(root.rglob("*.crash"))

reported = set()
for path in sorted(reports):
    raw = path.read_text(errors="replace")
    try:
        # IPS has a one-line metadata object followed by the report.
        report = json.loads(raw.partition("\n")[2])
    except ValueError:
        if "Process:" in raw and ("StadiaTV [" in raw or "StadiaTV.app/" in raw):
            lines = raw.splitlines()
            for i, line in enumerate(lines):
                if line.startswith("Thread ") and "Crashed:" in line:
                    print(line)
                    for frame in lines[i + 1:i + 50]:
                        if not frame.strip():
                            break
                        print(frame)
        continue
    if not isinstance(report, dict) or report.get("procName") != "StadiaTV" or report.get("incident") in reported:
        continue
    reported.add(report.get("incident"))
    images = report.get("usedImages", [])
    print("App exception", report.get("exception", {}).get("type"), report.get("termination", {}).get("indicator"))
    for i, thread in enumerate(report.get("threads", [])):
        if not thread.get("triggered") and i != report.get("faultingThread"):
            continue
        print("Faulting thread", i, thread.get("queue", ""))
        for frame in thread.get("frames", [])[:55]:
            index = frame.get("imageIndex", -1)
            img = images[index] if 0 <= index < len(images) else {}
            name = img.get("name", "?")
            offset = frame.get("imageOffset", 0)
            symbol = frame.get("symbol")
            binary = products / name
            if not symbol and name in ("StadiaTV", "StadiaTV.debug.dylib") and binary.is_file():
                base = img.get("base", 0)
                result = subprocess.run(["xcrun", "atos", "-arch", "arm64", "-o", str(binary), "-l", hex(base), hex(base + offset)],
                                        capture_output=True, text=True)
                symbol = result.stdout.strip()
            print(name, hex(offset), symbol or "?")
if not reported:
    print("No structured StadiaTV crash report found.")
