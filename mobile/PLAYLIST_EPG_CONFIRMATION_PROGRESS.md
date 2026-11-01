# Playlist EPG confirmation — 2026-10-25, in progress

Full iOS parity is not complete. This records a real-provider finding and its shared Android fix,
not a claim that any provider always sends the video advertised in its guide.

## Reproduction on the minified807 artifact

All gates were reconfirmed after the interrupted build:807 unit cases,802 pass/five optional skips,
zero failures/errors; all-module debug lint and phone release lint zero errors; debug/minified-phone
and TV assembly passed. The all-up-to-date confirmation exited0 in13s. Release v2 signature and
16-KiB alignment passed. Development-signed/unlocked/cannot-charge, not a production/dist release:

- Release `c5d203e08872864ce9045c74458c54d87360afe573bdf73d8a9185b4d13554f6`
- Phone debug `5c70c96754e4ed0986202dcf2c3a360a1fde548ff8246dc4c67f76fbca21414f`
- TV debug `0c394dab36f44fbc5bd9cbbc62586f79f4aecbf44ad4864bd8e9bd653abc866b`

The existing two playlists/drafts survived install-r. Normal Home View opened the unfinished QA
league's Roster, not Players, with Continue draft. The original A.J. Greer/ANA entry was unchanged.
At150% text, tapping the Fantasy on Home label changed only that switch; tapping the switch graphic
restored it. All three original fantasy toggles remained enabled. Actual merged row bounds showed
one clickable/checkable target per switch, not separate label/graphic actions. No TalkBack-device
or TV-remote certification is inferred from this touch/hierarchy check.

Only the separate Parity Native QA draft explicitly opened Players and added provider-reported
Brock Faber/MIN/D as its third pick. Home showed one rostered player/one live team fixture and a
Watch action. That initial Watch action was incorrectly presumed EPG-backed: it selected the timed
event-slot name `US ★ NHL GAME 05: WILD AT CANUCKS 10PM ET`, which had no programme title.
Later Ways to watch listed genuine Sportsnet One/regional Minnesota–Vancouver EPG alternatives,
but also falsely labelled that name-only event slot “Confirmed by your playlist EPG.”

The supplied account reported zero active/four available connections before the bounded playback
check. Explicit Options → Restart stream was invoked after allowing single-tap/fade-in to complete.
Decoded video resumed, but the inspected frame showed Panthers/Blackhawks while the selected
channel/companion advertised Minnesota/Vancouver. Thus this establishes restart-to-video only,
NOT correct-game playback, synchronized scores or a never-lag guarantee. The stream was force-stopped
promptly after the mismatch. Targeted AndroidRuntime/ExoPlayer error log entries were empty with
URLs suppressed. PSS239,059KB/RSS374,304KB/swap-PSS8,890KB, one Activity: a snapshot, not leak/peak,
physical-device, long-soak or crash-free certification.

The non-playing real provider Guide check did correctly join the actual current Sportsnet One
Minnesota–Vancouver listing to Brock Faber. At150%, its star/count remained beside the leading clock,
the sheet said ON NOW, and the future Panthers–Blackhawks listing said UPCOMING with an explicit
current-channel explanation. No autoplay occurred on opening those sheets. The active blue cell's
badge contrast was weak;814 switches that badge to onPrimaryContainer, pending runtime recheck.

Screenshots under docs/screenshots were visually inspected: fantasy807-home-compact.png,
fantasy807-home-live-150.png, fantasy807-settings-150.png, fantasy807-guide-leading-badge-150.png,
fantasy807-programme-status-150.png, fantasy807-upcoming-status-150.png,
provider807-restart-action-150.png, provider807-after-restart-150.png and
provider807-event-name-false-epg-label-150.png. The image provider807-options-before-restart-150.png
actually shows a decoded intermission ad after the HUD hid; it is NOT proof an options sheet opened.

Font1.0/Wi-Fi1/data1/540x1212/density210 read back restored. App stopped and emulator72212 exited0
before the next build. The attempt to restore the original selected league missed its now-offscreen
label; Parity Native QA is still selected and QA Hockey must be reselected in the next runtime pass.
No roster/playlist was deleted; no unrelated file/cache cleanup, commit, push or dist publication.

##814 correction — gates and actual source-policy checks passed; cold-guide follow-up open

Full build49214 exited0 in6m34s:809 pass/five optional skips/zero failures or errors across814 tests
(core648/phone138/TV28). All debug lint tasks and phone release lint have zero errors; existing warnings
remain core11/phone43/TV19+two hints. Debug/minified-phone/TV assembly, release v2 signature and
16-KiB alignment passed. These are development/unlocked builds, not production/dist publication:

- Release `d3c516c53526c721901432d2facb22701376b471dae6d3f05fee8b9e1002df22`
- Phone debug `88f1a85fcf814b14b4537e4fdcd39be9f14ea2c1d8f40a20fd35d0a3de0236d5`
- TV debug `b7b6fe603145c37a83d8a6140f306b64bcbc02f5ead1c6ff47ef0e71540f2e56`

- `RankedSource.isConfirmed` requires T1/T2 actual playlist title/description evidence, a nonblank
  programme title, and a finite probability in[0.7,1]. T3/T4/T5/T6 never become automatic Watch just
  because their numeric score is high. No third-party guide is introduced.
- Real EPG choices lead name-only suggestions even when a parsed timed name scores higher. Multiple
  evidence paths retain one row per playlist-scoped channel, preferring its real listing evidence.
- Phone Ways to watch says Listed in your playlist EPG versus Channel-name match/no EPG confirmation;
  it explains that guide evidence cannot verify the actual decoded video. Taps revalidate the source
  and current playback URL; stale selection gets feedback instead of playing an expired reference.
- Phone automatic companion lookup uses current fixture-validated evidence and the exact currently
  playing channel, not another mirror's guide evidence. Home/card/watch-bar actions revalidate.
- Fire TV Home/detail/live routes use validated source evidence. Live-games no longer swaps a
  guide-matched stream for a preferred lineup mirror that can have another guide binding. TV Game
  Centre keys ensure by actual fixture evidence/state and passes its expensive guide projection
  lazily to the shared off-main index. This complements earlier phone lazy/off-main fixes.
- Seven constructed regressions exercise name-only suggestions, false high-confidence tiers,
  malformed confidence/blank evidence, T2 priority over T4, same-channel deduplication, cold guide
  arrival/removal and a contradictory actual programme. They are unit fixtures, never runtime seeds.

Actual minified814 source-policy checks passed: name-only sources remain explicitly unconfirmed and
do not generate automatic Watch; after real guide refresh, true Sportsnet listing choices lead.
Regular Sportsnet One playback/restart-to-video was checked. Cold relaunch repeatedly lost that
programme information, so cold recovery and revised badge contrast are not certified.825 source
addresses publication/refresh/cache problems and is building. See GUIDE_COLD_CACHE_PROGRESS.md for
exact observations, preserved data/restored settings and the limitations. No all-provider,
hardware, production purchase or full-parity claim is made. Broader adaptive/new-icon, imported
fantasy-context, presentation and platform/device work remains in PARITY_EXECUTION.md.

Additional read-only audit: GuideLoader currently combines different playlist XMLTV schedules by
normalized guide ID. Colliding IDs from different providers need a separate source-ownership audit
and regression before cross-provider correctness can be certified. This is not a demonstrated cause
of the807 event-name bug, and814 does not claim to implement per-playlist XMLTV namespaces. The
stalled read-only git diff check was terminated without changing any Git state; build/test gates,
not that unfinished diff check, are the verification evidence above.
