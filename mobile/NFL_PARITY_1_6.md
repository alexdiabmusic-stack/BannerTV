# Native NFL calendar and play detail — 1.6

Reference: StadiaTV-iOS main rechecked at `8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`.
Sources traced: `NFLCalendarView.swift`, `NFLEndpoint.swift`, `NFLProvider.swift`, `NFLGameMapper.swift`,
`NFLPlayMapper.swift`, `NFLGameState.swift`, shared `FootballPlay.swift` / `FootballFieldView.swift`,
`NFLPlaysView.swift` and recorded NFLCoreTests fixtures. Source files establish behavior, not hardware reliability.

## Calendar

NFL → Schedule now has the native season/stage/week calendar rather than only a generic seven-day feed.
The initial selection comes from NFL's date-to-week endpoint in America/New_York, not an ISO calendar week or a
guess from the current month. If that request fails, Retry and explicit manual browsing remain available.
Season, preseason/regular/postseason and week controls use announced provider choices, including Wild Card,
Divisional, Conference Championship and Super Bowl labels. Announced bye teams remain distinct from game rows.
Each game retains its localized kickoff date, year and time, including completed results. The native Overview reserves
its quarter-score loading slot so the arriving table does not get inserted above the preserved visible list anchor.

Weekly calendar requests omit the drive chart and other heavyweight extras; opening a game fetches full detail.
Changing controls cancels the prior screen request, clears mismatched games and validates the returned week.
Explicit Refresh invalidates the relevant local body cache, but never bypasses provider cooldown/Retry-After.
The shared NFL client uses its existing public anonymous web-application flow, at most one rejected-token refresh,
bounded requests and memory-only tokens. No NFL user account is created or personal token bundled.

Native matches carry explicit provider/game/week identity and a namespaced app ID. Neither a native game UUID nor
a native team UUID is sent to ESPN. SportsRepository and EspnApi both guard this boundary; the phone skips its
universal summary request for these matches. Native calendar game pages use their full native feed directly.
Reminder/deep links resolve the namespaced game through NFL's date/week/schedule, not ESPN. Missing native link dates
are rejected instead of silently searching today's schedule. The unmatched Hide from Home action is omitted for
native calendar pages until cross-provider game identities are reconciled.
Until team identities are explicitly reconciled, their team-header links are disabled rather than opening an
unrelated ESPN team. Global Home scoreboards/standings/team/player routing are not claimed as fully native NFL parity.

## Play detail

- Authoritative snapshots rebuild corrected/deleted plays and preserve unknown participant stat codes. Participant
  identities are resolved against the season roster when available, otherwise use the provider's GSIS name. The
  provider's play description is preserved, not rewritten into an invented account of who did what.
- GSIS codes disambiguate missed field goals/extra points, incomplete passes, interceptions and fumbles. Touchdown/
  safety scoring types have priority. A fumble is not automatically labelled a turnover without the relevant stat.
  Unknown future play types remain generic Play with their raw kind available in details.
- Native drive cards expand/collapse into lazy play rows. The current incomplete drive expands automatically;
  completed/scoring drives do not masquerade as a current drive. Scoring/Turnovers/Penalties filters use the structured
  flags and include text-declared penalties. Filter selection expands matching drives for visibility. Plays whose
  drive is missing remain visible under Other plays rather than disappearing.
- Rows show type, quarter/clock, actual down-and-distance/ball position, first-down and turnover flags, description
  and expandable participant names/reported gain. Missing distance is a dash, never an invented Goal situation.
  New plays can wait behind Jump to live while reading history; corrections/removals still apply. The shared timeline
  policy retains the earlier NHL behavior and uses no forced jump animation.
- Quarter scores come from reported period values. Unused zero overtime columns are omitted unless overtime is
  explicitly active/final or has reported points. Missing cells remain dashes, and final games clear stale live
  possession/field/down context. Missing full-play refreshes retain the previous event/stat snapshot.

## Evidence and boundaries

Seventeen new core tests and one phone date/time test cover the recorded NFL game/weeks and constructed identity, status, code/type conflicts,
corrections, missing values, overtime, partial-feed, auth-refresh, cooldown and request-route cases. Provenance is in
`core/src/test/resources/parity/NFL_FIXTURES.md`; authentication responses are never fixture data or APK assets.
Measured release/runtime outcomes belong in VALIDATION.md, not inferred from unit tests.

Still open: complete native NFL team/player profiles/biographies, scoreboard/standings routing and disk snapshots;
broader live/corrected play/drive cases, authentic provider failures and physical-device/performance/accessibility tests.
This NFL schedule browser is not automatic synchronization with the user's phone calendar. That separate platform
parity item remains open, along with the broader cross-platform work in PARITY_EXECUTION.md.
