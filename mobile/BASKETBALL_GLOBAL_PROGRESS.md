# Native basketball continuation — not a parity completion claim

Reference: user's `StadiaTV-iOS` commit `8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`.
Read directly: NBA/WNBA providers, API clients/endpoints, schedule/scoreboard/roster/player/columnar DTOs,
game/status/box/play mappers, shared Game Center service/reducer/cache/view model, Game Center/box UI,
presentation builders and existing logo resolver/assets.

## Implemented in current source

- Shared NBA/WNBA native clients, registered global scores/schedules/teams/standings/rosters/profiles and details.
  NBA split-year/Eastern-day routing, current CDN schedule and eligible NBA HTTP fallback to Stats are separate
  from WNBA calendar seasons. WNBA live score/schedule/box/plays never depend on Stats. Historical WNBA dates
  are explicitly unavailable rather than fabricated empty slates. A stale "today" file reconciles against the
  announced schedule before claiming a current slate.
- Bounded requests/concurrency, decompressed body limits, per-host 403/429/transport cooldowns, Retry-After,
  no immediate retry loops. No proxy, host rotation or access-control bypass was introduced. Stats discovery
  requests have their own TTLs; live polling does not refresh standings/player career data.
- League-qualified game/team/player identities, exact known-game box lookup, team identity validation and guarded
  calendar links. ESPN legacy identities remain ESPN; native failures never reinterpret a native number as ESPN.
  Rosters reject a different supplied team/season and profiles select the latest actual reported season, preferring
  the reported traded-player total over a single stint. Standings display reported conference ranks, not inferred
  tiebreaks from win percentages/array positions. Missing ranks/stats remain missing.
- Team directories derive dynamically from announced schedule teams, not a hardcoded historical franchise list.
  Existing iOS 30 NBA SVGs and 15 WNBA PNGs are bundled with aliases and source inventory. All imported PNG Git blob
  hashes match the source. An unknown expansion team can still appear without a fabricated crest.
- Independent score/box/play recovery and timestamps, 120-second full-box refresh floor, selected-tab rich refresh,
  retained player/team stats on score-only updates and stable-ID play corrections/shot projection. Per-team box
  shrink checks cannot be masked by adding players on the other team. Final acceptance retains internal period
  identity while clearing live clock/context and recomputing winner flags.
- Bounded two-game raw memory cache and atomic public-only disk recovery (12 games / 24 MiB total / 4 MiB per
  game / 30 days), league/path/identity/time checks, partial-window play union and original successful section
  times. Cached native links resolve before network work. No tokens, playlist URLs or constructed test games enter
  these caches via a production test shortcut.
- Native in-app player cards, twelve box columns (including FG/3PT/FT fractions), whole minutes per current iOS UI,
  separate starter/bench/DNP sections and adaptive wrapping stat grids. Selected teams do not silently switch to
  the other team if their stats are missing. Live on-court indicators, source/time/error/saved notices and selected-
  tab Retry are implemented; a uniformly blocked host notice does not offer a futile immediate retry button.
- Source polling intervals: 12s live / 8s final-two-minute regulation-or-OT / 20s period end / 60s halftime,
  30s interruption, 60–300s scheduled, capped exponential failure backoff, stop after final reconciliation.
  Foreground network restoration, cancellation, native response completion and bounded phone snapshot storage are
  shared with the earlier NFL recovery work.
- Newer source also ports combined reported leader cards (with native profile links), curated FG%/3PT%/rebound/
  assist/turnover/paint comparisons, real date/venue/series/broadcast metadata, source chronological six-event
  lead-change/tie summaries and their explicit headlines. No opening 0–0 score is invented. ISO hours/minutes-only
  durations and sub-minute tenths share one parser with late-game polling/reducer clock checks. Live header timeout/
  bonus/broadcast context persists across tabs and final/live records no longer masquerade as pregame metrics.
- Basketball play history uses pinned quarter/OT headers, compact/medium/high native event priorities, official
  sentences with field-only fallback descriptions, assist/block/steal context and a deferred reading window. New
  identities wait while browsing history; corrections still update existing rows. Jump to latest/live and period/
  scoring filters remain accessible without continuous scroll movement. Quarter/OT labels have no single-OT ceiling.
- League Schedule is now a bounded date-range browser with previous/next seven days, native date picker, local-date
  result groups, original checked/saved state and historical-game calendar seeding. DST and invalid date cases have
  pure tests. A failed check is not shown as a successful empty result. NFL retains its source-specific week calendar.
- Failed roster/profile states no longer duplicate empty-result/Retry UI, and native player source labels remain
  visible even when the statistical endpoint fails. Native summary shots survive the old ESPN enrichment path.
  Invalid JSON HTTP-200 bodies are evicted from the exact text cache policy rather than pinned for a profile TTL;
  decoding failures are not automatically retried. The 545-test minified pass verified plays/clock/leader/stat/profile-
  failure/offline presentation; see VALIDATION.md for exact coverage and the two calendar defects it exposed.

## Validation boundaries

Constructed fixtures copied from the iOS test targets are clearly labelled in
`core/src/test/resources/parity/BASKETBALL_FIXTURES.md`. They prove routing/mapping/reducer/cache behavior only,
not real endpoint availability, current player rosters or live scores. No fixture is shipped as live production data.
Unit tests cover native/legacy identity separation, malformed/short tables, schedule reuse, WNBA host isolation,
cooldowns, season/day boundaries, expansion discovery, score-vs-box freshness, partial play correction and cold
cache provenance. See `VALIDATION.md` for exact build hashes/runtime coverage; do not infer it from compilation.

Standalone network probes in this continuation: public NBA today scoreboard returned HTTP 403 with the app headers;
the WNBA known-game box URL returned HTTP 200 **HTML**, not a decoded JSON success. A separate browser data path
returned a WNBA scoreboard whose reported date was older than the current date. These observations motivate strict
shape/date/error handling and are not a native game-centre success claim. Access blocks are respected.

The 526-test debug build passed all three suites/lint tasks and assembly, followed by a bounded real emulator pass:
native NBA/WNBA team discovery/crests, actual WNBA GSV–ATL final/quarters/12-stat cards, readable 150% text, 138 reported
shots, 513 raw cached plays and cold offline recovery retaining original times. Reconnection cleared the saved label.
Stats-backed roster/profile data was unavailable and is not claimed verified. Exact artifact/evidence is in VALIDATION.md.

The minified calendar pass found the real static feed's `gameTimeUTC: 1900-01-01` sentinel taking precedence over its
real `gameDateTimeUTC`, and calendar selection resetting after section switches. Newer source fixes both; a recorded
public field subset and malformed-date regression tests supplement the earlier constructed fixtures. The 547-test debug
APK then verified the real Oct 21 result and range retention through Teams/Schedule switches (exact hash in VALIDATION.md).
The chart now retains actual 2/3-point values and reported-or-coordinate-derived distance, uses the iOS half-court
transform/restricted semicircle/corner arc and marker sizes, explicitly counts beyond-half/other off-viewport attempts,
and keeps all attempts in accessible lazy rows (no silent 50-row cutoff). The 553-test debug APK verified the real
58/138 total and Q4 13/37 selection. No real heave occurred; off-canvas counting is covered by pure tests only.
Newer source strengthens court-line contrast after screenshot review; minified verification is still pending.

Still open: broader current-season endpoint/schema checks, new calendar/chart refinement and verification,
full game-info/timeline/live insertion interactions,
continuous live corrections/late-game transitions, wider tablet/TalkBack/device/performance coverage. Physical Android,
Cast and Auto hardware are unavailable. Full parity, backend purchase verification and broader soccer/platform work
remain on the active execution ledger.
