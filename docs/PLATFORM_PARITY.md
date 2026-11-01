# Migration status and release gates

## Imported baseline

The original Android workspace remains the authoritative source of the four Gradle modules. It reported 925 passing tests and five opt-in data checks skipped. The sanitized unified import independently reproduced those counts and built both APKs. The Apple baseline is the GitHub default branch at `e4946252ffd7e5bb78145069cd819e81b985286f`. All 2,476 tracked Apple paths were imported, including native targets, extensions, assets, 1,032 Swift files, fixtures and scripts. Existing upstream artwork bytes are preserved.

The original Apple Dynamic Type ratchet passed. The original NHL Swift library target built with the installed macOS SDK. The original direct fixture runner could not locate an Xcode platform directory, and SwiftPM's test build lacked the TestingMacros plug-in. The machine has Command Line Tools but no iOS simulator SDK. A full pre- or post-migration Xcode app baseline remains an external gate.

## Verification performed

| Check | Result |
| --- | --- |
| Unified import before NHL switch | Common metadata compiled. Four unit-test tasks, both Android assemblies, 925 passed, five skipped |
| Integrated common metadata, shared JVM and Android | Gradle build succeeded. Shared 236/236, core 466/471 with five opt-in skips, phone 200/200, TV 28/28. Total **930 passed, zero failures, five skipped** |
| First shared sports contract | Kotlin/JVM provider and bridge produced the versioned fixture. Swift compiled and decoded Minnesota 2 at Buffalo 3, period two, running clock 08:42, 522 seconds, matching teams and broadcast |
| Real public provider | The same bridge requested June 24, 2024. Swift decoded Edmonton 1 at Florida 2, final, provider game ID `2023030417` |
| Failure, cancellation and ranges | Checked sanitized HTTP denial, release of a cancelled request, intermission clock suppression, adjacent calendar windows for a 370-day schedule and identity deduplication |
| Apple structural checks | Xcode project and plist pass `plutil`. 681 app/extension/unit-test Swift files parse with the shared feature condition. Fixture decoder builds and runs with the macOS Swift compiler |
| Apple framework, iOS simulator and device | CI configured. Real Kotlin/Native linking, Swift type checking against its generated header, Darwin request, entitlement behavior and device UI/playback still require execution |
| GitHub Actions | Path-aware workflows prepared locally. No hosted runs recorded in this milestone |

The historical HTTP probe uses the shared JVM build. The Swift decoder is real, but these are separate processes. Only the Xcode live test can establish that the app executes the Kotlin/Native framework. The five skipped Android checks require local lineup/guide/provider samples.

The local JDK 26 Gradle worker initially failed to spawn. Using a fresh single-use daemon with `JAVA_TOOL_OPTIONS=-Djdk.lang.Process.launchMechanism=FORK` allowed the workers to run. Android tests were not disabled or weakened. CI uses JDK 17.

## Milestones and current owners

| Milestone | Implemented | Remaining gate |
| --- | --- | --- |
| 1. Unified repository | Android modules at root, Apple project under `apple/`, separate Git history with pinned provenance, private/local configuration excluded | Publish only after destination privacy and authorization are verified |
| 2. Ownership audit | Actual service methods, identities, stores and verification requirements in the migration matrix | Extend each row with measured parity as it is cut over |
| 3. Framework integration | Distinct static framework, Darwin adapters, per-platform ARM64 binaries, MyApp-only build phase, app-scoped host and rollback setting | Xcode build and interop type check on a full Apple runner |
| 4. First feature | NHL scores/schedules use KMP in the enabled build. Explicit Swift fixture clients still work. No parallel Swift discovery fallback on that route | Pass the real app integration test and verify existing Apple UI paths before promotion |
| 5. Existing data and native functionality | Existing bundle IDs, team/signing values, entitlements, Keychain services, SwiftUI, AVPlayer, CarPlay, Live Activities and stored-data formats retained. No write migration | Install over an existing app with saved teams, playlist UUIDs, watchlist and progress. Exercise stream matching, deep links and playback continuity |
| 6. Retirement | Legacy providers and their tests retained. Hardcoded private Podcast Index signing literals removed. Public NFL/PGA configuration was externalized | Remove code only after its runtime, package, extension and test consumers have verified replacements |
| 7. Simultaneous development | Shared fixture, Kotlin tests, Swift wire check, hosted build/test workflows | First green Android and Apple CI runs on the private destination |
| 8. Platform parity | Current source owners, native-only capabilities, gaps and rollback steps recorded | Device, Swift ABI, wider provider parity, credential provisioning and native playback remain open |

## Next acceptance steps

1. Run both workflows in the private repository. For Xcode, require a successful `MyApp` legacy build, the enabled app suites and an executed, passing `testRealSharedNHLDiscoveryPreservesAppleNavigationIDs`. Treat a missing live result as a failed gate.
2. Verify the existing NHL home → game detail → playlist stream flow, an intermission, offline refresh and cancellation. Exercise a previously installed app and CarPlay audio. Use the matching iOS 27.1+ SDK to check fold geometry and newer CarPlay presentation.
3. Enable the shared route by default for validated Apple SDKs in a separate reviewed change. Keep visionOS and unsupported architectures on their native configuration.
4. Migrate NHL teams/standings, followed identities and detailed Game Centre in separate slices. Then migrate fantasy and TMDB enrichment, with conservative identity and persistence tests. Keep IPTV/EPG, legacy title inventories and existing cloud stores until their shared replacements exist.
