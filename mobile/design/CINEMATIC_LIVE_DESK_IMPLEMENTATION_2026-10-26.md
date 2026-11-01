# Cinematic Live Desk — implementation checkpoint

## Scope

The user approved implementation after the research pack review and design audit. This slice covers
Android phone Home → Game Centre → player → return, plus the shared visual system and explicit Live
destinations. It is not a claim of full iOS parity, finished TV design, or device-verified polish.

The direction combines a compact broadcast feature with a useful live desk: followed-team games first,
stable compact scores, a real chronological schedule, and quieter supporting content. No decorative
gradient, auto-rotating feature, fabricated score, generated stadium image, or invented stream availability.
The current Match model has real team marks but no verified fixture photography; marks provide the feature
artwork, and existing article photography stays in the editorial section.

## Implemented

- Home selects a followed-team live fixture ahead of other live games, then the next relevant upcoming
  fixture. Live selection stays stable through score updates; nearer upcoming fixtures can replace a
  later fixture during progressive schedule loading. Completed/removed fixtures do not stay featured.
- Other live games use compact, aligned score rows and do not duplicate the hero in the live list.
  Sport filtering includes every supported sport, even when it is not followed.
- Today/Tomorrow/Weekend schedules include earlier finals, live games and upcoming games in start-time
  order, using local calendar boundaries. Pagination remains lazy, rather than composing a season of rows.
- Fantasy, favorite channels, watch history, playlist setup and news remain accessible below the core desk.
  Recently watched channels are not misleadingly labelled resumable programmes.
- Home's All live opens Live → Sports → All games. Guide and Browse channels open their exact destinations.
  Live sports distinguishes loading, unavailable, filtered-empty and genuinely empty states.
- The stable app-level saved-state holder now survives full-screen playback, preserving the screen's
  saveable state on return. Actual scroll/filter restoration still requires device verification.
- Shared navy/light surfaces, restrained blue action color, semantic status pairs, tabular scores,
  8/12/20dp shape roles, scalable type, 48dp actions and selectable segmented controls.
- Game Centre has a clearer score/team/watch hierarchy and narrow/large-text reflow. Nonurgent provenance
  is grouped in a labelled Data status disclosure; urgent uncertainty, timestamps and retries remain.
- Full and mini-player share truthful connecting/paused/buffering/reconnecting/failed/ended labels.
  Failed or ended live streams offer Restart. Alternate-stream actions require a distinct valid HTTP(S)
  request, including provider-required headers. Archive completion keeps replay semantics.
- Recovery copy is allowlisted/sanitized; raw provider URLs or exceptions are not shown. Recovery controls
  are scrollable, remain visible, and suppress underlying video gestures. Decoder/DVR/PiP ownership is unchanged.
- Watch controls still require the existing current playlist EPG confirmation and revalidate on activation.
  No matching threshold, score provider, stream engine or playlist credential behavior was changed.

## Quality checklist and boundaries

Source checks cover hidden-final scores, league/sport-specific paths, large-text reflow branches, selected
control semantics, loading/error copy, legitimate alternate-source eligibility, and stable event ordering.
Opaque semantic foreground/container contrast was calculated, not visually certified: status pairs are
at least 5.53:1 and action pairs at least 6.18:1. Legacy fixed status colors elsewhere still need migration.

Regression tests were added for feature priority/retention, progressive schedules, date ordering, exceptional
PRE states, safe playback labels/recovery, distinct request identity, provenance and resource retry targets.
Build/test/lint status is recorded below. The coordinated code gate has completed successfully.

Emulator QA remains deferred at the user's request. No new screenshots, TalkBack navigation results,
150–200% text visual results, hinge/modal-window proof, real stream playback test, Cast/Auto device proof,
or before/after visual-quality score is asserted. Follow UX_QA_PLAN_2026-10-26.md for those acceptance checks.
Guide toolbar simplification, TV-specific focus/scale issues and whole-app typography/status migration are
separate follow-up slices; preserved functionality does not mean every existing surface is redesigned.

## Validation

Completed on 26 October 2026 after the user requested build checks. Canonical module sources and build
configuration were synchronized to the existing temporary build mirror before Gradle started. All 13
changed phone source files and all three new test files were separately byte-compared with the mirror.
An optional whole-project checksum-only scan stalled on cloud-backed reads and was stopped; it made no
writes or deletions. The normal source synchronization and targeted byte comparisons both completed.

The staged debug gate passed in 2m44s: all three unit-test tasks, all three debug lint tasks, phone debug
assembly and TV debug assembly. The staged phone release gate passed in 5m31s: minification, assembly
and full release lint. Both used one worker, no parallel Gradle work, a 1280 MiB heap/512 MiB metaspace,
`--no-watch-fs`, and `-PpublicBuild=true`. No source synchronization ran concurrently with either build;
the separate checksum-only scan was read-only. No emulator ran and no user files/caches were deleted.

| Module | Tests | Passed | Skipped | Failures/errors |
| --- | ---: | ---: | ---: | ---: |
| Phone | 199 | 199 | 0 | 0 |
| Core | 664 | 659 | 5 | 0 |
| TV | 28 | 28 | 0 | 0 |
| Total | 891 | 886 | 5 | 0 |

The phone suite recompiled and ran; unchanged core/TV test results were reused by Gradle. All 30 new
cases passed: 10 live-desk, 10 playback-presentation and 10 match-data-status. Actual XML reports were
checked, including each new class. Debug lint has zero errors: core 11 warnings, phone 44 warnings,
TV 19 warnings plus 2 hints. Full phone release lint has zero errors and 44 warnings. Nonfatal Kotlin
unnecessary-safe-call warnings remain; this is not a warning-free build.

Artifacts under `/Users/alexdiab/Library/Caches/StadiaTVFirestick/build/`:

| Artifact | SHA-256 |
| --- | --- |
| `mobile/outputs/apk/debug/mobile-debug.apk` | `a7855cacbcd2e6ac1858ccd5240b22eed422bde261cf6911c6a65fcb702cda07` |
| `mobile/outputs/apk/release/mobile-release.apk` | `6dda32650395d799bf3a71d31c9549fb985cc4f734530d613d6fc4ed484ce930` |
| `app/outputs/apk/debug/app-debug.apk` | `8cf801d0d959c7cf63f6827bd4ebbe32f95bc8dd4d1cff78321ddd64e6f9ead0` |

All three APKs passed `apksigner verify` and `zipalign -c -P 16 4`. The minified phone APK is 7,622,989 bytes,
package `com.alexdiab.stadiatv.mobile`, BannerTV 1.7.0/code 8, minimum SDK 26/target 36, with a verified v2
signature. These remain development-signed artifacts, not Play-store production releases. The TV
signature verifier emits nonfatal META-INF/JAR-signature warnings; Java 26 also emits a native-access
warning. No verifier error occurred. The TV APK is unchanged from the earlier checkpoint.

The earlier cross-module nullable smart-cast error is fixed and both phone variants now compile. The
earlier disk-space stop is also superseded by these successful gates: this run began at about 4 GiB,
was monitored throughout, and ended at about 5.7 GiB free. No commit, push, production signing, emulator
or device validation was performed. Automated build checks passed; the visual-quality gate and
full iOS parity remain open.
