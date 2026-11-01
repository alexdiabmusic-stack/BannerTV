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
if [[ -n "${BANNER_XCODE_SCRATCH:-}" ]]; then
    scratch=$BANNER_XCODE_SCRATCH
    mkdir -p "$scratch"
else
    scratch=$(mktemp -d "${TMPDIR:-/tmp}/banner-xcode.XXXXXX")
fi
common=(-project 'Stadia TV.xcodeproj' -configuration Debug -destination "id=$destination" CODE_SIGNING_ALLOWED=NO)
verify_discovery() {
    xcodebuild build -scheme MyApp "${common[@]}" -derivedDataPath "$scratch/native" BANNER_KMP_ENABLED=NO
    local action=test
    for iteration in 1 2 3; do
        TEST_RUNNER_BANNER_LIVE_PROBE=1 xcodebuild "$action" -scheme MyApp "${common[@]}" \
            -derivedDataPath "$scratch/shared" BANNER_KMP_ENABLED=YES -parallel-testing-enabled NO \
            -only-testing:MyAppUnitTests/BannerFoundationDiscoveryTests/testRealSharedNHLDiscoveryPreservesAppleNavigationIDs \
            -only-testing:MyAppUnitTests/BannerFoundationDiscoveryTests/testSharedScorePreservesTheNativeMatchUsedForNavigationAndPlayback \
            -resultBundlePath "$scratch/live-$iteration.xcresult"
        xcrun xcresulttool get test-results tests --path "$scratch/live-$iteration.xcresult" > "$scratch/live-$iteration.json"
        python3 - "$scratch/live-$iteration.json" <<'PY'
import json,sys
def passed(node, required):
    if isinstance(node, list):
        return any(passed(child, required) for child in node)
    if isinstance(node, dict):
        name = str(node.get("name","")) + str(node.get("nodeIdentifier",""))
        if required in name and node.get("result") == "Passed":
            return True
        return any(passed(child, required) for child in node.values())
    return False
with open(sys.argv[1]) as f:
    nodes = json.load(f)
    for name in ("testRealSharedNHLDiscoveryPreservesAppleNavigationIDs",
                 "testSharedScorePreservesTheNativeMatchUsedForNavigationAndPlayback"):
        if not passed(nodes, name):
            raise SystemExit(f"{name} must execute and pass.")
PY
        action=test-without-building
    done
}

verify_tests() {
    xcodebuild test -scheme "$1" "${common[@]}" -derivedDataPath "$scratch/shared" BANNER_KMP_ENABLED=YES \
        "-only-testing:$2" -parallel-testing-enabled NO
}

case "${1:-all}" in
    discovery) verify_discovery ;;
    app-tests) verify_tests MyApp MyAppUnitTests ;;
    auxiliary-tests) verify_tests BannerRegression MyAppTests ;;
    all) verify_discovery; verify_tests MyApp MyAppUnitTests; verify_tests BannerRegression MyAppTests ;;
    *) echo "Use discovery, app-tests, auxiliary-tests or all." >&2; exit 2 ;;
esac
