# Native NHL event parity — 1.5

Reference: StadiaTV-iOS `8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`, particularly
`NHLPlayMapper.swift`, `HockeyPlayDescriptionBuilder.swift`, `NHLSituationParser.swift`,
`HockeyModels.swift`, `NHLPlayByPlayView.swift`, `NHLPlayEventRow.swift` and `NHLGamePresentation.swift`.
This is a specific parity checkpoint, not a full iOS parity certification.

- Event-specific primary/secondary players now distinguish shooters/goalies, blockers, hitters/hittees,
  faceoff opponents, penalty offenders/drawn-by/served-by players and bench penalties.
  Provider terminology is humanized without turning unknown future keys into goals. Numeric event codes
  are a fallback only when the semantic key is absent.
- Goals merge landing-summary player names/headshots, assists, strength, reported season goal/assist totals,
  reported score and valid replay links by native event ID. NHL IDs never enter the ESPN athlete route:
  event details offer the official NHL player page instead of opening an unrelated ESPN player.
- Empty-net strength respects the opposing goalie and known event owner. Situation fallback is scorer-relative,
  not always away-first. Unknown ownership stays unknown. No running score is inferred by counting goals.
- Shootout rounds count attempts per team after duplicate correction. Goal/saved/missed attempts remain distinct;
  their cards do not show normal cumulative scores or season goal totals. Period names include multiple overtime
  and shootout, and final headers retain the extra-period suffix without stale live-strength/goalie context.
- Overview prefers the native landing scoring summary, including when play-by-play is unavailable. Its complete
  chronological timeline is no longer truncated at the generic eight-event limit. Play-by-play remains separately
  sourced and labelled. Native surfaces have independent bounded requests and Retry-After cooldowns; a blocked
  play endpoint cannot discard a valid landing, box score or team statistics response. Missing refreshes retain
  previous events, while corrections reuse stable native IDs.
- Phone event cards show team identity, period/time, score where reported, season totals, replay and expandable
  player/location details. All/Goals/Penalties/Shots/Hits filters match iOS semantics, including delayed penalties.
  While reading history, new events wait behind a Jump to live action; visible corrections/removals still apply.
  The jump uses no forced animation. All event actions have at least a 48 dp target.
- Rink detail and shot-map coordinates reject invalid/missing values. A reported right-defending home side rotates
  **both axes**, not just x, to home-defends-left orientation. Unknown orientation is explicitly labelled in event
  detail rather than asserted as normalized. No distance-derived location is manufactured.

The three recorded NHL test payloads come from the iOS test resources (only a final newline added), with provenance in
`core/src/test/resources/parity/NHL_FIXTURES.md`; they are not bundled into the app. Constructed edge cases test
bench/double-minor attribution, code/key conflicts, shootout counting, correction identity, delayed display,
invalid coordinates, unknown owners and isolated HTTP 429 cooldown. See VALIDATION.md for measured runtime results.

Still outstanding: full native NHL profiles/caches/schedule routing, parity of curated leaders/key-stat/box-score
presentation, broader live/correction/penalty provider cases, physical-phone/TV accessibility and large-feed tests.
The all-platform backlog remains in PARITY_EXECUTION.md.
