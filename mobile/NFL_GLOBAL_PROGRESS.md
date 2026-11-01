# Native NFL global routing — active parity continuation

Source: `alexdiabmusic-stack/StadiaTV-iOS` at `8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`.
This is working source, not a parity-completion or delivered-APK claim.

## Source traced

Read the complete `NFLProvider`, `NFLEndpoint`, `NFLShieldClient`, game/legacy/statistics mappers, game-state
model and game-centre service/view-model/main/box UI. These are evidence, not additional user instructions.

## Implemented in this pass

- Global native NFL scores, schedules, teams/history metadata, division standings, rosters, typed player information
  and paginated injuries share the existing anonymous Shield token client. No account/backend/secrets were added.
- Game/team/player IDs are `nfl:`-qualified. Native failures cannot fall through to ESPN player/team endpoints;
  old bare ESPN IDs keep their original route. Saved NFL follows use scoped abbreviation/franchise aliases.
- Native calendar team buttons and game-box player links can now open native profiles. Score corrections recompute
  winners, foreign native game/team identities are rejected, and legacy numeric game links retain ESPN resolution.
- Long schedules advance only at source-supplied validated `dateBegin`/exclusive `dateEnd` boundaries; missing or
  invalid boundaries fall back to actual date lookups, never guessed season/week arithmetic. Native requests are
  concurrency-bounded and long schedules have a total timeout. Explicit dates filter the real local calendar day.
- Standings use complete reported division ranks, otherwise retain provider order, and expose NFL provenance. Missing biography/statistics remain
  absent; roster information is labelled as player information rather than made-up season performance totals.
- Injury cursors use the iOS-verified `pageToken` field, not `token`. Repeated/oversized cursors and incomplete or
  excessive pagination fail without presenting a partial report as complete. Aggregate people/injury rows are bounded.
- The provider retains one compact roster/metadata season, whitelisting UI fields. The game-detail client separately
  keeps one small name directory instead of repeatedly decoding the entire roster on live refreshes. Bearer request
  metadata uses `Cache-Control: no-store` so it does not enter the shared HTTP disk cache.
- Atomic public-game disk snapshots recover native cold links, score/plays/box and original separate summary/detail
  times. Retention is limited to 12 games/24 MiB/4 MiB per game/30 days; IDs, teams, timestamps and schema are checked.
- Lightweight scores update independently of a rich-drive outage. Rejected game/team IDs, older offsets and final
  rollbacks cannot replace accepted data. Missing drive packages keep old details without claiming a new detail time.
  A final summary attempts rich reconciliation once; manual Retry requests rich details immediately. A failed optional
  player-name directory does not discard a valid drive package.
- Source-derived six-second live/twelve-second rich cadence, halftime/interruption/pre-game intervals, bounded failure
  backoff and stop-after-final are wired into the foreground phone screen. A validated default-network transition
  wakes recovery without keeping an offline polling loop. Callback arguments are authoritative and callbacks are
  released after the screen stops collecting, following the official Android connectivity guide.
- NFL box score now uses all reported categories and full, wrapping player/stat cards with native profile links.
  Quarter scores retain pinned team/totals at large text sizes. Saved/offline state, original score/detail times and
  a contextual Retry notice are visible. Injury rows are now lazy, cached/retryable and show source/checked time.
- All phone game-cache mutations (including late native/extras completions) now share an eight-game retention limit,
  protecting the visible and playback-linked games. Access ordering is kept separately from StateFlow map equality.
- Newer source publishes saved NFL identity/details before network refresh, not after an outage timeout. Calendar
  links retain the actual game's season/stage/week; that seed is part of navigation-state identity. Live-delayed data
  keeps its accepted score and original time. Pregame placeholders do not masquerade as played 0–0 quarter scores;
  halftime/suspension cannot advertise an active drive, and postponed/cancelled/final games stop automatic polling.
- The source-traced Game Centre premium content boundary now covers team-sport details and F1 timing, without
  gating public scores, playlist watching or historical NFL calendars. PGA stays public as in its source view. Default
  builds remain unlocked/no-checkout; this is not licensed purchase-flow verification or a fabricated backend.
  Final results no longer offer an old live-stream button, but retain game actions; native NFL hiding is reachable.
  F1 gains the shared playlist-watch bar, with source state/guide revisions and published completion status.
- Shared native failure isolation is now capability-scoped, not league-wide. Standings success does not erase a score
  failure, and score failures do not disable standings. A validated network change resets I/O outages, never HTTP or
  non-I/O backoff; provider-local 403/429 cooldowns remain intact. Foreground feed loading wakes on reconnection and
  stops offline polling without erasing accepted games/times. Seven routing tests cover these boundaries.

## Verification

The latest combined unit suite reports 482 tests, 477 pass, five optional probes skipped, no failures/errors; current
three-module debug lint and phone-debug assembly pass in 2m40s. The 468-test tests/lint/minified release/TV-debug command passed in 6m06s with zero
lint errors (core 11/phone 43/TV 19 warnings, plus two TV hints). Native NFL tests cover identity/routing, biography,
date filtering, announced-week traversal, actual standings fields/ranks, cursor pagination, foreign responses, winner
corrections, roster-name reuse and fourteen cache/recovery cases. Phone tests cover polling/backoff, network transitions,
every-write retention, calendar/delayed-state identity and stat summaries. They use local sockets, the recorded game
and explicitly constructed cases, not injected runtime fixtures.

The earlier 449-test debug runtime loaded 32 NFL teams, the Arizona roster with NFL provenance, Budda Baker's native
in-app profile/typed biography and player-information header. Native AFC East standings loaded; that session exposed
alphabetical ordering, which prompted the reported-rank correction above. Injuries loaded, but that older screen did
not expose provenance; it is not proof of native injury routing. Logs contained no app/player errors; original network
and font settings were preserved. Screenshots: `nfl-native-profile-continuation.png` and
`nfl-native-standings-continuation.png` (the latter is deliberately the pre-rank-fix evidence).
The exact 468-test minified APK subsequently passed real native 20–24 Dallas/Philadelphia quarter/play/box/profile,
150% text/pinned-quarter scrolling, cold offline disk recovery with original times and automatic reconnection refresh.
After the outage, standings initially showed an explicit ESPN fallback; a fresh online process verified actual native
NFL ranks BUF/NE/NYJ/MIA and NFL-labelled injuries. That observation prompted the newer shared failure-isolation work.
No app/player errors were logged; original network/text settings were restored before emulator shutdown. Full evidence
and scope limits are in `VALIDATION.md`. The newer cached-first/calendar/gate/failure-isolation refinements still need
runtime verification and are not included in the minified hash below.

## Still open from the newly rechecked iOS sources

- Broader live drive/summary corrections, interruption/final transitions and sustained network/performance QA.
- Newer cached-first/calendar/gate/network-isolation runtime and minified checks.
- Physical-device verification; the owner confirmed there is no physical Android device available yet.
- Wider premium-surface audit and licensed purchase flow (default builds are explicitly unlocked; production billing is not ready).

The current minified workbench release is still 1.7.0 (8), development-signed and public/unlocked; SHA-256
`c72f90982070a6f4afa37807f45e1a7f476a9d3fbd3bdd76f951851c40a60c41`. Its v2 signature/16 KiB alignment pass.
This hash's bounded NFL runtime checks pass as described above; newer source refinements are not retroactively covered.
No `dist` file was overwritten and no GitHub push was performed.
