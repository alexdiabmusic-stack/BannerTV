#!/bin/bash
set -euo pipefail

# Uses the selected Xcode, or DEVELOPER_DIR when testing with a different Xcode.
# This direct runner avoids SwiftPM's nested macro sandbox in Xcode's assistant.
cd "$(dirname "$0")/.."
nhl_build_dir=$(mktemp -d "${TMPDIR:-/tmp}/nhl-tests.XXXXXX")
trap 'rm -rf "$nhl_build_dir"' EXIT
nhl_swiftc=$(xcrun --find swiftc)
nhl_toolchain=$(dirname "$(dirname "$nhl_swiftc")")
nhl_sdk=$(xcrun --sdk macosx --show-sdk-path)
if nhl_platform=$(xcrun --sdk macosx --show-sdk-platform-path 2>/dev/null); then
    nhl_developer="$nhl_platform/Developer"
    nhl_frameworks="$nhl_developer/Library/Frameworks"
else
    # Standalone Command Line Tools ship Swift Testing outside a platform bundle.
    nhl_developer="$nhl_toolchain/../Library/Developer"
    nhl_frameworks="$nhl_developer/Frameworks"
fi
nhl_arch=$(uname -m)
common=(-disable-sandbox -whole-module-optimization -Onone -sdk "$nhl_sdk" -target "$nhl_arch-apple-macosx15.0" -module-cache-path "${NHL_TEST_MODULE_CACHE:-${TMPDIR:-/tmp}/NHLCoreTestsModuleCache}" -swift-version 6)

"$nhl_swiftc" "${common[@]}" -enable-testing -emit-library -emit-module -module-name NHLCore \
    NHLCoreTests/Sources/NHLCore/*.swift -o "$nhl_build_dir/libNHLCore.dylib" \
    -emit-module-path "$nhl_build_dir/NHLCore.swiftmodule"
"$nhl_swiftc" "${common[@]}" -parse-as-library -module-name NHLCoreTests \
    -I "$nhl_build_dir" -L "$nhl_build_dir" -lNHLCore \
    -F "$nhl_frameworks" -framework Testing \
    -load-plugin-library "$nhl_toolchain/lib/swift/host/plugins/testing/libTestingMacros.dylib" \
    -Xlinker -rpath -Xlinker "$nhl_build_dir" -Xlinker -rpath -Xlinker "$nhl_frameworks" \
    -Xlinker -rpath -Xlinker "$nhl_developer/usr/lib" \
    NHLCoreTests/Tests/NHLCoreTests/*.swift NHLCoreTests/DirectRunner.swift -o "$nhl_build_dir/NHLTests"
"$nhl_build_dir/NHLTests" "$@"
