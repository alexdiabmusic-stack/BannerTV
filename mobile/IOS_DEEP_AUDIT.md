# iOS behaviour and presentation audit — Android phone 1.1

Historical 1.1 audit. Several gaps below were addressed in 1.2–1.7; the current implementation and remaining-work
authority is [PARITY_EXECUTION.md](PARITY_EXECUTION.md), not this historical inventory.

Reviewed 2026-10-22 against `StadiaTV-iOS` commit
[`8f595b0`](https://github.com/alexdiabmusic-stack/StadiaTV-iOS/tree/8f595b0790c7b0f08e2b3c6a7298a6861ff1af93)
and `StadiaTV` commit `a81771a64cb706ce38cdf9fd144b8e9e62333655`.

The original parity checklist described feature entry points. It did **not** establish detailed visual or behavioural
parity. A player with stock controls, a generic game card and a table for every sport missed substantial iPhone behaviour.
This audit supersedes those broad UI claims. Source inspection establishes intended behaviour, not proof that every iOS
feature works on a device. The Android changes and the remaining gaps below are separate.

## Sources traced

- [HomeView.swift](https://github.com/alexdiabmusic-stack/StadiaTV-iOS/blob/8f595b0790c7b0f08e2b3c6a7298a6861ff1af93/MyApp/HomeView.swift): section hierarchy, greeting, prime/curated heroes, Sports Day zero/one/multiple-game states, live command centre, countdowns, weekend boundaries, favourite channels and resume rails.
- [PlayerView.swift](https://github.com/alexdiabmusic-stack/StadiaTV-iOS/blob/8f595b0790c7b0f08e2b3c6a7298a6861ff1af93/MyApp/PlayerView.swift): shared video surface, portrait/landscape composition, chrome, auto-hide guards, gestures, source identity, score association, channel information, drawers, diagnostics and multiview entry.
- [PlayerActionRegistry.swift](https://github.com/alexdiabmusic-stack/StadiaTV-iOS/blob/8f595b0790c7b0f08e2b3c6a7298a6861ff1af93/MyApp/PlayerActionRegistry.swift): ordered quick actions, maximum three slots and default back-fill.
- [MatchDetailView.swift](https://github.com/alexdiabmusic-stack/StadiaTV-iOS/blob/8f595b0790c7b0f08e2b3c6a7298a6861ff1af93/MyApp/MatchDetailView.swift), `GameCentreViews.swift`, `GameDetail/GameDetailComponents.swift`: score hero, team destinations, primary/alternate streams, game actions, event timeline, leader strips, comparison-stat visualization, news and fantasy context.
- `Basketball/UI/BasketballGameCenterView.swift`, `BasketballGamePresentation.swift`, `BasketballCourtCoordinateTransformer.swift`, `NBAPlayMapper.swift`, NBA API clients/endpoints: lead-change/tie timeline, shot validation, stable event corrections, source identity, data freshness and host backoff.
- `NHL/UI/NHLGameCenterView.swift`, `NHLGameCenterService.swift`, `NHLPlayMapper.swift`, `HockeyModels.swift`: goals, strengths/assists, period context, player identities and accepting/rejecting feed revisions.
- `NFL/UI/NFLGameCenterView.swift`, `MLB/UI/MLBGameCenterView.swift`, `Soccer/UI/GameCentre/SoccerGameCentreView.swift`: distinct sport sections and live situations, not a single universal layout.
- `MatchesView.swift`, `LiveView.swift`, `LiveBrowserView.swift`, `StatsView.swift`, `NewsView.swift`, `SearchView.swift`, `UI/DesignComponents.swift`, `Theme.swift`, `Documentation/PlayerUpgradeAudit.md`: surrounding navigation, entity filtering, summaries, search ranking, reader/pagination differences, component tokens and documented technical limitations.

## Home

| iPhone nuance | Android 1.1 treatment |
| --- | --- |
| Time-of-day greeting, neutral foundation, 20-point horizontal margin, generous section rhythm | Implemented using phone Compose layouts and the existing iOS-derived palette |
| Prime game is not just another vertical game-list row | Dedicated 22dp gradient hero, opposing crests, central score/start time, league/status, countdown and separate actions |
| Curated team-matchup versus event heroes | Prime team matchup implemented; curated non-team/event promotion heroes remain different |
| Favourite channels immediately follow the hero | Compact horizontal crest rail before Sports Day |
| Sports Day has no-team, no-game, single/multiple-game states | Distinct follow/next-game states and live-first favourite-team rows; iOS's bespoke expanded single-game treatment is not pixel-identical |
| Fantasy context is relevant to today's viewing | Connected ESPN matchup card retained; native-fantasy player/event intelligence remains a gap |
| Live command centre has For You, All Sports and sport filters | Horizontal scoreboard rail, live count, scoped filters and distinct between-games empty state |
| Starting Soon is a timeline rather than another full list | Time-first horizontal cards with crests and countdown; appears alongside active live games |
| Schedule uses actual Today/Tomorrow/upcoming Sat+Sun, not a generic seven-day range | Calendar-based filters, date grouping and next-game fallback; DST/weekend regression tests |
| Continue Watching goes last so it does not displace current events | Moved to the bottom as a programme-aware resume rail |
| Entrance motion respects Reduce Motion | No forced multi-stage entrance animation in Android; full iOS staging has not been copied |
| Trending highlights | Not substituted with fake highlights; Android currently has a news section instead |

Implementation: `PhoneHomeSections.kt`, `PhonePresentation.kt`, existing `FeedSectionsBuilder`.

## Player

| iPhone nuance | Android 1.1 treatment |
| --- | --- |
| Video stays at one structural position through orientation changes | One retained Media3 controller and one stage position; landscape extras are siblings |
| Large portrait player with game-aware content underneath | Responsive tall stage; connected game uses the compact Game Centre; ordinary TV uses channel/On Now/Up Next |
| Cinematic top/centre/bottom controls, not the platform's default transport bar | Custom Compose scrims, channel/programme metadata, centre pause/play, PiP/orientation/options, programme progress, live-edge action, mute, restart and lock |
| Auto-hide pauses during interaction, buffering, pause, sheets and accessibility use | Guarded timeout and TalkBack state listener; persistent unlock action; timeout preference |
| Quick Guide/Channels/Recent/PiP slots, max three | Persisted choices with safe non-empty fallback; selecting order is supported, drag reordering is not |
| Double-tap uses the seek/channel preference | ±10 seconds only when Media3 reports seekability, otherwise explains the limit; channel-zap mode when disabled |
| Left/right vertical gestures control brightness/volume with HUD | Implemented with bounded values; brightness is window-local and restored on exit; volume uses the Android media stream |
| Gesture equivalents remain accessible | Explicit buttons and sliders coexist with gestures; no essential action is gesture-only |
| Landscape game-information panel does not stop video | Toggleable 300dp side panel, close action and landscape channel shortcuts |
| Source picker stays within the active channel's identity | Canonical-channel source copies only; switching source retains the connected match; full restart remains separate |
| Zapping after choosing a different quality copy | Resolves the current canonical lineup entry before moving; boundary buttons are disabled |
| Confirmed match association, never weak name-only association | Automatic association requires exactly one live game, confirmed source identity and the current provider programme title |
| Association expires when the programme changes | Current programme signature is monitored; old Game Centre association is cleared at the programme boundary |
| On Now/Up Next advances without a new XMLTV download | Clock-based channel-page refresh independently of guide revision |
| Score bug can be delayed and hidden | Existing score buffer/spoiler controls reused; player score remains hidden in spoiler mode |
| In-player compact guide and drawers preserve playback | Existing controller remains alive under sheets and guide selection replaces its channel |
| Track/aspect/buffer choices, diagnostics | Real audio/subtitle tracks, per-channel aspect, quality cap, buffer profile, reserve/phase/rate and notices |
| Multiview starts from the playing channel | New player entry seeds the first tile after stopping the single-player session; RAM-aware existing multiview reused |
| Swipe-down dismissal, landscape swipe-to-panel, source-chip swipe | Not yet ported; explicit minimize/panel/source buttons work |
| Rich sports/fantasy sidebar and live fantasy toasts | Generic sports companion upgraded; iOS native-fantasy event overlays/toasts not ported |
| AirPlay/commercial detection/DVR | No fake substitutes. Android PiP is native; casting/commercial detection/long-DVR are not implemented |

Implementation: `PhonePlayerModern.kt`, `PhonePlayer.kt` (video surface), `MobileViewModel`, `PhoneLibrary`, shared playback engine.
Android gesture/custom-control translation was checked against the official
[Compose tap guidance](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/tap-and-press),
[drag guidance](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-swipe-fling) and
[Media3 custom UI guidance](https://developer.android.com/media/media3/ui/compose-customization).

## Game Centre

| iPhone nuance | Android 1.1 treatment |
| --- | --- |
| Dedicated score hero and team-crest roster destinations | Separate surface, status, opposing crests/records, central score/start time and working team destinations |
| Primary confirmed stream and separately accessible alternatives | Persistent watch/find bar and provider-evidence source sheet; unconfirmed channels remain explicitly possible |
| Pregame broadcast is not automatically a live match | Action says Watch Channel outside live play; does not force a pregame match into the player |
| Sport-dependent navigation and section order | Hockey scoring, basketball lead changes/ties, soccer Events label, box score, full team stats, real drives where supplied and configured native shot tabs |
| Scoring timeline, not repeated generic cards | Period/clock, team crest, score after scoring events, event type and description |
| Full play history is needed for analysis | Up to 2,000 events retained instead of only the last 80; stable duplicate IDs take the newest correction |
| Period and scoring-only play filters | Implemented with sport's period labels |
| Leader cards are compact and accurate about the timeframe | Headshot strip; pregame says Season Leaders; full value can be opened rather than silently lost to truncation |
| Comparison stats distinguish counts from efficiencies | Counts use shares; fractions use denominators; percentages use independent bars; malformed values do not draw invented bars |
| Box score has readable player identities and groups | Fixed player-name column, horizontally scrolling stats, team/group selectors, starter/DNP information, totals and real athlete destinations |
| Cached content stays visible on partial refresh failure | Retained per-game snapshots, checked time, retry/error notice, foreground lifecycle polling and bounded 12-game memory cache |
| Final spoiler protection has a local reveal action | Final scores/details hidden until revealed for that game, without changing the global preference |
| NBA shot locations are not inferred from distance | Native NBA schedule/date/team identity resolution and live play data; iOS legacy conversion, sentinel/envelope/distance checks and corrected-event replacement |
| NHL shots use actual rink coordinates | Native date/both-team resolution, returned game identity check, rink bounds, roster names, team/period filters and tappable point/list details |
| Spatial feeds can be blocked | Unavailable state, bounded timeouts, 403 cooldown and Retry-After backoff; never substitutes fabricated points |
| Win probability and football drives | Parsed/rendered only when supplied by ESPN; no estimated probability curves or synthetic drives |
| Game information is a labelled card | Venue, broadcast, attendance/weather/officials where present, with checked/source label |

Implementation: `PhoneGameCentre.kt`, `NativeGameInsights.kt`, enriched `EspnSummary`/domain types, `MobileViewModel`.

### Sport-specific gaps that still matter

These are **not** dismissed as optional styling. They are still incomplete ports:

1. **MLB:** native at-bat/pitch feed, batter/pitcher matchup, base diamond/count, pitch traces, detailed lineups and replay/highlight links.
2. **NFL:** native week/calendar model, possession/field position graphic, full native play descriptions, richer drive progression and provider-specific situation reducers.
3. **Soccer:** formations/pitch positions, native lineups/substitutions/cards/shootout representation, live table, cursor-based commentary, native/FotMob shot maps and reconciliation.
4. **NHL:** goal-strength/assist/replay enrichment and native box-score/anti-regression reducer parity; Android's main summary remains ESPN, with native rink geometry enrichment.
5. **NBA/WNBA:** native on-court/bonus/timeout data, full native box-score reducers and stats-host fallback; WNBA shot geometry is not claimed verified.
6. **F1/PGA:** event-specific hero and driver/player/hole/track views rather than generic team-matchup assumptions.
7. **Shared detail:** game-relevant news ranking, richer fantasy context, full provider-specific freshness/regression semantics and the iOS density/motion details need further work.

## Surrounding screens

Following's favourite-team chips now **filter** rather than open the editor; league chips, management, live/today/week
summary and coming-up rows are added. The iOS news and standings snapshot sections, hidden-game actions and
playlist-coverage collapse are not complete Android ports.

Search now ranks exact names before prefixes/contains, requires all normalized tokens, and searches team directories,
players on up to six followed-team rosters, current games, provider channels, saved articles and real settings shortcuts.
Queries are debounced and large-playlist ranking runs off the UI thread; directory requests are bounded to two at a time,
partial results survive failures and retry is exposed. This is **not** a full worldwide player/podcast/article index.

Live channels/guide keep provider XMLTV and the existing identity/matching engine. iOS's richer sport navigator,
coverage/intelligence badges and custom channel-group editing remain differences. Stats has real data routes but still
lacks the dedicated iOS overview/action tiles and all format-specific league treatment. News remains browser-based
with a saved library, not the iOS embedded reader, load-more pagination, mute/offline stack. ESPN fantasy remains
read-only, not the iOS native/Sleeper/league-authoring suite. Apple-only services have not been simulated.

## Verification boundaries

See [VALIDATION.md](VALIDATION.md) for executable checks and final artifact results. Emulator checks are not proof of
physical decoder capacity, uninterrupted-provider resilience, every gesture/accessibility combination or complete UI parity.
The NBA CDN returned HTTP 403 in this environment; the spatial mapping has regression coverage, but a successful real
NBA shot chart on this network is **not** claimed. No test provider/login or constructed sports result is bundled in the APK.
