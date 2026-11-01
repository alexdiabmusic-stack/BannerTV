# Android phone validation

## Active continuation — 2026-10-26 (development, not production delivery)

The NHL global/provider/box/leader/cache, Stats/full-table and purchase-client continuation is documented in
[NHL_GLOBAL_STATS_PROGRESS.md](NHL_GLOBAL_STATS_PROGRESS.md) and [PLAY_BILLING.md](PLAY_BILLING.md).
This work does not complete iOS parity; remaining code and verification are tracked in PARITY_EXECUTION.md.

### Latest861 — adaptive phone code checkpoint; emulator QA explicitly deferred

**856 pass/five optional skips/zero failures/errors across861 tests** (core664/phone169/TV28).
Complete build5928 exited0 in8m34s using1280-MiB heap/512-MiB metaspace: all-module debug lint,
phone release lint, debug/minified-phone and TV debug assembly pass. Lint has zero errors, with
core11 warnings, phone44 warnings (debug/release), TV19 warnings/two hints. Existing warnings are
not represented as a warning-free build. The final minified APK passes v2 signature/16-KiB alignment;
its512-square launcher pixels match the pinned source conversion exactly.

- Release SHA-256: `a383312ea799dbc2cf67afad3defee6ebf9b04fc8aac98d92606fdbe431f000b`
- Phone debug: `75b1a9c37f97cb05f61f9542aa4a041d4ac3e1c6ef96b06df82d8f74bbe60065`
- TV debug: `8cf801d0d959c7cf63f6827bd4ebbe32f95bc8dd4d1cff78321ddd64e6f9ead0` (unchanged)
- Minified artifact: `/Users/alexdiab/Library/Caches/StadiaTVFirestick/build/mobile/outputs/apk/release/mobile-release.apk`

Development-signed/default-unlocked/cannot-charge, not a production/store/dist delivery. The package
retains versionCode8/versionName1.7.0; use this SHA rather than the version alone to identify it.

This source ports Home/Following/Live Sports, Guide inline details, sport/F1 Game Centre and attached-
match player adaptive arrangements. Actual fold geometry is lifecycle-observed and projected into
content coordinates; pure tests cover off-centre/zero-width/occluded/outside/multiple folds, RTL,
large text, compact/expanded, tabletop, PiP and classic-player behavior. Video stays at one structural
position. Large-screen/multi-window Back exits rather than depending on ignored orientation changes.
Hidden companion state is digest-scoped to its game/channel, retaining a game across stream copies
without leaking raw provider identities or restoring a different game's tab. The pinned iOS icon is
converted reproducibly; Home hero/Sports Day Watch controls revalidate EPG evidence at tap time.

The host briefly had only~600MiB free disk. The owner explicitly chose **continue code checks and
defer emulator QA**. No861 installation, new UI screenshots, resizing/gesture/selected-tab runtime,
stream playback/restart, memory/long-soak or physical-foldable checks are claimed. The emulator was
never started; no app data, playlists, roster, selected league or device settings changed. Last
installed/runtime-verified app remains830. Earlier830/814 runtime results do not certify861.

GitHub main moved mid-pass from b58b989 to73bf44c (three engine-only commits). Adaptive/icon source
is unchanged; the new startup/off-air/local-playlist engine delta is audited but **not ported** in
IOS_ENGINE_73BF_AUDIT.md. Imported fantasy joins, root/modal/secondary-window fold ownership,
remaining source/UI nuances, hardware and production-service checks remain open. See
ADAPTIVE_PHONE_PARITY_PROGRESS.md, design/ICON_PROVENANCE.md and PARITY_EXECUTION.md.
No source-control mutation, push, dist publication, unrelated cleanup or full-parity claim.

### Earlier830 — EPG/source/UI and cold-guide checkpoint

**825 pass/five optional skips/zero failures/errors across830 tests** (core664/phone138/TV28).
Full build70958 exited0 in14m29s; all-module debug lint and phone release lint have zero errors.
Debug/minified-phone/TV assembly, release v2 signature and16-KiB alignment pass. Development-signed/
unlocked/cannot-charge, not the older dist1.7.0 package or a production/store delivery:

- Release SHA-256: `e8ac4cc266ee5a6bd4f256cc7493dca40028c2252045c9ede2bc731e77a411f7`
- Phone debug: `6b06828b6052e531a867d56f0b071a3827199e877ffa9ab331c23ef30d99431e`
- TV debug: `8cf801d0d959c7cf63f6827bd4ebbe32f95bc8dd4d1cff78321ddd64e6f9ead0`

This continuation added compact Home/fantasy context, full-row labelled switches following Android
accessibility guidance, roster-first/explicit draft navigation, leading/high-contrast Guide badges,
actual programme status and scrolling detail actions. Real provider testing exposed false name-only
EPG confirmation and incomplete cold-guide compaction. Shared phone/TV source policy now requires
actual listing evidence, prioritizes/deduplicates it and revalidates taps without substituting an
unverified preferred mirror. Bootstrap/refresh publication is serialized, cancelled parses cannot
commit, manual guide refresh validates through both cache layers, download fallback is disclosed,
and guide refresh waits for the complete exactly analysed cached lineup.

Actual minified830 installed over the temporary debug diagnostic APK with saved data intact. Two
separate offline force-stop/cold launches at150% restored the real Sportsnet One morning schedule
without Refresh or network access. Both screenshots were visually inspected. This fixes the observed
two-schedule bootstrap wipe; it does not certify all providers/devices. No media was started in830;
real regular-feed restart-to-video checks were on814. Final PSS153,211KB/RSS303,536KB/zero swap,
one Activity; targeted app/player errors empty. Not peak/leak/long-soak or never-lag certification.

Original QA Hockey/Test Team/round2-pick2/A.J. Greer remained unchanged and reselected. The separate
QA draft and both playlists are preserved. Font1.0/Wi-Fi1/data1/540x1212/density210 read back restored;
app stopped/emulator63802 exited0, final APK hash rechecked. No commit, push or dist publication.
See GUIDE_COLD_CACHE_PROGRESS.md and PLAYLIST_EPG_CONFIRMATION_PROGRESS.md for source/runtime
boundaries. Full iOS parity, new adaptive/icon/imported fantasy nuances, cross-provider XMLTV ID
ownership and hardware/production checks remain open.

### Earlier825 build and814 runtime findings

The814 EPG-only correction passed809 tests/five optional skips, all build/lint/signature/alignment
gates and actual minified source-policy checks. Name-only suggestions no longer create automatic
Watch; after a real provider-guide refresh, listing-backed choices lead. Regular Sportsnet One's
explicit restart resumed decoded hockey and later frames advanced. Original QA Hockey/A.J. Greer
selection/roster were restored; the app/stream were stopped and emulator25266 exited0.

Cold relaunch repeatedly lost current Sportsnet programme data despite manual refresh restoring
2,219 guide channels.825 serializes bootstrap/refresh publication, guards cancelled parse commits,
makes manual refresh validate through both caches, isolates source-file keys and displays save/load
failures. Eleven focused real-file/mock-HTTP cases passed; the full825 unit suite is820 pass/five
optional skips/no failures. Full build/minified cold/offline verification is in progress. The current
output APK will be superseded by825 after packaging; no production/dist release is being made.
See GUIDE_COLD_CACHE_PROGRESS.md for exact boundaries and the outstanding cold/contrast checks.

### Earlier807/814 EPG confirmation findings

The807 refinements passed all build gates (reconfirmed exit0/13s after an interrupted tool session)
with802 pass/five optional skips/zero failures across807 tests, zero-error debug/release lint and
debug/minified-phone/TV assembly. Release SHA-256
`c5d203e08872864ce9045c74458c54d87360afe573bdf73d8a9185b4d13554f6`, development-signed/unlocked.
Actual large-text runtime verified compact Home, roster-first entry/explicit Continue draft,
full-row label/graphic toggle handling, current/future EPG status, scrolling detail actions and a
visible leading Guide fantasy badge. Real Minnesota–Vancouver EPG context matched native Brock
Faber, added only in the separate QA draft; the original A.J. Greer roster remains unchanged.

The actual Restart action resumed video, but that video contradicted the selected game.807 falsely
labelled a timed event-name-only stream as playlist-EPG-confirmed.814 now requires real T1/T2 listing
evidence for automatic Watch, prioritizes it over name suggestions, deduplicates evidence, revalidates
tap/source references and keeps the exact matched TV feed instead of substituting another mirror.
Its seven new regressions passed (809 pass/five optional skips across814 cases); full build and
exact minified814 runtime are in progress, not yet certified. Active-cell Guide badge contrast is
also corrected in source. See PLAYLIST_EPG_CONFIRMATION_PROGRESS.md for the full evidence/limitations.

The user-supplied provider credentials are not reproduced here. No third-party EPG was added.
The test stream/app were stopped and emulator72212 exited0 before814 compilation. Font/network/
display settings read back restored; original QA league selection was subsequently restored in814.
Both playlists and drafts are preserved. No never-lag/long-soak/device/
production-purchase/full-parity claim, no commit/push/dist publication.

### Native fantasy pools — latest778 checkpoint

**773 pass/five optional skips/zero failures/errors** across778 tests (core626/phone124/TV28).
All debug lint tasks have zero errors; release-only lint and debug/minified-phone/TV builds passed
in8m58s. Signature v2 and16-KiB alignment pass. Development-signed/unlocked/cannot-charge:

- Release SHA-256: `e604a3ed35c1405459b17ca59c00050dde966bd391f2a65feaee89e2b91fd35e`
- Phone debug: `163c30c4b8a9c873ab5eb77f46e7966b5ab12f27295f6ae68acb969e8552a1a3`
- TV debug: `aac9a52bde9158ffc5bda3d9f4ce084009c0fe8ec2bac7b2d6aa740cc7af88c4`

New empty NHL/NBA/MLB drafts now select iOS's native roster providers; old ESPN roster AND pick-history
IDs remain ESPN without renumbering. Bounded/coalesced loading, provider/club-conflict rejection,
partial-result disclosure/short retries/original-time retention, native role eligibility, custom NHL F,
player profiles/headshots, exact team/position filters and30-row pagination are covered. Backup accepts
custom F and preserves IDs/lineups; credential-bearing fantasy portrait URLs are stripped both ways.

Actual minified NHL checks retained the original QA Hockey/A.J. Greer entry and ESPN profile. A
separate native QA league loaded805 players, L+ANA filtered to four and the native Greer profile showed
NHL provenance/bio/actual-season stats.78 goalies paged30/30/18 with scroll-to-header at normal/150%;
Back/page/filter recreation retained state. Warm offline kept exact3:33PM load time; explicit online
Refresh completed3:38PM. Only the separate QA league drafted provider-reported Brady Tkachuk FLA/L
and selected F; pick/F survived cold offline launch. Cold directory/fixtures stayed explicitly
unavailable, no sample fallback. Reconnect with no Refresh/section/font action automatically completed
3:41PM and804 available players. Original league selection/roster restored unchanged afterward.

Three screenshots were visually inspected; targeted app/player error logs empty, no actual stream
started. PSS snapshots169,293/115,194/139,140KB, zero swap, not a peak/leak/physical certification.
Font1.0/Wi-Fi1/data1/540x1212/density210 read back restored; app stopped/emulator18062 exited0.
One separately labelled local QA league remains; no existing data deletion, Gradle/emulator overlap,
commit, push or production/dist release. See NATIVE_FANTASY_POOL_PROGRESS.md for exact source/runtime
boundaries. Full parity and native NBA/MLB/NFL pool/device/large-playlist certification remain open.

### Native fantasy fixtures — earlier752 checkpoint

**747 pass/five optional skips/zero failures/errors** across752 tests. All debug lint tasks have zero
errors; release-only lint and debug/minified-phone/TV builds passed in5m13s. Signature v2 and16-KiB
alignment pass. These remain development-signed/unlocked/cannot-charge, not production/dist delivery:

- Release SHA-256: `a1c2718a6254038f33672181603e17a3dd628c785a8b098cb8276a8d5434fcc3`
- Phone debug: `ab0a399db318fed1035758c25e96cb5f858d72200fba8bcbbe099086432f5f11`
- TV debug: `14dd0b074f75fa6816d042ce96663c4cc7c0597f151991e1771d26740155e578`

New source covers native roster eight-local-day fixture/opponent context, Game Centre/playlist-EPG
watch controls, aliases/doubleheaders/known-started locks and on-demand player-pool loading. Immediate
stream indexing/guide preparation is off-main and lazy; corrected fixture evidence and same-ID
URL/header changes invalidate old confirmations/references. Final/invalidation guards are tested.
An identical-guide assertion caught a StateFlow object-identity regression (one failed guard build,
21s, not installed); the value-equality fix now passes all cases. The748/early752 baselines passed
their own gates but were not runtime-certified; only the latest exact artifact above was installed.

Real minified752 runtime opened existing saved QA Hockey without changing its A.J. Greer/ANA entry or
lineups. Its team fixture sheet showed live at PHI Oct24 1PM, vs VAN Oct27 and BUF Oct29. Selecting the
first opened the correct native NHL Game Centre with actual1–2 score,08:16 second-period clock,
4-on-5 situation and real goals/assists. This is team fixture context, not proof of that player's
participation, current team after a trade, or individual live points. Back restored roster/section/
scroll;150% recreation retained readable fixture/actions. `fantasy752-real-fixtures.png` and
`fantasy752-roster-150.png` were visually inspected. The shared source sheet said No confirmed stream
for the existing four-channel test playlist, not a fabricated Play success. No actual stream started.

Both networks off retained original2:32PM schedule request time. Restoring networks, with NO Refresh/
font/tab action before inspection, completed a2:35PM schedule request and cleared offline state.
Targeted AndroidRuntime/ExoPlayer error logs were empty. Emulator process PSS snapshots were85,293KB
at Home,123,397KB after native-game navigation and105,684KB at the end, zero reported swap. These are
three bounded observations, NOT a measured peak, leak/codec/physical/large-playlist certification.
Font1.0/Wi-Fi1/data1/540x1212/density210 were read back restored; test app stopped/emulator exited0.
No Gradle/emulator overlap, user-data cleanup, commit, push or production release occurred.

Previous-season pool ranking/migration, full fantasy matchup/period scoring, cross-screen indexes/
guide badges/tickers/toasts, roster presentation polish and broader source/device QA remain open.
See NATIVE_FANTASY_FIXTURES_PROGRESS.md. Full iOS parity is not complete.

### Native soccer routing and podcast recovery — runtime-verified737 checkpoint

**732 pass/five optional skips/zero failures/errors** across737 tests. All three debug lint gates
have zero errors; release-only lint and phone debug/minified-release/TV debug packaging passed in one
5m4s build. Phone release signature v2 and16-KiB alignment pass. Development-signed/unlocked/cannot-charge:

- Release SHA-256: `031cd3f3231ab9f6f594455eee21051ee8199bc72c27aa4ee9a26c74cccd7288`
- Phone debug: `cd26b86c0644d2661669964348b8fd2ae47a2f480be9acd20daf16f4d831fb58`
- TV debug: `5919bcb6fbe1e7ee942da36c9eeaf394781b9c2fd008b62b4f446c6d0be61516`

Real minified737 runtime verified the734-discovered fixes: native La Liga overview includes real
cards/goals in chronological order; Full timeline opens the soccer filter/incident screen; Goals
shows62′/69′/84′ with actual scorer/assist detail. No first/second half is invented where FotMob does
not report it. Real Ottawa discovery returned rich title/publisher/artwork. With both networks off,
Refresh retained the exact art/publisher and original1:16PM search time; reconnect plus explicit
Refresh recovered1:17PM and cleared the failure notice. All sports is distinct from the For-you mode.
`laliga737-native-goals-filter.png` and `podcast737-offline-rich-card.png` were visually inspected.
Targeted AndroidRuntime/ExoPlayer error logs were empty. Settings read back font1.0/Wi-Fi1/data1/
540x1212/density210; app force-stopped and emulator exited0. No Gradle/emulator overlap or user cleanup.

These are bounded development/emulator checks, not full parity or production/hardware certification.
Unknown-half labels/period-marker polish, shot-pitch projection, broader feed/redirect recommendation
validation, native fantasy/Home/Following refinements and the remaining ledger still need work.
No commit, push, production signing, store charge or dist1.7.0 overwrite.

### Commentary and podcast discovery — earlier734 source

All734 tests completed: **729 pass/five optional skips/zero failures/errors**. All three debug lint
tasks have zero errors (core11/phone43/TV19 warnings), and debug/minified-phone/TV packaging passed
in4m43s. Release-only lint passed separately (1m37s); real minified runtime checks are in progress.
Phone release signature v2 and16-KiB alignment pass. Development-only/unlocked/cannot-charge artifacts:

- Release SHA-256: `247e08c241ccf7321d18cabd0a1e0f38b0d0f168923a7b440e5f84191fce4cc9`
- Phone debug: `5af0c2f5f4af8d54dfc4378d09681855ea7bb0f72c5e58d7483ebe6c7d1d2684`
- TV debug: `acf7939b21282383e05242a2516bd0c6645b3bda5fbd1cfbf3716447c08e70e0`

The newer source ports iOS narrative-only commentary and Jump-to-latest behavior. New leading entries
wait while reading history; corrections/removals and older-page loading remain independent. Opaque
IncidentCode labels are retained as provider metadata, not displayed as headings. FotMob events now
sort by reported minute/added time, retaining source tie order and placing a reported HT/FT boundary
after stoppage-time incidents at that base minute. Unknown times/halves are not manufactured. A new
real final-event field slice and constructed window tests cover these rules.

The iOS team-podcast registry/resolver now backs Android league/favorite topics, country-specific
public search, scoring/deduplication, bounded nonempty cache, curated fallbacks, Refresh and A-to-Z
sorting. Wrong-team results cannot obtain a trusted team tag just because search returned them.
Source/algorithm and remaining backend/feed-validation boundaries are in
[PODCAST_GITHUB_PORT_PROGRESS.md](PODCAST_GITHUB_PORT_PROGRESS.md). This does not establish full parity.

Real734 minified checks (no injected fixture/runtime data) passed narrative-only match-end/90+7′
commentary, manual history scrolling and Jump-to-latest return. Discover→Podcasts loaded actual
Ottawa recommendations, including Absolute Non-Sens and Sens Callups. Opening the former loaded
real RSS episodes (Fabian Zetterlund33min, Linus Ullmark35min). Selected team survived Back and150%
text recreation; actual A-to-Z rows included Absolute Non-Sens, Beatline Senators, Coming In Hot and
Locked On Senators. The episode and150% team screenshots were visually inspected. With both
networks disabled, Refresh retained recommendations and the original1:03PM search time; restoring
networks and explicit Refresh recovered a1:06PM search time without library loss. Targeted
AndroidRuntime/ExoPlayer error logs were empty. Font1.0/Wi-Fi1/data1/540x1212/density210 were read back
restored; test app stopped and emulator exited0 before rebuilding.

That runtime exposed two newer-source fixes, not present in734's APK: La Liga was omitted from three
phone native-soccer presentation gates (so overview/timeline used generic scoring-only UI), and an
offline catalog duplicate could replace verified publisher/artwork. Both are fixed with regression
tests; blank favorite names cannot match every local feed and the redundant topic label now reads
All sports. The newer737-source full build/runtime checks are in progress. Earlier734 verification
does not certify these later fixes. Shot projection/unknown halves, full recommendation/feed policy,
broader accessibility/device/other-ledger features and full parity remain open.

### Soccer recovery and MLS continuation — earlier716 checkpoint

Latest716 checkpoint passed **711 tests/five optional skips/zero failures/errors**. All three debug
lint tasks have zero errors (core11/phone43/TV19 warnings); release-only lint, phone debug/minified
release and TV debug builds passed in4m4s. Source was synced before building, with no emulator running.
Release `add7991f78e8c717deb3806524d8a787a62376486c5319057b0df6c88c396a30`,
debug `dd335776155aa5e333164031490e12d58832f9e4f6185ef33c1b9ccad118434e`,
TV `7ccff28ebda3f2519626f8bc25168e59591c01d6b69646c8b792712eb41fb3be`.
Release signature v2 and16-KiB alignment pass. These remain development-signed/unlocked/cannot-charge;
no production/dist delivery, commit or push.

The716 refinement addresses actual live pressure's null preallocated future
minutes, not zero samples. The mapper now skips those slots, retains prior pressure if temporarily all
null, and the phone counts only core La Liga source failures for score-poll backoff. A missing optional
graph/ticker can no longer progressively slow a healthy10s score poll to five minutes. This was found
from real713 runtime, not an invented data shape. New tests/full builds passed as above.

Real minified716 runtime: Racing–Espanyol was2–1 with reported92′→98′ clocks before the source-confirmed
final. The real pressure history rendered (94 reported samples), rather than failing on future null
slots. A cold restart with both networks disabled restored the2–1 final, real goal/assist history and
original12:11PM score time. Restoring Wi-Fi/data at16:17:15UTC required NO Retry/font/tab action:
FotMob recovery was observed by16:18:01; the official source and all error/saved notices recovered by
16:20:17 (upper observed bound~3m2s, not a claim of instantaneous recovery). Authority switched to
LaLiga, retaining its actual successful12:17PM catalogue time rather than the later retry's time.

The official published table, named4-2-3-1/4-4-2 dual formations at1600x1200+150% text, actual added-time
commentary (90+7′ and match-end text), referee Jesús Gil Manzano and VAR/assistant/fourth-official rows
were verified. `laliga716-pressure-real.png` and `laliga716-wide-lineups-150.png` were visually inspected.
Raw commentary incident titles (e.g.end14) still need presentation polish; shot pitch projection,
broader timeline/period/source-correction/accessibility and physical-device testing remain open.
Targeted AndroidRuntime/ExoPlayer error logs were empty. The host emulator emitted one color-buffer
error during resize, with no demonstrated app failure; this is not hardware graphics certification.
Settings read back restored: font1.0, Wi-Fi1/data1,540x1212/density210. Emulator fully exited. No app/
player process, unrelated user app or user files were left/removed by the test. Full parity remains open.

#### Earlier713 artifact history

Latest713 checkpoint: **708 pass/five optional skips/zero failures/errors**. All three debug lint tasks
have zero errors (core11/phone43/TV19 warnings); release-only lint, phone debug/minified release and TV
debug builds passed in5m40s. This includes the recorded actual live clock/current-club aliases,
independent official assignments and a cold-cache regression for a genuine official kickoff correction
invalidating old FotMob enrichment. Official data survives independently. New source artifact hashes:
release `dbc9cc5beabaf633b7a11b837d2d5e65a84c6ed033d8b979acd22ebff7d8efe4`,
debug `6960c4cf9d78b496838a39f0902b52d69c085609258623ecf9673a2a9162e518`,
TV `4b083df7a018b4799a6ed4bbf1d7b364c81751c630b6c19f472c77db3c9cf6da`.
Signature v2 and16-KiB alignment pass. Latest minified runtime checks are in progress. These remain
development-signed/unlocked/cannot-charge, not production/store or hardware certification.

Real minified713 runtime resolved live Racing–Espanyol through native cold link102344 and the verified
official49/8↔FotMob8696/8558 mapping. It displayed the actual1–1 score, reported clock76′→78′,
FotMob source/time, real goals/assists and named4-2-3-1 formation/rating tokens. The official2026/27
published table retained its own11:50AM check time while the game was live; it did not simulate live
points. The named-formation screenshot was visually inspected. Targeted app/player error logs were
empty. This exposed the pressure-null and optional-resource-backoff issues addressed in716 above.
The emulator settings were restored and it fully exited before rebuilding. Further cold/offline/
automatic-reconnect, commentary, pressure,150%/wide checks for the newest artifact remain open.

#### Earlier712/705 integration history

Newest712 source: all unit suites completed (707 pass/five optional skips/zero failures/errors).
Recorded actual live clock/current-club aliases and independent official assignments are added; its
full build is in progress and no new712 artifact hash is recorded yet.

The705-source refinement passed all unit suites (700 pass/five optional skips), all zero-error debug
lint tasks, release-only lint and phone debug/minified release/TV builds in5m44s. It includes server
revalidation (`max-age=0`) instead of
unchecked HTTP disk-cache freshness, bounded final-game recovery after a core-source failure, visible
player names on formation tokens and readable shot labels. A draft304 test caught `no-cache` being
unconditional in OkHttp5.3.2; the cache reference specifies `max-age=0` for conditional validation.
The corrected tests pass on the actual5.3.2 dependency, including conditional304 and403 handling.
The705 development artifact hashes (not the newer712 refinement) are:
release `48aea2998671f2c1c2fbf37cc59aabc47e8c79b8f3f19210caa14d1931f6bb24`,
debug `6fce7d28e2c96c33c4486ff0b92337b25092b763cca3fb269e98ce45d23e8d38`,
TV `1ec733669c0e3d6add408fa2e6be7ec3fcfcdb1b83616a3f7f9b823be1f7b82b`.
Signature v2 and16-KiB alignment pass. Source guidance:
https://github.com/lysine-dev/okhttp/blob/main/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Cache.kt
(historical square/okhttp URL redirects here; current-main guidance, exact5.3.2 behavior tested).

2026-10-24 native La Liga integration checkpoint: **701 tests,696 pass/five optional skips,zero failures/
errors**. All three debug lint tasks have zero errors (core11/phone43/TV19 warnings). Phone debug,
minified release including release-only lint, and TV debug assembled successfully in6m25s with
`-PpublicBuild=true`, one worker, no daemon/watch-FS and1536MiB heap/512MiB metaspace. Source mirror sync
completed before the run. This closes the previous release-only lint deferral for this newer source.

Release SHA-256: `ca23244859492b4f5ba08e6940818c6584a545fac2ae30120e925fdfdfed3f6f`.
Phone debug SHA-256: `f1fa21f745781572c8b0b4e53e1f0e5935b17f61e1ab65302df9abb1718e778d`.
TV debug SHA-256: `24420608b7155f9acbc6e6e0f38353220b32f0ea7947200bdd095337873a6d9c`.
Release signature v2 and16-KiB alignment pass. These remain development-signed/unlocked/cannot-charge
builds, not production signing or Play purchase verification. No dist replacement, commit or push.

La Liga is now registered in AppContainer/feed/native detail/cache/cold links/profile routing and phone
tabs. Official and FotMob accepted evidence retain independent source times. Rich snapshots are atomic,
twelve games/2MiB file/12MiB total/thirty days; retained in-memory detail is additionally bounded by count
and six million serialized characters. Tests cover both source outages, official-final/correction
priority, cold provenance, malformed resource retention, verified mapping, native-vs-legacy routing,
published-table freshness and cache bounds/corruption. Phone exposes source-dated score, formation
coordinates/ratings, a detailed shot list and attacking pressure with accessible paged values. A compact
inspectable error notice avoids stacking multiple failure banners over the viewport. No guessed shot
projection, player-ID join, event-derived score or simulated live table.

Real minified701 runtime (no injected fixtures) verified the official club directory, Real Madrid's
native roster and Courtois's official Opta profile: actual200cm/96kg/Bree/BE biography and2026–27 season
statistics (9 appearances/2 clean sheets during this session). Normal native Calendar opened today's
Rayo Vallecano–Elche0–3 final, official game102345. Three actual goals (23′/36′ penalties,68′ goal),
scorer/assist identities, reported4-3-3 formation coordinates/ratings and real shot outcomes/xG loaded.
A force-stop with Wi-Fi AND cellular disabled followed by `banner://game?id=laliga:102345&league=soccer/esp.1`
restored rich native data and the original11:10AM official score time. Targeted app/player logs were
empty before the offline check. Screenshots native-player, real-lineup, cold-offline and lineup-150
under docs/screenshots/laliga-minified-*.png were visually inspected.150% text preserved selection and
readable content; full large-text/wide/accessibility coverage is not yet established.

This session exposed refinements rather than a complete runtime pass: formation tokens needed visible
names; an HTTP disk-cache hit could be mistaken for a fresh detail check; and the initial final-game
reconnect request timed out. A later font/tab-triggered request recovered, so automatic reconnect
without other interaction is NOT certified by this artifact. The705 refinement above addresses those
issues and still needs fresh artifact/runtime verification. Published table/commentary/pressure/wide
runtime checks are also open. Settings read back restored: font1.0, Wi-Fi1, data1,540x1212/density210.
Emulator fully exited before the next build. No production/hardware/store certification is implied.

#### Earlier683/674 artifact history

Newer683-source checkpoint: all unit suites completed (678 pass/five optional skips), all three debug
lint tasks completed with zero errors (warnings remain core11/phone43/TV19), and phone debug assembled.
This adds9 pure La Liga authority/outage/final/correction/reschedule cases, not new registered app routes.
The full build progressed through minification but then stalled under host I/O/memory pressure; daemon
PID25458 was individually verified and terminated after more than90minutes with headroom around3.5GiB.
No other app/process or user file was removed. Its final output showed release-only lint incomplete;
do not treat the interrupted run as a full minified/TV pass. The prior68a9133b... release hash below was
confirmed intact before stopping. A lower-memory, no-daemon development packaging pass explicitly
defers `:mobile:lintVitalRelease`. That pass completed successfully (reported42m32s), including TV debug.
It reused the completed minification and explicitly did NOT finish the release-only lint check.
Final release SHA-256: `68a9133b8215186056225faeac897fbd07eef2b3271fe099c7384652d303f930`.
This is byte-for-byte identical to the runtime-tested674 tab-fix APK, not evidence of a new native La Liga
route. The added authority object remains unregistered and is tested as pure policy only.
Phone debug SHA-256: `89987250b595f3d48b9e9a9588341450a220d31c618f8892b5ef4827db153869`.
TV debug SHA-256: `0a98117971e7afac962bbb997f79d7c1f4184a275dd4df246f79bb651c7f8ccd`.
Release signature v2 and16-KiB alignment reverified. The temporary build used1024MiB heap/512MiB
metaspace and a single-use daemon; no emulator was running. Last disk check recovered to5.7GiB.
Release-only lint must be rerun with stable host resources before a new production-readiness claim.
No production/dist delivery, commit or push.

Newest full checkpoint: **674 tests: 669 pass/five optional skips/no failures/errors**. All three debug
lint tasks have zero errors (core 11 / phone 43 / TV 19 warnings). Phone debug, minified release and TV
debug succeeded in 4m23s using the scoped temporary source mirror after completed sync. Release SHA-256:
`b396e18e01f507190d7edd1f51e50b2793afa54d78a76b11025baab6e41fa18b`.
Debug SHA-256: `ae39b41c953cf6555e20ef06ab30a2f64a89e4b600a99b8da718dd0eb7f8e9c2`.
Signature v2 and 16-KiB alignment pass; this is development-signed, not production signing.
This adds MLS unpublished-announcement/native tabs/thin cold snapshots and tested La Liga/FotMob
discovery, profile, reconciliation, mapping cache, bounded gzip and detail-mapper foundations. La Liga
remains unregistered in the app pending detail authority/cache/UI integration. No new dist delivery, commit or push.

Real minified674 runtime: normal MLS calendar → actual Montréal–Nashville MLS-MAT-0009NT showed its
announced Oct24 1:30PM local kickoff with absent scores, native Timeline/Lineups/Team stats/Commentary/
table tabs and an explicit not-published notice. The Lineups tab correctly said the detailed lineup resource
was not published. A force-stop with Wi-Fi AND cellular disabled followed by the native game link recovered
the same fixture and original11:35PM schedule time from the separate thin cache. Restoring both networks
cleared the transport failure automatically, without Retry/relaunch; it did not invent a new schedule time.
The upcoming game's real published East conference table loaded without a match overview. Table rows
remained reachable by scrolling at150% text; the selected table content survived text-size recreation.
Screenshots were visually reviewed: mls-minified-announced-match-fixed.png,
mls-minified-announced-cold-offline.png and mls-minified-announced-table-150.png.
No app/player error was logged. Settings restored and emulator stopped before Gradle.

That150% check exposed the selected tab scrolling offscreen after font recreation even though its content
stayed selected. The subsequent shared UnderlineTabs change uses the documented LazyListState next-remeasure
request for selection/font/viewport/direction changes, without reacting to ordinary user scrolling.
The completed source sync was verified before a `--no-watch-fs` build actually recompiled the phone source.
All 674 regression checks, three zero-error lint tasks and minified-phone/TV builds passed (3m23s).
New release SHA-256: `68a9133b8215186056225faeac897fbd07eef2b3271fe099c7384652d303f930`.
New debug SHA-256: `3984a253c4b0eeae37ce752ee2c8676c331294b80e069d0dd956a30ee6da3ff5`.
Signature v2/alignment pass. Real minified runtime after midnight verified the selected Live table stays
visible after150% font recreation and a1600x1200 wide-window resize. Manual horizontal swiping to reveal
Overview stays put, with no snapback and the table content still selected. Screenshots
mls-minified-selected-tab-150-fixed.png and mls-minified-selected-tab-wide-150-fixed.png were reviewed.
The saved fixture initially displayed its correct Oct23 11:35PM date across midnight; a later genuine
schedule refresh advanced the schedule timestamp to12:14AM, not an overview404 being labelled fresh.
Wi-Fi1, cellular1, font1.0, size540x1212 and density210 were read back. No app/player error was logged;
emulator stopped. This is not physical-device/TalkBack/live-correction/codec certification.
Official API evidence used for the tab correction:
https://developer.android.com/reference/kotlin/androidx/compose/foundation/lazy/LazyListState

The earlier **628-test** full checkpoint (623 pass/five skips) used release SHA-256
`d50c822da8f1478e45c84fac619694b43f4d560895b75a19e367af5d0b7555c8`.
Real minified MLS checks verified the Inter Miami–San Diego2–2 result, all4 goals, independent first/
second-assist native profiles, player cards, older commentary,150% text, wide lineups, native calendar/
published standings, cold offline original times and automatic17-second reconnect. This found the
announced Montréal–Nashville detail404 edge addressed above;628 is not a pass for that edge.

Recovered 592-test checkpoint: 587 pass/five optional skips/no failures; all three lint tasks zero errors;
phone debug, minified release and TV debug successful. Release SHA-256
`502823e047d10d2ea173133e74d6e16b82584868a8831fbd2afef8dffdd22f78`; signature v2/16-KiB alignment pass.
Real minified EPL checks covered separate scorer/assist/on/off native profiles, filters and Back,
home-first captain/formation, 150% text, wide context and independent lineup columns, plus cold offline
score/events/original section timestamps. Immediate short reconnect found a finished-game cooldown bug.

The subsequent 596-test debug checkpoint (591 pass/five skips/no failures), SHA-256
`a79d0be9a3dcf699b96c237691b2921cd095f69948bd4853bc6f938d71cae59d`, fixed that issue: restoring the
network after **10 seconds** automatically cleared saved/error notices without Retry/relaunch. Corrected
wide pitch proportions were visually inspected. Settings restored, emulator stopped, app/player logs clean.
These are bounded emulator checks, not physical TalkBack, continuous live corrections or production QA.

Newer native MLS/shared-stats source has completed **616 tests: 611 pass/five skips/no failures/errors**.
Its lint/minified/TV verification is running; MLS real app runtime has NOT been checked yet. The 592/596
artifact hashes above must not be described as containing MLS. No new dist delivery, commit or push.

### Native EPL debug runtime (572-test source)

All three regression suites and phone debug assembly passed in 1m: 572 tests, 567 pass, five optional probes skipped,
no failures/errors. Exact installed debug SHA-256:
`0d548b49bde7639cd90c19caddf8f12417fb465e566152995d79a371562cdc46`.
Gradle was stopped before the emulator; installation returned Success after boot completion.

- The actual app loaded native EPL scores and the current club directory, including promoted clubs. Arsenal's roster
  visibly reported `Premier League · Source: EPL`, actual groups/players and heights. Its crest rendered.
- Roster → David Raya opened an **in-app native** profile, not a web/ESPN substitution: 2026–27, 7 games / 4 clean sheets,
  reported 183 cm / 80 kg / age 31 / birth country and nationality Spain / current club Arsenal. These are reported
  current-season facts, not historical match performance or a claim that every profile endpoint works.
- Native cold link `epl:2645258` loaded actual Everton 0–Chelsea 2, real crests, event names, 47.2%/52.8% possession and
  0.43/1.47 xG. Lineups defaulted to home Everton, its real 4-2-3-1 and David Moyes. Pitch tokens exposed full
  number/name/position/captain labels; the James Tarkowski captain token opened his native EPL profile (7 games / 1 goal /
  1 assist / 3 clean sheets). This is a UI semantics inspection, not full TalkBack playback certification.
- The official table reported EPL, 20 clubs and actual ranks. Horizontal gesture reached PTS (Manchester City 21,
  Arsenal 18) while rank/team columns stayed pinned. A back-state inspection raced a later deep link and is deliberately
  **not** counted as a navigation-retention pass.
- No AndroidRuntime/ExoPlayerImplInternal errors were logged. Wi-Fi=1, mobile_data=1 and font_scale=1.0 were read back
  before stopping app/emulator. No synthetic fixtures were inserted. Live corrections/polling, cold-offline soccer
  recovery, wide/tablet layouts and physical hardware remain open. This debug source has not had a new minified pass.

Evidence: [native roster](docs/screenshots/epl-debug-native-arsenal-roster.png),
[real profile](docs/screenshots/epl-debug-native-raya-profile.png),
[home-first formation](docs/screenshots/epl-debug-home-first-lineup.png),
[official table](docs/screenshots/epl-debug-native-official-table.png),
[pinned points](docs/screenshots/epl-debug-pinned-table-points.png).

### Native EPL / refined chart build checkpoint (566-test source)

All three test suites and debug lint tasks, minified phone release and TV debug assembly passed in 22m17s.
566 tests: 561 pass, five optional probes skipped, no failures/errors. Lint: zero errors, core 11 / phone 43 / TV 19
warnings. Release signature and 16 KiB alignment pass. Exact unversioned workbench release SHA-256:
`9570fb03ac1f1dc228782922b8668fcd388e9b9b1cde14c961a27c88e0487512`.
This checkpoint adds native EPL discovery/table/squad/profile routing and qualified identities, real kickoff and
stoppage formatting, in-app lineup links and stronger basketball court contrast. It has **not** been installed or
runtime-certified. Subsequent source adds nested profile/official-table guards, squad biography, number formatting,
home-first lineup defaults and HTTP-date Retry-After support; those are not in this hash. No new dist APK was delivered.

### Shot-chart debug verification (553-test source)

All three suites and phone debug assembly passed: 553 tests, 548 pass, five optional probes skipped, no failures/errors.
Exact debug APK SHA-256: `c55a5d8144ce497bdcb21b3d70991a08adf9ae02fd3ceead642e6ecc0425e7f1`.
The actual WNBA final displayed **58 made of 138 attempts**, court geometry, different 2/3-point marker sizes and a
complete lazy attempt list with real distances (including 2.3 ft / 24.3 ft) and official descriptions. Choosing Q4
updated the summary to **13 made of 37 attempts**. No beyond-half-court shots occurred in this game; that disclosure
and unclamped coordinates are regression-tested, not claimed exercised by the live feed. Visual inspection identified
faint court lines, and newer source increases their contrast; this small style change is not in the hash above.

Evidence: [summary](docs/screenshots/wnba-debug-shot-chart-refined.png),
[full court before contrast refinement](docs/screenshots/wnba-debug-shot-full-court.png),
[actual attempt details](docs/screenshots/wnba-debug-shot-court-details.png).

### Calendar repair verification (547-test debug source)

All three suites and phone debug assembly passed: 547 tests, 542 pass, five optional probes skipped, no failures/errors.
Exact debug APK SHA-256: `6856a38367463779803a7320d0c024ab8e2cbcc34140b7fcbe7aa311a47f473a`.
The installed APK showed the actual GSV 71–ATL 97 final on **Wednesday October 21**, plus an announced October 24 game,
in the Oct 21–27 calendar. Moving to Oct 28–Nov 3, visiting Teams and returning to Schedule retained that range and its
October 29/31 games. The native date picker opened with the retained date. These are real provider results, not seeded
fixtures. The emulator is stopped. This is a debug repair check, not a new minified or physical-device certification.

Evidence: [repaired results](docs/screenshots/wnba-debug-repaired-calendar.png),
[retained range](docs/screenshots/wnba-debug-calendar-retained-range.png).

### Newer minified basketball verification (545-test source; bounded runtime findings)

All three test suites/lint tasks, public-config minified phone release and TV debug build passed in 10m22s.
545 tests: 540 pass, five optional probes skipped, no failures/errors. Lint: zero errors, core 11 / phone 43 / TV 19
warnings. APK signature and 16 KiB alignment pass. Unversioned development-signed release SHA-256:
`ed118f0fd273c86f478a0695006d064743952dc9d2d79da5747fcf6d82cc2499`.
It includes the newer date browser, pinned/deferred basketball plays, leader/key-stat/game-info builders, sub-minute
clock precision, source chronological lead changes, persistent live header context and JSON-cache recovery correction.
The 526-test debug evidence below belongs to that earlier exact APK.
`dist/BannerTV-Android-Phone-1.7.0.apk` remains unchanged; no new delivery version was created.

- The exact release APK installed successfully after full emulator boot. Native WNBA 71–97 final/quarter data,
  chronological lead/tie headlines, actual points-leader cards (Williams 15 / Reese 21), curated percentage comparisons
  (34.8%–49.3% FG / 40.0%–40.9% 3PT) and native player navigation rendered in the minified app.
- Play history showed source `WNBA CDN`, pinned Q4, official sentences, assist/score context and real `0:02.3` /
  `0:28.7` clocks. Scrolling history exposed `Jump to latest`; activating it returned to Game End / Period End.
  Cold offline launch recovered the new play presentation and retained `5:59 PM` data when the device clock was 6:01.
  A failed native player profile visibly retained `WNBA · Source: WNBA` without ESPN substitution.
- Calendar testing found two real defects, **not passed coverage**: switching from a changed range to Teams and back
  reset the range; the known final was absent from the Oct 21–27 listing. Diagnosis used an authorised debug install
  to read only this public schedule cache (no other cache URLs, credentials, synthetic insertion or root access).
  The official cached season had 373 rows and included `1042600303`, but its `gameTimeUTC` was the sentinel
  `1900-01-01T00:00:00Z`; the real kickoff was `gameDateTimeUTC: 2026-10-22T00:00:00Z`. The old mapper chose the sentinel
  and filtered out the game. Newer source prefers real date+time, rejects sentinel-only dates, guards malformed daily
  filtering and hoists range state above section branches. A small exact recorded date-field fixture now covers this
  issue. The newer calendar fix is not in the 545-test APK hash above; its separate debug runtime check is documented above.
- No AndroidRuntime/ExoPlayerImplInternal errors were logged. Wi-Fi=1, mobile_data=1 and font_scale=1.0 were verified
  before ending the diagnostic session. Physical hardware, continuous live/new-play insertion and chart-detail parity
  remain open.

Evidence: [pinned plays](docs/screenshots/wnba-release-pinned-plays.png),
[leaders/comparisons](docs/screenshots/wnba-release-leaders-key-stats.png),
[offline plays](docs/screenshots/wnba-release-cold-offline-plays.png),
[observed calendar defect](docs/screenshots/wnba-release-calendar-coverage-gap.png).

### Basketball/global-feed debug verification (526-test source)

Full core/phone/TV tests, all three debug lint tasks and phone debug assembly passed in 2m46s. 526 tests: 521 pass,
five optional probes skipped, no failures/errors. Lint has zero errors (core 11 / phone 43 / TV 19 warnings).
The unversioned workbench debug APK SHA-256 is
`6f157b6c0736e5aec1675d102d366ef8039f5f67bb53330200c4af3f1f1cbfb0`.
The exact APK installed successfully before launch; Gradle was stopped for emulator work. This is not a new `dist`
delivery and is not a minified/R8 validation of the newer basketball code.

- Native NBA and WNBA team directories loaded through the app's normal network path, including local SVG/PNG crests,
  aliases and abbreviation placeholders for announced teams without bundled crests. NBA team header visibly reported
  `Source: NBA`. The standalone NBA 403 / WNBA HTML probes did not predict every emulator request; they remain failures,
  not evidence that the app's native requests all fail or all succeed.
- NBA Boston roster and the opened pregame detail were unavailable. WNBA Kayla Thornton's card opened an **in-app**
  player page, but the Stats-backed profile was unavailable. No live roster/player-stat success is claimed. These checks
  exposed duplicate roster empty/error Retry UI and missing source labels on failed player profiles, queued for refinement.
- Native WNBA game `wnba:1042600303` cold-opened GSV 71–ATL 97. Quarter values were 15/25/17/14 and 23/23/25/26;
  the final has no live-play button. Box cards showed real player names and twelve stats, including Kayla Thornton
  13 MIN / 0 PTS / 5 REB / 0 AST / 0-1 FG / 0-1 3PT; remaining stat cells were reachable by vertical scrolling.
  At 150% text, the grid adapted from six to four columns, names remained readable and all twelve values fit the card.
  Native on-court/live-only context was absent from this completed game. Source strings and reported times were visible.
- The native WNBA shot chart displayed **138 reported attempts** with team/period filters. A read-only inspection of the
  public cache confirmed both four-period arrays and **513 raw plays**, with matching original accepted surface times.
  No test fixture or synthetic game was injected into the app's runtime storage.
- With Wi-Fi/data disabled, force-stopping and cold-opening the same native link recovered final/plays/box/quarters.
  The UI showed `Offline WNBA data · 2:28 PM` when the device clock was 2:29 PM, retaining the original successful time.
  Restoring networking and returning to the game cleared the saved/offline notice without Retry. Cold offline Home
  now said `Scores unavailable` / `Schedule unavailable`, not a false zero-game or saved-scores claim.
- No AndroidRuntime/ExoPlayerImplInternal errors appeared in the bounded session. Wi-Fi=1, mobile_data=1 and
  font_scale=1.0 were read back before stopping the app/emulator. This does not certify physical devices, live NBA/WNBA
  correction/polling transitions, all layouts, TalkBack, Cast/Auto or sustained memory/decoder stress.

Evidence: [NBA teams](docs/screenshots/nba-debug-native-teams.png),
[WNBA teams](docs/screenshots/wnba-debug-native-teams.png), [native box](docs/screenshots/wnba-debug-native-box.png),
[150% box](docs/screenshots/wnba-debug-large-text-box.png), [native shots](docs/screenshots/wnba-debug-native-shots.png),
[cold offline box](docs/screenshots/wnba-debug-cold-offline-box.png),
[unavailable Home](docs/screenshots/home-debug-offline-unavailable.png).

The earlier 482-test debug APK `f458351c97d9994839c550dfa09a57bd3e8b76272a3a7c2a7035dca19c440f6b` also passed a bounded
runtime check: cached-first historical NFL, a 2025 Regular Season Week 1 calendar link, same-process native NFL standings
after an outage and the real 2025 Australian F1 classification with no final live-play action. Its cold-offline Home
wording defect was subsequently corrected and is covered by the newer 526-test pass above. Historical evidence:
`nfl-debug-seeded-calendar.png` and `home-debug-cold-offline.png` (the latter is explicitly pre-fix).

### Minified NFL recovery verification (468-test source)

Full core/phone/TV tests, all three debug lint tasks, public minified phone release and TV debug assembly passed in
6m06s. 468 tests: 463 pass, five optional probes skipped, no failures/errors. Lint: zero errors, core 11/phone 43/TV
19 warnings (plus two TV hints). The development-signed, unlocked build-cache release remains 1.7.0 (8), not a new
`dist` delivery. SHA-256: `c72f90982070a6f4afa37807f45e1a7f476a9d3fbd3bdd76f951851c40a60c41`.
APK v2 signature and 16 KiB alignment pass. The exact APK was installed successfully before launch with Gradle stopped.

- The real Dallas–Philadelphia game `nfl:f5908b6d-311e-11f0-b670-ae1250fadad1` loaded 20–24, actual quarter scores,
  scoring plays/drives and full GSIS-derived player cards. Dak Prescott's passing line was 21/34, 188 yards, 0 TD/INT;
  category cards showed real rushing players/counts. His card opened the native in-app NFL profile, visibly NFL and
  roster season 2026 with typed biography. This is current biography, not a claim about his historical season totals.
- Back retained the box selection/scroll. At 150% text, names/stat summaries wrapped instead of truncating in a fixed
  table. A separately verified horizontal gesture made Q4 reachable while DAL/PHI and totals 20/24 stayed pinned.
- With both Wi-Fi/data off, force-stopping and cold-opening the same game recovered the final, quarter table, plays,
  drive summaries and original `Offline NFL data · 1:01 PM` timestamp, not the later 1:03 retry time. No runtime test
  fixtures were injected. Restoring the validated network cleared the offline/error notices without pressing Retry.
- After that outage, global standings initially identified ESPN fallback, including after one Refresh. A fresh online
  process loaded native `NFL` AFC East rows in actual reported order BUF/NE/NYJ/MIA, ranks 1–4 (the older screen had
  alphabetic BUF/MIA/NE/NYJ). Native injuries visibly identified NFL and showed real published people/status/details.
  Inspection found the shared league-wide circuit breaker could hold unrelated surfaces in fallback after offline
  failures; capability-scoped and network-aware recovery is the next source change, not part of this APK hash.
- App/player logs contained no AndroidRuntime/ExoPlayerImplInternal errors. One post-recreation memory sample was
  135,972 KiB total PSS / 286,656 KiB RSS, two Activity objects/no WebViews. This is not sustained memory/leak or
  physical-Firestick certification. Original Wi-Fi=1, mobile_data=1, font_scale=1.0 were read back before stopping
  the app/emulator. Physical Android/Cast/Auto hardware remains unavailable.

Evidence: [category cards](docs/screenshots/nfl-release-category-box.png),
[150% cards](docs/screenshots/nfl-release-large-text-box.png),
[150% pinned quarters](docs/screenshots/nfl-release-large-text-quarters.png),
[cold offline plays](docs/screenshots/nfl-release-cold-offline-plays.png),
[observed fallback](docs/screenshots/nfl-release-standings-fallback.png),
[native ranks](docs/screenshots/nfl-release-reported-ranks.png),
[native injuries](docs/screenshots/nfl-release-native-injuries.png).

These bounded checks do not certify live six-second updates, all final/correction cases, interruption/backoff stress,
TalkBack or all devices. Full parity remains incomplete; broader native basketball/soccer, premium gates and platform
verification remain on the execution ledger.

### Native MLB continuation (after the NHL/navigation passes below)

The first MLB public-config debug UI pass used the current-source 422-test build, before the later R/H/E,
full event/action/runner, adaptive box-card and extras-cache refinements. It was installed successfully before
launch on the same Pixel 10 emulator; the build daemon was stopped before emulator boot.

- A real `mlb:744834` cold link loaded Mets 0–Nationals 1, 61 plate appearances, nine inning rows and actual MLB
  player box rows. The accepted public cache file contains all four original section timestamps, not test-fixture
  injection. The overview showed Jesse Winker's home run and actual batting leaders (including Winker and Adams).
- Tapping Francisco Lindor from the box opened an **in-app MLB** player page with jersey/position, MLB headshot,
  biography/height/weight/bats/throws/debut and explicitly labelled 2026 season statistics. These are current player
  statistics, not a claim about his 2024 game stats. His source was visibly MLB.
- The native at-bat tab showed pinned half-inning headers, expanded Brandon Nimmo/Derek Law pitch context and
  measured pitch locations. Scrolling exposed Jump to latest. Font-scale recreation 1.0→1.5→1.0 retained the
  selected at-bat page/disclosure and readable text; this does not certify all large-text layouts/accessibility.
- With Wi-Fi and mobile data off, force-stopping then relaunching the same native game recovered the saved final,
  scoring play and at-bats from disk. The front label retained `Saved MLB data · 10:52 AM` instead of the later retry
  time. Native section failures did not erase the timeline. An unavailable highlight notice remained, motivating the
  subsequent independent extras disk-cache work, not a false claim that clips played offline.
- Global MLB league scores/team directory/Boston roster/standings were reachable after reconnecting, but the
  observed AL table/roster shape appears to be the **ESPN fallback**, not proof of native global success. The later
  source-label implementation and fresh online verification must establish provenance explicitly. A read-only
  native standings HTTP probe returned six correctly named divisions; a unit test verifies native league discovery,
  query fields and `mlb:` rows. Neither alone establishes Android runtime routing success.
- Clean-log app/player session: no AndroidRuntime or ExoPlayerImplInternal error. Before shutdown, actual settings
  read back Wi-Fi=1, mobile_data=1, font_scale=1.0. The app and emulator were then stopped.

Evidence: [native profile](docs/screenshots/mlb-native-profile-continuation.png),
[measured pitches](docs/screenshots/mlb-at-bat-pitches-continuation.png),
[overview and leaders](docs/screenshots/mlb-overview-leaders-continuation.png),
[cold offline game](docs/screenshots/mlb-cold-offline-continuation.png),
[observed global table — provenance not yet native-verified](docs/screenshots/mlb-native-standings-continuation.png).

Subsequent source: an explicit unfollowed Stats league is no longer silently replaced by a followed league; native
R/H/E stays visible beside horizontally scrollable innings; game/probable-pitcher context, all pitch/action events,
runner scoring/movement and measured contact values are retained. The box now uses iOS-style adaptive player-stat
cards. MLB probability keeps its original at-bat indices instead of spacing missing samples as consecutive plays.
Independent bounded public highlights/probability disk caches preserve original times on failure. Authoritative
empty sections clear removed content; unavailable sections retain good data. Latest 431-test check passed before
the final box-card refinement; the next full current-source lint/minified release/runtime check is in progress.

The older workbench release hash below and the delivered `dist` APK do **not** include this MLB continuation.

### Minified MLB verification (432-test source, before shared-HTTP cancellation refinement)

Full core/phone/TV tests + debug lint, public minified phone release and TV debug assembly passed in 4m46s.
432 tests: 427 pass, five optional probes skipped. Lint remains zero errors (core 11/phone 43/TV 19 warnings).
The build-cache release is still 1.7.0 (8), not a newly distributed versioned APK. SHA-256:
`2101ca1009f980a026aa8c57667467342a4b2c31a3ddec0f727ca9c243913045`.
APK v2 signature and 16 KiB alignment checks pass. Default unlocked/no-checkout/public configuration is retained.

The exact release was installed successfully before launch, with Gradle stopped and original network/text settings
recorded. This fresh online session resolved the earlier provenance uncertainty:

- An unfollowed MLB Stats link stayed on MLB, displayed 30 teams and did not change saved follows. The native Boston
  roster explicitly identified MLB and showed source-supplied pitcher positions/biography. Native standings visibly
  identified MLB and showed five-team American League East ordering (Rays, Yankees, Red Sox, Orioles, Blue Jays),
  rather than the earlier ESPN fallback's league-shaped table. This establishes this session, not universal availability.
- Native `mlb:744834` displayed real R/H/E totals (NYM 0/1/0, WSH 1/5/0). Horizontal inning scrolling left team identity
  and totals pinned. The iOS-style Lindor/Nimmo cards showed readable stat labels/values; at 150% text Lindor's complete
  batting grid reflowed to four columns and retained the box page. The original scale was restored.
- The scoring-only at-bat filter showed Winker's scoring runner and provider measurements, 407 ft / 103.4 mph exit
  velocity, plus the pitch/action/RBI context. Missing AVG stayed `–`, not an invented value.
- Native probability displayed its real 0–60 at-bat index range, latest 100% and MLB provenance. With Wi-Fi/data off
  and the process force-stopped, the cold game reopened and recovered the probability chart at its **original**
  11:53 AM time. Highlights also recovered their 11:50 AM time and real Winker/Irvin/Law titles from disk. Cached
  highlight metadata is not downloaded video and no offline clip-playback success is claimed.
- Clean app/player logs remained empty of errors. One release memory snapshot was 84,599 KiB total PSS and
  231,776 KiB RSS, with one Activity/no WebViews; this is not a sustained or physical-Firestick performance result.
  Before shutdown, Wi-Fi=1, mobile_data=1, font_scale=1.0 were read back. The app/emulator were stopped.

Evidence: [native source/table](docs/screenshots/mlb-release-native-source-standings.png),
[pinned R/H/E](docs/screenshots/mlb-release-rhe-pinned.png),
[adaptive box](docs/screenshots/mlb-release-adaptive-box.png),
[150% box](docs/screenshots/mlb-release-large-text-box.png),
[runner/contact](docs/screenshots/mlb-release-contact-runner.png),
[native probability](docs/screenshots/mlb-release-native-probability.png),
[cold probability](docs/screenshots/mlb-release-cold-probability.png),
[cold highlights](docs/screenshots/mlb-release-cold-highlights.png).

Newer source work fixes shared-GET waiter cancellation and cancellation during response-body consumption, following
the iOS NFL client's ownership pattern. All five local-socket tests pass, including synchronization after the first
response-body read begins; the full 437-test suite and all three debug lint tasks pass. That networking change and minor source/plural wording refinements are
not included in the release hash above and still need current-source runtime/minification checks.

- Current combined unit results: 384 tests, 379 pass, five optional probes skipped, zero failures/errors.
- One-worker/no-parallel full check succeeds: core/phone/TV tests and lint, minified public phone release and TV debug.
  Lint: no errors; core 11 warnings (the additional Coil SVG newer-version notice), phone 43 warnings, TV 19 warnings/
  two hints. Dependency-upgrade notices are not runtime correctness claims and were not silenced.
- Current workbench release is still labelled 1.7.0 (8); it is **not** the prior delivered versioned 1.7 APK in `dist`.
  Do not confuse these artifacts. Build-cache APK SHA-256:
  `bec17c5748c40f5754f56e4aff94263b3429fa5e5aeff5d16966be8c25d1a264`.
  APK v2 signature and 16 KiB alignment checks pass. Default build is unlocked, with no configured purchase products/
  verifier endpoint and no enabled checkout. No purchase was attempted. Development signing remains, not production.
- JVM cases are offline fixtures/policy tests. Public NHL API probes in this continuation returned 403; they do not
  prove live/native future-schedule success. No physical Android device is available.

### First workbench minified UI pass (before the next profile/table refinements)

Pixel 10 ARM64 emulator, Android 37.1, 540 × 1212/210 dpi. Build daemon was stopped before boot; installation completed
after boot readiness and before launching. This pass used the exact workbench release hash above, not a fixture app.

- Stats deep link opened NHL, showed 32 teams and two saved follows, real crests and accessible navigation into
  Ottawa's team page. The app's **normal** network path loaded real NHL data despite the separate standalone 403
  probe; no alternate identity/proxy was added. Ottawa's schedule included a Rangers game tomorrow at 7 PM and later
  fixtures, closing the earlier global-feed omission for this session. This isn't an entire-season coverage guarantee.
- Ottawa's native roster opened Drake Batherson's in-app profile with NHL source, provider season/birthplace/height/
  weight and statistics. The visual pass exposed cramped two-column biography values and too many game cards above
  the roster; those are subsequently replaced by the new roster-first/profile-card source changes, not yet verified by
  this particular minified package.
- Full native Atlantic standings showed actual provider ordering, NHL GP/W/L/OTL/PTS/STK, real SVG crests and followed
  Ottawa highlighting. Horizontal scrolling reached Ottawa's 14 points/L1. The pass exposed loss of team context when
  scrolling; the subsequent frozen-team-column implementation is not claimed tested by this original screenshot.
- Native cold game link `nhl:2023020204` resolved the real Minnesota 2–Buffalo 3 final. Scoring summary/team comparisons
  loaded; Matt Boldy's game row expanded to full goals/assists/points/+/−/PIM/shots/hits/blocks/PPG/faceoffs/ice time/
  shifts/giveaway/takeaway values. Native profile action was reachable. A raw FO ratio presentation issue was noticed
  and subsequently fixed, with a regression test; no current live shootout/correction behaviour was exercised.
- Premium & purchases explicitly stated all features are unlocked and purchases disabled, with no checkout/price/
  restore control exposed in this mode. No real or test purchase was initiated.
- Clean-log session had no AndroidRuntime or ExoPlayerImplInternal error. The emulator/app were stopped afterwards.

Visual evidence: [Stats](docs/screenshots/stats-overview-continuation.png),
[standings before frozen-column refinement](docs/screenshots/standings-native-continuation.png),
[expanded NHL player before ratio formatting fix](docs/screenshots/nhl-player-box-continuation.png),
[purchase-disabled status](docs/screenshots/premium-disabled-continuation.png).

### Subsequent source refinements (not yet packaged as a new minified release)

393 combined tests: 388 pass, five optional probes skipped, no failures/errors. Native cache cold-link fallback,
winner correction, player-ratio formatting, typed optional roster biography/injury, provider headline stat labels,
season formatting, safe profile enrichment and no fabricated biography fields have regression coverage. Debug
compilation/assembly succeeds. Current UI has a roster-first page with grouped pinned headers, independent games
section, enriched player header, adaptive biography/headline cards, full-width stat rows, retained retryable profiles/
rosters and frozen standings identity columns. Full updated lint and UI/offline checks are in progress.

Updated debug pass: both core/phone lint return to zero errors after replacing an API-24-only collection cleanup call
so the shared API-23 TV core remains supported. Warnings remain core 11/phone 43; the TV lint result above is from the
full earlier run. All three unit suites still pass. Debug compilation/assembly succeeds.

- Ottawa opens at its grouped roster immediately, with a separate Games section rather than ten cards above players.
  Drake Batherson's provider-safe enriched header, readable birthplace/units, 2026–27 label, actual headline stats and
  full-width season rows rendered. At 150% system text the bio/headline grid became a readable single column.
- Frozen standings team/rank/crest rows remained visible alongside the horizontally scrolled OTL/PTS/STK values.
  Ottawa's 14 points/L1 stayed aligned with its highlighted team identity. Text scale was restored to 1.0 afterwards.
- With emulator Wi-Fi and mobile data both disabled and the process cold-stopped, the native game deep link resolved
  `nhl:2023020204` from the bounded disk snapshot. The real 2–3 final, scoring/assist/season annotations and native player
  box rows remained visible; failed-resource notices appeared independently. GAME INFO explicitly showed saved data
  at 9:39 AM, not the later offline retry time. Network settings were both restored enabled after the test.
- No AndroidRuntime or player error appeared in this second clean log. The app/emulator were stopped before rebuilding.

Screenshots: [revised player](docs/screenshots/player-profile-revised-continuation.png),
[150% text](docs/screenshots/player-profile-large-text-continuation.png),
[frozen table](docs/screenshots/standings-frozen-columns-continuation.png),
[cold offline saved timestamp](docs/screenshots/nhl-cold-offline-continuation.png).

The text-scale recreation also uncovered launch-intent replay returning to the original Stats link. A follow-up source
fix handles launch intents once and supplies stable, hashed, per-screen saveable-state scopes with unreachable-state
cleanup. A prominent saved-data label is added at the game tabs, rather than relying on GAME INFO. Those follow-up
navigation/provenance changes are not represented by the second debug screenshots.

The subsequent public-config debug build passes 397 combined tests (392 pass/five optional probes skipped). Its
lifecycle UI pass confirms a player page remains open after 1.0→1.5→1.0 font-scale recreation instead of reopening
the original Stats Intent. The chosen Team Games section also remains selected, and Back from a player restores the
previous scrolled roster position rather than resetting to its header. The initial roster retry in this pass was offline;
after the emulator network completed re-enabling, provider data loaded again. The successful lifecycle player sample
was ESPN-backed Dylan Cozens; the earlier Batherson checks were native NHL. No identities were substituted silently.

A repeat cold offline native game link shows `Saved NHL data` immediately below the game tabs, its original successful
check time and independent failed-resource notices, with the retained final/scoring data below. See
[saved-data banner](docs/screenshots/nhl-saved-banner-continuation.png). No AndroidRuntime/player error appeared.
Before the final shutdown Wi-Fi and mobile-data settings were both read back as 1 and font scale as 1.0, not merely
issued asynchronous enable commands. The app/emulator were then stopped. Full latest-source minified rebuild and
broader lifecycle/accessibility/device checks remain open as the parity task continues.

The prior delivered APK below is preserved separately; ongoing source changes are not represented by that package.

## 1.7 verification — 2026-10-23

Home/Following checkpoint, not full iOS parity. The pinned source details, implementation differences and remaining
coverage requirements are in [HOME_FOLLOWING_PARITY_1_7.md](HOME_FOLLOWING_PARITY_1_7.md).

### Automated checks and package

346 tests: 341 pass, five optional probes skipped, zero failures/errors. Nineteen new phone tests cover team-first
Following scope versus discovery, league-scoped selection, removed selections, duplicate keys, calendar/DST counts,
TBD/event fixtures, correct standings group/window/original ranks, native abbreviation fallback, bounded news scope,
Sports Day priority/spoiler-safe winner badges, cache coalescing/TTL, failure retention/retry, timeout, eviction/generation
and maximum two concurrent snapshot requests. Constructed games/tables live only in unit tests, not in the app.

The source-consistent command documented under 1.3 succeeds after the final reload changes: all three unit-test
suites and lint, the minified public phone release and TV debug regression build. Lint has no errors: core ten
warnings, phone 43 warnings, TV 19 warnings/two hints. APK v2 signature and 16 KB zip-alignment checks pass; the
release configuration omits the local odds key. Existing warnings are not claimed fixed by this checkpoint.

`dist/BannerTV-Android-Phone-1.7.0.apk`: 5,778,305 bytes; package `com.alexdiab.stadiatv.mobile`, version 1.7.0 (8),
minimum API 26, target API 36. Development signing remains in use, not production/store signing. Earlier versioned
APKs are preserved. The preliminary 1.7 APK was replaced by the final source-consistent build before handoff.

SHA-256: `17ffb669500f0a1971beb4301372a782b476e77d870314d3392768031a1bc573`.

### Final packaged runtime checks

Pixel 10 ARM64 emulator, Android 37.1, 540 × 1212 at 210 dpi. The final APK was installed after boot completion;
installation completed before launch. Installed metadata reports 1.7.0 (8), without the debuggable flag. Gradle was
stopped before launching the emulator on this 8 GB host; the emulator was stopped after the test session.

- Following Overview displayed the actual NFL up-next hero and a 13-game upcoming-week summary. Selecting the
  saved Ottawa team removed unrelated NFL fixtures. Real NHL headlines and the Eastern Conference context loaded;
  Ottawa was highlighted at the provider's fifth row with 14 points, rather than renumbered first in the window.
  Full table opened the NHL Standings tab directly, showing the same original ordering.
- The global feed returned no announced upcoming Ottawa fixtures during this session. This is **not** proof that
  Ottawa has no future games or that native NHL schedule coverage is complete. The UI says no events were returned;
  native scoreboard/team/calendar routing remains an explicit parity gap. No replacement game was invented.
- From Home's real Montreal–Chicago game on October 23 at 8 PM, team navigation and Follow team worked. Home
  immediately changed from the no-game state to the expanded one-game Sports Day: opposing crests, upcoming status,
  start time, separate reminder and Match info controls. The Android notification permission request, scheduling
  confirmation (7:50 PM), Reminder set state and cancellation worked. Match info reopened that same game. The QA
  reminder was cancelled; actual future delivery/reboot/battery-policy behaviour was not tested.
- After a cold relaunch, the Ottawa/Montreal follows remained, and Following showed Today 1 / This week 14 and the
  Montreal–Chicago up-next hero. Full schedule showed Today plus Sunday October 25 groups, real team crests and local
  kickoff times. Selecting Football removed the hockey fixture and retained the NFL rows. Overview/day/coming-up and
  full-schedule routes remained separate; no stream was falsely confirmed for the test's unmatched channels.
- No AndroidRuntime or ExoPlayerImplInternal error appeared in the clean-log final session. This is short UI/data QA,
  not playback/decoder stress, commercial-provider matching, physical Firestick/D-pad, TalkBack/font-scale, live insertion,
  live confirmed-watch, final/winner-spoiler or every Sports Day state certification. Cache failures/concurrency and
  spoiler rules have unit coverage; forced offline/recovery and those live UI states still need device tests.

Final release screenshots: [Following overview](docs/screenshots/following-overview-1.7.png),
[full schedule](docs/screenshots/following-schedule-1.7.png), [standings context](docs/screenshots/following-standings-1.7.png),
[single-game Sports Day](docs/screenshots/home-sports-day-1.7.png). Emulator-local QA follows/fantasy data are not
bundled app defaults. No test stream server or commercial provider credentials were needed for this checkpoint.

## 1.6 verification — 2026-10-22

Native NFL calendar and detailed play/drive checkpoint; full iOS parity is not complete. The source comparison and
remaining boundaries are in [NFL_PARITY_1_6.md](NFL_PARITY_1_6.md) and the execution ledger.

### Automated and public-provider checks

327 tests: 322 pass, five optional probes skipped, zero failures/errors. Seventeen new core tests cover recorded NFL calendar/
game responses, playoff labels/byes, native-vs-ESPN identity guards, statuses, GSIS play-type precedence, participant
dedup/names, down/distance/goal semantics, drive completion, corrected/deleted events, partial versus complete empty
snapshots, reported overtime columns, bounded auth refresh, native request paths and Retry-After cooldown. Unusual
cases are explicitly constructed. Native reminder-link resolution is also covered. Tests are offline; recorded-resource
provenance is alongside the fixtures. A phone presentation test covers localized dates/year and the Eastern/UTC
kickoff-day boundary, including completed calendar results.

A separate read-only public API check successfully minted the existing anonymous NFL web-client token in memory,
loaded 18 regular-season weeks for 2025 and 16 Week 1 games. The first game was Dallas at Philadelphia, native ID
`f5908b6d-311e-11f0-b670-ae1250fadad1`, September 5, 2025 at 00:20 UTC. Authentication responses/headers were neither
printed nor persisted. This confirms the public API path on this network, not every live game or production SLA.
The full weekly endpoint also returned 179 raw play records and period scores DAL 7/13/0/0 (20), PHI 7/14/3/0 (24);
unused overtime values were zero. Deleted records are processed, not counted as additional displayed plays.

The final source-consistent one-worker command under 1.3 succeeds: all three modules pass lint with no errors
(core ten warnings, phone 43 warnings, TV 19 warnings/two hints), minified public phone release and TV debug builds
succeed. APK v2 signature verification and 16 KB zip-alignment checks pass; release configuration omits the local
odds key. Tests/lint/builds are not hardware reliability certification.

### Final release runtime checks

Pixel 10 ARM64 emulator, Android 37.1, 540 × 1212 at 210 dpi. The exact final minified APK was installed after boot
completion and installation finished before launch. Installed package reports 1.6.0 (7), without the debuggable flag.

- NFL → Schedule resolved 2026 regular-season Week 7, displayed the provider's announced bye teams, and showed full
  local kickoff date/year/time. Switching to 2025 selected Week 1 and retained September 4, 2025, 8:20 PM alongside
  the completed Dallas 20–Philadelphia 24 result. The 2025 postseason selector displayed Wild Card, Divisional,
  Conference Championship and Super Bowl labels from the provider choices.
- A cold reminder-style `banner://game` link with the native namespaced ID and kickoff date resolved that same game
  through NFL. No ESPN ID substitution was needed. Direct calendar-to-game navigation also worked in the earlier
  source-equivalent core check; the final cold link repeats the native identity path on the exact packaged build.
- The real native quarter table is visible immediately after its loading slot, rather than being inserted above the
  preserved visible item. It shows DAL 7/13/0/0, total 20, PHI 7/14/3/0, total 24; horizontal scrolling reaches Q4/T.
  It omits unused zero overtime and does not show stale final-game field/down context. Compact/fixed-team-column
  table styling and broader live layout anchoring remain visual refinements, not claimed as pixel-identical parity.
- Native drive cards expand under the Turnovers filter. The actual 4th-and-3 incompletion at DAL 45 with 2:00 in Q4
  is correctly labelled Incomplete pass plus Turnover (downs), not an interception. Expanded details show provider gain
  zero, raw PASS kind and resolved Dak Prescott/CeeDee Lamb names; an unattributed team-stat participant stays explicit.
  The Penalties filter and real delay-of-game/no-play row also passed the prior same-core release check. No actual
  live insertion/correction, notification delivery or physical-device stress success is claimed.
- No AndroidRuntime or ExoPlayerImplInternal error was logged in the final clean-log session. Sports data was real;
  no local fixture server was used. The app and emulator were stopped afterwards. This is a short UI/network check,
  not a long-session, low-memory, remote-control, TalkBack or physical decoder benchmark.

Pre-package UI checks exposed missing dates on completed calendar cards and a preserved-anchor issue hiding an
arriving quarter table. Both were corrected, regression-tested/rebuilt, and repeated on the final APK above. These
are kept distinct from the unverified cross-platform/remaining native-provider requirements in the execution ledger.

Final release previews: [calendar](docs/screenshots/nfl-calendar-1.6.png), [historical results](docs/screenshots/nfl-results-1.6.png),
[overview/quarter scores](docs/screenshots/nfl-overview-1.6.png), [turnover drive](docs/screenshots/nfl-plays-1.6.png),
[play participants](docs/screenshots/nfl-play-detail-1.6.png), [postseason choices](docs/screenshots/nfl-postseason-1.6.png).

### Installable 1.6 artifact

`dist/BannerTV-Android-Phone-1.6.0.apk`: 5,761,921 bytes, package `com.alexdiab.stadiatv.mobile`, version 1.6.0 (7),
minimum Android 8.0/API 26, target API 36. Public release omits the local odds key and passes APK v2 signature and
16 KB zip-alignment checks. Signing remains development-only, not production/store signing. Prior APKs are preserved.

SHA-256: `78544448846dc8d34ece4e064a32239333061d2dd2b96003cd9bfc5c5a27adf8`.

## 1.5 verification — 2026-10-22

Native NHL event checkpoint; full iOS parity remains incomplete. See [NHL_PARITY_1_5.md](NHL_PARITY_1_5.md)
and the execution ledger for exact source behavior and remaining work.

The combined suites contain 309 tests: 304 pass, five optional probes skipped, no failures/errors. Eleven new NHL
tests cover real recorded iOS landing/box/PBP payloads and explicitly constructed penalty roles, bench calls, goal
enrichment, unknown event/key handling, native ownership, abbreviation aliases, deduplicated shootout rounds,
overtime labels, two-axis rink transforms, correction identity, independent HTTP 429 cooldown and history reading.
The recorded Minnesota/Buffalo fixture retains 320 events and five goals. See the test-resource provenance note.

The source-consistent one-worker command recorded under 1.3 succeeded: all three modules pass lint with no errors
(core ten warnings, phone 43 warnings, TV 19 warnings/two hints), the minified public phone release builds and the
TV debug regression build succeeds. Unit tests do not certify hardware, all live provider cases, TalkBack,
real-time scroll anchoring, commercial streams or physical decoder reliability.

### Final release runtime checks

Pixel 10 ARM64 emulator, Android 37.1, 540 × 1212 at 210 dpi. Installation completed before launch; the installed
package reports version 1.5.0 (6), without the debuggable flag. The initial install request while Android was still
booting was rejected, then succeeded after boot completion. No application code change was required.

- Actual ESPN game 401559437 resolves to native NHL game 2023020204, Minnesota at Buffalo, November 11, 2023,
  00:00 UTC. The public NHL endpoint and released app both show the reported 2–3 final score and five summary goals.
- Overview renders Henri Jokiharju's first-period 18:02 goal, player image, slap shot, Owen Power assist,
  even-strength label, season goal two and Power's seventh assist. The next goal is chronologically later,
  with the reported score rather than an inferred cumulative count. Replay is offered from the native link;
  opening the external clip/player website was not part of this UI check.
- Native Plays and Penalties filter work. The real Jordan Greenway interference penalty shows two minutes,
  drawn by Zach Bogosian, and the delayed-penalty event remains in that filter. Expanded detail offers Greenway's
  official NHL player page and the actual rink location, normalized to home-defends-left when reported.
- The real NHL shot map renders 135 reported attempts with team/period filters after the two-axis coordinate
  correction. Per-event invalid/missing geometry remains excluded by policy and unit tests.
- No AndroidRuntime or ExoPlayerImplInternal error was recorded in this clean-log 1.5 UI session. This is a short
  historical-game check, not live penalty/shootout/scroll-insertion or physical-device stability certification.
  Shootout counting, HTTP cooldown and deferred new-play corrections are unit-tested, not claimed as live runtime tests.

The test app and emulator were stopped afterwards; no test server was needed for this sports-only session.
Full native NHL profiles/caches, curated leaders/key-stat/box-score parity and wider lifecycle/accessibility/TV tests
remain in the ledger. Prior 1.4 startup/fixture observations are retained below and are not erased by this clean session.

Final release previews: [NHL scoring](docs/screenshots/nhl-scoring-1.5.png),
[penalties](docs/screenshots/nhl-penalties-1.5.png), [expanded event](docs/screenshots/nhl-event-detail-1.5.png),
[native shot map](docs/screenshots/nhl-shot-map-1.5.png).

### Installable 1.5 artifact

`dist/BannerTV-Android-Phone-1.5.0.apk`: 5,729,153 bytes, package `com.alexdiab.stadiatv.mobile`, version 1.5.0 (6),
minimum Android 8.0/API 26, target API 36. The minified public APK passes APK v2 signature verification and 16 KB
zip-alignment checks; generated release configuration omits the local odds key. Signing is development-only,
not production/store signing. Earlier artifacts are preserved.

SHA-256: `0da6e562f93b65db4d2f7ae38d0af64609d376a0fe9ae1a9e692efa837b3a2f3`.

## 1.4 verification — 2026-10-22

Player audio/Original sizing and native EPL detail checkpoint. Full iOS parity is not complete; see
[PLAYER_AND_EPL_PARITY_1_4.md](PLAYER_AND_EPL_PARITY_1_4.md) and the execution ledger.

### Automated checks

298 tests: 293 pass, five optional external-provider/performance probes skipped, no failures/errors. Added coverage:
physical-pixel/no-upscale/anamorphic/unknown-size policy; legacy aspect names; ISO/BCP-47 language normalization and
selection; changing captions when another language is already selected; same-language manual choices; stale/malformed
track IDs; startup pause/audio-only policy; settings migration and cloud language round-trip/sanitization. EPL tests
cover native team-keyed statistics, missing versus zero values, event buckets/names/added time/unknown kinds, corrected
and withdrawn incidents, response identity, published/provisional tables and deductions, and independent HTTP 429
resource cooldown. These edge-case fixtures are explicitly constructed, not claimed as real match recordings.

The full tests/lint/minified phone release/TV debug command is the one-worker command recorded under 1.3 below.
All three modules pass lint with no errors (warnings remain), the minified public phone release builds successfully,
and the TV debug regression build succeeds. Tests alone do not certify hardware reliability.

### Public provider checks

ESPN game 704611 (Everton at Chelsea, April 26, 2025, 11:30 UTC) resolves by teams/kickoff to native EPL game 2444803.
The actual native event endpoint returned structured goal/card/substitution buckets; Chelsea's goal has native player
517052, assist 448047, minute 27 and timestamp `20250426T125758+0100`. Statistics returned Chelsea possession 54.7%,
xG 0.8812, ten shots/seven on target; Everton 45.3%, xG 0.4417, six shots/three on target. The published 2024/25 table
returned 20 clubs, Liverpool first with 84 points after matchweek 38. This is the season's published table, not a claim
about positions at the historical game's kickoff. Actual live provisional-table movement is not yet verified.

Runtime fixtures stay outside the app/repository: the official AndroidX 320×240 five-second MP4 and a synthetic HLS
menu with English/French labels both pointing to the same official AAC tone. This tests routing/persistence, not foreign-
language content. Commercial playlists/credentials are not bundled.

### Final release runtime checks

Pixel 10 ARM64 emulator, Android 37.1, 540 × 1212 at 210 dpi; final minified 1.4.0 (5) APK installed and version verified.

- Chelsea/Everton Game Centre displayed the native 27th-minute goal with hydrated Nicolas Jackson and assist Enzo
  Fernández, correct home-first 1–0 score, native possession/xG/xGOT/shots and their source labels. The separate table
  tab loaded 20 clubs for the published 2024/25 season. Actual live provisional-table movement remains unverified.
- The synthetic HLS track menu selected French, persisted the language through force-stop/cold restart and selected
  French again without another manual choice. Automatic cleared the preference and selected the provider's default
  English track. Both language-labelled renditions contain the same tone; this is not an acoustic language test.
- Paused the corrected audio fixture at approximately ten seconds; it remained paused beyond the startup-watchdog
  interval and resumed on request. Real network transitions and forced-caption/codec variants remain device tests.
- Original mode centered the official 320 × 240 clip at 320 × 240 physical pixels without enlargement in portrait;
  selecting the mode retained the paused frame. Physical-device, TV scaled-canvas and PiP sizing are not certified.
- The first install/start overlap produced an Android framework startup NullPointerException in
  `ConfigurationController.updateLocaleListFromAppContext` / `ActivityThread.handleBindApplication`. Its cause is not
  established. After installation finished, the verified release cold-launched repeatedly. This observation is retained
  rather than claiming the entire QA session was crash-free.
- The initial temporary HLS fixture did not advance its playlist and lacked an end marker. Its repeated errors map to
  `DefaultHlsPlaylistTracker.PlaylistStuckException` in the release mapping. Correcting that external fixture to finite
  HLS with ENDLIST removed the observed recurrence; no application code was changed to hide these errors. The final
  clean-log launch/playback/audio-reset pass recorded no AndroidRuntime or ExoPlayerImplInternal error. This is a short
  synthetic session, not a commercial-provider reliability or long-session benchmark.

The app, emulator and temporary local fixture server were stopped after validation. Finite test media entered through
the live-channel playlist still uses the generic Live television companion; this does not certify a complete VOD library.

### Installable 1.4 artifact

`dist/BannerTV-Android-Phone-1.4.0.apk`: 5,712,769 bytes, package `com.alexdiab.stadiatv.mobile`, version 1.4.0 (5),
minimum Android 8.0/API 26, target API 36. The final minified public release is not debuggable, passes APK v2 signature
verification and 16 KB zip-alignment checks, and omits the local odds API key from generated release configuration.
Signing remains development-only; it is not ready for store distribution. Earlier APKs remain untouched.

SHA-256: `f5d2d8c7639191cf4ce54a52acf1e758b3200a9a1c804476a09343e4962e2fd2`.

Final release previews: [audio preference](docs/screenshots/audio-preference-1.4.png),
[Original picture](docs/screenshots/player-original-1.4.png), [native EPL statistics](docs/screenshots/epl-stats-1.4.png),
[published EPL table](docs/screenshots/epl-table-1.4.png).

## 1.3 verification — 2026-10-22

This checkpoint addresses player interactions, provider-declared replay/DVR and independent native MLB content.
It does not complete full iOS parity; see [PLAYER_PARITY_1_3.md](PLAYER_PARITY_1_3.md) and the execution ledger.

### Automated checks

The combined suites contain 282 tests: 277 pass, five optional external-provider/performance probes are skipped,
zero failures or errors. Added tests cover archive eligibility/retention, encoded Xtream credentials/ports/prefixes,
display-offset removal, all translated template tokens, M3U declaration inheritance/explicit disabling, bounded archive
guide retention, real-window seeking and moving-window scrub anchoring, pause policy, quick-action normalization/order,
gesture thresholds, cloud action-order round-trip, MLB content/probability parsing, correction ordering, per-surface
cooldown isolation, chart provenance and retaining extras across feed refreshes. Constructed edge cases are test-only.
All three modules pass lint with no errors; warnings remain. The minified public phone release and TV debug regression
builds succeed. Passing these checks does not establish physical-device reliability or complete feature parity.

The final source-consistent verification command uses one worker to avoid overlapping lint/optimizer memory pressure:

```sh
./gradlew :core:testDebugUnitTest :mobile:testDebugUnitTest :app:testDebugUnitTest \
  :mobile:lintDebug :core:lintDebug :app:lintDebug :mobile:assembleRelease :app:assembleDebug \
  -PpublicBuild=true --max-workers=1 --no-parallel \
  -Dorg.gradle.jvmargs='-Xmx2048m -XX:MaxMetaspaceSize=640m -Dfile.encoding=UTF-8'
```

### Emulator and public-provider checks

Pixel 10 ARM64 Android 37.1. The emulator-only local M3U/XMLTV provider supplies one declared archive channel, one
live-only rolling HLS DVR channel and the official AndroidX H.264 transport-stream sample. These are not bundled in
the app, and no commercial account/playlist is embedded.

- A completed TSN 1 programme showed a replay action only after its archive metadata loaded. The finite HLS replay
  rendered video, showed its actual 20-second media duration rather than the one-hour guide duration, finished with a
  replay-from-start action and did not reconnect or jump to live. Replay resumed from zero when requested.
- Paused replay seeking moved to 11 seconds and remained paused. A portrait pull-down minimized it without losing the
  replay or pause state. Explicit Return to live cleared archive mode; channel selection stayed in the same controller.
- A rolling HLS feed reported a real one-minute DVR window. Seeking behind live exposed Go Live; Go Live resumed at
  the recommended position. Portrait swipe-up rotated the retained channel/player into landscape.
- Reordered quick actions from Guide/Channels/Recent to Channels/Guide/Recent using the accessible move control.
- No AndroidRuntime or ExoPlayerImplInternal error was recorded in that debug replay/DVR/gesture session.
- Public native MLB endpoints for game 744834 returned 29 highlights and 61 probability points, starting at 52.2% home
  probability and ending at 100%. ESPN game 401569780 resolves to the same Mets/Nationals July 4, 2024 matchup; the
  native identity is resolved by teams/date/time, never by reusing ESPN's numeric event ID.

The minified 1.3 release additionally passed these checks:

- Installed as version 1.3.0 (4), retaining the existing playlist and Channels/Guide/Recent action order.
- Paused the rolling one-minute DVR feed for approximately 40 seconds. Resume remained behind live with Go Live
  available; it did not discard the still-seekable position. Explicit Go Live restored the LIVE state.
- Opened a completed programme from the in-player guide, rendered its finite replay, sought to 10 seconds while
  paused, restarted without switching to live, then reached the 20-second end with Replay from start and no reconnect.
  Return to live cleared archive mode only when explicitly requested. Archive companion no longer displays Up Next.
- The actual Mets/Nationals Game Centre resolved native game 744834, showed the MLB-reported probability curve and
  100% final home probability, and listed native highlights. The first real three-minute-five-second highlight rendered
  inside the app while the IPTV mini-player remained paused. Backgrounding paused the highlight at 51 seconds;
  returning left it at 51 seconds. Closing returned to Game Centre and did not silently restart the channel.
- No AndroidRuntime or ExoPlayerImplInternal error was recorded in those release checks. This is a short emulator
  session, not evidence of physical-device decoder capacity, commercial-provider reliability or long-session stability.

Final-score presentation now suppresses stale live inning/base context. Native MLB failure notices have a section-local
Retry action that respects provider cooldowns. After these corrections and the anchored scrub-preview label/accessibility
labels, the source-consistent full build succeeded. The exact resulting APK was reinstalled: the final header no longer
displayed live inning/base context, the native chart and a second real MLB clip rendered, and both DVR/replay sliders
retained pause state after seeking. Retained quick-action order and explicit replay return-to-live controls were present.
No application/decoder error was recorded in that final pass. The emulator, test app and local fixture server were stopped.

Physical pinch/source-chip/TalkBack, every provider dialect/expiry/timezone, archive/network recovery, Cast/Auto,
low-memory concurrent decoder use, long playback sessions and other execution-ledger gaps remain outstanding.

### Installable 1.3 artifact

`dist/BannerTV-Android-Phone-1.3.0.apk`: 5,696,385 bytes, package `com.alexdiab.stadiatv.mobile`, version 1.3.0 (4),
minimum Android 8.0/API 26, target API 36. The final minified public release is not debuggable, passes APK v2 signature
verification and 16 KB zip-alignment checks, and omits the local odds API key from generated release configuration.
Signing remains development-only; it is not ready for store distribution. Earlier APKs remain untouched.

SHA-256: `c10793e7aff0e91d4fcd40d9a2b1175f0a8a1fd39213a7ca7d64501fe976a9c8`.

Final release previews: [archive replay](docs/screenshots/player-replay-1.3.png), [DVR](docs/screenshots/player-dvr-1.3.png),
[native MLB probability](docs/screenshots/mlb-probability-1.3.png), [native MLB highlight](docs/screenshots/mlb-highlight-1.3.png).

## 1.2 verification — 2026-10-22

The working scope is **full iOS parity**, including Android platform equivalents. This is a tested implementation
checkpoint, not a statement that the scope is complete. Use [PARITY_EXECUTION.md](PARITY_EXECUTION.md) for the remaining
algorithms, UI nuances, integration setup and device testing.

### Automated checks

The combined core/phone/TV suites contain 263 tests: 258 pass, five optional external-provider/performance probes are
skipped, zero failures or errors. New tests cover native MLB/NFL/NHL/NBA/EPL mapping and correction handling, F1 wire
framing/compression/delta/state/session identity, PGA queries/gzip/scorecards/shots, finite scoreboard-flow completion,
fantasy transactions/drafts, Sleeper mapping, alert planning, cloud-file privacy/versioning/corrupt references and channel organization.
F1 fixtures include recorded data from the iOS repository; constructed provider inputs are labelled as such.
All three modules pass lint with no errors; warnings remain. The minified public phone release and TV debug regression
builds succeed. Tests and lint are not substitutes for physical-device performance validation.

The command used for regression verification is:

```sh
./gradlew :core:testDebugUnitTest :mobile:testDebugUnitTest :app:testDebugUnitTest \
  :mobile:lintDebug :core:lintDebug :app:lintDebug \
  :mobile:assembleRelease :app:assembleDebug -PpublicBuild=true --max-workers=2 --no-parallel
```

### Runtime checks

Pixel 10 ARM64 emulator, Android 37.1, 540 × 1212 at 210 dpi. Tests use emulator-local state, public sports/podcast
feeds and the existing two-channel local provider fixture; no user account or playlist credentials are bundled.

- Minified 1.2 APK: native local fantasy builder created an NHL personal team, loaded real roster players and drafted
  A.J. Greer. The saved roster showed the correct player identity and eligible lineup-slot actions.
- Native PGA: current tournament `R2026528` loaded the Butterfield Bermuda Championship, live round metadata,
  leaderboard, Ben Kohles's round-one scorecard and actual shot descriptions/coordinates. The trace is explicitly
  labelled schematic, not an invented geographic map. This is not exhaustive testing of every event/tab.
- F1: Australian GP 2025 official classification resolved the exact archived session and showed Norris, Verstappen
  and Russell. Rich archive/live feeds were unavailable in that network session; no live-session success is claimed.
- EPL: real Chelsea/Everton match metadata and reported formation/bench loaded after resolving the actual kickoff
  timestamp. Presentation was corrected to soccer home-first order and human-readable status.
- Podcasts: a real Fantasy Focus episode advanced from 12s to 82s while backgrounded; system media pause stopped at
  82s. This establishes one service/media-session lifecycle path, not every codec, download or physical car receiver.
- Custom groups: created a two-channel collection, reordered it, removed TSN 5, then confirmed Channels and Guide
  filtered to TSN 1. The underlying playlist and provider XMLTV were unchanged.
- Widget: launcher pin confirmation worked and the score snapshot appeared. Tapping NE @ CHI opened the correct
  Patriots/Bears Game Centre. Deep links retained one app activity rather than accumulating instances.
- Manual backup: Android's document picker exported a JSON file to Downloads, restore preview/cancel/confirm worked,
  and the app retained its connected two-channel playlist. The actual exported test file contained a sanitized channel
  reference and hashed podcast bookmark, not a provider host or episode/stream URL. A signed-in cloud document provider
  is not configured on this emulator; remote-provider upload/offline/conflict behavior still needs testing.
  The final APK additionally exported and restored a mandatory-format/schema file containing the saved fantasy league
  and channel group, retained one copy of the group, and preserved draft progress through a cold process restart.
  A file lacking its mandatory format/version was rejected with no changes. Corrupt fantasy references are unit-tested.
- Dark Cast button contrast is fixed. No receiver hardware is present, so actual receiver handoff is not certified.
- No application `AndroidRuntime` crash was recorded in these release smoke checks. Host/emulator RAM constraints,
  synthetic local streams and short sessions are not production performance or reliability benchmarks.

### Installable 1.2 artifact

`dist/BannerTV-Android-Phone-1.2.0.apk`: 5,680,001 bytes, package `com.alexdiab.stadiatv.mobile`, version 1.2.0 (3),
minimum Android 8.0/API 26, target API 36. The minified public release passes APK v2 signature verification and 16 KB
zip-alignment checks. The generated release configuration omits the local odds API key. Signing is development-only.

SHA-256: `866003e273fb0d36932ba1a6c687d61ba6e5dd52a280f63427d38de74f91d7e7`.

Final release previews: [backup](docs/screenshots/cloud-backup-1.2.png), [native PGA](docs/screenshots/pga-1.2.png),
[launcher widget](docs/screenshots/widget-1.2.png).

### Remaining release requirements

See the execution ledger for unported iOS details. Also required: physical low-memory phones/API 26, network/decoder
stress and long sessions; actual live F1; complete PGA/course/round paths; authenticated ESPN/Sleeper; notification
delivery/reboot; download/video/queue and accessibility; Cast and Android Auto hardware. Play Console products/test
accounts and a production signing keystore are not available. Builds use the development key, not production signing.

## 1.1 validation record

Validation date: 2026-10-22. The detailed source comparison and honest remaining gaps are in
[IOS_DEEP_AUDIT.md](IOS_DEEP_AUDIT.md). This supersedes the original feature-entry-point parity checklist.

## 1.1 automated checks

```sh
./gradlew :core:testDebugUnitTest :mobile:testDebugUnitTest :app:testDebugUnitTest \
  :mobile:lintDebug :core:lintDebug :app:lintDebug \
  :mobile:assembleRelease :app:assembleDebug -PpublicBuild=true --max-workers=2 --no-parallel
```

203 tests: 198 pass, five optional real-provider/performance probes skipped, zero failures or errors.
New regression coverage includes local-calendar weekend/DST boundaries, lead changes and ties, independent efficiency
bars, normalized search ranking, retained/corrected play history, drives/probability, athlete starter/DNP identities,
native provider team identity, NBA coordinate validation/corrections and HTTP Retry-After cooldown.
All three modules pass lint with no errors; existing dependency, compatibility and style warnings are not treated as
proof of device performance. The optimized public phone release and TV debug regression builds succeed.

## 1.1 installable artifact

`dist/BannerTV-Android-Phone-1.1.0.apk`: 3,269,508 bytes, package `com.alexdiab.stadiatv.mobile`, version 1.1.0 (2),
minimum Android 8.0/API 26, target API 36. APK v2 signature verification and 16 KB zip-alignment checks pass.
Signed with the development key; public-build configuration omits the local odds API key.

SHA-256: `1428d5ca672207c5c5515d65a8957726ee0460551c78e62e83edb62e92edb431`.

## 1.1 UI checks and boundaries

The Pixel 10 ARM64 emulator was used with a two-channel local provider/XMLTV fixture and the official AndroidX H.264
transport-stream sample. No personal account or test provider is bundled in the app.

- Visually checked the rebuilt Home hero/section hierarchy and pregame NFL Game Centre with real ESPN metadata.
- Rendered video with the custom portrait controls and channel On Now/Up Next companion; checked pause and control lock.
- Checked the landscape stage and its channel-information side panel, keeping the retained player controller.
- Installed the final minified 1.1.0 (2) APK, confirmed it is not debuggable, and repeated Home/Game Centre, decoded
  fixture playback, pause, restart and portrait-to-landscape companion checks. No `AndroidRuntime` crash was recorded
  in that session. The test player, emulator and temporary local provider server were stopped afterwards.
- Final release previews: [Home](docs/screenshots/home-1.1.png), [Game Centre](docs/screenshots/gamecentre-1.1.png),
  [portrait player](docs/screenshots/player-1.1.png), [landscape companion](docs/screenshots/player-landscape-1.1.png).
- Native NBA mapping is regression-tested, but the NBA CDN returned HTTP 403 on this network. A successful real NBA
  shot chart is **not** claimed. No missing points, play history, drives or probabilities are synthesized.
- Physical-phone performance, all native sport flows, large-font/TalkBack gestures, OEM PiP, long-running playback and
  authenticated fantasy remain device-QA requirements. See the explicit sport-specific gaps in the deep audit.

## Earlier 1.0 validation record

Validation date: 2026-10-22. Repository reference commits and the feature-by-feature comparison are in
[IOS_PARITY.md](IOS_PARITY.md).

### 1.0 automated checks

Final verification command:

```sh
./gradlew :core:testDebugUnitTest :mobile:testDebugUnitTest :app:testDebugUnitTest \
  :mobile:lintDebug :app:lintDebug :mobile:assembleRelease :app:assembleDebug \
  -PpublicBuild=true --max-workers=2 --no-parallel
```

The unit suite contains 188 tests: 183 pass and five optional real-provider/performance probes are skipped because
their external sample files are not configured. There are no failures or errors. The phone tests cover clipped guide
geometry, provider URL validation and reminder timing. The shared suites cover playlist parsing, provider EPG matching,
lineup identity/grouping, sports repositories and the resilient transport engine.

Both phone and TV lint checks pass with no errors; warnings remain for pinned dependency/SDK versions, intentionally
allowed user-provider HTTP traffic, unused shared TV resources, the PiP transition animation recommendation and minor
style/performance suggestions. The optimized phone release and TV debug regression builds succeed.
The extracted `:core` module also passes its direct lint check.

### 1.0 installable artifact

`dist/BannerTV-Android-Phone-1.0.0.apk` at the Android project root: 3,187,584 bytes, package
`com.alexdiab.stadiatv.mobile`, version 1.0.0 (1), minimum Android 8.0/API 26, target API 36.
APK v2 signature verification and 16 KB native-library/page zip alignment checks pass.

SHA-256: `b421d0c9abffe9980a2226b3542b61a2a837056f982689aa68fe01a43848526b`.

The optimized/minified release APK was installed successfully and its Home, playlist persistence and provider guide
were checked again. Final release previews: [Home](docs/screenshots/home-dark.png) and [Guide](docs/screenshots/guide-dark.png).
Release playback rendered the same H.264 fixture. Manual pause remained paused after Home/background entry and return;
native resume updated the mini-player back to playing. The temporary test server was stopped after validation.

### 1.0 emulator smoke test

Pixel 10 emulator, Android 37.1, ARM64. Tested with phone-size portrait and landscape layouts. A local HTTP server
provided a two-channel M3U, XMLTV listings and the official AndroidX Media `sample_h264.ts` fixture as an HLS stream.
No personal/provider account or stream credentials are included in the source or APK.

- Completed the welcome/leagues/teams/playlist onboarding flow, including navigating to NHL.
- Entered a playlist using the on-screen form, validated it and finished setup.
- Checked Home, Following, Live, Discover and Settings; sports data and team logos loaded.
- Confirmed that TSN 1 and TSN 5 display different listings from the playlist's XMLTV.
- Checked the cable-style guide, channel selection, a match centre and its stream-availability explanation.
- Confirmed that an unrelated game does not get a false confirmed Watch button.
- Started the official H.264 fixture, rendered video and restarted the media request. The decoder reported the
  first rendered frame after approximately 3.1 seconds in this emulator session; this is not a device performance promise.
- Checked portrait player, fullscreen landscape, minimization/mini-player, stop, options and native Android PiP.
  Android reported the activity in pinned windowing mode with picture-in-picture enabled.
- Checked light and dark appearance. Fixed whole-row league touch targets, player system-bar contrast, selected-chip
  colors, native/custom pause-state synchronization and manual-pause preservation across background entry.
- No application `AndroidRuntime` crash was recorded during this smoke test. The emulator/host was memory constrained;
  this is not evidence of production-scale stream or device stability.

## Still required before store distribution

- Test the final APK on physical low-memory and current Android phones, including API 26, with large real playlists,
  long-running streams, codec variations, mobile/Wi-Fi transitions, provider concurrency limits and interrupted connections.
- Exercise authenticated ESPN Fantasy/private-cookie accounts, notification delivery and reboot restoration on real devices.
- Verify multiview decoder capacity, audio routing, Bluetooth/headphone changes, large font/accessibility settings and OEM PiP.
- Configure a production signing key, release versioning, store listing, privacy policy and provider/content usage requirements.
  The installable release APK is currently signed with the development key. Public builds omit the local odds API key.
- Review the documented iOS feature differences; full iOS parity remains unfinished, not an optional omission.
