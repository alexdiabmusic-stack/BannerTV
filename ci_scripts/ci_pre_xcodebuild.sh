#!/bin/sh
# Xcode Cloud pre-build hook for BannerTV.xcworkspace (repository-root entrypoint).
set -eu

REPO_ROOT="${CI_PRIMARY_REPOSITORY_PATH:-$(cd "$(dirname "$0")/.." && pwd)}"
APPLE_ROOT="$REPO_ROOT/apple"
SECRETS="$APPLE_ROOT/Config/Secrets.xcconfig"

if [ ! -d "$APPLE_ROOT" ] || [ ! -f "$APPLE_ROOT/Stadia TV.xcodeproj/project.pbxproj" ]; then
  echo "error: Cannot find BannerTV's Apple project below $REPO_ROOT/apple" >&2
  exit 1
fi

# Generate the already-required, git-ignored public sports provider settings
# before Gradle evaluates :shared.
python3 "$REPO_ROOT/scripts/bootstrap-public-sports.py"

# Keep user secrets outside git and never print credential values to the log.
umask 077
: > "$SECRETS"
if [ -n "${YOUTUBE_API_KEY:-}" ]; then
  printf 'YOUTUBE_API_KEY = %s\n' "$YOUTUBE_API_KEY" >> "$SECRETS"
  echo "Configured YOUTUBE_API_KEY"
fi
if [ -n "${ODDS_API_KEY:-}" ]; then
  printf 'ODDS_API_KEY = %s\n' "$ODDS_API_KEY" >> "$SECRETS"
  echo "Configured ODDS_API_KEY"
fi

if [ "${CI_XCODEBUILD_ACTION:-}" = "archive" ]; then
  sh "$APPLE_ROOT/scripts/check-release-config.sh"
fi
sh "$APPLE_ROOT/scripts/check-dynamic-type.sh"
