# Provider guide cold-cache follow-up — 2026-10-26

830 passes the targeted cold-guide recovery check, not full iOS parity or all-device/provider
certification.825 full gates passed but its actual offline cold test still lost the listing;
read-only debug diagnostics identified incomplete-lineup compaction.830 fixes that startup path.
The preceding814 boundaries are in PLAYLIST_EPG_CONFIRMATION_PROGRESS.md.

## Latest830 verified checkpoint

Build70958 exited0 in14m29s:825 pass/five optional skips/zero failures or errors across830 cases
(core664/phone138/TV28). All-module debug lint and phone release lint have zero errors; existing
warnings remain core11/phone43/TV19+two hints. Debug/minified-phone/TV assembly, release v2 signature
and16-KiB alignment passed. Development-signed/unlocked/cannot-charge, not a production/dist release:

- Release `e8ac4cc266ee5a6bd4f256cc7493dca40028c2252045c9ede2bc731e77a411f7`
- Phone debug `6b06828b6052e531a867d56f0b071a3827199e877ffa9ab331c23ef30d99431e`
- TV debug `8cf801d0d959c7cf63f6827bd4ebbe32f95bc8dd4d1cff78321ddd64e6f9ead0`

The minified830 APK replaced the temporary diagnostic build with install-r. With BOTH networks
read back0 and font150%, actual cold launch restored Sportsnet One's Oct26 morning listings,
including6AM Minnesota–Vancouver and8AM Montreal–Winnipeg, without Refresh or a network fetch.
A second force-stop/relaunch repeated the same successful offline result: the full saved schedule
was not compacted away. These morning listings are not a live-game/video/individual-participation
claim; the fantasy badge did not decorate the morning re-air as last night's live fixture.

guide830-offline-cold-150.png and guide830-second-offline-cold-150.png were visually inspected.
Final non-playing PSS153,211KB/RSS303,536KB/zero reported swap, one Activity; targeted app/player
error entries empty with URLs suppressed. A snapshot/two cold runs are not a long soak, peak-memory
measurement, TV-remote/TalkBack/physical-device or all-provider certification. No media was started
in825/830; earlier real regular-feed restart observations remain explicitly scoped to814.

Original QA Hockey was reselected and Test Team/round2-pick2/A.J. Greer were read back unchanged.
Font1.0/Wi-Fi1/data1/540x1212/density210 read back restored. Both playlists and the separate three-
pick QA draft remain saved. App stopped/emulator63802 exited0; no Gradle/emulator overlap in full
builds, no unrelated file/cache cleanup, no commit/push/dist publication. The final APK hash was
rechecked after runtime. Broader parity and cross-provider XMLTV ownership remain open.

##825 actual runtime and830 startup gate

825 automatically read/matched the real provider guide without a manual Refresh, reaching2,219
channels. Its active current Minnesota–Vancouver programme showed the corrected white star/count
beside the leading time at150%; fantasy825-guide-contrast-150.png was visually inspected. An actual
offline force-stop/relaunch still showed No programme information; the screenshot
fantasy825-offline-cold-guide-150.png is failure evidence, NOT an offline-success claim.

The same-data debug825 APK was temporarily installed, without data clearing/root access, to inspect
only the guide file and safe Perf timings. Cached source signatures/credential URLs were never printed.
The valid binary cache had6,411 directory listings but only TWO actual schedules/190 programmes,
created2026-10-26T03:44:16Z during the cold launch. Sportsnet was absent. This is not a binary read-
ordering failure: bytecode reads keys before schedules, and all-column roundtrip tests pass.

Debug startup showed the first playlist's four channels analysed before the second playlist's21,937
channels finished loading/analysis. The pipeline was allowed to compact/save the full provider guide
against that first partial lineup. A later full analysis/refresh rebuilt1,313 schedules/121,624
programmes including Sportsnet One/East, created03:46:12Z. This read-only file evidence and the startup
timings identify an actual destructive partial-bootstrap path, beyond the concurrent publication risk.

830 waits for ALL saved channel caches and exact current channel analysis before any guide refresh.
Same-size old IDs/URLs/headers/guide bindings are rejected, not only count mismatches. A manual force
request made before readiness is queued until inputs are ready. Cached lineups remain usable while
their background network refresh runs or an unrelated playlist reports an error. Five constructed
readiness regressions cover these states; full830 gates and the repeated minified offline check
passed as recorded above.

The diagnostic app and emulator23606 were stopped/exited0 before830 compilation. The final runtime
returned to minified830 and restored networks/font/original selection as recorded above. No stream
was played in825/diagnostics; both playlists and rosters remain.

##825 build gates

Build5532 exited0 in6m22s:820 pass/five optional skips/zero failures/errors across825 cases
(core659/phone138/TV28). All debug/release lint tasks have zero errors; debug/minified-phone/TV
assembly passed, as did release v2 signature and16-KiB alignment. Development-signed/unlocked/
cannot-charge, no production/dist delivery:

- Release `df51413a03d2b46a973d2159e925e1335b0206c883b000ffb7fc863ff3167a4a`
- Phone debug `d49619bcd42ebb0d05e89bb8c4e0f04e5a43b3c25cdf156be76e6d9c19fd58ed`
- TV debug `a537abcd3019f7a6d2d1f65be3a01cccbf363e9f1126c399052ff6b9d901b16c`

## Actual814 observations

The minified app correctly labelled the misleading timed NHL event stream Channel-name match/no
EPG confirmation. With Sportsnet One programme data absent on a cold launch, the native Minnesota
Game Centre offered Find a stream, not automatic Watch. Settings reported1,796 channels with listings.
An explicit Refresh guide completed with2,219 channels; real Minnesota–Vancouver listing choices
then led Ways to watch. Repeated paths did not duplicate the event channel. This validates the EPG
confirmation fix, but not cold guide retention: force-stop/relaunch again lost the Sportsnet listing.

A bounded test explicitly chose the regular CA Sportsnet One HD feed, not the misleading event slot.
The source's real current playlist listing appeared in the player. Options → Restart stream was
invoked and decoded hockey frames resumed and advanced. Jerseys/arena footage were consistent
with the selected fixture, but no independent content-recognition, live-latency or score/video-sync
certification is inferred. The stream was force-stopped around11:19PM after roughly a minute.
PSS230,148KB/RSS395,504KB/zero reported swap, one Activity; targeted app/player error entries empty
with URLs suppressed. These are snapshots/short observations, not a long soak or physical-device test.

A read-only provider API check of only the two observed Sportsnet One names returned the same
SportsnetOne.ca ID for both. A surprising OneSoccer logo on the Rogers-named stream therefore did
NOT establish an app guide-binding bug. No provider account details/URLs/other channels are copied
here. The earlier wrong event-slot video remains a separate observed provider/name-confirmation issue.

Actual814 screenshots visually inspected: provider814-name-only-unconfirmed.png,
provider814-epg-sources-first.png (overwritten after real listings returned),
provider814-listed-feed-player.png, provider814-after-listed-restart.png and
provider814-listed-feed-later-frame.png under docs/screenshots. The image named
fantasy814-guide-contrast-150.png actually shows No programme information on cold relaunch; it is
NOT evidence that the revised badge contrast passed. That recheck remains pending.

Original QA Hockey selection was restored and its A.J. Greer/Test Team/round2-pick2 roster entry was
read back unchanged. Only the separate QA draft retains the three native test picks. Font1.0 was
restored; Wi-Fi/data/display remained at the previously read-back normal settings. App stopped and
emulator25266 exited0 before825 compilation. No data deletion, commit, push or dist publication.

##825 source under verification

- Bootstrap cache restore now shares the refresh mutex, and is skipped after a fresh publication.
  Old cache and fresh load no longer publish snapshot/directory/identity state concurrently. This
  addresses a real source race; it is not yet proof that this race caused the observed cold failure.
- A superseded refresh uses its own coroutine Job for cancellation, checks it while downloading/
  parsing, and checks again before publishing. It cannot commit a cancelled partial parse as success.
- Explicit Refresh guide bypasses both the source-file TTL and the HTTP cache's unvalidated fresh
  entry. Network validation can still legitimately reuse server-validated bytes. Routine loads retain
  their TTL; failures keep the prior complete file and report saved fallback instead of fresh success.
- Provider source cache file names use SHA-256 of the URL, not Java's collision-prone32-bit hash.
  Constructed Aa/BB URLs with an identical old hash now hold separate files. No credentials in names.
  New keys require a fresh source fetch; old raw source cache names are not trusted as a new owner.
- A fully failed same-signature reload retains an existing populated snapshot and its original time;
  it does not overwrite it with an empty result. No retention is claimed across changed ownership.
- Phone settings now show guide load/offline-copy errors, including a failed binary-cache save.
  Previously that save failure was silently ignored. The cache-save notice contains no raw exception URL.
- Eleven focused unit cases passed against real temporary files and the project's installed OkHttp
 5.3.2: normal TTL/explicit refresh, HTTP-cache network validation, original-time retention on503,
  declared/chunked limits, hash collision isolation, cancellation cleanup, future file timestamps,
  compressed bytes/private names, two-channel Unicode/all-column binary roundtrip, replacement and
  truncation rejection. Full suite now825 cases (820 pass/five optional skips), full gates passed;
  actual runtime findings and the830 follow-up are recorded above.

Official SDK documentation retrieval was unavailable (official-site404/empty searches/tagged-source
lookup failure); no third-party blog was used as API authority. Installed-version mock-server tests
establish actual request/cache behaviour. The targeted repeated minified offline-cold check now passes;
broader reconnect/device coverage and source-scoped XMLTV ID ownership remain open. No third-
party EPG, fabricated listing or injected runtime fixture was added.
