# MLB fixture provenance

`mlb-schedule.recorded.json` is the complete original `schedule.json` response from
`alexdiabmusic-stack/StadiaTV-iOS` commit `8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`,
`MLBCoreTests/Tests/MLBCoreTests/Fixtures/schedule.json`. It is a recorded June 1, 2024 schedule, not a current
schedule or simulated upcoming game. Existing `mlb-live-late.json` was previously sourced from the same iOS fixture
set for native Game Centre tests. All fixtures are unit-test resources only, not included in the app APK.

Other shapes in `NativeBaseballProviderTest` are explicitly constructed status, standings, roster, player, network
and identity cases. Public/mock response tests never use commercial playlist credentials or initiate purchases.
