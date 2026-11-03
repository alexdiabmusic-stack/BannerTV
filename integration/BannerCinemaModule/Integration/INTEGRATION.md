# BannerTV Cinema — integration guide (source-checked November 2, 2026)

> **Integration update, November 2, 2026:** iOS steps 1–5 have been applied to the MyApp target. Kotlin/Swift sources were relocated into active directories, the Apple factory patch was applied, and an iOS-only Xcode build phase/navigation were added. Copy instructions below document the *original staging layout*. Do not repeat those steps or apply the old patch. Xcode compilation, device QA, and further features remain outstanding.

## Purpose and verified baseline

This is **original feature code**, built to reuse the existing BannerTV KMP foundation and achieve the **movie/series browsing and playback journey** users recognize from Nuvio. Do not paste Nuvio's own GPLv3 files into BannerTV unless you intentionally comply with that license. The UI is not branded as Nuvio.

Sources inspected:
- `alexdiabmusic-stack/BannerTV`, branch `main`: `shared/src/commonMain/kotlin/com/alexdiab/stadiatv/catalog/{TmdbRepository,MediaLibrary,MediaPlaybackResolver}.kt`, `addons/{AddonLibrary,Stremio}.kt`, `shared/src/iosMain/.../AppleFoundation.kt`, `mobile/.../PhoneCinema.kt`, `core/.../AppContainer.kt`, `app/.../ui/AppRoot.kt`, `apple/MyApp/CompleteHomeView.swift`, `apple/MyApp/EntertainmentLibraryView.swift`, `apple/README.md`.
- `alexdiabmusic-stack/StadiaTV-iOS`, branch `main`: pre-foundation reference app (its content resides under `MyApp`); the same reference app has been imported under `BannerTV/apple/`.
- `NuvioMedia/NuvioMobile`, default branch `cmp-rewrite`: Compose Multiplatform app with home hero, poster/catalog rows, detail/episode pages, add-on streams, player, library, watch progress, subtitle and tracking modules. Its `LICENSE` is **GPL-3.0**.

**Do not reimplement** TMDB / IMDb verification, Stremio manifest parsing, Stremio stream resolver, shared media library rules, Android playlist/EPG guide, or sports logic. These exist in BannerTV.

## Feature files and destinations

| Package file | Destination in a checkout of BannerTV |
|---|---|
| `Kotlin/CinemaBridge.kt` | `shared/src/iosMain/kotlin/com/alexdiab/stadiatv/catalog/CinemaBridge.kt` |
| `Integration/AppleFoundation.patch` | Apply to root with `patch -p1 < Integration/AppleFoundation.patch` (or add the three imports + cinema factory manually) |
| `Swift/CinemaModels.swift` | `apple/MyApp/Cinema/CinemaModels.swift` |
| `Swift/CinemaSecurity.swift` | `apple/MyApp/Cinema/CinemaSecurity.swift` |
| `Swift/CinemaRuntime.swift` | `apple/MyApp/Cinema/CinemaRuntime.swift` |
| `Swift/CinemaScreens.swift` | `apple/MyApp/Cinema/CinemaScreens.swift` |
| `Swift/CinemaPlayback.swift` | `apple/MyApp/Cinema/CinemaPlayback.swift` |
| `Swift/CinemaSettings.swift` | `apple/MyApp/Cinema/CinemaSettings.swift` |
| `AndroidTV/BannerCinemaTV.kt` | `app/src/main/kotlin/com/alexdiab/stadiatv/ui/cinema/BannerCinemaTV.kt` |

**Add Swift files to the Xcode application target**, not only into the directory. `BannerFoundation` must be built, linked and imported, per the existing `apple/README.md`. Include the **existing** `apple/KeychainAddonVault.swift` in the Xcode target exactly once. Do not create a second `AppleFoundation` object if your host already owns one: inject the existing instance into `CinemaRuntime` and make its lifetime application-wide.

## Apple integration

1. From a clean `BannerTV/main` checkout, create a feature branch.
2. Copy Kotlin bridge and apply the factory patch. Its explicit constructor signature is `CinemaBridge(tmdb, MediaPlaybackResolver(addons), addons, stateStore)`.
3. Run `./gradlew :shared:compileKotlinIosSimulatorArm64` from a macOS machine with full Xcode and Kotlin/Native installed. The repository currently has only `iosArm64` and `iosSimulatorArm64` framework targets. **Do not assume this package has already passed Xcode compilation.**
4. Link the `BannerFoundation` static framework with the existing Xcode Run Script (`:shared:embedAndSignAppleFrameworkForXcode`). See `apple/README.md` for linker/framework search paths and `ENABLE_USER_SCRIPT_SANDBOXING=NO`.
5. Add all six Swift files to the iOS app target. Replace one existing movie/series navigation destination with `BannerCinemaRoot()`. For example, in `TelevisionHomeView.swift` change:

```swift
NavigationLink { EntertainmentLibraryView() } label: {
    Label("Your Movies & Series", systemImage: "film")
}
```

to

```swift
NavigationLink { BannerCinemaRoot() } label: {
    Label("Your Movies & Series", systemImage: "film")
}
```

The existing destination contains extra modifiers: preserve its styling when replacing its `NavigationLink` destination. You can also present `BannerCinemaRoot()` as a tab destination in the app's actual root navigation builder. Do **not** remove the Live TV or Sports tabs.

6. Run on iPhone simulator/device. In Cinema Settings, supply a TMDB API **Read Access Token**, stored locally in Keychain. Public builds need your own compliant production credential provisioning; do not put the token in `xcconfig` included in distributed binaries or checked-in files.
7. Connect a **preconfigured HTTPS** Stremio add-on manifest ending with `/manifest.json` in Cinema Settings. The existing `AddonLibrary` validates and stores it in device-only Keychain, not the watchlist.
8. Open a movie, try Play, select a source, play, pause, leave and return to verify Continue Watching. Open a series and pick a specific season/episode.
9. Keep the existing Xtream VOD `EntertainmentRepository` operational until you migrate verified provider identities and playback routes; this package does not delete it or invent matches based only on title text.
10. The TVOS target requires its own Kotlin/Native target and host wiring; `iosMain` does **not** automatically build for tvOS. **This Apple part is iPhone/iPad code first.**

### Swift bridge API used by these views

```text
AppleFoundation.cinema(readToken, stateStore) -> CinemaBridge
CinemaBridge.home / search / browse / detail / episodes
CinemaBridge.sources / library / toggleSaved / recordProgress
CinemaBridge.listAddons / addAddon / enableAddon / removeAddon
CinemaRequest.cancel()
```

The bridge exposes JSON transport DTOs because they are robust across changes to Kotlin's ObjC-exported data classes. The domain model lives only in shared Kotlin. Request callbacks return generic, sanitized error messages; they never log credentials, URLs or full provider responses. When replacing callback code with `async` wrappers, always ensure the Swift continuation is completed on cancellation as well as failure.

## Fire TV integration

This module contains a native Compose screen that calls the actual existing `AppContainer.tmdb`, `.addons`, `.mediaSources`, and `.metadataConnection`. Its player is Media3 ExoPlayer and sends a stream's provider headers to the media source. It stores only non-secret media-library metadata atomically on the device. It does not replace live IPTV playback.

1. Copy `AndroidTV/BannerCinemaTV.kt` to the destination listed above.
2. Add an explicit Cinema tab or a navigation route in `app/src/main/kotlin/com/alexdiab/stadiatv/ui/nav/*`. The existing enum is `Tab`, and `TabContent` lives in `ui/AppRoot.kt`. For example add the branch `Tab.Cinema -> BannerCinemaTV(LocalContainer.current)` and the corresponding top bar/tab enum entry; use the actual source's `LocalContainer` composition local.
3. Add appropriate metadata setup to TV Settings (the screen has a development-only `Metadata` action). Do not embed token in `BuildConfig` for public releases. Persist TV add-ons with the already provided Android credential vault.
4. Test with a D-pad: hero focus, posters, season buttons, source picker, player Back, and content scroll. Test Fire TV actual codecs and audio tracks. This is not a claim that Media3 can play every provider's codec/container.
5. Keep `mobile/.../PhoneCinema.kt` intact: your Android phone already has cinematic discovery, season/episode browsing, source picking, resume and library. Moving to a new UI without a proven parity test would discard working features.

## Nuvio-inspired functional coverage

| Area | This package | Additional integration needed for close Nuvio parity |
|---|---|---|
| Backdrop hero / posters / rows | Apple + TV | Design QA, optional parallax and hero auto paging |
| Popular and trending films / series | Apple + TV | Personalized/localized rails, filters, managed custom catalogs |
| Search | Apple | TV search/D-pad keyboard and pagination |
| Details, seasons, episodes, cast | Apple + TV | Extra video artwork, trailers and episode ratings |
| User-installed Stremio manifests | Apple + TV | GUI for editing/checking configurations and required catalog extras |
| Stremio stream resolution | Reuses existing shared resolver | Source ranking, codec probes, better fallback, timeout telemetry |
| Source picker / playback | Native AVPlayer + Android Media3 | MPV/libVLC fallback for MKV/custom headers, external subtitle downloads, engine switching |
| Local watchlist / resume | Apple + TV | Shared profile IDs and cross-device synchronization, up-next logic |
| Xtream VOD | Preserved in existing Apple legacy flow | Verified-identity unification with TMDB/series and duplicate management |
| IPTV live TV / EPG / sports | Preserved in BannerTV | Integrate tabs/home composition; do not duplicate IPTV infrastructure |
| Trakt/Simkl, Debrid, downloads, skip intro, watch party | Not included | Independent provider integration phases |
| Full tvOS native app | Not included | Add Kotlin tvOS targets, SwiftUI remote layout, TV player and system integration |

## Critical correctness and security constraints

- TMDB provides **metadata, not playback**; display the rating as `TMDB`, never fabricate an IMDb rating.
- IMDb IDs must be returned as verified external IDs by TMDB before querying an add-on. Never create `tt...` IDs from numeric TMDB IDs or title matching.
- Save watch progress by `(media kind, TMDB ID, season, episode)` and record completion; do not store full stream URLs, headers, tokens or configured add-on manifests in media history/cloud backups.
- Honor provider-supplied headers, but **do not** pass them to AVPlayer via undocumented header injection APIs. The supplied Apple picker disables sources needing custom headers or known unsupported extensions; use a supported native MPV bridge for that parity feature.
- Do not leak add-on manifest URLs, secrets, stream URLs or Keychain error descriptions in analytics/crash reports.
- If implementing public app distribution, check TMDB usage/attribution requirements and app store policies. The notice in the screens is required, and the official TMDB branding should be added in Credits where appropriate.
- Nuvio's repository is **GPLv3**. This pack is newly written; if you directly copy or derive from Nuvio's GPL code, satisfy its copyright/license/source redistribution terms. Do not copy logos, app names or assets.

## Test gates and honest status

- The Swift source files have been **syntax parsed** with `swiftc -frontend -parse` on Linux; `CinemaModels.swift` has been **type-checked** against Foundation on Linux.
- This environment does **not** have Apple SDKs or BannerFoundation framework artifacts, and this ZIP has not been compiled into your app or run on a device.
- The Android TV file has not been built inside your Gradle tree. Its media player, lifecycle, input focus and network path need device validation.
- Use the existing BannerTV baseline commands from `shared/CINEMA_QA.md`:

```sh
./gradlew :shared:compileCommonMainKotlinMetadata :shared:jvmTest \
    :core:testDebugUnitTest :mobile:testDebugUnitTest :app:testDebugUnitTest \
    --no-parallel --max-workers=1 -PpublicBuild=true
./gradlew :mobile:assembleDebug :app:assembleDebug --no-parallel --max-workers=1 -PpublicBuild=true
```

On macOS/Xcode, additionally compile the framework and run an iOS simulator build. Verify no build-time secrets end up in public artifacts, and test playback with your own authorized source configurations.
