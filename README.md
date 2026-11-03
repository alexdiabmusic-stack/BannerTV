# BannerTV for Android

> **Apple / Xcode Cloud:** Open [BannerTV.xcworkspace](BannerTV.xcworkspace) at the repository root, then select the shared `MyApp` scheme for iOS. It references the existing `apple/Stadia TV.xcodeproj` without duplicating the shared Kotlin foundation. See [Apple setup](apple/README.md#xcode-cloud--repository-layout).

The Android home of BannerTV, the sports and live TV app that also exists for iOS and tvOS. It contains the
**Fire TV / Android TV** app (`:app`, package `com.alexdiab.stadiatv`) and the **Android phone** app
(`:mobile`, package `com.alexdiab.stadiatv.mobile`), with Android adapters and playback in `:core`
and portable sports/business logic in the Kotlin Multiplatform `:shared` module.
The TV interface uses Compose for TV, remote focus and a fixed 1920x1080 canvas. The phone interface uses
Compose Material 3, touch navigation, system insets, portrait/landscape playback and native picture-in-picture.

The app does not provide or bundle television streams. You add a playlist you are authorised to use.

## Location in StadiaTV

This app has its own section in [alexdiabmusic-stack/StadiaTV](https://github.com/alexdiabmusic-stack/StadiaTV/tree/main/Firestick-Android-TV),
under `Firestick-Android-TV/`. Open that directory as the Android Studio project, or build from a repository checkout:

```bash
cd Firestick-Android-TV
./gradlew :app:assembleDebug
```

The Gradle project is self-contained in this directory; the repository's older root-level app has its own build.

## Repository layout

```
app/                 Fire TV / Android TV screens and launcher
mobile/              Android phone screens, launcher and phone integrations
core/                Android storage, playlist/EPG/matching, lifecycle and playback adapters
shared/              Kotlin Multiplatform sports, fantasy, favourites, Stremio add-ons and TMDB
apple/               Apple host adapters and integration instructions (Xcode verification pending)
gradle/, gradlew     the Gradle wrapper; builds need no installed Gradle
README.md            this file
```

The phone app has its own [setup and build guide](mobile/README.md) and an [iOS parity audit](mobile/IOS_PARITY.md).
Kotlin packages retain their existing `com.alexdiab.stadiatv` names. Portable services now live under
`shared/src/commonMain/kotlin`; Android adapters remain in `core/src/main/kotlin`.
The [foundation guide](shared/README.md) documents provider coverage, credential handling and remaining Apple integration work.
The TV app's focus widgets are kept in `app/src/main/kotlin/.../ui`; the phone screens use independent touch layouts.

## What the TV app does

Five destinations in the top bar, with Settings as an icon beside the clock:

- **Home** - "Your Sports Tonight" (the title follows the time of day): one compact featured game with a scoreboard,
  **Watch Live** on the best confirmed channel (or **Find a stream**, with the reason when nothing is confirmed), and
  **Match Centre**; then Live Around the Leagues, Continue Watching, Your Teams, Today's Top Picks, your Live TV channels
  and the latest results, ordered by what is going on (live games lead; mornings look back, evenings look ahead).
  If scores could not be refreshed it says so and shows how old they are, rather than presenting them as current.
- **Sports** - one hub with Live, Schedule, Standings & Stats and News. Every live game with the streams linked to it,
  a five-day schedule per league, standings, leaders, teams, rosters, athlete profiles, injuries, F1 and golf fields,
  and ESPN merged with CBS Sports, BBC Sport and Sky Sports news, de-duplicated and ranked.
- **Live TV** - a discovery screen built from the lineup: Continue Watching, favourite channels, Sports On Now, News,
  Movies, Canada, USA, French and Kids, each tile showing a clean channel name, the quality that will play and what is
  on with its progress. Browse All opens the flat list of every channel with search, categories and quality filters.
- **Guide** - the TV Guide as a tab: a timeline grid (the default), an On Now list and a Live Games view that links each
  game to its channel. See "The lineup" below.
- **My Banner** - what is yours: Teams & Games (followed teams and leagues), Channels (starred channels) and Fantasy
  (your ESPN league). Hold OK on any channel tile to star it.
- **Match Centre** - a compact companion: score header with a persistent Watch Live, line score, top performers, team
  stats, plays and box score, then more ways to watch, labelled moneyline odds and related news.
- **Player** - HLS/IPTV playback with a protected raw-transport path: Media3 keeps consuming its compressed reserve while
  the app replaces a failed provider socket, realigns 188-byte MPEG-TS packets, rejoins at a random-access picture and
  repairs continuity counters plus PTS/DTS/PCR. Xtream uses raw TS first and HLS as its fallback. The player also has a
  live score overlay (with an optional delay), quality, audio and subtitle choices, and a Fantasy panel. With the controls
  hidden: OK shows them, Up shows the scoreboard (or what is on), Down opens the channel switcher, Back closes whatever
  is open first.
- **Multiview** - 2, 3 or 4 streams at once (side by side, stacked, picture-in-picture, 3-up, 4-up), one with sound,
  with four saved layouts. Devices flagged low-RAM are limited to two streams.
- **First run and Favourites** - setup asks for your sports, then your leagues (a sport's leagues come pre-selected to
  narrow down), then your teams (optional), then an optional playlist. Those choices decide what Home shows: the featured
  game is a starred team's game that is live or within a day, else the best game in your leagues, else (only if there is
  nothing of yours) any game; "live games", Top Picks and results are limited to your leagues and teams, while the Sports
  tab always lists every game. **Settings → Favourites** adds or removes sports, leagues and teams at any time, with the
  starred teams listed so one can be removed without finding its league; My Banner has an "Edit favourites" shortcut,
  and Home offers "Choose Favourites" until something is chosen. Redoing setup starts from what is already chosen.
- **Settings** - playlists, favourites, spoiler-free mode, score delay, buffering profile, controls timeout, HD / 4K
  preference, subtitles, home country, ESPN Fantasy connection.

Playlists are M3U links or Xtream logins, and the guide is built from a master channel catalogue; see "The lineup".

Visual rules: live is shown by the LIVE badge alone, never by a glowing card; the focus ring marks where the remote
is, and a filled chip or tab marks what is selected; screens keep 80 px side margins on the 1920x1080 canvas.

## The lineup

A provider's channel list is thousands of differently-spelled streams ("CA ★ CTV OTTAWA HD", "CAF ★ TVA SHERBROOKE").
`lineup/` turns it into the lineup the guide shows:

1. **Catalogue** (`CatalogData.kt`) - about 390 channels in 12 sections (broadcast networks with local stations, Canadian
   and US sport, regional sports, news, entertainment, documentary and lifestyle, premium, kids, French, Spanish) with
   tiers, genres and the aliases providers use. The US and Canadian channel of the same name are separate entries.
2. **Matching** (`StreamName`, `CatalogMatcher`) - a name becomes an identity: catalogue channel, city or callsign for a
   local station, country of the feed, coast. A guide id ("CTVOttawa-CJOH.ca") is read the same way.
3. **Grouping** - HD, FHD, SD and 4K copies of a channel are one entry; event, PPV and adult streams are left out; free
   streaming (Pluto TV ...) channels are kept apart and hidden by default.
4. **A schedule for every channel** (`LineupBuilder`), in this order: the provider's id (only if it has programmes and
   belongs to this channel), another quality's id, a guide channel found by identity, the network's schedule from a
   sister station in the same time zone, the East feed shifted three hours for a West feed, and finally the same brand's
   stream in the other country (that stream then plays, so picture and schedule agree).
5. **Playing** - HD before SD (and 1080p before 4K) unless you turn "Prefer HD" off; the viewer's country's feed first;
   a stream that stops responding fails over to the next copy of the channel.

The guide itself (`live/GuideLoader`, `GuideStore`) streams a provider's XMLTV once (hundreds of MB is fine), keeps only
the channels the lineup can use, stores a week of programmes as compact arrays, and caches the result so the next launch
has a guide immediately.

Subtitles: embedded CEA-608/708 captions and WebVTT tracks appear as a **CC** button and in Options; the choice is
remembered and re-applied on the next channel.

## Data sources

| Area | Source |
| --- | --- |
| Scores, schedules, standings, rosters, Game Centre | ESPN (the identity used for favourites and stream linking) |
| News | ESPN, CBS Sports, BBC Sport, Sky Sports RSS |
| Odds | MoneyLine API (NFL, NBA, MLB, NHL) |
| Guide | the playlist's own XMLTV only, so schedule and stream IDs always come from the same provider |
| Fantasy | ESPN Fantasy API |

NBC Sports and FOX Sports RSS feeds exist in the iOS app but were returning a 404 page and an empty channel when checked
(2026-10-14), so they are not used here.

## Build

Requirements: JDK 17+ and Android SDK platform 36 (library versions are pinned to releases that compile against it).

```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew :mobile:assembleDebug     # Android phone APK
./gradlew :app:assembleRelease      # R8-minified APK with the baseline profile
./gradlew :core:testDebugUnitTest :mobile:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:lintDebug            # lint (the project is kept at zero errors)
```

The release APK is signed with the machine's debug key unless a `keystore.properties` file (git-ignored) is present at the
repository root with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. A debug-signed APK installs fine on your own
devices, but a build made on another machine, or later signed with a real key, cannot update an installed copy: Android
requires one uninstall first. Decide on the real key before sharing the app widely, and back it up: it cannot be recovered.

Machine-specific settings live in `local.properties` (git-ignored):

```properties
sdk.dir=/path/to/Android/sdk
# Optional: write build output somewhere else (useful when the project folder is synced by iCloud or Dropbox).
build.root=/Users/you/Library/Caches/StadiaTVFirestick/build
# Optional, for the tests that need a whole real provider lineup: a folder holding its player_api JSON and XMLTV.
# Those tests are skipped when it is absent.
LINEUP_DATA_DIR=/path/to/saved/provider/data
# Optional API keys; a missing key simply disables the feature that needs it.
ODDS_API_KEY=...
ODDS_API_BASE_URL=https://mlapi.bet/v1
YOUTUBE_API_KEY=...
```

Keys are read at build time into `BuildConfig` and never committed. APKs are written to
`<build.root>/{app,mobile}/outputs/apk/{debug,release}/` (or the module's `build/outputs/apk/...` without `build.root`).

**An APK contains its keys and anyone can read them out of it.** For an APK you will share or attach to a public GitHub
release, build with `./gradlew :app:assembleRelease -PpublicBuild=true`: every `*_API_KEY` is left empty, and the features
that need one (moneyline odds) are simply off.

## Testing against a real provider (debug builds)

Debug builds carry a few tools that are not in release builds (`app/src/debug`):

```bash
# Add a stream login without typing it into the form (so real credentials never show on screen or in screenshots); runs the same check-and-add as the form.
adb shell am broadcast -n com.alexdiab.stadiatv/.DebugPlaylistReceiver -a com.alexdiab.stadiatv.DEBUG_ADD_XTREAM --es host H --es user U --es pass P
# Play a spread of the lineup's channels one after another and log time-to-first-frame and rebuffers.
adb shell am broadcast -n com.alexdiab.stadiatv/.DebugPlaylistReceiver -a com.alexdiab.stadiatv.DEBUG_ZAP_TEST --ei count 8 --ei seconds 20
# Sample where the app's threads are busy (simpleperf is not permitted on most devices).
adb shell am broadcast -n com.alexdiab.stadiatv/.DebugPlaylistReceiver -a com.alexdiab.stadiatv.DEBUG_SAMPLE --ei seconds 30
adb logcat -s PlaybackMetrics ZapTest Sampler Perf
```

Use a throwaway login against a local relay rather than a real subscription's credentials where you can.

## Install on a Fire TV

Enable **Developer options -> ADB debugging** (and, to install without a computer, **Apps from Unknown Sources**), then:

```bash
adb connect FIRESTICK_IP:5555
adb install -r BannerTV-2.1.0-public.apk
```

Or put the APK at a link and open it with the Downloader app on the Fire TV. The package is `com.alexdiab.stadiatv`; it
appears in the Fire TV apps list as BannerTV. The minimum is Android 6 (API 23), so it needs Fire OS 6 or later; the oldest Fire TV devices (Fire OS 5, Android 5.1) are not supported.

## Releasing

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts` (a build can only replace an installed one with a higher
   `versionCode`).
2. Run the unit tests and lint, then build the shareable APK with `-PpublicBuild=true`.
3. Create a GitHub release for the tag (`v2.1.0`) and attach that APK. Do not attach a build made without `-PpublicBuild=true`.

## How it is organised

```
core/src/main/kotlin/com/alexdiab/stadiatv/
  AppContainer.kt      service locator: one instance of every repository and store
  domain/              Match, League, Team, Stats, GameCentre models
  net/                 HttpGateway (retries, Retry-After, TTL cache, request coalescing), JSON helpers
  sports/              EspnApi, provider interface, SportsRepository with a per-league circuit breaker
  data/                MatchFeed (the Home feed), PreferencesStore, news (RSS + ranking), odds, fantasy
  lineup/              channel catalogue, name / guide-id matching, lineup building, LineupStore
  live/                M3U / Xtream / XMLTV parsing, PlaylistStore, GuideLoader / GuideStore, StreamLinker (match -> channel)
  playback/            PlaybackController (Media3), Neverlag-style transport engine, multiview layouts, score buffering
  ui/livetv/           pure guide filtering/ordering models shared by the apps and regression tests
app/src/main/kotlin/com/alexdiab/stadiatv/ui/       TV screens, focus widgets and panels
mobile/src/main/kotlin/com/alexdiab/stadiatv/mobile/  phone screens and Android integrations
legacy/                the previous Java UI and data layer, kept for reference and not compiled
```

Design notes that matter when changing the UI:

- `FocusCard` animates scale, ring and glow inside draw/graphicsLayer lambdas, so focus movement never recomposes.
- Rows use `focusRestorer`; pushed screens claim focus themselves and keep D-pad movement from leaking into the tab
  content underneath (`OverlayHost` in `AppRoot.kt`).
- The tab bar previews a tab after a short focus debounce and cancels the preview when focus leaves the bar. Up from the
  top of a screen returns to the tab you are on (`up` on the content group in `AppRoot.kt`; `focusRestorer` already owns `exit`).
- Sports and My Banner are hubs: a row of section chips over screens that were written to sit under the bar, so they read
  `LocalTopInset` instead of the `TopBarInset` constant.
- Network work goes through `HttpGateway`, so identical in-flight requests share one call and responses are cached briefly.

## Performance

The release build is minified and ships a baseline profile (`app/src/main/baseline-prof.txt`) so ART compiles the
Compose, TV and playback code at install time instead of running it interpreted on first launch. The profile is a
package-level list, not one recorded from a benchmark; generate one on a real Fire TV when you have it.

Stream resilience is intentionally Firestick-sized: Fast, Balanced and Stable target roughly 8, 20 and 35 seconds while
Media3 caps compressed buffering at 24, 32 and 48 MB. A local reserve can hide an outage only until it is exhausted; the
UI calls this feature **Stream shield** rather than promising that an unavailable provider can literally never freeze.

## Testing

Unit tests cover the stream engine (packet-boundary recovery, timestamp/continuity splicing, explicit discontinuity
packets, reconnect arbitration, delivery cadence and conservative playback pacing), plus the lineup (name parsing,
catalogue matching, guide resolution, HD priority, fallbacks, the guide's
filters and ordering, the compact schedule store) against a fixture of real provider names, plus ESPN scoreboard and Game Centre parsing, stream linking, playlist and guide parsing, channel filtering, news parsing and ranking,
odds matching, fantasy parsing and request handling, and multiview layout rules. Playback, focus behaviour and
performance depend on the device and the provider, so check them on a real Fire TV: codec support, memory and
cleartext-stream behaviour differ between Fire OS versions.
