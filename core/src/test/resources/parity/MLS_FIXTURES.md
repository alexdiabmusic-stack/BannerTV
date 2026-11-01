# MLS fixture provenance

Copied verbatim from `alexdiabmusic-stack/StadiaTV-iOS` commit
`8f595b0790c7b0f08e2b3c6a7298a6861ff1af93`, directory
`MLSCoreTests/Tests/MLSCoreTests/Fixtures/`, through the GitHub connector.
Each `mls-NAME.source.json` corresponds to upstream `NAME.json`.

These are upstream test resources, never runtime seed data or proof that Android has loaded a real game.
Upstream describes the match resources as an Inter Miami CF / San Diego FC 2–2 game, MLS-MAT-0009LX.
Preserve the payload as supplied: some schedule metadata dates differ from planned kickoff dates; tests must
use the actual planned-kickoff field and must not silently rewrite the source to make it consistent.

The implementation must follow actual fields over stale source comments: this key-events fixture DOES
contain a team_id on shots, even though the iOS mapper comments describe a missing team ID. Prefer a valid
explicit team ID, and use a uniquely verified overview-player/team mapping only when it is missing.
Likewise, the player-stat fixture contains shots_on_target; its supplied values need not be discarded
because an older mapper comment calls them unconfirmed. Missing values must still remain missing.

No verified shootout fixture is included. Do not turn an unknown outcome into a missed kick or infer a
complete shootout from a partial sequence. MLS crest and season-roster availability are disclosed source gaps.
