# Cinematic TMDB implementation — November 1, 2026

## Scope and reference

Reference: the user's three screenshots (`IMG_5959.PNG`, `IMG_5960.PNG`, `IMG_5961.PNG`) and the BannerTV UX research pack. The references establish artwork-led featured titles, compact metadata, poster rows, continuation progress, prominent playback actions and cast. Existing sports navigation and the followed-games home retain their roles.

The implementation is native Android Compose, backed by shared Kotlin models. No concept image is presented as a device screenshot. The SwiftUI and Fire TV versions of these movie screens are not implemented in this change.

## Audit and resulting changes

| Priority | Before | Implemented response |
| --- | --- | --- |
| P0 | Movies & Shows opened the add-on administration/catalog text interface | Dedicated discovery, search, library and detail routes; add-on management remains reachable |
| P1 | TMDB had basic metadata but no rich visual presentation model | Configured image sizes, localized logos, backdrops, cast/crew, series dates, episodes and recommendations |
| P1 | No movie/episode resume library | Shared watchlist/progress/completion/merge rules, Android persistence and cloud-file backup |
| P1 | A title did not connect directly to enabled streaming add-ons | Verified IMDb identity → enabled add-on resolution → source picker → player/retry/source switching |
| P1 | A source lookup could wait on slow providers/subtitles | Bounded concurrency and per-resource/overall deadlines, preserving working streams when subtitle retrieval fails |
| P1 | Metadata credential provisioning was not connected to the app | Encrypted runtime-only vault with validation before replacement and a development setup UI; no embedded token |
| P2 | Missing images/metadata could leave blank cards | Image placeholder, text fallback for missing/failed title logos, initials for absent portraits, omitted unknown ratings/runtime |

## Design rules applied

- Near-black `#090C12` media background, `#1B2029` elevated surfaces, white primary actions and `#ADB3BE` secondary text.
- A single artwork scrim protects text readability. No decorative glow, animated wallpaper or pervasive glass layers.
- 2:3 posters, 16:9 continuation/still images and circular cast portraits; consistent 14dp poster corners and restrained spacing.
- Featured pages change through user gestures or labelled 48dp controls. No automatic carousel movement.
- Rows and grids are lazy, paginated and keyed by media type plus ID; only the current featured title requests rich details.
- Native Material controls provide interaction semantics; icon actions are labelled, headings marked, saved/progress states do not rely on colour alone.
- Director/writer/cast details are subordinate to the title and primary action. Series episodes remain a separate, explicit selection step.
- TMDB scores are labelled TMDB. No fabricated IMDb rating, remaining time, completion marker or streaming availability.

## Completed non-device checks

- The supplied token successfully authenticated direct requests to TMDB configuration, popular movie and series catalogs, and movie details with images and credits. Only result counts/status were logged; no credentials were displayed.
- The approved TMDB SVG was obtained from the official branding page and included unchanged in the phone app's metadata credits.
- Source review confirms the private token is not a BuildConfig field or resource. Local developer settings are ignored by Git; runtime metadata and add-on vaults live in `noBackupFilesDir`.
- Regression tests cover localized/neutral image selection, cast ordering, series dates/runtime/specials, partial discovery failure, cancellation, watchlist/progress merge and completion, add-on enablement/identity/timeout behavior, credential replacement/locked storage and media backup sanitization.

## Build and regression result

**BUILD SUCCESSFUL in 1m 33s.** Common Kotlin metadata compilation, all four unit-test tasks and both Android debug assemblies passed.

| Module | Tests | Passed | Failures / errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Shared Kotlin | 231 | 231 | 0 | 0 |
| Android core | 471 | 466 | 0 | 5 |
| Android phone | 200 | 200 | 0 | 0 |
| Fire TV | 28 | 28 | 0 | 0 |
| **Total** | **930** | **925** | **0** | **5** |

The skips are the existing opt-in lineup/guide/provider-data tests identified in `VERIFICATION.md`. All four test tasks executed; none of their passes is inferred from source compilation.

Synced Documents files became locally unavailable during compilation. Verification therefore used an APFS-cloned source snapshot at `/private/tmp/banner-cinema-verify`, excluding generated build/cache directories and private local configuration. Only `sdk.dir` and `build.root` were supplied to that snapshot. All 806 source/project/fixture/assets files copied successfully; a subsequent correspondence check found no changed source/config/resource input among the 470 checked files. The working project remains in its original location.

Command, run from the snapshot:

```sh
./gradlew --no-watch-fs --continue \
  :shared:compileCommonMainKotlinMetadata :shared:jvmTest \
  :core:testDebugUnitTest :mobile:testDebugUnitTest :app:testDebugUnitTest \
  :mobile:assembleDebug :app:assembleDebug \
  --max-workers=1 --no-parallel -PpublicBuild=true \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx1200m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8'
```

Artifacts are in the configured build root, `/Users/alexdiab/Library/Caches/StadiaTVFirestick/build`, under `mobile/outputs/apk/debug/mobile-debug.apk` and `app/outputs/apk/debug/app-debug.apk`. They are development builds. Both passed ZIP integrity and Android signature checks. Private-credential scans of all decompressed APK entries found neither the supplied token nor the API key, including their UTF-8 and UTF-16 representations.

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| `mobile-debug.apk` | 34,519,934 bytes | `b5daba37bdcbba97c5dd4d74c4f5ddfb9d4f7b9d12e95bd75134d0b322b14bf6` |
| `app-debug.apk` | 29,565,235 bytes | `00bebaeefb6db6435114f0485d8b1a165a89d0daa7e9e2ed686298cf910566ff` |

## Device and release gates

No visual score is assigned against `QA_CHECKLIST.md` without device/emulator evidence. Device and emulator QA remain deferred per the user's instruction; there is no iOS SDK on this Mac.

Still required: small-screen/large-text/rotation screenshots, TalkBack traversal, image/logo contrast with varied real artwork, Back and scroll restoration, real source playback/resume/subtitles, background/foreground behavior and secure storage across device restarts. Those paths are implemented but not represented as device-tested.

The app needs runtime metadata provisioning on a development device. Production provisioning is unresolved; no hosted credential service was deployed. Public users should not be asked to supply a developer TMDB key as the final onboarding design.
