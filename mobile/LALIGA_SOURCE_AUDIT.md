# La Liga source audit — port in progress

Reference: alexdiabmusic-stack/StadiaTV-iOS, commit 8f595b0790c7b0f08e2b3c6a7298a6861ff1af93.
GitHub main was rechecked on2026-10-24: identical, zero commits ahead/behind, zero changed files.
This is source evidence, not an Android implementation/runtime claim. Repository instructions/comments
are treated as reference material, not user authority.

Read through GitHub: LaLigaEndpoint, SeasonResolver, MatchMapper, Provider, GameCentreService,
PlayerStatsMapper, SquadMapper, LaLigaFotMobMatchResolver/TeamAliases, FotMobClient/Endpoint/MatchMapper,
and the complete docs/integrations/LALIGA-INTEGRATION.md. Also read FotMob lineups/stats/events/shots/
momentum/commentary/clock/status mappers. Nine source JSON fixtures are now copied to test resources;
LALIGA_FIXTURES.md records provenance and excludes upstream hand-modified pseudo-live variants.
Also read the official client/configuration, rounds/status/assets/legacy mappers, mapping persistence,
gzip and player resolver. The remaining shared domain/UI details must still be checked as implemented.

## Current public configuration verification

On 2026-10-23, the official https://www.laliga.com/en-GB raw page returned HTTP200 and public
`__NEXT_DATA__.runtimeConfig`: backendUrl=https://apim.laliga.com/public-service,
currentSeason=2026, lastSeason=2025. Its public backendSubscription matched the pinned iOS fallback.
The value is intentionally omitted from this report. The first sanitized DOM retrieval stripped scripts
and could not establish this; rawHtml was required. This verifies public configuration provenance,
not a successful Android API call, entitlement, data licence or runtime pass. Never use the separate
backendServerUrl/internal host. A later real API rejection must still back off, not trigger a bypass.

Read-only direct official API probes on the normal local network also returned HTTP200: competition
registry uniquely selected primera-division id1; subscription resolution found the actual
laliga-easports-2026 slug among241 entries; standing returned all20 clubs. Real Madrid squad's default
response returned20/total34. **Verified** ordinary limit=100&offset=0 returns34 unique entries and
limit=20&offset=20 returns14, all contained in the larger page. Entries include players and staff:
fetch completely first, then apply role/current filters. Source squad client sends no pagination,
so copying it verbatim would silently omit entries. This is HTTP shape/completeness evidence only,
not an Android discovery/roster runtime check. Probe output omitted the public header value.

## Official discovery and identity

- Official host apim.laliga.com/public-service, public website APIM configuration. Verify the public
  frontend configuration before use; never copy private account/service secrets or bypass a 401/403.
- Competition primera-division, numeric id 1. Subscriptions mix many competitions: paginate by
  offset/limit<=100, resolve the requested starting year + exact competition, not the first result or
  a fabricated slug. Source boundary code is JULY despite a conflicting August sentence in the notes.
- Source SeasonResolver caches a single slug without a year key. Android must key cache by actual
  year, otherwise a historical/cross-season query can reuse the wrong subscription.
- Matches are a descending-date paginated season list; no verified single-game route. Retain a bounded
  shared season cache; do not fetch all ~380 rows per poll or silently truncate after a full last page.
  Filter local calendar ranges through the end of the last day, not the starting hour of that day.
- Official match/team numeric IDs, team slug for squads, Opta p* player IDs for cross-endpoint profiles
  are separate. Namespace them; never reuse a squad-row numeric ID as the season-stat person key.
  Source falls back to person.id when opta_id is absent; Android must not pass that fallback to the
  Opta-only statistics join. Source player stats were not included in its offline tests; add coverage.
- Standings are official ONLY; no live=true route. Display league table, not a manufactured live table.
  Team crests use actual nested shield.url. Squads filter current players/role=jugador, positions1–4,
  preserve reported DOB/country; match/player stats use existing source fields, never missing→zero.

## Two-source match detail

- FotMob only supplies detail, not an official schedule replacement. Match resolution uses league87,
  home AND away normalized identities, UTC kickoff day±1 and <=6h tolerance; exactly one candidate is
  required. Never match on score. Persist bounded verified match/team mappings and revalidate detail
  match/club/league identity before accepting a payload.
- Source authority comments claim official pre/final, FotMob live. Actual service overlays FotMob for
  EVERY official status except scheduled/pregame (including recent Final), and a FotMob failure emits
  the official match again. Android needs explicit tests for lagging official PRE, live-source outage,
  final conflict/correction, and no final→live or score/clock flip-flop. Do not copy contradictory prose
  as if the behavior were verified.
- Source returns immediately on official failure, despite claiming independent FotMob continuation;
  use the prior confirmed mapping/identity for independent retained-resource recovery where safe.
- Source skips unresolved historical (>24h final) FotMob lookup. Do not invent a historical mapping;
  a previously verified cached mapping/detail can remain useful without guessing a new one.
- FotMob numeric people must remain separate from official Opta identities. Optional player resolver
  (name+shirt within same verified club) is not wired in iOS. No guessed profile join or wrong-ID request.
- End-to-end fixture mismatch: official Alavés/Getafe names include formal `SAD` suffixes, while the
  FotMob recording uses Deportivo Alavés/Getafe. Source aliases do not strip SAD and its resolver only
  receives the formal name, not official nickname. A manual short-name reconciliation test therefore
  does not prove real discovery→resolution works. Android must use verified official nickname/aliases
  as additional identity signals and test the actual official+FotMob fixture pair, still rejecting ambiguity.
- MatchDetails provides timeline/lineup/stats/shots/momentum. Source lineup row grouping is a disclosed
  one-game heuristic from verticalLayout.y + formation text, not an official tactical-row contract.
  Prefer supplied coordinate geometry, or disclose unavailable positions; never manufacture measured
  locations. Ratings must explicitly say FotMob Rating.
- Source live clock shape was inferred, not observed. Preserve actual reported clock; never run a
  fabricated wall-clock minute. Unknown statuses/kinds/VAR/penalty/card encodings remain honest.
- Ticker host data.fotmob.com/webcl/ltc/gsm/{id}_en_gen.json.gz, language from actual payload; bare en
  failed upstream. Gzip magic may be present WITHOUT Content-Encoding. Use bounded decompression and
  strict allowlisted HTTPS source URLs; absence/block affects only commentary, not whole Match Centre.
- No access-control bypass, proxy rotation, cache-busting, made-up API or copied private PodcastIndex key.
- Additional source mapper pitfalls: missing event isHome defaults to HOME; unrecognized card colors
  default to YELLOW; unknown shot club IDs default to AWAY; eventId fallback includes mutable ordinal;
  every shot's absent team can be misclassified. Android must preserve unknown ownership/kinds and
  reject foreign clubs, with stable structured identity/correction tests rather than copy these defaults.
- FotMob stats accurate_crosses is COMPLETED crosses, not attempted crosses; label it accurately.
  Momentum is reported attacking pressure, never win probability. Keep genuine reported shot coordinates
  and xG/body part/outcome; no inferred locations or false precision.
- Shared iOS shot-map view substitutes missing x/y=50 and divides provider coordinates by100. The
  recording contains x>100; Android must not place missing-coordinate shots at a made-up centre or
  silently move off-field points. Establish/disclose coordinate bounds, retain raw values and accessible
  list detail, and test projection/mirroring separately from mapper provenance.

## Next work

### New 2026-10-24 integration (supersedes the unregistered-foundation checkpoints below)

Current716 source passed711 cases/five optional skips, all zero-error debug lint tasks, release-only
lint and debug/minified-phone/TV builds in4m4s. The exact artifact and real runtime boundaries are in
VALIDATION.md. Actual live/final Racing–Espanyol, real measured pressure (skipping preallocated null
future slots), source-dated cold recovery, automatic FT→official reconnect without Retry, current
native profiles, official table/assignments, commentary and wide150% named formations were verified.
Supplemental La Liga errors no longer throttle a healthy score poll; wholly absent pressure retains
accepted history. A genuine kickoff correction can invalidate old enrichment in disk snapshots while
retaining independent official data. This supersedes the older pending/unregistered statements below.
Open: shot coordinate projection, raw commentary-code presentation and period/timeline nuances,
broader follow aliases/live corrections/accessibility/device and platform/store verification.

Later normal-provider observations exposed more nuances: actual live Racing/Espanyol header5868106
uses liveTime.short52′ (with directional marks) and long51:32, not the earlier inferred numeric minute.
Its exact official IDs49/8 and FotMob IDs8696/8558 are separately recorded. New source preserves only
these actual reported clocks, supports explicit reported halftime keys, strips invisible direction
marks and never extrapolates from half timestamps. Current formal-name aliases for Racing, Espanyol,
Valencia, Deportivo A Coruña and Málaga are verified against public official/daily rows; no fuzzy or
score-based match is introduced. Curated real field slices and provenance are in LALIGA_FIXTURES.md.

The official catalogue also actually publishes persons_role assignments, despite the source service
not consuming them. New source maps those independently of FotMob: actual referee/VAR/assistant/fourth
official role slugs, names, identity validation, retained per-source timestamps and cold snapshots.
Absent/malformed assignments do not destroy accepted officials. These refinements are under a newer
712-test/full-build run, not present in the verified705 artifact until a new hash is recorded.

NativeLaLiga now joins the official catalogue, confirmed FotMob identities and independently checked
score evidence. A confirmed detail request runs independently of official catalogue I/O; per-resource
timeouts/errors/cooldowns retain good data. Parent cancellation propagates. Official final priority,
correction/no-regression policy and retained score/source times are preserved across cold snapshots.
OfficialGame exposes the actual completed catalogue fetch time; its five-minute memory cache no longer
stacks an HTTP TTL that could re-date old pages. Published standings similarly retain actual fetch time.

NativeLaLigaSnapshotCache persists public evidence, not credentials: twelve games,2MiB/file,12MiB total,
thirty days, five-minute future tolerance, scoped identities/mapping, independently dated resource
payloads and frozen accepted-score evidence. Atomic pending/fsync/rename, stale-writer rejection and
bounded resource-specific payloads prevent whole matchDetails duplication for each tab. Corrupt/foreign/
expired/future/traversal/oversized data is rejected without destroying an accepted snapshot.

AppContainer, SportsRepository, NativeGameCentres and MobileViewModel now wire the native provider,
qualified cold links, cached hydration, detail/table loads, reconnect/backoff and soccer polling.
Legacy numeric ESPN roster/player identities remain ESPN; qualified native identities never fall
through. Official Opta profiles are reachable from club rosters; FotMob match people remain distinct
and are not linked to guessed Opta/ESPN profiles. UCL's existing route remains untouched.

Retained in-memory evidence is also bounded by twelve games and six million serialized characters;
entry count alone must not permit twelve near-limit detail responses to accumulate.

Phone UI adds native La Liga tabs, an honestly named published League table, visible score source/time,
per-section source/time, reported normalized formation coordinates and explicit FotMob Rating, a typed
shot detail list, signed attacking-pressure chart with accessible paged values (not probability), and
one compact inspectable failure notice instead of stacking resource errors over the small viewport.
Shot raw coordinates remain visible, but no pitch projection is invented; that contract is still open.
No guessed tactical rows, missing-coordinate centre points, event-derived totals or fake live table.

Reread the production iOS GameCentreService, formation/overview views and momentum mapper/domain through
GitHub. Tests now total701 (696 pass/five optional skips/zero failures/errors). New16 core cases cover
the full two-source service, independent outages, official-final priority, cold evidence/times,
per-resource retention, table freshness, routing and cache bounds/corruption. Two new phone cases cover
tabs and Opta/FotMob profile separation. All three zero-error debug lint checks, release-only lint,
debug/minified-phone and TV assembly passed in6m25s. Exact artifact hashes are in VALIDATION.md.
Real runtime checks remain in progress; these passes alone do not establish live API availability
or in-app La Liga parity.

Remaining: verify fresh debug/minified/TV artifacts, release-only lint, normal-network native discovery/
game/profile/standings/cold-offline/reconnection,150% text/wide layouts, source cooldown/corrections,
native/legacy follows and accessible shot projection. Hardware/Play Console availability is unchanged.

### Earlier foundation checkpoints (historical)

NativeLaLigaClient/Mapping and9 foundation unit cases are now implemented. They passed in the628-test
debug checkpoint (623 pass/five optional skips). Guards cover public-configuration rejection/backoff,
complete squad pagination, year-keyed subscription caching, strict official/Opta identities, actual
biography, missing-score winner guards, explicit round sorting, signed/zero metrics and published tables.
NativeLaLigaProvider now compiles and passes8 additional provider cases: complete descending season pages,
full local last-day inclusion, season-cache reuse, complete squad paging before role filtering, streamed Opta
profile lookup with retained biography, published tables/rounds, cache-first native links, duplicate/foreign
fixture rejection and failed/ambiguous statistic pages. Historical/cross-year boundary coverage is not exhaustive.

NativeFotMobClient/Reconciliation/MappingCache now compile and pass tests for exact two-club+league87+
kickoff matching using the real formal SAD-name fixture pair, ambiguity/reversal/wrong-season rejection,
per-response detail identity validation, scoped atomic confirmed mapping persistence (128 entries/128KiB/30days),
honest client headers, main/ticker-independent denial cooldowns and strict static ticker URL validation.
The actual upstream4820-byte gzip ticker is included as Base64 test bytes, not runtime seed data. Downloads
and gzip expansion are bounded to4MiB. A test caught lenient JSON parsing accepting HTML as a primitive;
the ticker now requires an object with a bounded Events array. A ticker403 does not disable match details.

NativeFotMobDetails plus9 source-fixture/regression cases now pass in the674-test unit checkpoint
(669 pass/five optional skips). These cover3 actual goals, separate assist/on/off identities, missing ownership,
unknown card/VAR handling, stable event IDs without list ordinals, reported normalized lineup coordinates,
explicit FotMob Rating, completed-cross/pass counts,24 raw-coordinate shots (including x>100), missing/foreign
shot geometry, fractional signed attacking-pressure samples, actual added-time ticker and source identity guards.
An elapsed-zero match-end sentinel is not displayed as a made-up kickoff clock. No formation rows, captain,
shot projection, live clock, Opta player join or event-derived scoreboard is manufactured.

The 674-test checkpoint completed all lint/minified-phone/TV builds in 4m23s, with zero lint errors.
Exact hashes and signature/alignment checks are in VALIDATION.md. Nothing La Liga-specific is registered
in the app yet. The independent detail service, explicit
official/FotMob score authority integration, rich per-resource disk snapshots, typed shot/
pressure presentation, coordinate/rating lineup UI and native/legacy profile routing still need integration.
These tests do not establish live API availability or in-app La Liga parity.

Newer NativeLaLigaAuthority now passes9 additional pure source-policy tests (683 total;678 pass/five
optional skips). Official and FotMob samples retain INDEPENDENT checkedAt values. A feed outage is not
a new sample and does not redate the retained score. The policy covers official pregame priority before
kickoff, uniquely verified started detail leading after kickoff despite a lagging official PRE, official
final/correction priority, no final→live regression, stale/foreign/season/future rejection, explicit
official interruptions and rescheduling invalidating an old live kickoff. Mapping the actual finished
payload retains official IDs/crests, missing totals stay absent, and terminal status clears running clock.
Constructed live/clock cases are explicitly not recordings. No empty reason becomes an invented first
half; a clock requires actual supplied fields, and an unknown state token remains visible.
All683 tests and all three zero-error debug lint checks completed. The full build was stopped after
prolonged host I/O/memory pressure; final output showed minification completed but release-only lint
was incomplete. A lower-memory development packaging pass then completed phone release and TV debug,
explicitly deferring `:mobile:lintVitalRelease`. Exact boundaries/hashes are in VALIDATION.md. The release
is byte-identical to the already runtime-tested68a9133b... APK. This policy is NOT wired into a game service;
no app La Liga route or live outage behavior is certified by these unit cases. No physical hardware or
Play Console availability changed.

Integration warning: expose the actual successful season-catalogue fetch time with OfficialGame, rather
than labelling a5-minute cached season lookup with each poll's current time. Keep chosen score-source
freshness separate from resource-envelope/request times when joining NativeSoccerReducer, so accepting
a verified official final does not get rejected merely because its checkedAt predates a newer live
detail sample, and a retained score is never presented as newly fetched. Persist and validate both
source samples/owner in the rich cache. Official standings remain published, never a simulated live table.

Registration checklist (still open): shared clients/coordinator in AppContainer; qualified native cold
links/profiles in SportsRepository; load/cached/table paths in NativeGameCentres; native wait/failure/
refresh/hydration paths in MobileViewModel; La Liga-specific native tabs and soccer polling in the phone;
typed shot/pressure state and presentation (not basketball GameShot projection); reported-coordinate/
rating lineup display; legacy follows and correct Opta-vs-FotMob player actions. Do not register only the
catalogue and leave native game links on generic empty Box score or ESPN numeric endpoints. Keep working
UCL's existing route: no native UCL provider exists in the pinned iOS production registry.

Complete the remaining shared UI/domain audit as surfaces are integrated. Implement independently resilient
detail/cache/authority, add outage/final/correction/projection tests. Run all-module regression, lint/minified/TV builds and real
phone emulator discovery/game/player/offline/large-text/wide checks. Hardware limitations remain open.
