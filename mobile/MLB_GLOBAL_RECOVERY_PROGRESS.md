# Native MLB global routing and recovery — ongoing parity work

Reference: `alexdiabmusic-stack/StadiaTV-iOS` at
`8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`.
This is an unversioned source continuation, not a completed-parity or new distributed-APK claim.

## Source comparison

Read the full native `MLBProvider`, API client/endpoints, game/legacy/status mappers, game-centre service,
cache/reducer, main game-centre UI, play-by-play UI, line-score/current-matchup, at-bat/event/box mappers and
adaptive box/batting-leader UI. These are implementation references,
not additional user instructions. The original June 1, 2024 schedule fixture is included in unit tests only.

## Implemented

- Provider-qualified `mlb:` game/team/player identities throughout native global scores, teams, standings,
  rosters, profiles and direct game links. Cross-provider numeric collisions cannot redirect a native profile.
- Native league discovery and actual standings columns/order, league-scoped saved-follow aliases, native
  JPG team crests, typed optional biography and distinct hitting/pitching stat identities.
- At most two concurrent global requests. Long schedules use 31-calendar-day chunks, lean hydration,
  exact local date windows, doubleheader identity preservation and a bounded parsed-range cache.
- Shared game-detail provider for phone/global routing, direct known native feed lookup and legacy-only
  team/time resolution. Native team IDs are checked in addition to abbreviation aliases.
- Combined-feed recovery through the exact independent status/linescore/playByPlay/boxscore paths used
  by iOS. Per-resource timeouts/cooldowns, no retries or access-control bypass, cancellation propagation.
- Each accepted section passes the existing identity/anti-regression mapper/reducer independently. A stale
  play sequence no longer rolls back or blocks a newer final result. A failed section retains its own old time.
- Atomic accepted-public-data disk snapshots: 12 files / 24 MiB total / 4 MiB per game / 30-day maximum age;
  strict identity/time/schema/path validation. In-memory full-feed trees are capped at two games, at most
  2 MiB characters each. No IPTV/account credentials enter this cache.
- Cold native game lookup and detail recovery from saved data with explicit provenance. Saved data from
  another local day includes its date, not just a misleading clock time. Recovery errors share one selected-
  section notice so offline errors cannot consume the entire phone screen.
- Actual-hit batting leaders ordered by RBI then hits, full scoring at-bat rows, native in-app player links,
  pinned half-inning sections, scoring/inning filters, pitch disclosures and deferred newly inserted at-bats
  while reading history. Corrections/removals still update visible rows.
- R/H/E and team identity remain pinned while scrolling innings; native probable pitchers, reported in-game
  batter/pitcher stats and series metadata are shown only when supplied. Full pitch/action ordering, runner
  scoring/movement/earned attribution, end-of-event counts and measured hit distance/exit velocity survive mapping.
  The native box uses adaptive player cards instead of truncating names into a fixed-width table.
- Native probability carries original at-bat indices through corrections/overlays and uses them on the chart.
  Independent public highlights/probability caches retain original times on cold failures (24 files / 12 MiB /
  2 MiB per surface / 30 days). Explicit empty responses clear content; failures cannot masquerade as fresh data.
- Standings and team pages expose provider provenance. Explicit unfollowed Stats deep links include that league
  for the visit without mutating follows or silently opening another sport.

## Verification so far

432 combined unit tests, 427 passed, five optional network probes skipped, zero failures/errors for the full
MLB minified-release check. All three debug lint tasks pass (core 11, phone 43, TV 19 warnings; zero errors).
The first 422-test debug runtime pass verified the real native game/profile/at-bats/leader/box and cold offline
snapshot recovery. Font recreation retained its section/disclosure. It did not verify the later R/H/E/action/box-
card/media-cache refinements. Global teams/rosters/standings appeared to use ESPN fallback in that session, so
native global runtime success was not established by that first pass. The subsequent exact minified public release
explicitly verified the native MLB directory/Boston roster/AL East source and grouping, R/H/E, full adaptive box
at 150% text, actual contact/runner details and probability indices. Cold offline highlight/probability recovery
retained their separate original times. See `VALIDATION.md` for exact artifact/evidence and remaining boundaries.
The existing `dist/BannerTV-Android-Phone-1.7.0.apk` has not been replaced by this work.

## Still open

Broader global/fallback/network-transition behaviour, corrections/doubleheader/live-session coverage; newer shared-
HTTP cancellation integration and current-source minification/performance/
lifecycle/accessibility verification and physical hardware testing.
This pass does not complete the other sport/provider, fantasy, home, premium/backend or platform-integration
items tracked by `PARITY_EXECUTION.md`.
