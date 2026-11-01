# La Liga source fixtures

Copied verbatim as UTF-8 test resources from alexdiabmusic-stack/StadiaTV-iOS,
commit 8f595b0790c7b0f08e2b3c6a7298a6861ff1af93,
LaLigaCoreTests/Tests/LaLigaCoreTests/Fixtures/<original-name>.json.
Local names add `laliga-` and `.source`; these are test-only, never injected into runtime UI/data.

Included: official competitions, finished/prematch matches, rounds, squad, standing,
subscription page 1, FotMob day matches and full finished match details.
The upstream finished FotMob recording is Alavés–Getafe with 24 reported shots.
Official season/player endpoints use different ID schemes; fixtures are not permission to join by numbers.
The upstream JSONs are curated page slices: matches report total380 but contain1/3 rows; subscription
page reports total241 but contains6; squad reports total34 but contains20. These are NOT complete
season/directory/roster recordings. Pagination tests must construct honest multi-page envelopes or
probe the actual current endpoint, not weaken completeness guards to make these slices pass.

The upstream hand-modified FotMob live-firsthalf/pregame variants have NOT been copied here and must
not be described as recordings of a genuine live/pregame response. Any future constructed cases must
remain explicitly labelled as such. The upstream gzip ticker is now included byte-for-byte in
`laliga-fotmob-ticker-finished.source.base64` (text representation of the original gzip bytes,
Git blob 5bf7e13f751f0ddfd31caad18ab67ca3981ca379, original size4820). The test decodes Base64 then
the actual gzip; no Content-Encoding is invented. Its reported home/away identities are9866/8305.

See mobile/LALIGA_SOURCE_AUDIT.md for source-code contradictions and required validation boundaries.

`laliga-fotmob-final-event-order-20261024.source.json` is a selected unmodified field slice from a
bounded direct read of FotMob match5868106 after its real Racing/Espanyol2–1 final on2026-10-24.
Eight of26 rows are retained to exercise chronology: HT45, goals62/69/84, substitution90+5,
card90+7 and the trailing FT90 marker. Player/name and unrelated fields were omitted deliberately.
The live-header and official field slices are earlier independent samples; tests joining them to
this event slice explicitly construct a wrapper, not claim a single simultaneous full response.

## Additional real provider recordings —2026-10-24

`laliga-official-live-20261024.source.json` is an unmodified-value field slice of the official
`/api/v1/matches?subscription=laliga-easports-2026&competition=primera-division&limit=100&offset=200`
response, selected game102344. It is NOT a complete page. Actual official Racing/Espanyol IDs49/8,
formal/nickname spellings, kickoff, SecondHalf state,0–0 scores and six persons_role assignments were
observed around11:29AM Toronto. Omitted fields include unrelated assets/channel marketing metadata.

`laliga-fotmob-live-header-20261024.source.json` is an unmodified-value general/header slice of public
`https://www.fotmob.com/api/data/matchDetails?matchId=5868106`, fetched around11:25AM Toronto. It records
the real `liveTime.short="52\u200e\u2019\u200e"`,long51:32 shape (NOT the earlier inferred minute field),
actual club IDs8696/8558,0–0 scores and reported half timestamps. The two providers were sampled
independently, not claimed simultaneous. No content resource is present in this slice.

`laliga-fotmob-current-rows-20261024.source.json` contains identity/kickoff field slices of four real
rows from `/api/data/matches?date=20261024&timezone=UTC`, league87. It is not the full daily response;
tests construct the miniature league wrapper explicitly. Names include actual Racing Santander,
Espanyol,Valencia and Deportivo A Coruña. Málaga's exact name was separately observed in the20261023
daily row5868101 (Alavés/Málaga). All requests used the app's honest UA and public endpoints; no
credentials, browser impersonation, proxy/access-control workaround or runtime seed was used.

`laliga-fotmob-live-pressure-20261024.source.json` is a first4/last4 unmodified-value slice of93 actual
momentum samples for5868106, fetched around11:53AM Toronto. The future88/89/90/90.75 minutes have null
values; they are placeholders, not reported zero pressure. Tests explicitly construct the resource
wrapper when joining this independently observed slice with the earlier recorded header.
