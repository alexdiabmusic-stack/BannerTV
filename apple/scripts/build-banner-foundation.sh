#!/bin/sh
# MyApp precompile phase: build Kotlin/Native only for iPhone and iPad targets.
set -eu
case "${PLATFORM_NAME:-}" in
  iphoneos|iphonesimulator) ;;
  *) echo "BannerFoundation: skipping non-iOS platform ${PLATFORM_NAME:-unknown}"; exit 0 ;;
esac
REPO_ROOT="${SRCROOT}/.."
cd "$REPO_ROOT"
./gradlew --no-watch-fs :shared:embedAndSignAppleFrameworkForXcode

FRAMEWORK="$REPO_ROOT/shared/build/xcode-frameworks/${CONFIGURATION}/${SDK_NAME}/BannerFoundation.framework"
if [ ! -d "$FRAMEWORK" ] && [ -f "$REPO_ROOT/local.properties" ]; then
  # The root Gradle build optionally redirects build outputs through build.root.
  BUILD_ROOT="$(sed -n 's/^[[:space:]]*build[.]root[[:space:]]*=[[:space:]]*//p' "$REPO_ROOT/local.properties" | tail -n 1)"
  if [ -n "$BUILD_ROOT" ]; then
    case "$BUILD_ROOT" in
      /*) ;;
      *) BUILD_ROOT="$REPO_ROOT/$BUILD_ROOT" ;;
    esac
    FRAMEWORK="$BUILD_ROOT/shared/xcode-frameworks/${CONFIGURATION}/${SDK_NAME}/BannerFoundation.framework"
  fi
fi
if [ ! -d "$FRAMEWORK" ]; then
  echo "error: BannerFoundation.framework not found for ${CONFIGURATION}/${SDK_NAME}. Check the Kotlin Gradle build and build.root." >&2
  exit 1
fi
DEST="${TARGET_TEMP_DIR}/BannerFoundation"
mkdir -p "$DEST"
ln -sfn "$FRAMEWORK" "$DEST/BannerFoundation.framework"
echo "BannerFoundation: Xcode framework prepared at $DEST"
