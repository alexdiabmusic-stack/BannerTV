# Full iOS parity execution ledger

User scope: all in-app iOS features **and Android equivalents for platform integrations**, confirmed in chat.
Latest observed reference: `StadiaTV-iOS` `73bf44cffa6a700b7e88390d62e9424692a6fd10` (main advanced
mid-pass on2026-10-26). Adaptive/icon work is pinned to unchanged b58b989 source; the three-commit
73bf44c delta affects only stream-engine code/tests/docs and is audited, not yet ported, in
IOS_ENGINE_73BF_AUDIT.md.
Checkpoints through778 used `8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`; the four-commit compare
adds adaptive/two-pane layouts and an icon update, not fantasy provider/scoring changes.
This is a working ledger, **not a completion claim**. A feature entry point alone does not establish parity.

## Current work

- Adaptive phone continuation rechecked iOS main at b58b989. Home, Following, Live Sports,
  Guide inline details, sport/F1 Game Centre and attached-match player layouts now use usable
  content geometry; lifecycle WindowManager features, off-centre/zero-width hinges, RTL and
  large-text fallback have pure regressions. Long lists stay lazy. Player video identity is stable,
  and large-screen/multi-window Back cannot depend on an ignored orientation request. Latest source
  also ports the pinned icon and revalidates Home hero/Sports Day Watch taps. The first856-test full
  build passed851/five optional skips/zero failures and all lint/packaging gates;858 follow-up also
  passed853/five skips, all gates and artifact checks. Final861 companion-state isolation gates
  passed856/five skips/no failures, all lint/packaging/signature/alignment/icon-pixel checks in8m34s.
  After host disk pressure the owner explicitly deferred emulator QA; no emulator/app data was
  touched. Actual resize/player/Guide checks remain unverified. See ADAPTIVE_PHONE_PARITY_PROGRESS.md.
  This is not physical-foldable, full-parity, production or never-lag certification.

- Latest830 checkpoint passed825 tests/five optional skips/zero failures/errors, all zero-error lint
  gates and debug/minified-phone/TV packaging (14m29s), with v2 signature/16-KiB alignment. Actual
  minified two-run offline cold testing at150% retained the real Sportsnet One schedule without
  Refresh/network, fixing the partial-bootstrap guide wipe. Original roster/selection, fonts,
  networks and display settings restored; app/emulator stopped. Both playlists and own QA draft
  remain saved. Exact hashes/evidence are in VALIDATION.md and GUIDE_COLD_CACHE_PROGRESS.md.
  This is development/unlocked, not production/dist/full-parity or never-lag certification.

- Cross-screen fantasy803 passed798 tests/five optional skips/no failures, all zero-error lint gates
  and debug/minified-phone/TV builds (6m57s), then ran against the user's actual test provider.
  Its own playlist XMLTV populated2,203 channels with listings after importing21,974 provider channels.
  The actual Rangers–Senators listing matched native Adam Fox, not the unrelated rostered teammate;
  explicit Watch channel decoded the then-current Kings–Devils broadcast with native companion UI.
  This is not individual participation/points, synchronization, never-lag or device certification.
  Original roster/playlists were preserved; a separate test provider and own QA draft remain saved.
  Runtime findings led to807 compact Home/full-row switch/leading Guide badge/time-status/roster-first
  refinements.807 full gates and actual minified large-text checks passed, but real Restart-to-video
  exposed a timed event-name stream wrongly labelled EPG-confirmed and showing another game.
 814 now requires actual listing evidence for automatic Watch, prioritizes real EPG over names,
  deduplicates evidence and prevents TV from substituting a different preferred mirror.814 unit
  gates passed809/five optional skips; full6m34s build and minified source-policy checks passed.
  Actual regular Sportsnet One restart resumed/advanced decoded hockey; no sync/never-lag guarantee.
  Cold relaunch repeatedly lost programme data despite manual refresh restoring2,219 guide channels.
 825 now serializes cache/bootstrap publication, guards cancelled parse commits, makes explicit
  guide refresh bypass both cache layers, preserves complete downloads with disclosed fallbacks and
  surfaces offline-save failures. Its eleven new file/mock-HTTP regressions and full825 build passed.
  The real large-text contrast check passed, but offline cold still lost the listing. Read-only debug
  diagnostics identified a two-schedule cache compacted against the first four cached channels before
  the21,937-channel provider's analysis completed.830 now gates refresh on all cached inputs and exact
  analysed channel identities; five regressions and full830 gates/repeated minified offline checks
  pass. See PLAYLIST_EPG_CONFIRMATION_PROGRESS.md and
  GUIDE_COLD_CACHE_PROGRESS.md. Adaptive/new-icon and imported ESPN/Sleeper joins remain open.
  See NATIVE_FANTASY_CONTEXT_PROGRESS.md. Full parity is not complete; no push or dist release.

- Latest778 native fantasy pool checkpoint passed773 tests/five optional skips/no failures/errors,
  zero-error all-module debug lint, release-only lint and phone debug/minified/TV builds (8m58s).
  It adds source-safe native NHL/NBA/MLB pools for new drafts while preserving every old ESPN roster/
  pick ID; bounded partial/retry/retained-time handling, conflict rejection, native roles/custom F,
  profile/headshot/eligibility UI, filters,30-row pagination and backup identity/privacy regressions.
  Actual minified NHL tests loaded805 players, combined ANA/L filters, separated native and ESPN
  profiles, paged78 goalies at normal/150%, retained offline3:33 time, explicitly refreshed3:38,
  persisted an own-QA native pick/F cold offline and automatically reloaded3:41 on reconnection
  without Refresh/section/font action (804 available after pick). Original QA roster/selection restored;
  one separately labelled QA league retained, settings restored/emulator exited0. Screenshots checked,
  targeted logs empty; no actual media/physical/large-playlist/other-sport pool certification. See
  NATIVE_FANTASY_POOL_PROGRESS.md and VALIDATION.md. Full parity remains open; no push/dist release.

- Earlier752 native fantasy fixture checkpoint passed747 tests/five optional skips/no failures,
  all zero-error debug lint, release-only lint and debug/minified-phone/TV builds (5m13s). It adds league-scoped exact team
  aliases, real eight-day roster fixture/opponent context, distinct doubleheaders, Game Centre/shared
  playlist-EPG watch controls, known-started lineup locks and on-demand player-pool loading. Immediate
  EPG indexing now runs off-main; lazy guide projection/index reuse, corrected-event/URL/header
  cache identity, final/invalidation guards and the identical-guide StateFlow regression are tested.
  Real minified QA opened the existing unchanged Anaheim roster entry's live Philadelphia fixture,
  native NHL Game Centre and two future fixtures. Back/150%/original offline2:32 schedule time and
  automatic reconnect2:35 passed. The existing test playlist remained explicitly unconfirmed; no
  actual stream-play/large-playlist or individual-player scoring success is claimed. Screenshots
  inspected, targeted app/player logs empty, settings restored, app stopped/emulator exited0. See
  NATIVE_FANTASY_FIXTURES_PROGRESS.md and VALIDATION.md for hashes/boundaries. Full parity remains open.

- Earlier737 source passed732 unit cases/five optional skips/no failures, all zero-error debug lint,
  release-only lint and phone debug/minified/TV packaging in5m4s. It fixes the two real734
  runtime findings: La Liga now enters shared incident-aware overview/timeline/filters, and failed
  podcast refreshes retain verified publisher/artwork ahead of thin bundled duplicates. Blank team
  names cannot match every feed; All sports is distinct from the For-you library mode. Real minified
  checks now verify cards/goals in chronological overview, Full timeline and62′/69′/84′ Goals filtering,
  real Ottawa rich cards, offline exact artwork/publisher/original1:16PM retention and explicit online
  recovery1:17PM. Screenshots inspected, targeted app/player logs empty, settings restored/emulator
  exited0. Exact development artifact hashes are in VALIDATION.md. The preceding734 runtime verified actual narrative
  commentary/Jump-to-latest, real Ottawa recommendations/RSS, Back/150%/A-to-Z and offline original
  search-time retention/reconnect. Settings restored and emulator exited before rebuilding.

- Earlier734 source passed729 tests/five optional skips, all zero-error debug lint, release-only lint
  and phone debug/minified/TV packaging (4m43s). iOS narrative-only commentary/Jump-to-latest,
  history-safe pending entries and explicit FotMob minute/added-time/boundary ordering are added
  without inventing halves. The bundled154-team podcast registry is now used for country-scoped public
  discovery, relevance ranking, deduplication, retained fallbacks, league/favorite topics, Refresh and
  A-to-Z sorting. Actual minified runtime checks are in progress. See VALIDATION.md and
  PODCAST_GITHUB_PORT_PROGRESS.md. No production/dist release, commit or push; full parity remains open.

- Earlier716 checkpoint:711 pass/five optional skips/no failures; all three zero-error debug lint tasks,
  release-only lint and debug/minified-phone/TV builds passed in4m4s. Native La Liga is registered end to
  end: official calendar/clubs/rosters/Opta profiles, exact FotMob enrichment, owning-score authority,
  independent source times/failures, bounded atomic cold snapshots, real clock strings, officials,
  named formations/ratings, shot detail, pressure and published tables. Future-null pressure slots are
  not fake zeros, and supplemental errors cannot throttle a healthy La Liga score poll. The exact
  development APK and hashes are in VALIDATION.md, not the older dist1.7.0 delivery.
  Real minified runtime covered Racing/Espanyol live1–1→2–1/final,76′→98′ clocks, measured pressure,
  official table/assignments, actual commentary, rich cold-offline original12:11 score time, automatic
  FT then official-source recovery without Retry, and wide150% named dual formations. Settings were
  restored and emulator fully exited; no targeted app/player error appeared. Earlier native club/
  roster/Opta/current-season-profile and Rayo/Elche checks remain documented. Full parity is NOT complete:
  shot-map projection, remaining soccer presentation nuances, broader source/correction/accessibility/
  device QA and the rest of the ledger below remain open. No commit, push or production/store release.

### Earlier integration checkpoints (historical)

- Newer712-source refinement passes707 unit cases/five optional skips; full build is in progress.
  This adds recorded actual live-clock handling, exact current promoted-club aliases and independent
  official-catalogue referee/VAR assignments. The preceding705 source passed all regression/lint/
  minified-phone/TV checks. Real701 minified checks established native clubs,
  roster/Opta profile/current-season stats, normal Calendar→Rayo/Elche0–3 game102345, real goals/assists,
  reported formation/ratings, shots/xG and rich cold-offline recovery with original score time.
  That session also exposed HTTP disk-cache revalidation, first-reconnect final retry and visible
  formation-name issues; these are fixed and built in705, not certified by the701 runtime. New712
  minified/runtime verification is in progress, and emulator settings were restored/stopped. See VALIDATION.md.

- 2026-10-24 La Liga integration is now registered in source: shared official/FotMob clients,
  independently resilient detail coordinator, source-evidence score authority, bounded atomic rich
  snapshots, native cold links, provider-safe roster/Opta profiles, published league table, native tabs,
  original per-resource/source times, reported-coordinate formation/rating presentation, typed shots
  and attacking pressure. No third-party EPG source was introduced. All701 unit tests completed:
  696 pass/five optional skips/no failures/errors. All three zero-error debug lint checks, release-only
  lint, debug/minified-phone and TV assembly passed in6m25s. Exact hashes are in VALIDATION.md; older
  APK hashes do not contain this route. Real Android checks are in progress. Shots currently have a
  detailed accessible list, not a guessed pitch map. Coordinate projection, real La Liga discovery/
  game/profile/offline/font/wide checks and broader follow aliases remain open. See LALIGA_SOURCE_AUDIT.md.

The entries below retain earlier artifact/history boundaries; newer source above supersedes their
statements that La Liga is unregistered.

- New 674-test checkpoint: 669 pass/five optional skips/no failures. MLS announced-but-unpublished
  games now have a typed upcoming state and separate bounded thin schedule snapshots; live/final/published
  overview failures stay errors. All-module lint/minified-phone/TV checks passed in 4m23s with zero lint
  errors; real upcoming-game/cold-offline/reconnect/published-table checks passed. A subsequent shared
  tab-strip fix also passed the full build and real minified150%-font/manual-swipe/wide-window checks;
  settings were restored and emulator stopped. See VALIDATION.md for exact artifacts and limits.
  La Liga official discovery/squad/profile, exact scoped FotMob reconciliation/mapping cache, bounded
  gzip client and source-traced detail mappers are tested but NOT registered in the app. Detail-service
  authority/corrections/rich cache/UI/profile routing and actual Android La Liga checks remain open.
  A newer683-test checkpoint (678 pass/five optional skips) adds independently timestamped La Liga
  score authority/final/outage/correction/reschedule rules. All debug lint checks passed. Development
  phone/TV packaging completed after stopping a resource-stalled full build and explicitly deferring
  release-only lint. The minified APK is byte-identical to the runtime-verified tab-fix artifact.
  The policy is not registered in an app game service and does not establish in-app La Liga parity.

- EPL recovered checkpoint: all 592 tests/lint/minified-phone/TV build checks passed; real minified
  event/player/substitution/lineup/offline checks completed. Newer 596-test debug source fixes immediate
  reconnect for finished games and independent sections without resetting HTTP/provider cooldowns;
  a 10-second offline-to-online emulator test recovered automatically. MLS native client/provider,
  validated detailed resources, namespaced bounded cold cache, profile pagination and shared soccer UI
  are now being verified. See SOCCER_GITHUB_PORT_PROGRESS.md and MLS_SOURCE_AUDIT.md for artifact boundaries.

- Native NBA/WNBA global discovery, qualified IDs, dynamically announced teams, typed roster/actual-season player
  profiles and reported-rank standings are registered alongside shared detail clients. WNBA live routing never uses
  Stats. Direct known-game lookup, independent score/box/play freshness, bounded atomic cold snapshots, source polling,
  twelve-stat adaptive starter/bench/DNP cards and imported iOS crests are regression-tested. The 526-test debug pass
  verified both native team directories, a real WNBA 71–97 final, 150% cards, 138-shot chart, 513-play cache, cold offline
  box/quarters/original times and reconnect without Retry. Stats-backed roster/profile availability, broader overview
  presentation and live/minified/device checks remain open. See BASKETBALL_GLOBAL_PROGRESS.md and VALIDATION.md.
- Exact-identity feed merges now advance live games to final and accept corrected scores without letting richer old
  metadata override new results. Cross-provider dedup requires a unique same-opponents/time counterpart in both
  directions; distinct/ambiguous doubleheaders and racing sessions remain distinct. Missing final totals never become
  the last live score. Decompressed HTTP body limits and policy-isolated caching bound oversized native responses.
- Current unversioned continuation: native NHL global scores/weekly schedules/teams/standings/rosters/profiles now
  route through provider-qualified identities. Saved ESPN follows resolve by league-scoped team identity; native IDs
  never fall through to ESPN player/game endpoints. SVG team crests have a registered Coil decoder. Public NHL
  score/schedule/standings/roster probes returned HTTP 403 on this network; this is not a live-provider success claim.
  The subsequent minified app pass on its normal network path loaded native NHL teams, future Ottawa fixtures,
  roster/profile and Atlantic standings. Those checks, not the standalone probe, establish that session's coverage.
- NHL details now have box-derived skater/goalie leaders, curated key comparisons, full grouped expandable player
  statistics and native in-app profile links. Landing, box and PBP freshness are accepted independently: lagging plays
  cannot veto a newer overview/final result. Corrections replace full event sequences, truncated ones are retained,
  and missing landing annotations retain prior verified goal video/totals. Bounded atomic public-payload disk snapshots
  preserve per-section fetch times and are explicitly saved data until refreshed. Cold/runtime coverage is ongoing.
- Added iOS Stats information architecture (sport-specific sections, followed-team shortcuts, adaptive action tiles,
  cached/retryable team directory, format guidance and actual native F1/PGA routes). Full standings now display
  sport-specific columns, available conference/division views, followed-team highlighting and original provider order.
- Source-traced roster/profile refinement: roster-first pages, grouped pinned headers, separate game lists, typed
  optional biography/injury data, enriched provider-safe player headers, adaptive fact/headline cards and readable full
  stat rows. Profile/roster caches retain last good data on failure. Frozen standings identity columns and cold-link
  recovery from saved NHL metadata are implemented; updated runtime/font-scale/offline checks are in progress.
  The revised debug pass verified roster-first/native profiles, readable 150% text, frozen-team column alignment and
  real cold offline saved-game/box recovery with original timestamps. Recreation revealed launch-intent replay;
  the subsequent per-page state/intent fix is being verified, not assumed complete from compilation.
  That public-config debug pass now verifies page/section retention across text-scale recreation, roster scroll
  restoration on Back, prominent saved-data provenance on a cold offline game, and clean application/player logs.
- Android Play Billing 9.1 client, offers, pending/suspended handling, restore queries, foreground rechecks, bounded
  entitlement validity and Premium/access UI are implemented. Receipt tokens stay in memory, never in backup/logs.
  Default builds explicitly unlock features and cannot charge anyone. Paid mode requires real product IDs and an
  owner-controlled HTTPS verification/acknowledgement service; no service, account, product or price was fabricated.
  The server contract and actual licensed purchase flow remain open, not only production configuration.
- Native MLB global scores, bounded month-chunk schedules, teams, active-league standings, typed rosters and
  season player profiles now use `mlb:` identities. Legacy ESPN follows use league-scoped aliases; native IDs never
  fall through to ESPN on failure. Cold game links use the known native game feed, not a team/time guess.
  Independent status/linescore/plays/box recovery, per-section accepted times and bounded atomic public-data snapshots
  now preserve good sections when the combined feed fails. Old play sequences cannot veto a newer final score;
  mismatched native teams/boxes are rejected. Saved timestamps include the date outside today's local day.
  Source-traced batting leaders, inning-grouped pinned play history, deferred new at-bats and in-app native player
  links are implemented. R/H/E remains pinned, full pitch/action/runner/contact context survives mapping, the box uses
  adaptive player cards, and probability retains actual at-bat indices. Independent bounded media/stat caches preserve
  original times across cold failures and distinguish authoritative empty responses from unavailable data.
  The minified 432-test build verified native global teams/roster/standings provenance, readable 150% box cards,
  real R/H/E/contact/runner details and cold offline highlight/probability recovery. Live doubleheaders/corrections,
  broader lifecycle/accessibility and physical-device checks are not covered by this short session.
- Shared data requests now count active waiters: cancelling one screen cannot cancel another consumer, but cancelling
  the last waiter closes the abandoned HTTP call, including during body consumption. Local-socket regression tests
  cover surviving consumers, cancellation/retry, timeout, body reads and header-isolated cache identity. This newer
  network change is under verification and not in the last minified APK.
- Native NFL global provider now covers scores, announced-week schedules, teams/history, actual division standings,
  typed roster/player information and cursor-paginated injuries through the existing anonymous client. Native IDs,
  legacy ESPN routing, scoped follow aliases, compact season caches and bounded name reuse are regression-tested.
  No-store bearer requests avoid HTTP disk metadata retention. Debug runtime verified native teams/roster/profile/
  standings and exposed alphabetical standings order, now corrected using reported ranks. New source ports bounded
  cold-game snapshots, independent rich/score recovery and original times, source polling/backoff/reconnection,
  category-based wrapping box cards, pinned quarters and lazy provenance-labelled injury rows. Every phone game-cache
  mutation is bounded, including native/extras responses. The 468-test minified build verifies native game/profile/box,
  150% cards/pinned quarters, cold offline plays/original times, automatic reconnect and native rank/injury provenance.
  Newer source adds cached-first display, seeded historical calendars, interruption/pregame/delayed-state nuances,
  source-traced Game Centre/F1 gates (default remains unlocked), final-result actions and capability-scoped transport
  recovery. Native HTTP/rate limits are not reset on reconnection. Newer runtime and broader live/gate/device checks
  remain open (see NFL_GLOBAL_PROGRESS.md).

- Native MLB feed, counts/bases/matchup, complete at-bats/pitches, box score and stale-update reducer translated.
- Native NHL metadata, plays, goal strength/assists/replay, box score, team stats and anti-regression translated.
- Native NFL anonymous web-client authentication, week identity, drives, deleted/corrected plays, GSIS-derived
  player/team statistics and field geometry translated. Tokens are memory-only; public web-client configuration
  is distinct from personal accounts and the private PodcastIndex secret in the iOS source.
- NBA/WNBA native box/PBP, on-court/bonus/timeouts, player identity and shared reducer translated. NBA stats-host
  PBP fallback is separate; WNBA does not use it. Blocked hosts back off; no bypass/proxy/IP rotation.
- EPL native team/time identity, reported formation rows, commentary deduplication/cursor and retained lineups translated.
- EPL 1.4: native goal/card/substitution buckets, corrected structured event identity, lineup/batch player names,
  Opta statistic mapping, explicit event/stat provenance and independent resource failure/cooldown handling.
  Published/provisional native standings load separately on demand, with reported points/deductions and season labels.
  Public Chelsea/Everton game 2444803 responses confirmed the resource shapes and the 20-club 2024/25 table.
- New EPL continuation: native global discovery/team/table/squad/player routing, qualified `epl:` identity, bounded opaque
  pagination, strict London dates, native calendar links, in-app lineup players and stoppage-time formatting. All 566
  current regression tests ran (561 pass/five optional skips); all lint/minified/TV checks passed. Direct public
  HTTP shape checks succeeded; app runtime checks and nested-profile/bio refinements remain open. See SOCCER_NATIVE_PROGRESS.md.
- Added sport-specific phone visualizations, pitch details, lineup/bench lists, commentary, shootouts and matchup news.
- Full provider body cache is now bounded by characters as well as number of entries.
- Added iOS MLB/NFL feed fixtures and explicitly constructed NBA mapping fixtures to **unit tests only**.
- F1: SignalR Core framing/handshake/subscribe, affinity negotiation, raw-DEFLATE telemetry, recursive sparse deltas,
  stale-topic rejection, fixed-capacity telemetry/trails, session identity checks, reconnection, archive and official
  classification fallback, disk snapshots, calendar/standings, timing/driver/track/strategy/control/radio/weather views.
  The real Australian GP 2025 classification opened correctly in the emulator. Recorded iOS timing fixtures pass
  mapping/compression/reducer tests. Continuous live timing still needs an actual live-session device test.
- PGA: original GraphQL documents, centralized public frontend configuration, throttling/coalescing/backoff, bounded
  gzip decoder, event discovery/season calendar, leaderboard/following, scorecards, on-demand round shots, schematic
  traces, course/hole detail, tee times, weather, coverage metadata and FedExCup. Real current tournament, leaderboard,
  player scorecard and shots loaded in the minified emulator app; full tournament/tab coverage is still outstanding.
  Missing coordinates are never manufactured.
- News: native article reader, text/appearance/spacing, progress restore, paywall fallback, offline copies and source
  mute/story hiding; podcast catalog, public search/RSS, subscriptions, queue/order/history, progress, DownloadManager,
  service-owned Media3 audio/video, lock-screen/headset controls, Android Auto browser and voice search.
  A real Fantasy Focus episode played and advanced from 12s to 82s while backgrounded; system pause worked with no
  AndroidRuntime/decoder error. This does not certify physical Android Auto or every feed/codec.
- Native fantasy: local personal/simulated leagues, custom roster/scoring/category configuration, snake/CPU draft,
  atomic add/drop/replace, slot/capacity/game-start validation and transaction history. No invented live scoring feed.
  Added read-only Sleeper NFL discovery/matchups/rosters/standings and a disk-streamed large player directory.
  Native NHL personal-league creation, real roster-pool loading and one saved draft pick passed emulator checks.
- Fixed the finite scoreboard flow's erroneous `awaitClose`, which held MatchFeed's load lock indefinitely.
- Android Cast sender, route picker/session controls/default receiver handoff; unsupported provider-specific headers
  are explained, not silently omitted. Hardware receiver verification is outstanding.
- Home-screen score widget with sanitized deep links, bounded refresh and saved-data timestamps; custom channel
  collections in Channels/Guide, group membership/order controls; global search includes news and podcasts.
  Widget pin/game link and custom group creation/reorder/membership/filtering passed release emulator checks.
- Followed-team notification planning/settings: seven-day start reminders, manual-reminder ownership, live/close
  dedup ledger, spoiler protection and morning briefings. Delivery/reboot/device QA remains outstanding.
- User chose **manual cloud backup file**, not a new account backend. Added Android document-provider export/import,
  restore preview/validation, whitelisted preferences/library/fantasy and sanitized channel/history references.
  Playlist logins, IPTV URLs, account sessions, reminder queue and downloaded content are excluded. This is Android-
  specific backup/restore, explicitly not real-time or iCloud-compatible sync; privacy tests are in the phone suite.
  Local document-provider export, restore preview/cancellation/confirmation passed release emulator checks; an actual
  signed-in cloud provider still needs verification. The final versioned file restored the test fantasy league/group,
  preserved draft progress after a cold restart and rejected an unidentified file without changes. Version/schema and
  fantasy references are validated; merge overflow is rejected.
- Runtime EPL match/formation pitch loaded from the real provider; corrected soccer home-first presentation and raw
  status formatting, absent event-score placeholders, dark Cast icon contrast and duplicate deep-link activities.
- Player 1.3: Xtream archive flags/days and per-stream/global M3U catch-up metadata survive cache reloads. Isolated
  resolver translates UTC Xtream paths and iOS template tokens, removes display offsets and validates retention.
  Past-programme sheets offer replay only for the exact capable source. Replay has an explicit return-to-live action,
  never auto-fails over to a live broadcast, never saves an archive URL into history and ends without a reconnect loop.
- Actual Media3 seekability/window duration drive the DVR/replay timeline, including clamped seeking and moving-window
  scrub anchoring; XMLTV duration is not presented as available media. Archive recovery/profile changes retain position.
  Guide keeps up to seven days of provider-supplied archive listings only for declared archive streams; other channels
  retain the six-hour lookback. An unknown retention value is not a guarantee of provider archive availability.
- Ordered player quick-action editor, normalization/defaults and cloud backup of action order; portrait pull-down
  minimization/swipe-up rotation, landscape brightness/volume, pinch fit/crop and source-chip swiping. Explicit buttons
  remain available, touch exploration disables custom video gestures, and controls stay visible during a scrub/drag.
  Replay seek/restart/completion, DVR seek/Go Live, a 40-second DVR pause and retained action order passed minified
  release checks. Portrait swipe-down/up and action editing also passed debug emulator checks.
  Pinch/source-chip/assistive-technology/physical-device coverage remains outstanding.
- MLB native highlights and win probability load independently of the combined feed, with per-surface TTL/cooldown,
  HTTPS MP4 validation, correction-by-at-bat and retained data on refresh. The chart names the actual source. Added an
  in-app finite highlight viewer that pauses on background entry rather than treating a clip as IPTV or a podcast.
  Public native endpoints returned 29 highlights and 61 probability points for game 744834. The real native chart and
  a highlight rendered in the minified app; background pause/return/close retained paused state. Per-section Retry
  respects provider cooldowns. Finished games no longer show stale live inning/base context.
- Player 1.4: persistent normalized audio-language preference and Automatic reset, source-generation track identities,
  caption-language switching/same-language manual selection, physical-pixel Original mode and legacy aspect-choice
  compatibility. Manual pause before the first frame no longer provokes startup recovery. Language is part of the
  sanitized cloud file; provider track indices are not saved. Runtime checks are recorded in VALIDATION.md.
- NHL 1.5: event-specific player roles and bench/drawn/served penalty attribution, rich native goal/assist/season totals,
  scorer-relative/empty-net strength, deduplicated per-team shootout rounds, extra-period labels and native landing
  summary fallback. Independent surface timeouts/cooldowns retain valid sections. Full chronological scoring summary,
  rich event cards, player/replay/rink details, event filters and deferred new-play display while reading history.
  Recorded iOS NHL fixtures cover the actual 320-event Minnesota/Buffalo game; edge cases are explicitly constructed.
  The minified release displayed that real game's scoring/penalty details, normalized rink location and 135-attempt
  shot map; no app/player error was logged in that short UI session. Live insertion/shootout/device QA remains open.
- NFL 1.6: native season/stage/week calendar, announced playoff labels/byes, thin weekly schedules and explicit
  provider identity on native calendar game pages; native UUIDs are guarded from ESPN endpoints. Detailed drive/play
  cards, participant names, GSIS-resolved missed kicks/pass/turnover types, first-down/penalty filters, deferred new
  events, reported quarter scores and cleared final-state field context. Team/profile/global-feed routing is still open.
  The final minified release loaded current/historical calendars, playoff choices, native cold links, real quarter
  values, turnover drives and resolved player names. Local result dates and initial quarter-table visibility were fixed
  and rechecked. This short emulator pass does not certify live updates, notification delivery or physical devices.
- Home/Following 1.7: team-first Following scope distinct from discovery, live/up-next hero, local-date full schedule,
  context-aware news and standings, original row ranks/followed-team highlight and correct full-table destinations.
  Bounded coalesced snapshot caches retain good data on failure and expose checked time/retry; follow changes queue
  behind an older load instead of losing their reload. Fast month schedules don't wait for the whole favourite season.
  Home gains the opposing-crest single-game Sports Day with separate reminder/info/confirmed-watch actions and
  spoiler-safe winner labels; favourite channels use saved order. See HOME_FOLLOWING_PARITY_1_7.md for boundaries.
  Final release checks passed for real NHL headlines/standings, team scoping, Montreal's single-game card/reminder
  permission/schedule/cancellation/info route, cold-start saved follows and full-schedule sport filtering. No upcoming
  Ottawa fixtures were returned by the global feed; native team/schedule coverage is not claimed complete. Live/final
  card states, actual reminder delivery and physical-device stress remain unverified.
- Regression checkpoint: 572 tests, 567 pass, five optional probes skipped, no failures/errors, plus phone debug assembly and bounded real native EPL discovery/roster/profile/game/formation/table checks. Earlier 566-test source passed all three debug lint tasks, minified phone and TV debug assembly; latest profile/biography/header refinements need a new minified pass. Calendar and 553-test chart source also passed bounded real-feed checks. Prior final package
  and remaining QA details are recorded in VALIDATION.md; passing tests do not establish complete parity.

## Still to close and verify

- Broader EPL/MLS/La Liga live/correction and native/legacy follow-alias verification; La Liga shot
  projection/presentation contract.716 already exercised real official discovery/profiles and live/final,
  cold-offline, reconnect, large-text and wide formations; do not restart those completed checks as absent.
  The pinned iOS catalog lists UCL but registers no native UCL provider; preserve/verify Android's existing
  ESPN UCL route rather than inventing a nonexistent iOS native implementation.
- Native NHL/MLB/NFL/NBA/WNBA/EPL/MLS disk caches/global/profile/calendar routing are implemented;
  remaining work is nuanced parity audit and broader source-outage/correction/device verification, not
  the wholesale absence of these providers. Exact provider/runtime boundaries are in the league progress notes.
- MLB full at-bat UX/context metrics and broader content/device verification; broader NFL live drive/play verification;
  broader live NHL scoring/penalty correction cases and runtime verification of the new leaders/key-stat/box presentation.
- F1 full live-session/network-transition/radio/track runtime verification; PGA complete tab/round/course/player QA.
- Native fantasy pools/eligibility/draft/fixture joins are implemented and have bounded runtime checks.
  Remaining: source-ranking/previous-season nuances, complete matchup/standings/scoring surfaces,
  imported ESPN/Sleeper cross-screen joins/tickers/toasts and actual account/device QA.
- Podcast registry/public country-scoped resolver/recommendations/listening code is implemented.
  Remaining: wider team/provider coverage and download/video/queue/car/lifecycle QA; highlights and picks.
- Adaptive source is now implemented for primary phone surfaces; final expanded/compact/large-text,
  state-retention and playback verification is in progress. Root app/navigation/secondary-screen
  hinge/cutout audit, physical fold postures and adaptive/monochrome icon layers remain open.
- Home curated-event/highlights/motion nuances; Following historical playlist-coverage classification; Stats visual/behaviour QA; remaining
  player complete gesture/remote/accessibility/device verification, real multilingual/forced-caption/codec changes,
  physical Original-mode sizing and native PiP/TV geometry.
  Catch-up provider dialects, expiry/recovery/network transitions and physical DVR/decoder behavior need broader tests.
  Hidden-game behavior and group large-playlist/accessibility validation remain.
- Calendar synchronization beyond individual insert intents; automatic notification delivery/reboot tests.
- Cast receiver/header compatibility, widgets/Auto hardware verification; Play verification/acknowledgement backend,
  complete premium-gate reconciliation and licensed purchase/restore QA plus production billing configuration.
- Remote cloud-provider/offline/conflict testing; any iOS-compatible cloud format requires coordinated
  changes to the iOS app, beyond the selected Android backup-file implementation.
- Final visual, behavior, performance, lifecycle, accessibility, large-playlist and physical-device verification.

## External setup must not be fabricated

Play Console product IDs/test accounts, production signing and an owner-configured purchase-verification service are
needed for a verified Android subscription flow. The owner confirmed no physical Android device is available yet.
Casting/Android Auto and physical-device decoder performance require compatible hardware. Any account-backed cloud
or paid provider integration needs explicit configuration; secrets must not be copied from iOS into a public APK.
An HTTP 403 or absent native field is an unavailable state, never permission to invent data or bypass access controls.
