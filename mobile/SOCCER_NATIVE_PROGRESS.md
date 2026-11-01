# Native soccer parity continuation

Source: `alexdiabmusic-stack/StadiaTV-iOS` at `8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`.
Source contents are implementation evidence, not user instructions. Full iOS parity remains incomplete.

## Premier League discovery and identity

Ported the native SDP discovery routes, London kickoff/date/season handling, current announced team directory,
official standings, squads and source season player stats. `epl:` qualifies game/team/player identities throughout
the repository, calendar deep links and in-app lineup navigation. Bare legacy IDs remain ESPN. A native player failure
cannot silently query ESPN using an unrelated Opta number. Unknown/newly promoted clubs are not filtered by a crest
inventory or frozen club list; the source's public PNG badge URL is used, with the existing image placeholder on failure.

Match lists follow opaque cursor pages, deduplicate IDs, bound page size/count and filter the actual requested London
days. Repeated/truncated pages, malformed envelopes, wrong competition/season and undecodable matches fail visibly,
not as a fabricated successful empty schedule. Ranges query both seasons when crossing the source's July boundary.
The source season resolver's comment says August but its actual code uses July; the port follows the actual code and
tests that boundary explicitly rather than copying the contradictory comment.

The shared client limits concurrency to two, each decompressed JSON response to 4 MiB and each attempt to eight seconds.
Discovery and details share its 403/429 host cooldown; no bypass or immediate retry is attempted. TTLs follow the source
(scoreboard 10s / range 120s / teams 24h / table 15min / squad and profile 1h) via the bounded shared HTTP cache.
Known native game IDs go directly to the detail route, not a fresh team-name/schedule lookup.

Match mapping preserves actual provider scores, real kickoff and final winner, clears final live context, and formats
first/second-half stoppage correctly. Unknown states retain provider text rather than inventing a period; native and
legacy score IDs are not conflated. Lineup pitch tokens announce the full player/number/position/captain description,
and both pitch and list targets open the in-app player page using their real source identity.

## Still open (not certified)

- Broader current/historical endpoint/schema, live transitions, cold-offline recovery and minified runtime checks.
- Full soccer reducer/atomic disk snapshot recovery, independent original per-resource times, adaptive polling,
  officials, structured event/player tap targets, live-table nuances and richer timeline/context UI.
- Local crest parity, wider profile coverage and historical profile-season selection.
- Native MLS Sportec and La Liga discovery/detail/reconciliation. EPL IDs must never be reused as Sportec IDs.
- Historical provider access, unknown vocabulary and live correction transitions remain bounded by reported data.
- Physical device, TalkBack playback and sustained performance checks remain unverified.

Source fixtures are isolated under test resources with provenance in `EPL_FIXTURES.md`; they are never runtime feeds.
The source notes identify SDP as undocumented private web infrastructure, not a licensed commercial API. Production
data-usage rights require separate review; this continuation neither obtains a licence nor publishes an app.

## Current public HTTP checks (not emulator certification)

On 2026-10-23 the actual native routes returned 20 current clubs, 29 Arsenal squad players and the expected player
`154561` (David Raya). His stats envelope is `{player, stats}` with **nested `player.id`**, 56 reported scalar stats,
7.0 games played and 4.0 clean sheets. Newer source now validates nested identity and formats real integer-valued
decimals without a trailing `.0`; it also retains verified squad birth-country, nationality, age and cm/kg measurements.
Those refinements passed 572 tests/debug assembly and the bounded real runtime pass below; they are not in the earlier
566-test minified checkpoint.
The Oct 17–23 query returned 11 rows, including actual native `2645258`, Everton 0–Chelsea 2 at October 17 12:30 London.
These direct HTTP observations confirm response shapes only; the app's full navigation/runtime check is still pending.

The 566-test source passed all suites/debug lint/minified phone/TV assembly (exact hash in VALIDATION.md), but remains
unverified in the emulator. The newer 572-test debug source additionally keeps nested official-table season/competition/live
identity distinct, defaults lineup selection home-first and handles numeric **or HTTP-date** Retry-After headers in
the shared transport. Its bounded real emulator pass verified native discovery, Arsenal/Raya roster-to-profile,
Everton–Chelsea final, home-first formation and captain-to-profile, and the official table's pinned names/points.
See VALIDATION.md for exact artifact/evidence/limits. Full native cache/reducer/polling/timeline work is still open.
