# Player and MLB parity checkpoint — 1.3

Reference: StadiaTV-iOS `8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`.

## Source behavior translated

- `CatchupResolver.swift`: completed-programme eligibility, declared retention, UTC Xtream timeshift paths, display
  offset removal and `{start}`, `{end}`, `{utc}`, `{lutc}`, `{timestamp}`, `{duration}`, `{durationMinutes}`, `{offset}`.
  Encoded credential path segments stay encoded, including escaped slashes, and host prefixes/ports are preserved.
- `M3UProviderAdapter.swift` and `XtreamProviderAdapter.swift`: per-stream archive capability, days and template metadata.
  Android additionally supports header defaults and explicit per-stream disabling without leaking metadata to the next entry.
- `PlayerActionRegistry.swift`: known ordered actions, three-slot limit and safe non-empty defaults. Android uses explicit
  move/remove/add controls so reordering does not depend on a drag gesture; the order is part of manual cloud backup.
- `PlayerView.swift`: portrait minimize/rotation swipes, landscape brightness/volume, pinch aspect selection and source
  chip swipe. Android's system back gesture remains system-owned. Crop is labelled Zoom on Android, not stretch/Fill.
- `MLBGameCenterService.swift`/`MLBGameCenterReducer.swift`: independent native content and probability resources,
  strict native game IDs, actual HTTPS MP4 playback URLs, corrected/deduplicated at-bat probabilities and retained
  independent surfaces when the combined feed refreshes. Absent values never become an invented 50% curve.
  Native highlight/probability errors can be retried independently, without bypassing rate-limit cooldowns.
  Finished-game headers/overview do not present the last inning/base snapshot as a live situation.

## Playback distinctions

Live TV, adaptive live DVR and provider archive replay are not interchangeable. XMLTV describes a programme but does
not prove any of that programme is buffered. The seek bar uses the player's current seekable media window. For live
windows, a scrub is anchored to the window's media time when its wall-clock start is known. Go Live uses the provider's
recommended default position. An archive ends normally and can replay from the beginning; recovery preserves its last
position and cannot auto-select a live copy. Returning to live is a separate explicit action.
The behind-live indicator is relative to that recommended position when available, so a provider's intentional
30-second live latency is not mistaken for DVR delay. Long pauses do not discard a still-seekable DVR position.

The guide's normal lookback remains six hours. Additional provider-supplied listings are retained only for declared
archive streams and capped at seven days to avoid multiplying the entire guide's memory use. The provider can still
refuse an archive because of subscription, expiry or protocol differences. Unsupported templates explain the limitation.
No account credentials or archive URLs are added to cloud backup; history continues to reference the original channel.

The implementation follows the official [Media3 live-window semantics](https://developer.android.com/media/media3/exoplayer/live-streaming)
and [Compose gesture arbitration](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures).
These informed the distinction between programme progress and DVR, moving-window seeks, and one gesture owner for pinch/drag.

## Validation boundaries

See [VALIDATION.md](VALIDATION.md) for actual tests and artifact results. A local rolling HLS/VOD provider fixture uses
the official AndroidX transport-stream sample. It verifies application behavior, not a commercial provider's entitlement,
retention, timezone dialect or long-running decoder reliability. The fixture is not bundled in the application.

Still required: real-provider archive failure/expiry and timezone variants, longer pauses/window expiry and network transitions, physical
pinch/source gestures, TalkBack/large text, low-memory decoder use when opening highlights, highlight PiP, original-size
aspect mode, preferred audio persistence and the broader iOS gaps in the execution ledger. Full iOS parity is not complete.
