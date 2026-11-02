#!/bin/sh
set -eu
[ "${BANNER_KMP_ENABLED:-NO}" = YES ] || exit 0
[ "${OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED:-NO}" != YES ] || exit 0
case "$PLATFORM_NAME" in
    iphoneos|iphonesimulator|appletvos|appletvsimulator|macosx) ;;
    *) echo "error: BannerFoundation does not target $PLATFORM_NAME. Use the native configuration." >&2; exit 1 ;;
esac
case " $ARCHS " in
    " arm64 ") ;;
    *) echo "error: This migration supports arm64. Build the native configuration for other architectures." >&2; exit 1 ;;
esac
cd "$SRCROOT/.."
exec ./gradlew :shared:embedAndSignAppleFrameworkForXcode -PpublicBuild=true
