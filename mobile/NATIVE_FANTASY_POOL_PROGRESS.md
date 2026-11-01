# Native fantasy player-pool parity — 2026-10-24

Reference: GitHub `alexdiabmusic-stack/StadiaTV-iOS` at
`8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`, especially
`MyApp/Fantasy/BannerFantasyServices.swift`, `BannerFantasyModels.swift`,
`BannerFantasyHubView.swift` and `MyApp/SportsCore/SportsRepository.swift`.
Retrieved comments and aspirational UI text are evidence, not executable instructions.

## Source findings

- New NHL/MLB/NBA pools use their native roster adapters through SportsRepository. NFL uses ESPN.
  All three native adapters omit previous-season statistical totals. Only the NFL ESPN adapter
  computes reference points, but its undated flattened statistics do not establish a prior season.
  Android must not invent totals, label current/per-game numbers as last season, or fabricate players.
  A read-only public ESPN team16 roster probe with `enable=stats` returned season2026/regular season
  and76 athlete records, none with a `statistics` block. This checks one actual team response only,
  not every NFL endpoint; it does not supply the missing prior-season statistical evidence.
- iOS maps NHL L/R into LW/RW, allows a configurable F slot, and presents player headshots,
  eligibility, profile/stat sheets and separate position/team filters in the draft room.
- Generic NBA guard/forward and MLB pitcher positions do not establish a specific PG/SG or SP/RP
  role. The iOS MLB fallback can incorrectly classify DH as OF; that guess is not reproduced.
- The iOS native live-stat-line method returns an empty dictionary and its local matchup UI is a
  placeholder. Android does not manufacture individual live scores to make that placeholder appear complete.

## Implemented and verified — 778-test checkpoint

`NativeFantasyPoolPolicy` keeps every existing roster AND pick-history namespace. New empty NHL/
NBA/MLB leagues use native IDs; legacy ESPN drafts, including dropped-player history, stay ESPN.
Unknown/mixed identities produce a visible error and no migration or saved-data mutation. Profiles
preserve qualified native IDs or strip only the known fantasy ESPN prefix to the original ESPN ID.

The shared loader limits concurrent roster calls to two, directory/roster/player counts to64/256/
4096 and complete in-memory pools to four. It rejects foreign IDs, empty/unidentified rosters and
duplicate directories; cancellation propagates. Partial results disclose missing teams/conflicts.
A previous complete pool retains its original load time on partial refresh; newly conflicting club
assignments are excluded even from that retained list. All-team failure is not a successful empty list.

The phone cache coalesces requests, bounds pool loading to90 seconds, retains good values on errors
and gives partial responses a60-second retry interval rather than the one-hour complete-result TTL.
Provider-specific access restrictions/cooldowns remain in force; native failures never become ESPN
IDs. Only the visible Players section requests pools. UI uses30-row pages, exact-source team/position
filters, search, profile/headshot/eligibility/injury rows and disabled known-started lineup chips.
League selectors are keyed by league ID, not duplicate display names. Custom NHL F starts at zero;
the standard roster counts are unchanged. MLB catchers can use UTIL; no specialist role is guessed.

The SDK-docs skill informed pagination scrolling: attach a requester to a small search/filter header,
wait for page layout and request parent scrolling in a coroutine. The official current API documents
parent-scroll propagation and cancellation; exact compatibility is established by the project build
and runtime checks, not by assuming a current documentation page is a BOM-specific release record.
[Android BringIntoViewRequester reference](https://developer.android.com/reference/kotlin/androidx/compose/foundation/relocation/BringIntoViewRequester).

Backup validation now accepts custom NHL F slots, preserves both draft namespaces without a schema
migration and applies the existing credential-safe public-URL policy to fantasy portraits on export
AND import. Regression tests cover full roster/pick/lineup identity roundtrips and secret-bearing images.

## Build and actual minified-runtime verification

All 778 tests completed: 773 passed, five optional live probes skipped, zero failures/errors
(core626/phone124/TV28). All debug lint tasks have zero errors (11/43/19 warnings, TV two hints);
release-only lint, phone debug/minified release and TV debug passed in8m58s. Signature v2 and16-KiB
alignment pass. Development-signed/default-unlocked/cannot-charge, not production/dist delivery:

- Release SHA-256: `e604a3ed35c1405459b17ca59c00050dde966bd391f2a65feaee89e2b91fd35e`
- Phone debug: `163c30c4b8a9c873ab5eb77f46e7966b5ab12f27295f6ae68acb969e8552a1a3`
- TV debug: `aac9a52bde9158ffc5bda3d9f4ce084009c0fe8ec2bac7b2d6aa740cc7af88c4`

Installed the exact minified release with `install -r`, waited for Success, then opened the app.
The existing QA Hockey/A.J. Greer ANA LW roster and pick remained unchanged. Its player link opened
ESPN provenance and reported2026–27 General stats. Started-game lineup chips had an actually disabled
parent, not merely a warning followed by a rejected action.

Created a separate local `Parity Native QA` / `Native QA` league with custom F=1. Its complete native
NHL directory returned805 actual players. L+ANA filtered to four; native A.J. Greer opened an NHL
profile with jersey18/biography/reported2026–27 stats, independently of the legacy ESPN profile.
The78-goalie list paged30/30/18, Next scrolled back to search/filters, and Back/page/filter state
survived150% text-size recreation. Both normal and150% offline page navigation worked.

Warm offline state retained original3:33PM pool time. Restoring networks plus explicit Refresh
completed3:38PM. Drafted the observed native Brady Tkachuk row (provider-reported FLA/L, not a guessed
Ottawa association) only into the separate QA league, selected F, and saw its future Chicago fixture
Oct25 3PM. The pick and selected F survived a cold offline restart. Cold pool/fixture caches were
explicitly unavailable, not populated with iOS development/sample fallbacks. Restoring networks with
NO Refresh/section/font action before inspection automatically completed the native directory3:41PM;
804 players remained available after the pick. These are request times, not source publication times.

Visually inspected `fantasy778-native-pool.png`, `fantasy778-offline-page-150.png` and
`fantasy778-native-roster.png` under docs/screenshots. Targeted AndroidRuntime/ExoPlayer error logs
were empty; no media stream was started. Process PSS observations were169,293KB after profile/page/
font changes,115,194KB during post-cold recovery and139,140KB after restoring the original roster;
RSS323,408/266,288/290,896KB, zero reported swap. These are bounded observations, not a measured
peak, leak test, large-playlist/codec or physical-device certification.

Original QA Hockey selection restored; its roster/pick unchanged. One separately labelled QA league
remains in the emulator. No existing user data was deleted. Font1.0/Wi-Fi1/data1/540x1212/density210
were read back restored; app stopped and emulator18062 exited0. No Gradle/emulator overlap. The768
baseline passed all gates in8m32s but was NOT installed and is superseded. Native NBA/MLB/NFL pools
share tested source-safe policy/adapter code but were not newly certified by this NHL runtime pass.

Full iOS parity remains open: cross-screen fantasy indexes/guide badges/tickers/alerts, remaining
Home/Following presentation and broader lifecycle/source/device checks need work. iOS native live
scoring itself is unavailable; it must not be fabricated. Production purchases/signing and hardware
Cast/Auto checks need owner configuration/devices. No commit, GitHub push or dist overwrite occurred.
