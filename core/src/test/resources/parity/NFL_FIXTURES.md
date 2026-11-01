# NFL fixture provenance

`nfl-regular-final.json` and `nfl-weeks.recorded.json` come from StadiaTV-iOS commit
`8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`, NFLCoreTests fixture resources.
The source README records public NFL Shield responses captured September 22, 2026 via the public
WEB_DESKTOP application flow. The final game is Dallas at Philadelphia, September 4, 2025 local time.
Weeks are the provider's announced 2026 regular-season schedule, not a recording of live results.
A terminal newline may differ from the source; JSON data is not altered.

Authentication headers/token responses are never stored. These resources are unit-test-only, not in the APK.
Unusual status, correction, type-conflict, failure and ownership inputs in the tests are explicitly constructed.
