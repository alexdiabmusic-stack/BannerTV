#!/usr/bin/env python3
"""Provision the existing public NFL/PGA web-client defaults for both native apps."""
import argparse
import hashlib
import re
import urllib.request
from pathlib import Path

PIN = "e4946252ffd7e5bb78145069cd819e81b985286f"
FILES = {
    "MyApp/NFL/API/NFLClientConfiguration.swift": "c05553adb460081d86fe7caf1fbc4e4cca97d2c0f5c74df499e176cc9cd33fdb",
    "MyApp/PGA/API/PGATourProviderConfiguration.swift": "bc63a85df3bcc40fc0a128fdd7df481050ac039834cb9e02eaa8000fb8cfd015",
}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, help="Optional checkout of the pinned Apple source")
    args = parser.parse_args()
    destination = Path(__file__).resolve().parents[1] / "apple/Config/ProviderDefaults.xcconfig"
    if destination.exists():
        print("Local provider configuration already exists.")
        return
    texts = []
    for name, digest in FILES.items():
        if args.reference:
            data = (args.reference / name).read_bytes()
        else:
            url = f"https://raw.githubusercontent.com/alexdiabmusic-stack/StadiaTV-iOS/{PIN}/{name}"
            with urllib.request.urlopen(url, timeout=20) as response:
                data = response.read(64 * 1024)
        if hashlib.sha256(data).hexdigest() != digest:
            raise SystemExit("Provider reference differs from the pinned version.")
        texts.append(data.decode())
    names = {"NFL_WEB_CLIENT_KEY": "clientKey =", "NFL_WEB_CLIENT_SECRET": "clientSecret =", "PGA_WEB_API_KEY": "currentFrontendAPIKey:"}
    lines = []
    for setting, symbol in names.items():
        match = re.search(re.escape(symbol) + r'\s*"([a-zA-Z0-9_./+=-]+)"', "\n".join(texts))
        if not match:
            raise SystemExit("Provider reference format changed.")
        lines.append(f"{setting} = {match[1]}\n")
    with destination.open("x") as output:
        destination.chmod(0o600)
        output.writelines(lines)
    print("Public sports defaults are ready.")

if __name__ == "__main__":
    main()
