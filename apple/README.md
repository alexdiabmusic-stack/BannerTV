# Apple host integration

For movies and TV metadata, the new Nuvio-style iPhone TMDB configuration is documented in [CINEMA_TMDB_SETUP.md](CINEMA_TMDB_SETUP.md). It supports personal TMDB v3 API keys or v4 read tokens stored in Keychain, a connection test, metadata language, and functional artwork/credits/episodes/recommendation switches. No Nuvio credential is bundled.

`shared` builds the `BannerFoundation` static framework for `iosArm64` and `iosSimulatorArm64`. The current Swift app remains the reference UI. The code here does not replace its existing SportsCore routing until the framework can be built and the adapters verified in Xcode.

## Build and link (iPhone/iPad)

The MyApp target has an iOS-only **Build BannerFoundation** Run Script phase before Compile Sources. `apple/scripts/build-banner-foundation.sh` runs `./gradlew --no-watch-fs :shared:embedAndSignAppleFrameworkForXcode`, accommodates an optional `local.properties` `build.root` override, and exposes the static framework from `$(TARGET_TEMP_DIR)/BannerFoundation` to the iOS-only Swift build settings. MyApp's script sandboxing is disabled for this integration.

Use full Xcode (not Command Line Tools alone), JDK 17+ and the Gradle wrapper. On a new checkout, first run `python3 scripts/bootstrap-public-sports.py` from the repository root to provision the existing git-ignored public sports provider defaults. Open `apple/Stadia TV.xcodeproj`, choose the `MyApp` scheme and an iPhone simulator/device, and build. For a separate Kotlin check, run `./gradlew :shared:compileKotlinIosSimulatorArm64` on macOS.

The Cinema implementation resides at `apple/MyApp/Cinema/`. Because the Xcode project uses a `PBXFileSystemSynchronizedRootGroup` pointing at `MyApp`, Swift files in that folder are automatically target members. `KeychainAddonVault.swift` was moved there and is compiled exactly once. All seven Swift files are guarded with `#if os(iOS)`, so the existing macOS, tvOS and visionOS targets retain their legacy entertainment destinations.

`CinemaRuntime.shared` constructs an iPhone Cinema foundation once. Sports host migration remains separate work. **The framework has not yet been built or tested on a device**; Gradle/Xcode compilation and functional QA remain required.

## Existing Swift app adapters

- Route each existing provider through the cancellable `foundation.sportsBridge` callback facade and translate the shared domain model into the existing Swift presentation models. Preserve explicit `nativeReference` IDs; do not strip them before calling player, roster or Game Centre APIs.
- The concrete providers and detailed Game Centre loaders are also available under `foundation.sports` for existing specialised screens. NBA and WNBA remain separate clients; LaLiga retains official/FotMob reconciliation; F1 exposes its timing flow and game state reducer.
- Android assets are resolved by Android; the Apple asset URI must be resolved by the Swift host to its bundled crest. No synthetic network URL is returned for a local crest.
- Supply an `RssDecoder` implemented using Apple's XMLParser when constructing shared `NewsRepository`. Sports news selection, relevance and ranking are shared; XML parsing is an OS adapter.
- Keep Keychain URLs out of the cloud backup. Restore ordinary preferences/favourites and reconnect device-local add-ons. Never turn a locked Keychain read into an empty library.
- `sportsBridge` returns a `SportsRequest`; retain it and call `cancel()` on view disappearance or Swift task cancellation. It sanitizes failures and delivers callbacks on the main dispatcher. Direct suspend methods are exposed as Objective-C completion handlers / Swift async methods. Kotlin Flow still needs a lifecycle-aware Swift subscription adapter; cancelling a Swift Task alone does not guarantee cancellation of a Kotlin coroutine. That integration must be verified before switching live screens.

## Verification status

This Mac has Command Line Tools but no iOS SDK. `xcrun --sdk iphonesimulator --show-sdk-path` fails. The Apple source has not been type-checked against a generated framework or linked into the Swift app. Kotlin common metadata and JVM/Android tests cannot substitute for that gate. Native build, Swift model adapters, lifecycle cancellation and device streaming remain explicit release requirements.
