# Native fantasy fixture/context continuation — 2026-10-24

Reference: `StadiaTV-iOS` main `8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`:
`MyApp/Fantasy/BannerFantasyStore.swift` (`refreshEventContexts`, matching/opponent/indexes),
`FantasyEventLinker.swift`, `BannerFantasyServices.swift` and `BannerFantasyModels.swift`.
This is not a full-parity/completed-fantasy claim.

## New source

- Selected native roster entries link to actual team fixtures through the configured sports provider,
  independently of whether that league is followed on Home. The bounded/coalesced phone schedule
  cache now supports a distinct eight-local-calendar-day query, while league browsing remains seven
  days by default. Only the visible nonempty roster refreshes its schedule, at the existing two-minute
  cache cadence/lifecycle boundary; failures retain the last good snapshot and original request time.
- Matching is league-scoped, uses existing exact abbreviation aliases, rejects missing/ambiguous
  ownership and contradictory explicit legacy player-sport prefixes, and never joins numeric player
  IDs to native game/athlete IDs. Exact/cross-provider duplicate handling reuses the shared guarded
  feed merger. Distinct/ambiguous doubleheaders survive. Live then upcoming fixtures precede completed
  ones; a morning completed doubleheader still locks that scoring day's lineup.
- Roster cards expose opponent/local full date, reported state, native-safe Game Centre, all fixtures
  and the existing playlist-EPG watch/source controls. A completed fixture cannot launch a current live
  channel. No third-party EPG or fantasy point estimate is introduced. Actual off-day/unpublished
  schedule and source failure are distinguished rather than silently labelled zero/no game.
- Saved-roster opening no longer fetches every club's player pool. The explicit player section still
  uses its established legacy ESPN IDs until a safe saved-roster native-ID migration is available.
- Inspection found `StreamAvailabilityStore.ensure` could build/rank synchronously on its caller's
  UI thread despite the class comment describing background work. It now dispatches to Default like
  the batch path; phone watch controls also move guide-argument preparation off-main. A thread test
  checks the real immediate path; another ensures finals never build a live-channel index.
- Follow-up source makes guide projection lazy under the shared lock (one per guide/playlist index,
  not per teammate/card). Event-cache identity now includes kickoff/opponents/broadcast evidence;
  corrected fixtures hide old confirmations before asynchronous re-linking. Phone effect identity
  follows those linking inputs too. Same-ID playlist URL/header changes rebuild channel references,
  so an old expiring playback URL cannot survive simply because the channel name stayed unchanged.
  Constructed regressions cover projection reuse, rescheduling, opponent correction and URL/headers.
  Final/invalidation hiding is checked too. A new identical-guide rebuild assertion caught a real
  StateFlow conflation regression (equal maps can retain an older equal object); evidence comparison
  now uses value equality after verifying the current event, not object identity. The first guard
  refinement build failed that test (expected1 confirmation/got0); corrected verification passed.
  No failing artifact was installed or delivered.

## Source boundaries and remaining work

The inspected iOS `ESPNSportsDataProvider.statLines` returns an empty dictionary; its linker likewise
does not provide reliable individual live fantasy points. Android does not manufacture that data.
Previous-season roster-stat ranking, full matchup scoring/periods, native player-pool migration,
cross-screen fantasy indexes/guide badges, drive ticker/toasts, and larger roster/performance/device
coverage remain open. The upstream development sample-player fallback is not used as runtime data.
Existing immutable add/drop/replace safety is retained rather than copying mutable-before-validation
source behavior.

Latest752 source passed747 tests/five optional skips/no failures, all zero-error debug lint,
release-only lint and debug/minified-phone/TV builds (5m13s). Real minified runtime verified the saved
unchanged ANA roster entry's PHI/live and VAN/BUF future fixture sheet, native NHL Game Centre,
Back/150% retention, original offline schedule time and automatic reconnect request recovery.
The existing test playlist was honestly unconfirmed; no actual stream was started, and team fixture
context is not individual participation/scoring evidence. Two screenshots were inspected, targeted
app/player logs were empty, settings restored and emulator exited0. Exact hashes and bounded PSS
observations/limitations are in VALIDATION.md. Full parity remains incomplete.
