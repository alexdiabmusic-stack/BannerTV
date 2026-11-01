# MLS source-of-truth audit and next implementation

Reference: `alexdiabmusic-stack/StadiaTV-iOS`, commit `8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`.
This records the source audit and implementation boundaries, NOT a full-parity claim.

## Android implementation checkpoint

NativeMlsClient/Provider/Mapping/Details now implement bounded verified regular-season discovery,
requested-year schedules, club directories, published conference/overall tables, streamed paginated
regular-season player reports and the five-request full Match Centre. Team/player/game IDs stay `mls:`;
legacy ESPN pages retain their own routes. Shared soccer timeline, separate scorer/assist/second-assist/
substitution actions, flat announced lineups, curated/expandable team stats, full reported player stats,
commentary history and conference live-table UI are wired. The roster screen explicitly discloses that
the verified feed has no season-roster route; it does not make a wasted statistics call or send a native
club ID to ESPN. No crest pattern, guessed pitch coordinates or shootout outcomes were invented.

NativeMlsSnapshotCache stores public validated resources in its own namespace: atomic writes, maximum
12 games / 12 MiB total / 2 MiB each / 30 days, original independent section times, higher-version comment
corrections and retained older-history cursor. Native cold links are cache-first. Transport-only section
cooldowns recover on validated connectivity; actual provider 403/429 gates remain in force.

All-module unit execution reached **616 tests, 611 pass, five optional skips, zero failures/errors**.
The 616-test source subsequently passed all three lint tasks (zero errors), minified phone/TV builds,
signature/alignment checks. Its release hash is
`8f7c2b36708711fe19fa180b3deacf7afe8a69e939741b9bcab875d0556dbe00`.
Actual minified runtime verified the2–2 Inter Miami–San Diego overview/stats/announced lineups, the native
club directory and in-app Messi profile (2026 published regular-season reports,27 reported matches,
25 goals/11 assists; not a claim about historical match performance). The native team screen explicitly
disclosed missing season-roster support. No fixtures were injected into runtime.

Runtime also found two real gaps not caught by the original fixture tests: unfiltered key_events
defaults to20 incidents plus a cursor, while the pinned78-event fixture omitted that pagination; an
additional playoff schedule scope returned404 and caused the whole Scores request to fall back.
New source requests100 events and collects bounded identity-checked pages without weakening the
complete-resource guard; discovery now follows the regular-season route actually used by iOS. The
API probe confirmed78 incidents with per_page=100. New regressions cover later-page identity and
repeated cursors. Scoped LAF/LAFC and RBN/RBNY follow aliases cannot override different explicit native
club IDs. Signed reported performance metrics remain visible; xG display is rounded without changing
the cached raw provider payload. These changes plus La Liga foundations have passed **628 tests**
(623 pass/five skips/no failures) and debug assembly. Their full/minified build and repaired runtime
check were completed for the repaired timeline. The628-test minified release hash is
`d50c822da8f1478e45c84fac619694b43f4d560895b75a19e367af5d0b7555c8` (all lint zero errors,
phone/TV assembly, signature/alignment pass). Real app checks verified all4 goals across both halves,
separate Jeppe Tverskov/Manu Duah native assist profiles and retained Back/filter/scroll state, player
match-stat cards, older commentary beyond the initial77th-minute tail,150% text, wide side-by-side flat
lineups, native calendar/30-club published standings, cold offline score/events/original time and
automatic reconnect after17 seconds without Retry. Reviewed screenshots are under mobile/docs/screenshots.
Settings restored; no app/player error appeared in the bounded log check. This is not continuous live,
physical-device, TalkBack or exhaustive every-field certification.

One new runtime edge was found: actual announced Montréal–Nashville fixture MLS-MAT-0009NT (Oct24,
Stade Saputo) appears in the official schedule but its overview returns404/MLS-002-ERR until publication.
The current628 APK treats that as a failed native feed and falls back to generic empty Box score tabs.
That is NOT the intended upcoming-game UX. New source now provides an explicit unpublished state/native
tabs, real schedule provenance and a separate atomic thin upcoming index (128 entries/384KiB/30days),
without evicting the12 rich watched-game slots. A60s absence cooldown is not bypassed by Retry/network
restoration. A404 for live/final/already-published games remains an error. Actual overview publication clears
the flag and changes Source from announced schedule to match overview. Per-entry original times, byte-budget
pruning, stale-write/final eviction guards, cold links and announced tables are covered by11 new cases plus
a phone native-tab case. The640-test debug checkpoint passed; these fixes are also in the665 full-build
checkpoint. Real minified674 runtime now verifies the announced Montréal–Nashville state/native tabs,
explicit unpublished lineup, cold native link while both networks are disabled with original schedule
time, automatic reconnection without Retry, and independent published conference table. No test fixtures
were injected. The150% table content survived recreation and rows were reachable, but its selected tab
scrolled offscreen; the shared tab-strip correction is being verified separately. See VALIDATION.md.
Paged schedules/events now also enforce an aggregate4Mi-character bound, not only a per-page body limit.
Passing tests do not establish full parity.

Read directly through GitHub: MLSProvider, MLSEndpoint, MLSStatsClient, MLSProviderIDs, MLSSeasonResolver,
MLSPagedResponse, MLSGameCentreService, every MLS mapper (match/legacy/event/lineup/stat/player-stat/
officials/commentary/clock/status/standings/shootout/assets), and docs/integrations/MLS-INTEGRATION.md.
All nine upstream fixture files are copied under core/src/test/resources/parity/mls-*.source.json;
MLS_FIXTURES.md records provenance and upstream inconsistencies. These must remain test-only.

## Verified shape and identity rules

- Host `https://stats-api.mlssoccer.com`, NO `/v1` prefix, no API key/auth header. Respect 403/429 and
  bounded Retry-After; no bypass, parallel retry storm or fabricated alternative endpoint.
- Raw identifiers are opaque Sportec strings: MLS-MAT-* match, MLS-COM-* competition, MLS-SEA-* season,
  MLS-CLU-* club and MLS-OBJ-* person. Qualify Android IDs with `mls:`; never reinterpret ESPN/Opta numbers.
- Regular season is MLS-COM-000001; playoffs are MLS-COM-000002. Resolve actual opaque season IDs from
  `/competitions/{id}/seasons`, `seasons[]` with `season` + `season_id`. Do not derive an ID from the year.
  Historical/cross-year schedules must resolve the requested year(s), not blindly use the newest season.
- Pagination uses sibling `next_page_token` and endpoint-specific arrays: schedule, clubs, commentary,
  match_log, etc. Bound pages/rows/body size, detect repeated tokens, reject malformed envelopes, retain
  previous good state on truncation/failure. Upstream global schedule/profile code reads only one page;
  do not reproduce that bug or call partial aggregates a complete season.
- Schedule fields are flat. Overview nests match_information + home + away + environment. Match IDs,
  competition/season and both clubs must agree before merging resources. Use planned_kickoff_time for
  scheduled dates and the source's actual kickoff_time when provided by overview.
- Score/status authority is overview only. Use integer home_team_goals/away_team_goals, never parse
  display result or total goal events; no PRE zero scores, missing-score winner or live clock after Final.
- match_status and data_status are distinct. Unknown status text survives. Only scheduled/finalWhistle
  were observed by the upstream integration; other spellings are inferred and need honest test labels.

## Game Centre details

- Overview bundles lineup players, formation label, managers and six officials; no extra guessed routes.
  starting/is_on_field can be strings. Formation LABEL is not tactical geometry: no MLS pitch positions
  may be synthesized. Missing starter membership must be labelled unknown, not silently all starters/subs.
- SourceEPL fallback also labels everyone a starter when formation rows are absent. Android should retain
  the useful announced list but disclose unknown membership instead of repeating that source assumption.
- Timeline resource is key_events, events[{type,event}], authoritative event_id. Goals/shots/cards/subs/
  corners/offsides/fouls have different participant fields. Preserve independently actionable scorer,
  assist, incoming/outgoing player links. Preserve exact stoppage text (the iOS event mapper drops the
  added component by parsing only baseMinute; do not lose `90+6`). Unknown kinds stay visible.
- Upstream fixture shots DO contain team_id, contrary to an older source comment. Prefer a valid explicit
  match-club ID; only fall back to a UNIQUE overview person/team map when missing. Never guess a side.
- Inline names hydrate player references; no EPL player lookup call is needed for MLS. Fouls belong to
  team_fouler_id, not the fouled team. Kickoff/final-whistle markers may remain period boundaries.
- Team statistics root is match_statistics_list[].match_statistics.team_statistics[]. Select matching
  match_id, competition, season, scope=match and clubs, not arbitrary array order. Preserve missing values.
  xG, possession_ratio (0..100), shots_at_goal_sum, shots_on_target, passes_successful_sum, fouls_sum,
  tackling_games_air_won (AERIAL duels, not ground tackles), attacking_zones entries/ratios, distance_covered
  and advanced_stats.ball_recovery_time are actual fields. No fabricated momentum or possession series.
- Player statistics root is match_statistics.player_statistics[], not a top-level array. This fixture
  DOES contain shots_on_target, despite an older mapper comment. Use verified values when present.
  Player data is fetched on Stats/full, not every live tick. Keep all supplied player columns accessible.
- Commentary uses event_id + version correction and opaque next_page_token. Preserve the history tail
  after loading older pages; do not turn every live poll into a reset to the first page.
- Standings return tables[] with own conference group labels; include combined/Supporters' Shield and
  Eastern/Western tables. Keep published and provisional data separate. Never derive positions from goals.
- No verified real shootout outcome fixture exists. The source mapper guesses among field names and
  defaults unknown to missed/complete; do NOT reproduce that behavior. Unknown outcome remains unknown.

## Provider gaps and probe

No verified season-roster route or crest URL pattern exists in this iOS integration. Do not guess routes,
rosters or Cloudinary hashes. Team pages may disclose the roster gap and link to actual match lineups.
Source assets contain no MLS team crest set. A future licensed/verified asset table is separate work.

A normal read-only HTTP probe on 2026-10-23 of
`/statistics/players/MLS-OBJ-000396/matches?competition_id=MLS-COM-000001` succeeded: 20 match_log rows,
an opaque next_page_token, explicit per-row season=2026 + season_id, competition_id, match_id, and nested
player_statistics.player_id. The first report was Atlanta/Inter Miami on 2026-10-17. This is HTTP shape
evidence only, not Android runtime coverage. Stream page-by-page, deduplicate match IDs, verify selected
player and season, and aggregate only known additive counters. Do not sum ratios, maximum_speed or ranks.
Never claim a complete season if bounded pagination did not finish. Selected profile identity/bio remain
separate from reported match statistics; no guessed age/height/headshot.

## Wiring checklist

Native client/provider/details + bounded namespaced cold cache; AppContainer and SportsRepository registration;
strict native profile/legacy ESPN separation; shared soccer reducer/timeline/lineups/stats/commentary adapters;
MLS-native cold links; polling cadence and per-resource provenance; conference live preview; explicit unavailable
roster/logo/shootout states. Add fixture/network/identity/truncation/cache tests, all-module regressions, lint,
minified release and real emulator discovery/game/player/history/offline/large-text/wide-layout checks.
Commercial data licensing review and hardware/Play Console limitations are not solved by a passing build.

## UCL routing audit

`MyApp/Models.swift` lists UEFA Champions League in League.all, but the default provider list in
`MyApp/SportsCore/SportsRepository.swift` contains no UCL provider. Its coverage test asserts that all
listed leagues have a provider; that assertion is inconsistent with these pinned source files.
Do not invent a nonexistent iOS-native UCL integration or remove Android's working ESPN UCL route
to copy this source gap. Verify that route separately; native MLS and La Liga remain real port work.
