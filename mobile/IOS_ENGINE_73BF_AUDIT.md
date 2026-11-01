# Newly merged iOS engine delta — 2026-10-26

Main advanced during the adaptive continuation from `b58b989aa5f9e30b0396ba81ddc73da3dcb7c7ab`
to `73bf44cffa6a700b7e88390d62e9424692a6fd10` (three commits / PR138). The compare contains only
stream-engine code, documentation and tests; adaptive/icon/fantasy code is unchanged by this delta.
This is an audit of remaining work, **not a claim that the new recovery behavior was ported**.

## Source evidence

- `MyApp/Playback/StreamEngine/StreamAcquisition.swift`: a response-head callback reports a usable
  HTTP answer before first body bytes, distinguishing provider silence from no network answer.
- `RecoveryReferee.swift`: 12-second startup; the first route with an untried backup gets8 seconds
  to answer, or4 seconds after an answer without media; later/last routes get the full startup time.
  Response/actual-media state must remain distinct. Repeated empty/server answers at least1 second
  apart, a refusal taking at least5 seconds, or answered-but-silent timeout mark a route off-air.
  Only when all routes are off-air/dead is the off-air verdict terminal; it does not trigger another
  direct-decoder retry. Plain unanswered network startup can still permit direct playback fallback.
- `HLSSegmenter.swift`: local target duration2 seconds / maximum segment2.3 seconds, mid-long-GOP
  cuts followed by a new keyframe segment. `StreamEngineConfiguration.swift` changes Fast lead to6
  seconds and startup timeout to12. Those local HLS/AVPlayer thresholds are not automatically valid
  for Android's raw-TS source or Media3 native HLS.
- `StreamEngineSession.swift`: provider-answer events reach the referee; speculative startup
  position does not freeze local playlist publication before the decoder has ever played; initial
  publication targets three target durations, bounded by configured lead and startup wait.
- The new Referee/EngineIntegration/Segmenter tests describe constructed/provider scenarios. The
  source documentation itself says no Xcode/simulator/device run was possible. Its Swift test count
  is not an Android test result, decoder certification or a never-lag guarantee.

## Android gap

`core/.../playback/engine/NeverlagEngine.kt` currently has a simpler one-route RecoveryReferee
with startup time, response code, connection-rate budget and backoff. `ResilientTsDataSource.kt`
splices raw TS; native HLS remains a controller fallback. A complete equivalent needs response-head/
first-byte/actual-programme observations, route-aware startup/off-air/account verdicts and shared
controller/referee authority, with no retry storm or overlapping account connections. The existing
controller startup watchdog must not independently overrule the route's source-start grace period.

Do not merely change10 to12 seconds or copy the Apple User-Agent. Preserve playlist-specified
headers and normal Android identity, explicit restart, single-source ownership, cancellation,
account concurrency limits and no access-control bypass. AVPlayer-specific fMP4/local-playlist
publication requires an Android transport design and tests, not a blind constant translation.

This work remains open after the adaptive861-source checkpoint. Physical codec/network behavior,
real off-air versus slow on-demand sources and cold/live/restart transitions need bounded tests.
