# Home and Following — Android phone 1.7

Source comparison: `alexdiabmusic-stack/StadiaTV-iOS` commit
`8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`, inspected through the connected GitHub repository.
These source files describe application behaviour, not instructions or guarantees of iOS runtime correctness.

## Source details translated

- `MyApp/MatchesViewModel.swift`, `loadFollowing`: favourite-team leagues are filtered to the selected teams;
  explicitly followed competitions **without** a favourite team include all fixtures. This is not Home's broad
  discovery fallback. Android previously used `settings.inScope`, showing the entire catalog with no explicit
  league choice, and every team in a favourite league. Following now has its own pure, league-scoped selection.
  Like iOS, choosing a team narrows that league even if the league was also selected. The team editor can remove
  individual teams when the desired scope is the whole competition.
- `MatchesView.swift`, `mainScroll`: live/today/week summary, first-live-or-next-announced hero, six coming-up
  events, sport selection, local-date groups and a full announced schedule. Android retains its useful
  Yesterday/Today/Tomorrow views alongside the new Overview, rather than removing recent results.
- `FollowingStandingsSnapshot` / `FollowingStandingsPanelView`: selected-team/league context, first favourite
  fallback, the relevant group, actual original row ranks and the followed-team highlight. The Android window
  stays at up to five rows even near the bottom of a group; it never resets the displayed rank to one.
  Golf/tennis aren't forced into team standings. Full table opens the actual Standings tab (F1 Championship),
  not the default Scores/calendar route. F1 rows do not navigate to a fictional team roster.
- `MatchesView.swift`, `loadNews`: bounded league requests, partial results, publication order and duplicate
  IDs/headlines. Android additionally follows the selected entity for the news scope and respects existing
  source mute/story hiding. Cards retain reader, save, hide and publisher actions.
- `HomeView.swift`, `YourSportsDayCard` / `SportsDaySingleGameCard`: separate zero/one/multiple states;
  one game now has opposing 44dp crests, status, score/start time, a followed-team marker and state-specific
  actions. Watch live appears only with a confirmed provider match; pregame has a real persisted reminder
  and separate Match info action. Final spoiler protection hides both score and winner badges.
- `FavouriteChannelsRail`: Home and Following resolve favourites in their saved insertion order, not playlist
  order. Existing persisted sets retain their available iteration order; this does not add drag reordering.

## Data and performance

- The shared feed now requests 30 days for followed/favourite leagues, matching iOS Following's horizon.
  This fast month lane doesn't wait for all chunks in the favourite-team 365-day lane. Its first ESPN month
  request is identical to the season lane's first chunk, so existing HTTP coalescing/cache avoids duplicate
  wire requests. The month lane is limited to three concurrent league loads; HTTP TTL/body bounds still apply.
  Provider publication and event limits can return less than a full season; the schedule says so.
- The foreground feed task is keyed by the followed-team/league signature, as iOS's preference-keyed task is;
  editing follows requests fresh schedules instead of waiting for the next 30/60-second interval. If an older
  preference signature is already loading, this caller waits for its lock and rechecks current preferences/cache;
  it doesn't silently lose the reload. Timer/manual requests still coalesce while a load is in progress.
- Phone-only snapshot caches retain at most eight keys each, coalesce duplicate requests, limit each cache to
  two concurrent requests, enforce deadlines and cancel evicted work. Generation identity prevents cancelled
  old requests from replacing newly requested data for the same key. Successful data survives a refresh error.
- Snapshots load when their lazy section is reached, not at app startup. Five-minute cache TTL, one-minute
  automatic error cooldown, explicit refresh/retry and checked timestamps avoid repeated scroll-triggered
  network loads or a silent permanently spinning section. News has at most three league requests and an
  eight-second per-league deadline, with a 12-second overall deadline.
- Following's clock updates every 30 seconds; local calendar boundaries, not fixed 24-hour arithmetic,
  determine day/week counts across daylight-saving transitions. Rows and schedule use stable event keys.
- No provider credential, sample playlist, synthetic game or constructed standings result is bundled in the app.
  New synthetic cases live only in unit tests. EPG matching/playback continues to use the user's playlist data;
  ESPN headlines and standings do not replace the playlist guide or confirm streams.

## Remaining differences

This checkpoint is not full iOS parity. Curated Home event promotions, the recent-highlight rail, staged motion,
fantasy live context/toasts, Stats overview and broader native sport/provider details remain in the execution
ledger. Following's iOS historical league-coverage classifier/collapse is **not** implemented here: lack of a
current confirmed stream is not sufficient evidence to label a league absent from a playlist. Its 14-day,
10-observed-game evidence window needs a separately persisted/validated port. No leagues are hidden on that
unsupported inference. Manual hidden-game preferences are still honoured by these screens.

Source/UI comparison, offline tests and short emulator checks are separate kinds of evidence. See
[VALIDATION.md](VALIDATION.md) for measured build/runtime results and the installable artifact. Physical phone,
Firestick/remote, TalkBack, font-scale, low-memory and live-update stress testing remain necessary.
