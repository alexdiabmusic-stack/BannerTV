# Apple host integration

`shared` builds the `BannerFoundation` static framework for `iosArm64` and `iosSimulatorArm64`. The current Swift app remains the reference UI. The code here does not replace its existing SportsCore routing until the framework can be built and the adapters verified in Xcode.

## Build and link

Install full Xcode and select it with `xcode-select`. In the iOS target, set `BANNER_FOUNDATION_ROOT` to this repository's absolute path. Add a Run Script phase **before Compile Sources**:

```sh
set -eu
cd "$BANNER_FOUNDATION_ROOT"
./gradlew :shared:embedAndSignAppleFrameworkForXcode
```

Set `ENABLE_USER_SCRIPT_SANDBOXING = NO` for that Gradle phase. Add `$(BANNER_FOUNDATION_ROOT)/shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)` to Framework Search Paths if using default Gradle output; this project can redirect build output through the root build script, so resolve the actual `shared` build directory before setting the path. Link `BannerFoundation` in Other Linker Flags (`$(inherited) -framework BannerFoundation`). Do not add the same framework a second time through CocoaPods/SPM. See the [official direct-integration instructions](https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-direct-integration.html).

Copy `KeychainAddonVault.swift` into the Swift app target after the framework is available. Create one `AppleFoundation(cacheDirectory:vault:)` for the app lifetime, using a subdirectory of `FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]` and the Keychain vault. Keep its lifetime above individual screens; call `close()` only when retiring that foundation instance.

Merge the usage-description key in `BannerFoundationInfo.plist` into the app's existing Info.plist for user-configured LAN servers. This is a fragment, not a replacement for the app's Info.plist. Keep App Transport Security and TLS certificate validation enabled.

## Existing Swift app adapters

- Route each existing provider through the cancellable `foundation.sportsBridge` callback facade and translate the shared domain model into the existing Swift presentation models. Preserve explicit `nativeReference` IDs; do not strip them before calling player, roster or Game Centre APIs.
- The concrete providers and detailed Game Centre loaders are also available under `foundation.sports` for existing specialised screens. NBA and WNBA remain separate clients; LaLiga retains official/FotMob reconciliation; F1 exposes its timing flow and game state reducer.
- Android assets are resolved by Android; the Apple asset URI must be resolved by the Swift host to its bundled crest. No synthetic network URL is returned for a local crest.
- Supply an `RssDecoder` implemented using Apple's XMLParser when constructing shared `NewsRepository`. Sports news selection, relevance and ranking are shared; XML parsing is an OS adapter.
- Keep Keychain URLs out of the cloud backup. Restore ordinary preferences/favourites and reconnect device-local add-ons. Never turn a locked Keychain read into an empty library.
- `sportsBridge` returns a `SportsRequest`; retain it and call `cancel()` on view disappearance or Swift task cancellation. It sanitizes failures and delivers callbacks on the main dispatcher. Direct suspend methods are exposed as Objective-C completion handlers / Swift async methods. Kotlin Flow still needs a lifecycle-aware Swift subscription adapter; cancelling a Swift Task alone does not guarantee cancellation of a Kotlin coroutine. That integration must be verified before switching live screens.

## Verification status

This Mac has Command Line Tools but no iOS SDK. `xcrun --sdk iphonesimulator --show-sdk-path` fails. The Apple source has not been type-checked against a generated framework or linked into the Swift app. Kotlin common metadata and JVM/Android tests cannot substitute for that gate. Native build, Swift model adapters, lifecycle cancellation and device streaming remain explicit release requirements.
