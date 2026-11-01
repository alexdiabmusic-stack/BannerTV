# Player and Premier League parity — 1.4

Reference: `StadiaTV-iOS` main rechecked at `8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`.
This closes specific gaps, not full iOS parity.

## Player behavior translated

- `Playback/PlaybackController.swift` and `Preferences.swift`: save the preferred **audio language**, not a provider's
  array index. It applies on later channels, restarts, reconnects and player recreation. Automatic restores platform/
  provider defaults. Same-language variants can be selected for the current source; those particular variants are not
  promised across different streams. Missing languages fall back rather than disabling sound.
- BCP-47 region/script tags and ISO-639 two/three-letter aliases are normalized. The new setting defaults safely in old
  settings/backups and is included in the existing sanitized manual cloud file. It contains no stream URL or credential.
- Subtitle preference changes now check the desired language, not merely whether some subtitle is already selected.
  A manually chosen same-language track remains selected. Track-menu IDs include the source generation, so a stale
  menu cannot apply a reused group/index in a later stream. Current support and indices are checked before overrides.
- `Playback/VideoNowPlaying.swift` / `OriginalVideoSize`: Original uses physical display pixels, never upscales a known
  picture and reduces only to fit. Unknown dimensions fall back to Fit; anamorphic pixels retain their aspect ratio.
  The picture remains centered in the same retained player. Native PiP, TV's scaled canvas and OEM surface behavior
  still require hardware checks.
- The four visible choices are Fit, Fill (crop), Stretch and Original. Legacy stored enum names are intentionally kept:
  Android's old `Fill` value still means stretch, and old `Zoom` still means crop. Existing per-channel choices do not
  silently change effect. The description under the choices explains the current mode.
- Startup recovery respects manual pause, including before the first frame. Resuming rearms startup checking; playing
  audio-only sources are not mistaken for a failed video startup.

Official [Media3 track-selection guidance](https://developer.android.com/media/media3/exoplayer/track-selection)
explains why language constraints can cross media items but exact track-group overrides cannot.
The [PlayerView](https://developer.android.com/reference/androidx/media3/ui/PlayerView) and
[VideoSize](https://developer.android.com/reference/androidx/media3/common/VideoSize) contracts inform sizing and pixel-ratio handling.

## Native EPL resources translated

From `EPLEndpoint.swift`, `EPLEventMapper.swift`, `EPLStatKeyMapper.swift`, `EPLMatchMapper.swift` and
`EPLGameCentreService.swift`:

- Events are home/away **buckets** of goals, cards and substitutions, not a flat list. Structured identities include
  match, side, kind, player, timestamp and ordinal. Corrected minute/name text does not duplicate the event. An explicit
  complete empty snapshot can remove withdrawn native events; an unavailable or mismatched response retains data.
- Native team identities are checked separately from ESPN identities. Display attribution is mapped to the app's team
  IDs. Lineups supply player names; missing references are batch-hydrated. Unresolved players remain explicit IDs.
- Provider timestamps control ordering; untimed incidents use a deterministic minute insertion policy without a
  non-transitive timestamp/minute comparator. Added-time labels are preserved. Unknown incident types stay visible,
  without being guessed into scoring events. Scores/clock always come from match metadata, never event counting.
- The native statistics array is keyed by `teamId`, independent of order. The translated Opta keys cover possession,
  xG/xGOT, shots, chances, passing, defending and discipline. Missing/invalid values remain absent or a dash, not zero.
  Actual zero values remain zero. Statistics/events identify their chosen source in the UI.
- Lineups, commentary, events, statistics and player hydration fail independently with bounded timeouts and per-surface
  cooldowns, including Retry-After. Failed secondary feeds cannot discard valid match metadata or other sections.
  Announced lineups use a longer cache TTL. Retry actions do not bypass provider cooldowns.
- The native table is a separate on-demand resource. `live=true` is requested only for live matches and labelled
  provisional. Other states request the published table for the match's season, not an invented table at that match's
  historical minute. Reported positions, points and deductions remain distinct; deductions are not subtracted twice.
  Refresh/error handling and saved snapshots are independent of the main game feed. No event-based table is fabricated.

## Boundaries

See [VALIDATION.md](VALIDATION.md) for measured checks. Unit inputs describing unusual events/failures are explicitly
constructed, not falsely labelled recordings. The synthetic language fixture uses two language labels on the **same
official AAC tone**; it tests track selection/persistence, not translation or real French-language programming.
The small video fixture and all local test playlists stay outside the repository/APK.

Still outstanding: actual live provisional-table movement, additional EPL states/provider shape changes, full native
soccer box scores/profiles/caches and MLS/La Liga/UCL reconciliation; real multicodec/multilanguage track changes,
subtitles/forced captions, network transitions, low-memory phones, accessibility and physical TV/PiP sizing.
The complete cross-platform backlog remains in [PARITY_EXECUTION.md](PARITY_EXECUTION.md).
