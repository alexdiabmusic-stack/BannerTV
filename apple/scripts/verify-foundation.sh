#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Ask Xcode for a destination the scheme supports on its selected SDK.
list=$(xcodebuild -showdestinations -project 'Stadia TV.xcodeproj' -scheme MyApp)
destination=$(python3 -c 'import sys,re
for line in sys.stdin:
    match=re.search(r"id:([0-9a-fA-F-]{36}),",line)
    if "platform:iOS Simulator" in line and "name:iPhone" in line and match:
        print(match[1]);break
else: raise SystemExit("An available iPhone simulator is required")' <<< "$list")
scratch=$(mktemp -d "${TMPDIR:-/tmp}/banner-xcode.XXXXXX")
common=(-project 'Stadia TV.xcodeproj' -configuration Debug -destination "id=$destination" CODE_SIGNING_ALLOWED=NO)
xcodebuild build -scheme MyApp "${common[@]}" -derivedDataPath "$scratch/native" BANNER_KMP_ENABLED=NO
TEST_RUNNER_BANNER_LIVE_PROBE=1 xcodebuild test -scheme MyApp "${common[@]}" \
    -derivedDataPath "$scratch/shared" BANNER_KMP_ENABLED=YES -parallel-testing-enabled NO \
    -only-testing:MyAppUnitTests/BannerFoundationDiscoveryTests/testRealSharedNHLDiscoveryPreservesAppleNavigationIDs \
    -resultBundlePath "$scratch/live.xcresult"
xcrun xcresulttool get test-results tests --path "$scratch/live.xcresult" > "$scratch/live.json"
python3 - "$scratch/live.json" <<'PY'
import json,sys
def passed(node):
    if isinstance(node, list):
        return any(passed(child) for child in node)
    if isinstance(node, dict):
        name = str(node.get("name","")) + str(node.get("nodeIdentifier",""))
        if "testRealSharedNHLDiscoveryPreservesAppleNavigationIDs" in name and node.get("result") == "Passed":
            return True
        return any(passed(child) for child in node.values())
    return False
with open(sys.argv[1]) as f:
    if not passed(json.load(f)):
        raise SystemExit("The real Swift-to-Kotlin NHL test must execute and pass.")
PY
xcodebuild test -scheme BannerRegression "${common[@]}" -derivedDataPath "$scratch/shared" BANNER_KMP_ENABLED=YES \
    -only-testing:MyAppTests -only-testing:MyAppUnitTests -parallel-testing-enabled NO
