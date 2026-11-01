# Premier League source fixtures

The four `epl-match-*.source.json` files are copied unchanged (apart from terminal newline) from
`alexdiabmusic-stack/StadiaTV-iOS`, commit `8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`,
`EPLCoreTests/Tests/EPLCoreTests/Fixtures/match-*.json`.
The source integration notes describe full-time/prematch as observed shapes and live periods as inferred vocabulary.
The stoppage fixtures use explicit test IDs `9000001`/`9000002` and are constructed cases, not live recordings.
These are test resources only. None is a fallback feed, a current result claim or inserted into runtime storage.
Other synthetic cases in NativePremierLeagueProviderTest are labelled constructed and use MockWebServer only.
The inline Raya biography uses a small exact field subset from the public squad probe on 2026-10-23. The nested
profile test combines observed identity/count shapes with a deliberately synthetic `ratio: 0.125` to test precision;
that ratio is not a claim about Raya or a captured profile statistic.
