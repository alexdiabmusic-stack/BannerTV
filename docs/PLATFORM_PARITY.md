# Migration status and release gates

## Imported baseline

The original Android workspace remains the authoritative source of the four Gradle modules. It reported 925 passing tests and five opt-in data checks skipped. The sanitized unified import independently reproduced those counts and built both APKs. The Apple baseline is the GitHub default branch at `e4946252ffd7e5bb78145069cd819e81b985286f`. All 2,476 tracked Apple paths were imported, including native targets, extensions, assets, 1,032 Swift files, fixtures and scripts. Existing upstream artwork bytes are preserved.

The original Apple Dynamic Type ratchet and NHL Swift library build passed. All 25 NHL tests across three suites passed in both the pinned source and the unified tree using the macOS Command Line Tools’ Swift Testing plug-in and runtime. Run `bash apple/NHLCoreTests/run-tests.sh` to reproduce the unified check. This Mac lacks the iOS simulator SDK. A hosted Xcode 26.4.1 runner successfully built the complete application, native framework and extension using the original NHL route.

## Verification performed

| Check | Result |
| --- | --- |
| Unified import before NHL switch | Common metadata compiled. Four unit-test tasks, both Android assemblies, 925 passed, five skipped |
| Integrated common metadata, shared JVM and Android | Gradle build succeeded. Shared 236/236, core 467/472 with five opt-in skips, phone 200/200, TV 28/28. Total **931 passed, zero failures, five skipped** |
| First shared sports contract | Kotlin/JVM provider and bridge produced the versioned fixture. Swift compiled and decoded Minnesota 2 at Buffalo 3, period two, running clock 08:42, 522 seconds, matching teams and broadcast |
| Real public provider | The same bridge requested June 24, 2024. Swift decoded Edmonton 1 at Florida 2, final, provider game ID `2023030417` |
| Failure, cancellation and ranges | Checked sanitized HTTP denial, release of a cancelled request, intermission clock suppression, adjacent calendar windows for a 370-day schedule, identity deduplication and a seven-day UTC window reaching its last Eastern date across daylight saving time |
| Apple structural checks | Xcode project and plist pass `plutil`. 681 app/extension/unit-test Swift files parse with the shared feature condition. Fixture decoder builds and runs with the macOS Swift compiler |
| Apple frameworks and simulator | GitHub Actions ran the shared iOS simulator tests and linked five debug frameworks for iOS device/simulator, tvOS device/simulator and macOS ARM64. Xcode built the native app, then compiled and launched the enabled app on an iPhone simulator. It executed a real Darwin request from the existing sports repository through the KMP framework |
| Standalone Apple tests | Dynamic Type, ten sports packages (CFL, EPL, F1, LaLiga, MLB, MLS, NBA, NFL, NHL and WNBA) and MatchLinker passed. The native engine suite reported two failing scenarios out of 143 tests |
| Hosted Android | A clean Ubuntu runner passed all four Gradle test tasks and assembled both debug applications. [Completed Android checks](https://github.com/alexdiabmusic-stack/BannerTV/actions/runs/41360844607) |
| Hosted Apple history | The first framework run identified missing auxiliary test-scheme membership. A later build identified an Objective-C optional-throwing Keychain requirement. The separate regression scheme and Apple storage protocol address those issues. On `8335a84` the enabled app returned Game 7 with the expected navigation and team IDs. The one-day schedule assertion used June 24 UTC, excluding the game’s June 25 00:00 UTC start. [Framework build](https://github.com/alexdiabmusic-stack/BannerTV/actions/runs/41360068321) · [Real iPhone simulator request](https://github.com/alexdiabmusic-stack/BannerTV/actions/runs/41361597166) · [Latest checks](https://github.com/alexdiabmusic-stack/BannerTV/pull/1/checks) |

The local wire probe and the hosted iPhone app provide different evidence. The local probe uses Kotlin/JVM and a separate Swift process. The simulator runs the native app’s actual framework, Darwin transport, repository and Swift presentation mapper. The five skipped Android checks require local lineup/guide/provider samples.

The local JDK 26 Gradle worker initially failed to spawn. Using a fresh single-use daemon with `JAVA_TOOL_OPTIONS=-Djdk.lang.Process.launchMechanism=FORK` allowed the workers to run. Android tests were not disabled or weakened. CI uses JDK 17.

## Native discovery stability

At `575dd34`, the fixture comparing the original and shared matches passed inside the iPhone app. The same simulator run reported an invalid free during real discovery. On the following run both the real score/schedule and native fixture passed on two fresh launches. The third launch reproduced the crash. The symbolicated fault was `OnboardingStore.__deallocating_deinit` calling Swift’s back-deployed main-actor deinitializer while SwiftUI discarded an onboarding view. The store now explicitly uses nonisolated teardown and retains main-actor isolation for its UI state. CI repeats the live, projection and synchronous store-release tests on three launches. [Faulting thread](https://github.com/alexdiabmusic-stack/BannerTV/actions/runs/41363108294) · [Swift runtime issue](https://github.com/swiftlang/swift/issues/88036) [Observed simulator failure](https://github.com/alexdiabmusic-stack/BannerTV/actions/runs/41362389305)

## Inherited native player failures

The hosted engine suite reported the wrong terminal state and a second connection for a silent channel. It also ended its HLS outage sample before the expected recovered frame appeared. On the pinned original Apple source, the same loopback scenarios reproduced the silent-channel failure and intermittently selected MPEG-TS instead of HLS for HEVC. The HLS outage case passed locally. The original engine production sources are byte-identical in the consolidated tree. Keep these checks visible and verify real stream recovery on Apple hardware before release.

## Milestones and current owners

| Milestone | Implemented | Remaining gate |
| --- | --- | --- |
| 1. Unified repository | Android modules at root, Apple project under `apple/`, separate Git history with pinned provenance, private/local configuration excluded | Both baselines published to private `main`. Foundation changes are on [draft PR #1](https://github.com/alexdiabmusic-stack/BannerTV/pull/1) |
| 2. Ownership audit | Actual service methods, identities, stores and verification requirements in the migration matrix | Extend each row with measured parity as it is cut over |
| 3. Framework integration | Distinct static framework, Darwin adapters, per-platform ARM64 binaries, MyApp-only build phase, app-scoped host and rollback setting | All five framework links and the original-route Xcode build passed. The enabled app imported and executed the framework. Track its integration result independently from broader native and hardware regression gates |
| 4. First feature | NHL scores/schedules use KMP in the enabled build. Explicit Swift fixture clients still work. No parallel Swift discovery fallback on that route | Pass the real app integration test and verify existing Apple UI paths before promotion |
| 5. Existing data and native functionality | Existing bundle IDs, team/signing values, entitlements, Keychain services, SwiftUI, AVPlayer, CarPlay, Live Activities and stored-data formats retained. No write migration | Install over an existing app with saved teams, playlist UUIDs, watchlist and progress. Exercise stream matching, deep links and playback continuity |
| 6. Retirement | Legacy providers and their tests retained. Hardcoded private Podcast Index signing literals removed. Public NFL/PGA configuration was externalized | Remove code only after its runtime, package, extension and test consumers have verified replacements |
| 7. Simultaneous development | Shared fixture, Kotlin tests, Swift wire check, hosted build/test workflows | Android passed on the private destination. Apple’s discovery, original app suite, auxiliary suite and standalone player checks have distinct results |
| 8. Platform parity | Current source owners, native-only capabilities, gaps and rollback steps recorded | Device, wider provider parity, credential provisioning and native playback remain open |

## Next acceptance steps

1. Track the review branch’s Android and Apple checks. For Xcode, require a successful `MyApp` legacy build, the enabled app suites and an executed, passing `testRealSharedNHLDiscoveryPreservesAppleNavigationIDs`. Treat a missing live result as a failed gate.
2. Verify the existing NHL home → game detail → playlist stream flow, an intermission, offline refresh and cancellation. Exercise a previously installed app and CarPlay audio. Use the matching iOS 27.1+ SDK to check fold geometry and newer CarPlay presentation.
3. Enable the shared route by default for validated Apple SDKs in a separate reviewed change. Keep visionOS and unsupported architectures on their native configuration.
4. Migrate NHL teams/standings, followed identities and detailed Game Centre in separate slices. Then migrate fantasy and TMDB enrichment, with conservative identity and persistence tests. Keep IPTV/EPG, legacy title inventories and existing cloud stores until their shared replacements exist.
