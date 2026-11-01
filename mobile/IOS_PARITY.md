# iPhone → Android phone implementation audit

**Current execution/evidence: [PARITY_EXECUTION.md](PARITY_EXECUTION.md) and [VALIDATION.md](VALIDATION.md).**
The detailed 1.1 review in IOS_DEEP_AUDIT.md and earlier inventory are historical, not the current
completion checklist. This summary was reconciled on2026-10-26; an entry point is not full parity.

Current iOS main rechecked2026-10-26; original cross-repository review2026-10-22:

- `alexdiabmusic-stack/StadiaTV-iOS`, latest observed `73bf44cffa6a700b7e88390d62e9424692a6fd10`.
  Main advanced mid-pass; adaptive/icon source remains unchanged from b58b989. The new engine-only
  delta is audited but unported in IOS_ENGINE_73BF_AUDIT.md.
  Checkpoints through778 used `8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`.
- `alexdiabmusic-stack/StadiaTV`, commit `a81771a64cb706ce38cdf9fd144b8e9e62333655`, including the current Firestick section and older Android scaffold.

The phone implementation follows the current iPhone navigation and visual system. It uses Android touch controls,
system insets, dynamic font sizes and PiP rather than the TV app's remote focus widgets or fixed 1920×1080 canvas.

| iOS source | Android phone implementation | Status |
| --- | --- | --- |
| `ContentView.swift` / `Theme.swift` | Five bottom destinations; dark/light/system palettes, blue actions, neutral cards, semantic live color | Implemented |
| `OnboardingView.swift` / `WatchSetupView.swift` | Sports/leagues → teams → validated playlist connection; optional later setup | Implemented |
| `PlaylistsView.swift` / provider adapters | M3U and Xtream add/edit/default/refresh/remove, Keystore-encrypted Xtream credentials, provider headers | Shared core; phone UI implemented |
| `HomeView.swift` | Featured event, followed games, continue watching, live games, favorite channels, fantasy entry and news | Implemented |
| `MatchesView.swift` | Following, day filters, favorite teams/channels, ESPN fantasy | Implemented |
| `LiveView.swift` / `LiveTVView.swift` | Channels, searchable guide and live sports; confirmed Watch actions | Implemented |
| `TVGuideView.swift` | Channel/time grid, proportional cells, current-time marker, search, six-hour navigation, programme details | Implemented |
| `StreamAvailabilityStore.swift` | Existing shared stream matcher and provider XMLTV; uncertain matches remain labeled as possible channels | Implemented; no third-party EPG |
| `PlayerView.swift` / stream engine | Shared Media3 controller, restart/recovery, mini-player, portrait/landscape, PiP, quality/tracks/aspect/buffer choices | Implemented for existing Android transports/codecs |
| `MultiScreenPlayerView` | RAM-aware two/four stream grid, exclusive audio, per-stream restart, save/restore | Implemented |
| `MatchDetailView.swift` / native Game Centres | Native NHL/MLB/NFL/NBA/WNBA/EPL/MLS/La Liga, F1/PGA and explicit legacy ESPN routing, rich tabs, independent freshness and cold caches | Source ports and bounded runtime checks; wider correction/device QA open |
| `StatsView.swift` / `RosterViews.swift` | Scores, seven-day schedule, standings, teams, roster, player, leaders, injuries, racing/golf field | Implemented |
| `NewsView.swift` / article library | Publisher feeds, embedded article reader, save/offline copies, hidden stories and muted sources | Present; broader publisher/offline/lifecycle QA open |
| `SearchView.swift` | Games, channels, team directories, followed-team players, leagues, news and podcasts | Present; not a worldwide athlete index; full search nuance audit open |
| `NotificationsCalendarSettingsView` | Manual/automatic followed-game planning, briefing/live/close dedup, spoiler protection, reboot receiver, calendar insert | Planning/settings present; automatic delivery/reboot QA and calendar synchronization beyond insertion open |
| `FantasySettingsView` / Banner native leagues / Sleeper | Read-only ESPN and Sleeper, local authoring/drafts/lineups, native NHL/NBA/MLB pools, native fixture/EPG context | Native bounded runtime checks; imported cross-screen joins, full scoring/matchup nuances and actual account QA open |
| `SettingsView.swift` / `MoreView.swift` | Appearance, playlists, guide refresh/country, teams, captions, buffering, quality preference, spoiler mode, score delay, library and setup | Implemented for the Android feature set |

## Remaining differences

Full parity is NOT complete. Podcast discovery/listening/download/queue code, custom channel groups,
catch-up/DVR, player gestures/tracks/actions, pitch/shot/track charts and native sport providers are
present; do not restart their ports as if absent. Their exact source/runtime boundaries, remaining
presentation/correction nuances, highlights/picks and account/device checks are in PARITY_EXECUTION.md.
The current adaptive continuation is documented in ADAPTIVE_PHONE_PARITY_PROGRESS.md, including
shared-scroll versus virtualized-pane tradeoffs and physical hinge/root-navigation checks still open.

The user requested Android equivalents for platform integrations and selected a manual cloud backup
file, not real-time iCloud sync. SAF export/preview/restore, widgets, notifications, voice/media/Android
Auto service and Cast client exist. Cloud-provider/hardware verification remains open. The Play Billing
client defaults to explicitly unlocked/cannot-charge; the owner has no Play products/signing/backend
yet. A real receipt-verification/acknowledgement service and licensed purchase QA are not fabricated.

The shared Android transport engine protects raw MPEG-TS acquisition and keeps native HLS as a fallback. It does not port
the iOS loopback-HLS/fMP4 packager or promise playback through an outage longer than the available reserve. Real providers,
account concurrency restrictions and hardware codecs still determine which streams can be decoded on a particular phone.

## Validation

The implementation is checked with the shared core unit suite, TV UI logic suite, phone guide/URL/reminder regression tests,
both Android builds, Android lint and a Pixel emulator smoke test. Actual results and any device-only limitations are recorded
in the delivery message; repository inspection alone is not runtime proof of complete iOS parity.
