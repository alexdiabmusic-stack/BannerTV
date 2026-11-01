# Local build checkpoint — 2026-10-23

## Current status: disk/build interruption recovered

Latest716 run completed all tests (711 pass/five optional skips), zero-error debug lint, release-only
lint and phone debug/minified/TV builds in4m4s. Subsequent real minified verification completed with
no targeted app/player error. Emulator settings restored and process fully exited; no Gradle/emulator
overlap or user-file cleanup was needed. VALIDATION.md contains the current exact hashes and limits.

Newest2026-10-24: the registered La Liga701-test source completed all regression suites, all three
zero-error debug lint tasks, phone debug/minified release INCLUDING release-only lint, and TV debug in
6m25s. The earlier release-only lint deferral is closed for this newer artifact. A single-use1536MiB/
512MiB Gradle process ran without the emulator; free disk stayed around7–8GiB. Gradle exited before
the emulator was started for real runtime checks. Exact artifacts/coverage are in VALIDATION.md.
This resolves that build condition, not full iOS parity or physical/store verification.

Latest2026-10-24:683 tests (678 pass/five optional skips) and all zero-error debug lint checks passed.
A subsequent full build stalled after minification under host pressure, with free disk falling to about
3.5GiB. Only the verified task-owned Gradle daemon PID25458 was terminated; no user file or other app
was removed. Lower-memory single-use development packaging then completed phone release/TV debug
with `:mobile:lintVitalRelease` explicitly deferred. Last headroom5.7GiB. The release remains byte-identical
to the runtime-verified68a9133b... artifact. Release-only lint and full parity remain incomplete; see
VALIDATION.md for exact hashes and boundaries. Do not restart memory-heavy build+emulator together.

Newer continuation: the 674 checkpoint completed all unit suites, debug lint, minified phone
release and TV debug assembly: 669 pass/five optional skips, zero lint errors. Disk headroom is about
10 GiB. Canonical iCloud source reads can still stall; a scoped temporary
non-Git source mirror (`/private/tmp/banner-android-build.08JiwP`) and project cache
(`/private/tmp/stadia-gradle-project.BAm8lH`) make builds deterministic and fast after completed rsync.
Canonical edits remain in this original workspace; the mirror is not the source of truth, and no user cache,
Git metadata or project files were deleted. Stop Gradle before emulator work on this8GiB machine.
Always wait for rsync completion before invoking Gradle. A later source-sync/build overlap produced an
old all-UP-TO-DATE result; that result was explicitly NOT accepted for the new tab change. The completed
sync was byte-compared and the build rerun with `--no-watch-fs`, which actually compiled the changed phone
source. Retain that flag for subsequent mirror builds to avoid stale filesystem-watcher input snapshots.
See VALIDATION.md/MLS_SOURCE_AUDIT.md/LALIGA_SOURCE_AUDIT.md for exact current coverage and remaining work.

The history below is retained for provenance, not a current disk blocker. Free space recovered to
about 14 GiB and remains about 11 GiB after source hydration/builds. A complete **592-test** checkpoint
passed (587 pass, five optional skips), all three debug lint tasks had zero errors, and phone debug,
minified phone release and TV debug assembled. Release SHA-256:
`502823e047d10d2ea173133e74d6e16b82584868a8831fbd2afef8dffdd22f78`.
Signature/alignment and bounded real minified EPL emulator checks passed; immediate short reconnect
exposed a recovery bug now being fixed/tested. See SOCCER_GITHUB_PORT_PROGRESS.md for exact boundaries.

A later Gradle project-cache read failed because executionHistory.bin became cloud-only. The retry
uses a new scoped temporary project cache; the original cache and all user files are preserved.
No commit/push or replacement of dist 1.7.0 was performed. Full parity is still incomplete.

## Historical checkpoint and interruption

Full iOS parity is NOT complete. The latest verified checkpoint has 572 tests: 567 pass, five optional probes skipped.
Its debug APK SHA-256 is `0d548b49bde7639cd90c19caddf8f12417fb465e566152995d79a371562cdc46`.
Real emulator checks passed native EPL discovery, Arsenal/Raya profile, Everton–Chelsea result/formation/captain
profile, and official table pinned names/points. Exact screenshots and limits are in VALIDATION.md.

The earlier 566-test minified phone release / TV debug / all three lint tasks passed. Its release SHA-256 is
`9570fb03ac1f1dc228782922b8668fcd388e9b9b1cde14c961a27c88e0487512`. It does not include the 572-test refinements.
The dist 1.7.0 delivery remains unchanged. No commit or push was performed.

## Latest edits NOT compiled or tested

See SOCCER_GITHUB_PORT_PROGRESS.md for the newer structured event/timeline/officials port and exact GitHub
source references. Five further cases were written, and existing cases extended, but they have not run.
The 572-test debug APK does not contain these or the earlier pending cadence/resource edits below.

SoccerDetail original per-surface times / full-refresh marker / bounded player directory; tab-scoped native soccer
fetching with a real 120s full-refresh timer and retained failed-resource data; native EPL polling cadence/final stop,
failure backoff, await-completion and legacy numeric deep-link separation. New NativeSoccerPollingTest (two cases)
and PhoneGameRefreshTest EPL cadence case are written, but their build did not start successfully. Do not claim 575
tests or runtime coverage until it actually runs. Cold disk recovery, officials and richer soccer timeline remain open.

## Blocker

Continuation update: free space recovered to 6.1 GiB and the wrapper/build became readable enough to start.
Space then fell to 135 MiB, and edited source files were again marked cloud-only. The build reached core
Kotlin/unit-test compilation but was explicitly stopped before tests/new APK verification. `1 Daemon stopped`
and daemon shutdown were confirmed. Some source edits happened after the first core compilation, so that
partial run does NOT verify this source checkpoint. No files or caches were removed and no push was made.
Restore stable headroom (preferably at least 10 GiB AFTER downloading/keeping the project locally).

Earlier failure history:

macOS marked project files `compressed,dataless` (cloud-only). Wrapper execution and file reads time out. Using the
existing identical Gradle 9.4.1 distribution also failed: app/build.gradle.kts could not be read (Operation canceled).
Apple's scoped download API accepted requests for project build/source folders but reported NotDownloaded,
downloading=false and requested=false. Finder automation timed out; no account or system setting was changed.
Disk availability fell from 594 MiB to **282 MiB, 100% capacity** on the final check. Delayed two-line wrapper/build-file
reads eventually returned, but key files still show dataless; full source availability/build recovery is unconfirmed.
No files were removed/replaced to recover space. The task's Gradle daemon was confirmed stopped.

Free sufficient local space (preferably 10+ GB), then in Finder download/keep this exact project folder locally.
Confirm the build scripts and sources are readable before resuming tests. Emulator is stopped; stop the Gradle daemon
if any remains. Physical Android/Cast/Auto hardware and Play Console setup are separately still unavailable.
