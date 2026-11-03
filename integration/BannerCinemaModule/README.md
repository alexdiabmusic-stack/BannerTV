# Banner Cinema Module — integrated iOS sources

The original staging package is now partly integrated into the iPhone app (steps 1–5). Active source files:

- `shared/src/iosMain/kotlin/com/alexdiab/stadiatv/catalog/CinemaBridge.kt`
- `shared/src/iosMain/kotlin/com/alexdiab/stadiatv/foundation/AppleFoundation.kt` (cinema factory)
- `apple/MyApp/Cinema/` (six Swift Cinema files plus `KeychainAddonVault.swift`)
- `apple/scripts/build-banner-foundation.sh` and the MyApp Xcode target Run Script phase

The iPhone Movies tab and Live TV home Movies & Shows shortcut now route to `BannerCinemaRoot()`. Existing Series, Sports, Live TV, IPTV, and legacy Xtream VOD paths are preserved. `AndroidTV/` and `Tests/` files remaining in this staging folder are not yet integrated. `Integration/INTEGRATION.md` describes the historical copy procedure; do **not** reapply its AppleFoundation patch. Full platform compilation, device testing and source configuration remain outstanding.
