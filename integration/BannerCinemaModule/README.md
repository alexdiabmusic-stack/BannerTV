# Banner Cinema Module — movies and series for BannerTV

**Source-based feature implementation** for the BannerTV shared Kotlin foundation, native SwiftUI iOS host, and Fire TV / Android TV host. Uses original source code. Nuvio-inspired UX but no Nuvio source code or assets are included.

## Start here

Read **`Integration/INTEGRATION.md`** before copying files. Your existing shared foundation **already has** TMDB, Stremio add-ons, source resolution, progress and watchlist rules; use those instead of rewriting them. The Android phone already has `PhoneCinema.kt`; do not overwrite it.

Main feature: scrolling hero, browse shelves, search, details, season/episode picker, cast, recommendations, watchlist and resume, secured add-on setup, source selection, basic native playback. The Android TV view is D-pad oriented. Native AVPlayer cannot handle every Nuvio stream type; extra playback engines, add-on catalogs, subtitle sidecars, tracking sync and Apple TV still require separate integration.

No GitHub files were changed by generating this ZIP. There is no claim of a finished, compiling iOS/Android release; this is a code package for integration and validation in Xcode/Codex/Android Studio.
