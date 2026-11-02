# Developing BannerTV

Start changes to reusable behavior in `shared/commonMain`. Commit a real provider fixture or state transition, then implement the Swift presentation adapter and Android consumer. For migrated capabilities, the app must select exactly one service owner per request. Keep fixture injection separate from production routing.

## Local commands

Start at the repository root. Configure the Android SDK through Android Studio or ignored `local.properties`. Use `python3 scripts/bootstrap-public-sports.py` to configure public web-client parameters. Keep private tokens, signing keys, user files, caches and build products outside Git.

```sh
./gradlew :shared:compileCommonMainKotlinMetadata :shared:jvmTest \
  :core:testDebugUnitTest :mobile:testDebugUnitTest :app:testDebugUnitTest \
  :mobile:assembleDebug :app:assembleDebug -PpublicBuild=true
scripts/check-nhl-contract.sh
```

For a live contract probe, set `-PnhlLiveOutput=/absolute/path/to/nhl.json` on `:shared:jvmTest`, then run `scripts/check-nhl-contract.sh /absolute/path/to/nhl.json`. The JVM probe uses the shared NHL provider and discovery facade with a real HTTPS test transport. Swift checks the resulting game identity. The ordinary fixture suite does not need the NHL service online.

With full Xcode installed:

```sh
bash apple/scripts/test-all.sh checks
bash apple/scripts/test-all.sh packages
./gradlew :shared:iosSimulatorArm64Test :shared:linkDebugFrameworkIosArm64 -PpublicBuild=true
apple/scripts/verify-foundation.sh
```

Use the platform and runner details in [Apple integration](APPLE_KMP_INTEGRATION.md). The Xcode Cloud pre-build hook locates the imported Apple project relative to its own script, including its existing local secrets configuration.

## Pull requests and CI

Use small changes that identify the current owner, affected platform contracts, tests executed, fallback behavior and remaining gates. The Android workflow runs shared and Android tests and both assemblies. The Apple workflow runs package fixtures, native framework builds, the native application baseline, repeated live NHL and onboarding checks, the original app unit suite and the auxiliary regression suite. All Xcode stages reuse the iPhone selected at the start of the job. Both workflows support manual dispatch. Shared, Gradle and cross-contract changes trigger both platform paths.

The two workflows are prepared in the unified repository. A local JVM/Swift contract check, a hosted Xcode compilation and a physical-device playback check are distinct results. Record them separately. A portable feature becomes the Apple production default only after both platform gates pass.

## Persistence and retirement

Record sample old IDs and values before changing a persistence contract. Test read-only decoding first, include malformed/locked/missing cases, and retain backups and a rollback path. Verify matching, duplicates, timestamps and stable account/playlist identities. Never treat a locked Keychain as an empty library to overwrite.

Before removing a Swift service, search the app, extensions, standalone packages, tests, background tasks and scripts for its callers. Verify the replacement using the same fixtures, then run both applications' affected suites. The current migration retains all legacy providers. [Feature ownership matrix](MIGRATION_MATRIX.md)
