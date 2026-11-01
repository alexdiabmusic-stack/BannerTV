# Basketball fixture provenance

The `nba-*.constructed.json` and `wnba-*.constructed.json` fixtures were copied from `NBACoreTests` / `WNBACoreTests` in the user's StadiaTV-iOS repository, commit `8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`. The source README explicitly labels these constructed fixtures, not recorded or network-verified payloads. Player rosters, scores and seasons are synthetic test inputs, not statements about actual events or current teams.

Tests prove mapping/routing against these shapes only. Real endpoint availability, current schemas, timing, images and device presentation require separate runtime verification. No constructed fixture is included in production assets or used as a live-data fallback.

`wnba-schedule-dates.recorded.json` is different: these are exact public date/ID field subsets read from the app's
cached official `https://cdn.wnba.com/static/json/staticData/scheduleLeagueV2.json` on 2026-10-23 (reported season 2026,
373 rows). No teams, scores, credentials or unrelated cache entries were copied. The known final's `gameTimeUTC`
was the 1900 placeholder while `gameDateTimeUTC` contained the real 2026 kickoff. This recorded subset covers that
actual calendar regression; it is not a complete captured season or a production data fallback.
