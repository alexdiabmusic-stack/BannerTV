# Native soccer source parity — implementation checkpoint

Full iOS parity remains incomplete. Verification below distinguishes completed artifacts from newer source.

## Current continuation — native MLS and La Liga foundations

Latest737 passed732 cases/five optional skips/no failures, all zero-error debug lint, release-only
lint and phone debug/minified/TV builds (5m4s). It closes the real734 phone routing omission: La Liga
now uses shared soccer incident-aware overview and timeline filters. Real minified cards/chronological
goals, Full timeline and62′/69′/84′ Goals filtering passed. Screenshot inspected; settings restored,
emulator exited0, targeted app/player logs empty. Release SHA-256:
`031cd3f3231ab9f6f594455eee21051ee8199bc72c27aa4ee9a26c74cccd7288`.
Unknown halves remain unknown; period labels/polish and shot projection still require work.

Earlier734 source adds iOS narrative-only commentary, Jump-to-latest and history-safe pending updates.
Provider minute/added-time ordering closes HT/FT after reported stoppage incidents without inventing
halves or missing times.729 tests/five optional skips, all zero-error debug lint, release-only lint and
phone debug/minified/TV builds passed. Actual new runtime checks are in progress; see VALIDATION.md.
Its release SHA-256 is `247e08c241ccf7321d18cabd0a1e0f38b0d0f168923a7b440e5f84191fce4cc9`.

Earlier716 source passed711 cases/five optional skips, all zero-error debug lint checks, release-only
lint and phone debug/minified/TV assembly (4m4s). La Liga is registered end to end, including independent
official/FotMob authority/cache, actual live clocks/current-club names, official assignments and typed
phone enrichment. Release SHA-256:
`add7991f78e8c717deb3806524d8a787a62376486c5319057b0df6c88c396a30`.
Real minified live/final Racing–Espanyol, measured pressure, cold offline original times, automatic
FT→official recovery without Retry, real commentary, official table/referee/VAR and wide150% named
formation checks passed. Settings restored, emulator exited, targeted app/player logs empty. Broader
soccer UI/shot projection/correction/device work and full parity remain open; see VALIDATION.md.

### Earlier foundation/history boundaries

Newer2026-10-24: La Liga is now registered with independently resilient official/FotMob detail,
source-evidence authority and rich cold snapshots, native profiles/links/table routing and typed phone
enrichment UI. All701 tests completed (696 pass/five optional skips), all debug lint tasks have zero
errors, and minified-phone/release-only-lint/TV builds passed in6m25s. Release SHA-256:
`ca23244859492b4f5ba08e6940818c6584a545fac2ae30120e925fdfdfed3f6f`.
Signature v2/alignment pass. Real Android La Liga checks are in progress, not yet a runtime success
claim. The shot list preserves raw coordinates; pitch projection is still open. See VALIDATION.md
and LALIGA_SOURCE_AUDIT.md. Earlier unregistered-foundation statements below are historical.

The674-test checkpoint passed all unit suites (669 pass/five optional skips), zero-error lint and
debug/minified-phone/TV assembly. Exact artifacts and bounded runtime coverage are in VALIDATION.md.
Real minified MLS now covers complete goal history/participant profiles/older commentary plus
announced-but-unpublished details, a separate thin cold schedule cache, offline native links and
independent published tables. Network restoration retains actual data times and needs no Retry.
The newer shared selected-tab/font-remeasure correction passed full build and real minified150%-text,
manual horizontal-swipe and wide-window checks. Exact new artifact hashes are in VALIDATION.md.

La Liga official discovery/squads/profiles, exact fixture reconciliation/confirmed identity persistence,
bounded gzip transport and detailed source mappers are tested but not registered in the application.
See LALIGA_SOURCE_AUDIT.md for precise remaining service/authority/cache/UI work. These foundations do
not establish in-app La Liga parity. No optional/provider denial was bypassed and no production/hardware
coverage is claimed. The dist1.7.0 package and GitHub are unchanged.

## Recovered verification checkpoint

Disk headroom recovered and all-module verification completed: **592 tests, 587 passed, five optional
skips, zero failures/errors**; all three debug lint tasks had zero errors; phone debug, minified phone
release and TV debug assembled successfully. Release SHA-256:
`502823e047d10d2ea173133e74d6e16b82584868a8831fbd2afef8dffdd22f78`.
Signature v2 and 16-KiB ZIP alignment verified. The dist 1.7.0 package was not replaced.

That release includes the canonical soccer reducer, independent resource times, bounded atomic EPL
cold snapshots, cache-first native links and home-first adaptive lineup presentation. Real emulator
checks covered Everton–Chelsea and Manchester City–Sunderland, separate scorer/assist/on/off native
profiles, captain links, filter/Back retention, 150% text, wide context/lineups and cold offline recovery
with original section timestamps. Actual screenshots are under docs/screenshots/epl-minified-*.png.
No AndroidRuntime/player error appeared in that bounded session. This is not continuous-live, physical
TalkBack, Cast/Auto or production decoder certification.

An immediate reconnect within the transport cooldown reproduced a finished-game recovery bug. Newer
source now clears **transport-only** game/section cooldowns on validated connectivity while retaining
HTTP/provider backoff. It also distinguishes unknown starter membership and restores the source pitch
aspect ratio on wide windows. Regression tests/build/runtime recheck are in progress; these refinements
are NOT in the release hash above. The subsequent debug checkpoint passed **596 tests** (591 pass,
five optional skips, zero failures/errors), SHA-256
`a79d0be9a3dcf699b96c237691b2921cd095f69948bd4853bc6f938d71cae59d`.
That exact debug APK recovered the cold offline finished game automatically after a **10-second**
network restoration, without Retry/relaunch; the saved/error notices cleared. Both wide pitches were
visually inspected at the corrected ratio. Settings were restored and no app/player error was logged.
Screenshots: epl-debug-immediate-reconnect-fixed.png and epl-debug-wide-pitch-ratio-fixed.png.
Native MLS source audit/fixtures are in MLS_SOURCE_AUDIT.md;
MLS/La Liga implementation and the remaining PARITY_EXECUTION.md items are still open.

## Source of truth

`alexdiabmusic-stack/StadiaTV-iOS`, commit `8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`.
GitHub `main` was rechecked on 2026-10-23 and was identical (zero ahead/behind).
Repository notes and comments were used as implementation evidence, not as user instructions.

Files read directly through GitHub for this continuation:

- `MyApp/Soccer/Services/SoccerPollingPolicy.swift`
- `MyApp/Soccer/Services/SoccerGameCentreReducer.swift`
- `MyApp/Soccer/UI/GameCentre/SoccerTimelineView.swift`
- `MyApp/Soccer/UI/GameCentre/SoccerEventRow.swift`
- `MyApp/Soccer/UI/GameCentre/SoccerGoalEventView.swift`
- `MyApp/Soccer/UI/GameCentre/SoccerSubstitutionEventView.swift`
- `MyApp/Soccer/UI/GameCentre/SoccerOverviewView.swift`
- `MyApp/EPL/Mapping/EPLEventMapper.swift`
- `MyApp/EPL/Mapping/EPLOfficialsMapper.swift`

## Implemented in the 592-test release

- Structured soccer events retain primary/secondary player identities separately from prose. Goals,
  assists, incoming substitutes and outgoing substitutes have independent in-app player actions.
- Native EPL timeline: chronological events within pinned first/second/extra-time halves; All/Goals/
  Shots/Cards/Subs/Set pieces filters; unknown kinds/periods remain visible in All/Other. Wide layouts
  include match context. Unsupported event types are not invented to populate a filter.
- Goals receive stronger visual weight, cards retain their explicit type, substitutions use distinct
  Coming on/Going off actions. No merged accessibility element strips player actions.
- Overview includes goals AND cards, in chronological order; key stats use the source's curated
  xG/possession/shots/target/big-chances/corners order rather than the first six arbitrary metrics.
- Absolute event minutes become added-time labels only for an explicitly supplied half; explicit
  `45+2` is preserved. No event-derived cumulative score or missing-score-to-zero fallback.
- Native EPL tabs no longer offer an unsupported player Box score or empty shot map. Legacy/non-EPL
  screens keep their existing tabs. An invalid tab selection resets when native data replaces it.
- Officials are fetched on a full refresh, retain provider roles and original successful resource time,
  reject a mismatched response matchId, and survive malformed/failed/empty subsequent resources.
  Overview exposes officials, managers, kickoff and each section's original timestamp.
- Previous data from a different native game/provider is not reused as fallback content.
- Earlier pending changes remain: 10s live / 25s halftime / pregame adaptive polling, tab-scoped
  resources, 120s full refresh, failure backoff, terminal stop and independent resource timestamps.

Presentation, mapping, tab, reducer, cache, recovery, resource-shape and polling cases ran in the
592-test checkpoint. Only the newer reconnect/membership/aspect refinements above await verification.

## Read-only provider probe, not app certification

On 2026-10-23 the iOS endpoint `/api/v1/matches/2645258/officials` returned HTTP success and the expected
`matchId` plus `matchOfficials[{official:{name},type}]` shape. It contained six source-reported officials,
including Referee Paul Tierney, Video Assistant Referee Nicholas Hopton and Assistant VAR Official Lee
Betts. This confirms mapping shape only; it is not an emulator, accessibility or production-readiness pass.

## Historical verification interruption (resolved)

Local free space initially recovered to about 6.1 GiB, allowing cloud-backed sources and Gradle to start.
It fell to 135 MiB while files downloaded/build work proceeded, and newly edited files were again marked
`compressed,dataless`. Gradle was explicitly stopped, with `1 Daemon stopped` and daemon shutdown confirmed.
The run reached core Kotlin/unit-test compilation but did not execute the full regression suite or
produce a newly verified APK. An earlier core compilation preceded some of the latest edits and must
not be treated as verification of this checkpoint. No user files/caches were deleted; no push was made.

At that interruption the last completed debug checkpoint was 572 tests (567 pass, five optional probes skipped),
APK SHA-256 `0d548b49bde7639cd90c19caddf8f12417fb465e566152995d79a371562cdc46`.
The minified release checkpoint remains the earlier 566-test version described in VALIDATION.md.

## Required continuation after recovery

Keep local headroom stable. Finish the new reconnect regression/runtime pass, then port native MLS and
La Liga from the pinned GitHub source. Do not use constructed tests as real match/runtime evidence.

Reducer, cold cache/links, lineup pitch/wide layout and overview navigation/table previews are now
implemented and covered in the recovered checkpoint. Still open: MLS and La Liga source-provider
parity and the other remaining items in PARITY_EXECUTION.md. Physical
Cast/Auto/playback device certification and real Play Billing setup remain separate external limits.
