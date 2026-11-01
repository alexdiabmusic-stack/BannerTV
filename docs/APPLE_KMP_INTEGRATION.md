# Apple integration and rollout gate

Open `apple/Stadia TV.xcodeproj` and select the existing `MyApp` scheme. The application target has the only new Gradle build phase. `BannerSharedKit`, `BannerLiveActivityExtension` and the test targets retain their original linkage. The KMP framework module name is `BannerFoundation`.

## Build setup

1. Run `python3 scripts/bootstrap-public-sports.py` from the repository root.
2. Select a full Xcode and a JDK suitable for Gradle. Kotlin 2.4.21 lists Xcode 26.4 in its tested compatibility table. The Apple workflow selects Xcode 26.4.1 on an ARM64 macOS runner. The original source also contains APIs for iOS 27 and 27.1. SDK conditions omit those calls on SDK 26 and preserve their original runtime availability checks on supported SDKs. Build and exercise the 27.1 adaptive layout and CarPlay features with their matching SDK before release.
3. Build the native configuration with `BANNER_KMP_ENABLED=NO`. To exercise the migration, pass `BANNER_KMP_ENABLED=YES` to xcodebuild or set it on the application target. The verification command is `apple/scripts/verify-foundation.sh`.
4. The validation script builds the native route, executes the named live NHL test in the enabled app, and checks its xcresult for an actual pass. It then exercises both existing application test targets with the shared route enabled. The auxiliary `MyAppTests` target joins the shared scheme and uses the same app host and deployment minimum as `MyAppUnitTests`.

The app phase runs `apple/scripts/build-foundation.sh` before Compile Sources. It invokes `:shared:embedAndSignAppleFrameworkForXcode` relative to `SRCROOT`. Xcode searches its built products directory for the Kotlin framework. The migration configuration disables script sandboxing for the app's Gradle phase and links the static framework through `OTHER_LDFLAGS`. There is no second embedding or signing phase.

| Existing Xcode platform | KMP binary declared | Current validation |
| --- | --- | --- |
| iPhone/iPad device and ARM64 simulator | `iosArm64`, `iosSimulatorArm64` | Real app import, device-framework link, simulator tests and live request are CI gates |
| Apple TV device and ARM64 simulator | `tvosArm64`, `tvosSimulatorArm64` | Dedicated link tasks in CI. Native TV UI and remote checks still required |
| Apple Silicon Mac | `macosArm64` | Dedicated framework link task. Native app launch and Keychain behavior still require verification |
| Intel Mac, Intel simulator, visionOS | Native configuration | No corresponding KMP binaries in this migration. Keep the native route |

The Kotlin targets share the original Darwin/OS adapters through `shared/src/appleMain`. The current Compose annotation dependency publishes Apple Silicon Mac/tvOS/iOS variants. A simulator or iOS library cannot be substituted for another platform's binary.

## NHL discovery

`BannerAppEnvironment` lazily creates one main-actor `BannerFoundationHost` with the existing Keychain adapter, a disposable cache subdirectory and `AppleFoundation`. SwiftUI and CarPlay already use the shared app environment. There is no additional background poller.

Only default application instances of `NHLProvider` select KMP in the enabled build. Explicitly injected Swift clients keep the provider's fixture and legacy behavior. Scores and schedules delegate before reaching the old Swift transport. The roster code uses the selected discovery source when it needs a team abbreviation.

`AppleNhlDiscovery` calls the native NHL provider directly. It moves work off the UI dispatcher, bounds requests, propagates cancellation, and returns either a versioned discovery payload or a sanitized failure with the available HTTP status. The Swift adapter cancels the Kotlin job and resumes its own waiting continuation once. An obsolete result cannot resume a cancelled Swift task. The wide-range schedule adapter uses consecutive local-calendar windows and deduplicates by source identity.

The Swift projection uses the existing numeric match/team IDs, `league.bannerKey`, and `banner-asset:/NHLLogo_…`. It retains clock seconds, the running flag, period, venue and broadcast names. Both routes use the original Apple NHL status-label formatter. A bundled fixture compares the projected teams, game identity, time, status label, broadcast list and other fields used by native navigation and stream matching. The schedule covers every NHL Eastern date intersecting the viewer’s local window. Pregame scores use the shared provider's policy of omitting unconfirmed display scores. Existing Game Centre and channel matching still consume the Apple `Match`.

## Evidence and rollback

The fixture originated in the pinned Apple NHL suite. The shared JVM test requires the native Kotlin mapper/bridge to emit the matching JSON structure. A separate executable built with the installed Swift compiler decodes that version using the production Swift decoder. It also consumes an actual historical NHL response through the JVM build of the same bridge. This provides evidence for the wire and identity contract. It does not execute the iOS framework or Darwin transport. See [validation results](PLATFORM_PARITY.md).

Set `BANNER_KMP_ENABLED=NO` to return to the original native sport routing. Neither configuration migrates stored data. Keep the default native until the enabled app passes CI and device checks for navigation, background/foreground refresh, favourites, Game Centre entry, CarPlay and stream matching.

The remaining Keychain/add-on, fantasy, Swift TMDB enrichment, AVPlayer and media-library adapters each need their own tests. The existing LAN usage-description fragment is available when Apple add-on management is enabled.

[JetBrains direct integration](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html) · [Compatibility table](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html) · [Native target support](https://kotlinlang.org/docs/native-target-support.html)
