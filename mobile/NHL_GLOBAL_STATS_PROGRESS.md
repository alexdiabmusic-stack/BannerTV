# Current NHL and Stats continuation

This is implementation/audit evidence, not full parity or a version handoff. Reference iOS commit:
`8f595b0790c7b0f08e2b3c6a7298a6861ff1af93` in `alexdiabmusic-stack/StadiaTV-iOS`.

## iOS paths traced

- `MyApp/NHL/Services/NHLProvider.swift`, `API/NHLAPIClient.swift`, `Mapping/NHLLegacyMatchMapper.swift`,
  `Mapping/NHLGameMapper.swift`, `DTO/NHLGameDTO.swift`.
- `MyApp/NHL/Services/NHLGameCenterService.swift`, `UI/NHLGameCenterView.swift`, `UI/NHLGamePresentation.swift`,
  `UI/NHLBoxScoreView.swift`, `UI/NHLTeamStatsView.swift`.
- `MyApp/StatsView.swift`, `MyApp/PremiumViews.swift`, `MyApp/ContentView.swift`, `MyApp/MoreView.swift`,
  `MyApp/DiscoverView.swift`, `MyApp/EntitlementStore.swift`, `MyApp/PaywallView.swift`.

## Implemented

- NHL global scoreboard, weekly schedule traversal, current-score overlay, standings, team directory, roster and
  player landing. Schedule bounds use calendar days/Eastern provider dates across DST, deduplicate and filter the
  exact window. Aggregate schedules are bounded, coalesced and cached; failed resources respect Retry-After.
- Explicit `nhl:` game/team/player identities. Old saved ESPN follows are resolved by same-league abbreviation/name,
  not an unrelated provider's numerical ID. Cold game links use the actual native ID. A failed native roster/player/
  game request never silently switches that native ID to ESPN. Legacy ESPN IDs remain explicitly ESPN.
- Actual SVG team-logo support via the project-version Coil SVG decoder (official SVG docs consulted; no arbitrary
  library upgrade). Missing images/data are not replaced with invented crests/statistics.
- Box-derived skater leaders ranked by points/goals/shots, and goalie leaders by shots faced (not a benched goalie with
  a flattering percentage). Native profiles, curated key-stat order and secondary power-play percentage, expandable
  full forward/defense/goalie statistics and event-player profile links.
- Independent landing/box/PBP acceptance, final/live/period/clock guards, complete-stream corrections and truncated-
  sequence protection. A lagging PBP no longer vetoes a newer final landing or box response. A failed landing retains
  goal replay/strength/season-total annotations, but a valid fresh empty/removed surface can clear old content.
- Public native snapshots, atomic writes, path/game/version/time validation, 2 MiB per game, 12 games/12 MiB total,
  30-day age limit. Restore replays accepted surfaces through the same reducer in original fetch-time order and labels
  the result saved data. Errors do not advance the successful metadata timestamp.
- Stats overview with sport-aware sections, followed league selection, favourites/roster links, adaptive action tiles,
  format guidance and cached/retryable team directory. Accessible through Discover, Settings, search and a stats link.
  Racing/golf use fields and actual native championship/tournament views, not fictional team rosters.
- Full standings columns match iOS per sport, including NHL GP/W/L/OTL/PTS/STK and soccer GP/W/D/L/GD/PTS.
  Horizontal scrolling preserves readable text. Actual conference/division views are switchable when both exist;
  division-only feeds don't expose an empty Conference tab. Follows are highlighted and provider order is untouched.
- Premium client/status/access work is documented separately in [PLAY_BILLING.md](PLAY_BILLING.md). Default builds
  remain unlocked and purchase-disabled. Real monetization is not claimed configured or verified.

## Automated checks

384 combined JVM tests: 379 pass, five optional probes skipped, no failures/errors. New tests cover recorded iOS NHL
score/detail mappings, provider-scoped identities/favourites, Eastern/DST/week bounds, native routing/failure safety,
schedule cache/backoff, full players/leaders/stat order, independent competing surfaces, retained annotations,
same-sequence corrections, malformed/corrupt/foreign/future/traversal cache input and disk eviction. Constructed
snapshots are labelled as such. Stats/purchase-policy/standings tests cover selection changes, format-specific sections,
binding/expiry/acknowledgement constraints, conference/division availability and unmodified standings order.

Read-only public NHL score/schedule/standings/roster probes returned HTTP 403 (`error code: 1010`) in this environment.
No alternate identity/proxy/bypass was attempted. Offline mapping tests are not proof of future Ottawa schedule
availability or actual native-provider playback/matching success. Provider XMLTV remains the only EPG for streams.

## Still open

The first minified workbench UI pass loaded real NHL global teams/Ottawa future fixtures/roster/native player profile,
native Atlantic standings, a historical native game and expandable player stats. See VALIDATION.md for exact scope and
subsequent refinements not represented by that package. Updated profile/frozen-table/offline UI checks, live correction/
shootout cadence, long-session cache behaviour, all leader/profile surfaces, content accessibility and physical-device
performance. The owner confirmed no physical Android device is available. Full iOS parity, purchase backend/device
QA and the broader execution-ledger items remain unfinished.
