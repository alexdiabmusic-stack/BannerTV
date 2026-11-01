# iOS podcast discovery continuation — 2026-10-24

Reference: `alexdiabmusic-stack/StadiaTV-iOS`, pinned main
`8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`.
This is an implementation/validation record, **not a full-parity claim**.

## Source traced

- `MyApp/TeamPodcastResolver.swift`: country-specific public Apple searches, per-seed queries,
  team-name/alias evidence,140/55/12/8 weights,70-point acceptance threshold,30-show result cap and
  seven-day nonempty resolution cache.
- `MyApp/PodcastStore.swift`: lazy bundled catalogs/154-team registry, exact scoped seed identity,
  public-search-first then curated-feed merge and feed identity deduplication.
- `MyApp/PodcastView.swift`: league/favorite-team topics adjacent in the strip, team resolution on
  selection, explicit Refresh and Recommended/A-to-Z sorting.
- `MyApp/PodcastMatcher.swift`: inspected advanced sport/team query/scoring nuances. Its production
  PodcastIndex dependency is NOT the same thing as a public Apple Search endpoint. The private
  PodcastIndex credential from the iOS source is not copied into Android or tests.

## Android continuation

The previously bundled team registry is now decoded lazily and used. Team/league topics, public
country-scoped resolution, deterministic relevance ranking, normalized-feed deduplication, curated
fallbacks, explicit Refresh, visible loading/failure/last-successful-search state and alphabetical
sorting are connected to the existing podcast subscriptions, RSS pages and audio service.

Search returning a result is not evidence of team relevance: title/publisher scoring happens before
adding team tags. Unique token-boundary aliases avoid substring/duplicate-alias inflation, and seed
identity is league-scoped rather than a cross-provider numeric join. Requests are bounded to five
seed queries,50 rows/query,2MiB/response and8seconds/query, with cancellation propagated. Resolution
memory is capped at32 teams. Only a complete nonempty successful search receives seven-day caching;
empty/incomplete searches have a60-second short cache.401/403/429 stop subsequent queries and honor
bounded denial/Retry-After cooldowns even if Refresh or a different team is selected. A failure cannot
re-date retained recommendations as a newly completed search.

The public Apple endpoint requires no invented user account/backend. Existing generic search, RSS
enclosure validation, queue/history/downloads, background playback, system controls, Android Auto
browser and video/PiP remain in place; those are not replaced by the new resolver.

## Honest remaining boundaries

Bundled registry policy notes ask for three active team-specific feeds, redirect-final deduplication
and feed-level freshness validation. The iOS resolver itself does not establish all those promises.
Android does not manufacture a three-show guarantee, label league fallbacks as team coverage, or
claim a search result is an active playable show before RSS is opened. URL normalization is not a
claim that every redirect-equivalent feed has been resolved/deduplicated. Public-search candidates
are checked for actual supported enclosures by the existing RSS episode page.

The separate PodcastIndex-backed broad recommender is not configured, and catalog artwork resolution,
listening-library refinements, broader stale-feed/redirect cases and physical Auto/headset/device
verification remain open. Existing prior real background audio playback does not certify this newer
discovery flow.

The734 source passed729 unit cases/five optional skips, all zero-error debug lint, release-only lint
and minified-phone/TV builds. Real minified discovery returned Ottawa shows and an actual publisher
RSS episode page. Team selection survived Back/150% text recreation; A-to-Z browsing, offline
original-time retention and explicit reconnect Refresh passed. Both saved screenshots were inspected;
targeted app/player error logs were empty and settings were restored/emulator exited0.

That run exposed an offline duplicate preferring thinner catalog metadata. The newer737-source
merge now retains previously verified publisher/artwork before bundled duplicates; a regression test
asserts this. Blank favorite-name matching and the duplicate All-sports topic label are also refined.
All737-source regression/lint/release-only-lint/minified-phone/TV gates passed (732 pass/five optional
skips/no failures,5m4s). The real minified737 offline refresh now retains the exact verified artwork,
publisher and original1:16PM search time; explicit online Refresh recovers1:17PM and clears the error.
The clarified All-sports topic is present. Screenshot inspected, targeted app/player logs empty,
settings restored and emulator exited0. See VALIDATION.md for exact development artifact hashes.
