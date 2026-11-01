# BannerTV for Android phones

The touch-first Android phone app, based on the current iPhone app in `alexdiabmusic-stack/StadiaTV-iOS`.
The project uses native Compose Material 3 screens and shares the TV app's sports, playlist, provider EPG,
matching, favorites, history, fantasy and Media3 playback code through `:core`.

Open the Android project root (the directory containing `settings.gradle.kts`) in Android Studio. This module
depends on `:core`; it is not a separate copy of the TV app.

## Build and install

Requires JDK 17+, Android SDK 36, and Android 8.0/API 26 or newer on the phone.

```sh
./gradlew :mobile:assembleDebug
./gradlew :core:testDebugUnitTest :mobile:testDebugUnitTest :app:testDebugUnitTest
./gradlew :mobile:lintDebug :app:lintDebug
./gradlew :mobile:assembleRelease -PpublicBuild=true
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
```

When `local.properties` defines `build.root`, APKs are under `<build.root>/mobile/outputs/apk/`.
The package is `com.alexdiab.stadiatv.mobile`, so the phone and TV builds can coexist. Release builds currently
use the development signing key; configure a production keystore before store distribution.

Latest tested checkpoint: `dist/BannerTV-Android-Phone-1.7.0.apk` at the Android project root.
See [VALIDATION.md](VALIDATION.md) for its checksum, exact build command, emulator results and testing boundaries.

## Phone experience

- Home, Following, Live, Discover and Settings bottom navigation, matching the iOS information architecture.
- A first-run flow for sports/leagues, favorite teams and M3U/Xtream playlist setup. Setup can be completed without a playlist.
- Validated add/edit flows, encrypted Xtream credentials, provider headers and playlist refresh/default selection.
- Live channels, favorites, a searchable cable-style programme grid and sports-event stream selection from the playlist's XMLTV.
- Personalized home games, scores, schedules, standings, teams, rosters, player profiles, leaders, injuries and match centres.
- Native article reader with reading appearance/spacing, progress, offline copies, saved stories and source mute/hide;
  restricted or paywalled articles fall back to the publisher instead of bypassing access controls.
- Podcast catalog/search/RSS, subscriptions, queue, downloads, bookmarks, audio/video, background playback,
  lock-screen/headset controls and an Android Auto audio browser/voice-search integration.
- Banner local fantasy leagues with roster/scoring customization, personal/simulated drafts and roster transactions;
  ESPN league connection and read-only Sleeper NFL league/matchup/roster/standings. Private ESPN cookies use encrypted
  storage. Native fantasy does not yet have a verified live scoring feed.
- A retained Media3 session, mini-player, portrait and landscape playback, native PiP, restart, channel switching, quality,
  audio/subtitle choices, aspect modes and configurable buffering.
- Version 1.1 adds custom cinematic player controls, a sports/channel companion, lock and brightness/volume gestures,
  canonical-source switching and configurable quick actions. Home now has a matchup hero, Sports Day, live/time rails
  and calendar-correct schedule filters. Game Centre adds sport-aware tabs, timelines, comparison bars and a fixed-name
  scrolling box score; native NBA/NHL spatial enrichment is shown only when validated coordinates are available.
- Version 1.2 adds deeper MLB/NFL/NHL/NBA/WNBA/EPL native Game Centre data, F1 timing/archive/session views and native
  PGA leaderboards, scorecards, shots, course and tournament views. Missing or blocked feeds remain unavailable states.
- Version 1.3 adds source-declared archive replay from Guide, real DVR/replay seeking, finite replay completion and
  explicit return to live. Xtream archive metadata and M3U catch-up templates are respected; nothing is inferred solely
  from a channel name or XMLTV duration. Provider-supplied archive history is bounded to seven days for memory use.
  Player quick actions can be reordered, and portrait minimize/rotate, pinch fit/crop and source-chip gestures coexist
  with accessible controls. MLB highlights/probability load independently; highlights open in a finite in-app viewer.
- Version 1.4 adds remembered audio language, source-safe track choices, corrected subtitle-language changes and
  Original pixel-size playback. Picture modes now clearly distinguish Fill (crop) from Stretch without changing old
  saved choices. EPL adds native events, richer statistics and a separately fetched published/provisional table with
  independent failures and source labels. See [implementation notes](PLAYER_AND_EPL_PARITY_1_4.md).
- Version 1.5 deepens native NHL events: penalty roles, bench calls, goal/assist season totals, shootout rounds,
  multiple-overtime labels, source-independent scoring summaries and richer cards with replay/player/rink details.
  Event filters and a Jump to live action preserve reading position as new plays arrive. See [NHL notes](NHL_PARITY_1_5.md).
- Version 1.6 adds the native NFL season/stage/week calendar and results, announced playoff labels/byes and safely
  namespaced native game pages. NFL plays have expandable drives, participant names, down/first-down/turnover details
  and structured filters; reported quarter scores omit unused overtime. See [NFL notes](NFL_PARITY_1_6.md).
- Version 1.7 adds team-first Following scope, a live/up-next hero, date-grouped full schedule, scoped headline rails
  and contextual standings with actual ranks and followed-team highlights. Snapshots have bounded/coalesced requests,
  deadlines, retained data, checked times and retry. Home's single-game Sports Day gains opposing crests, state-specific
  actions and spoiler-safe winner labels. Follow changes reload schedules without waiting for a timer; month schedules
  can arrive before the complete season. See [Home/Following notes](HOME_FOLLOWING_PARITY_1_7.md).
- Google Cast sender controls with default-receiver handoff, a launcher score widget, custom channel groups in
  Channels/Guide, hidden games, and expanded search for news/podcasts/settings. Provider-specific stream headers
  cannot be forwarded by the default Cast receiver; the app explains that limitation rather than omitting them.
- Two/four-stream multiview according to device RAM, one audible stream, removal, recovery and saved arrangement.
- Manual reminders plus opt-in followed-team start/live/close-game and morning-briefing planning; add-to-calendar opens
  the phone's calendar app. Alerts depend on Android battery policy and sports refreshes, not a push-notification backend.
- System/dark/light appearance, spoiler controls, delayed score overlay, caption preference and HD/4K choices.
- Manual backup/restore through Android's document picker, with validation and a confirmation preview. Choose a cloud
  document provider or a local file in **Settings → Cloud backup & restore**. Preferences, library metadata, podcast
  bookmarks and local fantasy leagues can be backed up without exporting playlist credentials or IPTV stream URLs.
  Files are not encrypted by BannerTV; choose a private folder. This is Android-specific, not real-time/iCloud sync.

Full iOS parity is **not complete**. [PARITY_EXECUTION.md](PARITY_EXECUTION.md) is the current implementation and gap
ledger, including sport-specific algorithms, integration testing and production setup still outstanding.
See [IOS_PARITY.md](IOS_PARITY.md) for the initial repository feature inventory, not a completion certification.
The deeper [iOS behaviour/presentation audit](IOS_DEEP_AUDIT.md) describes the 1.1 player, Game Centre, Home, Following
and search refinements and lists the remaining sport-specific differences without treating them as complete ports.
See [VALIDATION.md](VALIDATION.md) for build results, emulator checks and the remaining device-testing requirements.

## Data and permissions

No streams, provider logins or personal playlist are bundled. Guide data comes from the configured playlist only.
HTTP is permitted for user-supplied providers because many IPTV servers still require it; HTTPS remains supported.
Notification permission is requested only when enabling alerts or setting a reminder. Calendar insertion uses a system
intent and requires no calendar read/write permission. IPTV playback pauses on background entry unless playing in native
PiP; podcasts use a user-visible foreground media service and continue with system media controls. Xtream logins are Keystore-encrypted;
M3U links and downloaded channel caches use private app storage (not separate encryption). App data is excluded from Android
automatic backup/device transfer. The explicit user-selected backup file excludes playlist/account connections, IPTV URLs,
reminders, downloads and offline article bodies. No personal login is bundled in source control.

The Cast sender uses the documented Android sender integration; podcast background playback uses a Media3
`MediaLibraryService`, and backup uses Storage Access Framework document contracts. Physical Cast/Android Auto,
long-running playback and low-memory phone verification are still required. Play Console products/test accounts and
production signing have not been configured, so a verified production purchase/restore flow is not claimed.
