# NHL recorded fixture provenance

The three `nhl-final-*.recorded.json` payloads are copied from
`alexdiabmusic-stack/StadiaTV-iOS`, commit `8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`,
`NHLCoreTests/Tests/NHLCoreTests/Fixtures/final-*.json`.
The source README records direct download from `api-web.nhle.com` on 2026-09-21.
Only a final newline was added; the JSON payloads are unchanged.

They contain game 2023020204, Minnesota at Buffalo, November 11, 2023 at 00:00 UTC,
final score 2–3, including 320 play-by-play entries and five summary goals.
They are unit-test resources only, not bundled with the app or fetched during tests.
Other unusual schema, correction and failure cases in NativeHockeyEventsTest are constructed inputs,
not recordings of live matches.
# Global NHL provider additions

`nhl-score.recorded.json` is the `currentDate` and `games` subset of
`NHLCoreTests/Tests/NHLCoreTests/Fixtures/score.json` from the same pinned iOS source commit
`8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`. The unrelated top-level betting-partner metadata was omitted.
The recorded game is Vegas at Seattle, January 1, 2024, final 0–3, ID 2023020573. No result is constructed.
`NativeHockeyProviderTest` explicitly constructs malformed/status/standings/roster/profile/HTTP cases separately.

