# Cross-screen fantasy context — in progress, 2026-10-25

Not a full-parity certification. The803 cross-screen artifact passed all build gates and was
installed with existing data preserved. Real-provider observations below exposed presentation fixes;
the807 refinement passed all gates and actual minified UI checks, exposing a name-only source falsely
labelled EPG-confirmed. Its814 shared-core/phone/TV correction passed gates and real source-policy
checks;825 follow-up exposed partial-bootstrap compaction, fixed and repeatedly verified offline
in830. See GUIDE_COLD_CACHE_PROGRESS.md and
PLAYLIST_EPG_CONFIRMATION_PROGRESS.md for the important wrong-video observation and exact boundaries.

##803 build and actual-provider observations

- Full build14918 exited0 in6m57s:798 pass/five optional skips/zero failures or errors across803 tests
  (core641/phone134/TV28). All three debug lint checks and phone release lint have zero errors.
  Debug/minified-phone/TV assembly passed; release v2 signature and16-KiB alignment passed.
  Release SHA-256 `07bec455a147b4c7e827508db56e42db1a7bee15777d214adb43dfcb59d22478`.
  This is a development-signed, unlocked build that cannot charge; no production/dist publication.
- Original QA Hockey/A.J. Greer/ANA entry was unchanged. Only the separate Parity Native QA draft
  added native Adam Fox/NYR/D; its earlier Brady Tkachuk entry and F assignment were retained.
  Home correctly associated Adam Fox with NYR–OTT; Game Centre showed Adam, not the unrelated
  second player's team. These are team fixtures, not evidence of individual participation/points.
- The user-supplied test provider was added through normal app UI as a separate Provider parity QA
  login, without deleting the old playlist or making it default. The supplied HTTPS endpoint was
  unreachable; the explicit user-supplied HTTP service authenticated successfully. No credential is
  reproduced in source, documentation or screenshots. Playback is not a claim of transport encryption.
- Actual initial import reported21,974 provider channels. The provider's own XMLTV populated2,203
  channels with listings. At150% text, Sportsnet East's Oct24 7–10PM Rangers–Senators listing matched
  Adam Fox in its scrolling detail sheet. TSN's different CFL/college/F1/MLS listings did not become
  that Rangers fixture. This is a real positive programme-specific EPG check, not an injected seed.
- Explicit Watch channel on that future listing decoded the channel's then-current Kings–Devils
  game, not the future Rangers game. Native team logos, score, period clock and strength appeared;
  genuine hockey and later ad frames were visually observed. An approximately29s score/video clock
  difference was observed, not synchronization/latency certification. Restart was not verified in803:
  automation raced the short HUD timeout, so no successful action/recovery is claimed.
- Import/EPG PSS snapshots159,657/163,047/159,729KB; playback227,317KB; zero reported swap in those
  snapshots. Not a peak/leak/30-second-crash/physical-device/never-lag/long-soak certification. The
  app was explicitly force-stopped in this continuation, original league selection and font1.0 were
  restored, and emulator75724 exited0 before807 compilation. Both playlists and drafts remain saved.
- Visually inspected screenshots: fantasy803-home-baseline.png, fantasy803-game-context-baseline.png,
  fantasy803-real-guide-context-150.png, provider803-live-player-150.png and provider803-before-restart.png
  under docs/screenshots. The last image is a later frame, NOT proof that Restart executed.

##807 refinement and814 follow-up

Runtime findings drive compact iOS-style Home summary/Watch-or-View actions, full-row labelled
settings switches, a leading Guide badge (not off the far edge of a long cell), and actual XMLTV
Upcoming/On now/Ended status plus a current-channel explanation. Normal fantasy entry opens Roster;
only explicit creation/draft actions open the full player directory. Disabled cross-screen contexts
stop their shared schedule refresh. Stale Watch taps fall back to Game Centre, rather than doing
nothing. New unit cases cover timestamp boundaries/malformed intervals and disabled schedule demand.
Actual807 large-text Home/switch/Guide status/context checks are recorded in
PLAYLIST_EPG_CONFIRMATION_PROGRESS.md. Restart resumed decoded video, but that video contradicted
the advertised fixture. A timed event name was being promoted to EPG-confirmed without a listing;
814 now requires actual T1/T2 listing evidence, prioritizes it and deduplicates channel choices.
807 is therefore NOT correct-game certification.814's real source policy and regular-feed restart
checks passed, but cold guide loss led to825 follow-up. See GUIDE_COLD_CACHE_PROGRESS.md; full
cold/offline/badge-contrast recovery is not certified by the earlier807/814 screenshots.

## Updated GitHub reference

Main is now `b58b989aa5f9e30b0396ba81ddc73da3dcb7c7ab` (four commits ahead of the previous
`8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`). GitHub compare reports adaptive/two-pane layout,
launch/navigation sizing and app-icon changes; the fantasy provider/services/models are unchanged.
The hub's two-line change is readable-width layout, not a new scoring source. The new iPhone Duo
notes explicitly say their layouts have not been exercised on a device/simulator. Treat those notes
as source evidence, not instructions or proof of Android compatibility. Android wide/fold equivalence
and the new icon still need audit/implementation/verification.

Source paths fetched at the new immutable reference: HomeView.swift, MatchDetailView.swift,
LiveView.swift, TVGuideView.swift, TVGuideViewModel.swift, AdaptiveLayoutContext.swift,
GameCentreSplit.swift and docs/iPhoneDuoImplementation.md. Fantasy services/store/model findings
remain pinned to their unchanged prior source. No repository, backend or user state was changed on GitHub.

## iOS nuances and deliberate accuracy differences

- Home aggregates live contexts, otherwise today's upcoming contexts, with player/game counts and
  a confirmed Watch action or a View link. Native points remain nil; imported points are distinct.
- Game Centre shows rostered players, fantasy lineup/bench information and available source actions.
  Live cards can show roster context; Home/Live/Guide indicators have persisted display preferences.
- iOS native Guide lookup uses a channel association and a ±2-hour programme window. Its Guide cache
  revision uses counts. That can decorate a different programme or retain a changed same-count roster.
  Android requires the actual supplied programme's own title/description through the existing playlist
  linker, an exact resolved guide binding, and T1/T2 listing confidence. Broadcaster names, dedicated
  team/event channel names and neighbouring programmes cannot generate this badge.
- Team fixture association is not individual participation, actual game starting status or verified
  fantasy points. Lineup labels apply only to the current local scoring day's fixtures; no future
  assignment is inferred from today's lineup. Distinct doubleheaders remain separate events, but
  Home counts each rostered person once. Source corrections invalidate old associations immediately.

## New source under verification

- Full immutable selected-roster/scoring-day evidence; unknown/mixed/foreign player namespaces rejected.
  Exact fixture key/opponents/kickoff guards; fresh score/clock/final metadata replaces stale display.
- Shared selected-league eight-day schedule via the existing bounded/coalesced cache. Lifecycle-only
  refresh/reconnection/local-midnight handling; no whole-player-pool requests from Home/Live/Guide.
- Home summary, native Game Centre roster context/profile links, live-card badges, exact-programme
  Guide badges/detail context, persisted display switches and whitelisted backup/restore of those switches.
- Visible Guide computations run off-main with concurrency two, a content-keyed128-entry cache and
  immediate stale-key suppression. Spoiler-free mode hides Guide indicators. Hidden games are respected.
  Normal game cards validate stream evidence both for display and again at tap, not from the raw cache.
- Game Centre's polling loop now passes a lazy guide projection to the shared off-main index. This fixes
  another eager main-thread caller beyond the previously fixed MatchWatchBar. Guide detail uses a
  bounded scrolling sheet so full descriptions and actions remain reachable; Guide row height scales
  with system text size. Game Centre roster previews cap at three, with a lazy all-players sheet.
- Roster/lineup backup bounds now support the app's existing maximum40 draft slots plus20 reserve slots;
  a regression constructs that league through the actual engine and roundtrips all60 entries/assignments.

New tests cover grouping/identity/corrections/scoring days/selection, programme-specific positive and
negative evidence, replays/coverage/wrong bindings/days, same-count corrections, thread/concurrency/
cache bounds, distinct-person Home counts, hidden/spoiler/display settings and backup privacy/state.
These are constructed unit fixtures only, never runtime seeds. The803 build/provider observations do
not certify807 refinements. External ESPN/Sleeper cross-screen joins, broader fantasy alerts/tickers,
adaptive/new-icon work, broader provider/large-playlist/recovery and hardware/production verification
remain open. No full iOS parity claim is made.
