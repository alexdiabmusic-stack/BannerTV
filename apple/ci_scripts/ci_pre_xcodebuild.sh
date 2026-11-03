#!/bin/sh
# For older workflows configured against apple/Stadia TV.xcodeproj directly.
# Keep all Xcode Cloud provisioning in the repository-root script.
set -eu
REPO_ROOT="${CI_PRIMARY_REPOSITORY_PATH:-$(cd "$(dirname "$0")/../.." && pwd)}"
exec sh "$REPO_ROOT/ci_scripts/ci_pre_xcodebuild.sh"
