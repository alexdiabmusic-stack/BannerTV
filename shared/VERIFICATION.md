# Initial foundation verification — November 1, 2026

This is the historical baseline before the cinematic TMDB implementation. Current APKs replace the artifacts recorded here; use [CINEMA_QA.md](CINEMA_QA.md) for the latest build results and checksums.

## Executed checks

The following combined build completed successfully in 7m 40s using the project's existing Android SDK and JDK. All four test tasks executed in this run.

```sh
./gradlew --no-watch-fs --continue \
  :shared:compileCommonMainKotlinMetadata :shared:jvmTest \
  :core:testDebugUnitTest :mobile:testDebugUnitTest :app:testDebugUnitTest \
  :mobile:assembleDebug :app:assembleDebug \
  --max-workers=1 --no-parallel -PpublicBuild=true \
  -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx1200m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8'
```

| Module | Tests | Passed | Failed / errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Shared Kotlin foundation | 221 | 221 | 0 | 0 |
| Android core | 469 | 464 | 0 | 5 |
| Android phone | 199 | 199 | 0 | 0 |
| Fire TV | 28 | 28 | 0 | 0 |
| **Total** | **917** | **912** | **0** | **5** |

Counts come from Gradle's JUnit XML results. The five skips are existing opt-in checks that require local provider samples or probe files: `LineupReportTest`, `LineupPerfTest`, `LineupProbeTest`, `ProviderEpgMatchRealDataTest` and `GuideLoaderRealDataTest`. No skipped test is counted as a pass.

The shared suite covers existing sport-specific fixtures, retained state and recovery behavior, ESPN fallbacks, fantasy, odds and news logic, plus new CFL fixtures, Champions League mapping, add-on resource/credential handling, TMDB identities, time/encoding, HTTP response limits/cache/cancellation, Swift callback cancellation and F1 authentication-stop policy. These are offline regression checks; they do not certify current availability of every upstream service.

## Android artifacts

The configured build root on this Mac is `/Users/alexdiab/Library/Caches/StadiaTVFirestick/build`. Both files passed ZIP CRC checks, contain their compiled manifest and DEX files, and passed Android SDK `apksigner verify`. They are debug-signed development builds, not production store releases.

| App | Artifact under build root | Application ID | Version | Size |
| --- | --- | --- | --- | ---: |
| Phone | `mobile/outputs/apk/debug/mobile-debug.apk` | `com.alexdiab.stadiatv.mobile` | 1.7.0 (8) | 34,354,988 bytes |
| Fire TV | `app/outputs/apk/debug/app-debug.apk` | `com.alexdiab.stadiatv` | 2.1.0 (3) | 29,516,082 bytes |

SHA-256:

```text
mobile-debug.apk  606d7547918ba8487e098c0399b9c734a315cfc5033d19e59e5165383c807a67
app-debug.apk     61cae92a1cce67fa4fe71200bbaf4fb70f8664a7b27a9b3174fa0578df5a4bf5
```

## Remaining verification and integration

- Full Xcode and an iOS SDK are unavailable on this Mac. Kotlin/Native framework compilation, Swift type-checking against that framework, app linking and native tests have **not** passed a verification gate yet. The Keychain adapter passed Swift syntax parsing only; the Info.plist fragment passed plist validation. Follow `apple/README.md` before changing the Swift host's production routing.
- Physical-device and emulator QA remain deferred as requested. Playback, LAN permission prompts, secure storage across device restarts and live F1 socket behavior still require device checks.
- The shared sports implementation is consumed by both Android apps. The Swift host's presentation-model and lifecycle adapters remain integration work; this record does not claim complete iOS runtime parity.
- The wider foundation still needs the remaining playlist/XMLTV/EPG migration, native TMDB discovery UI, Apple add-on screens and cloud-file integration. Existing Android playlist/guide/backup behavior remains in its existing host modules. No hosted add-on or account backend was deployed.
