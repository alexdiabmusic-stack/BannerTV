# Adaptive Android phone parity — 2026-10-26

Working evidence, not a full-parity or physical-foldable certification.

## Reference

GitHub `alexdiabmusic-stack/StadiaTV-iOS` main rechecked at
`b58b989aa5f9e30b0396ba81ddc73da3dcb7c7ab`:

- `MyApp/Adaptive/AdaptiveLayoutContext.swift`, `AdaptiveTwoPane.swift`, `GameCentreSplit.swift`.
- `MyApp/HomeView.swift`, `MatchesView.swift` (Following), `LiveView.swift`, `TVGuideView.swift`.
- `MyApp/PlayerView.swift` / `MatchPlayerScreen`, `docs/iPhoneDuoImplementation.md`.

The iOS notes themselves label their fold layouts source-level/unverified. They do not establish
device support on Android. No purported iOS-only reserved-region API was copied into Android.

## Implemented in this continuation

- Activity-owned, lifecycle-collected AndroidX WindowManager 1.5.1 features, with an empty initial
  value so devices that never emit a fold still render. The flow is not retained across Activities.
- Pure pixel geometry translates window-coordinate hinges into each content's coordinate space.
  Real off-centre hinges, separating zero-width folds, occlusion, outside-subtree features, RTL,
  text-scaled minimum pane widths and multiple/unsupported divisions are handled. A layout that
  cannot fit two usable panes chooses the largest hinge-free region rather than crossing it.
- Home overview/hero/channels/sports-day/fantasy beside live/schedule/news/history when usable.
  This bounded overview preserves both content columns in one shared scrolling composition.
- Following matches beside contextual news/standings; Live Sports beside starting soon, with no
  empty second pane. Long lists stay virtualized. These Android lazy panes scroll independently
  when expanded; they do not duplicate the iOS shared-scroll implementation for unbounded feeds.
- Guide grid beside programme details on usable expanded windows, with a selection placeholder
  and explicit Close. Compact and player-picker guides retain the sheet. The channel grid stays
  lazy; description/roster/replay/watch semantics and playlist-only EPG evidence are preserved.
- Sport Game Centres and F1: score/watch chrome beside full-height tabs, with compact stacking.
  Header and tab composition stays stable through resize; the header can scroll at large text.
- Attached-match player: book/expanded side-by-side and tabletop video/companion arrangements;
  classic portrait/landscape, explicit Game action and PiP remain supported. The video AndroidView
  is always the same unconditional first child, not reconstructed/keyed by geometry. Companion
  saveable state survives hiding, and narrow quick-action rows scroll to keep controls reachable.
  Back no longer depends on an ignored orientation request on >=600dp displays or multi-window.
- Latest source icon converted from the pinned original SVG/embedded PNG, with Git-blob verification
  and a reproducible importer; only the phone launcher resource changes. See design/ICON_PROVENANCE.md.
  Home/Following hero and Sports Day Watch controls also revalidate actual EPG evidence at tap time.

## Documentation checked

- [WindowInfoTracker](https://developer.android.com/reference/kotlin/androidx/window/layout/WindowInfoTracker):
  Activity lifetime, Flow API and delayed/absent initial emissions.
- [FoldingFeature](https://developer.android.com/reference/kotlin/androidx/window/layout/FoldingFeature):
  application-window bounds, separating folds and fully occluded regions.
- [Window releases](https://developer.android.com/jetpack/androidx/releases/window#1.5.1): stable 1.5.1.
- [Android16 adaptive changes](https://developer.android.com/about/versions/16/behavior-changes-16#large-screens):
  target36 large-display orientation restrictions. Small fullscreen phones keep rotate-before-exit;
  large and multi-window players exit instead of trapping Back on a no-op orientation request.

## Code verification passed; runtime deferred

31 new pure geometry/navigation/state tests cover compact/expanded/font/empty-secondary behavior, off-centre/zero-width/
translated/outside/multiple/malformed folds, occlusion, RTL, tabletop, classic player, attached-match
auto layout and PiP. First full checkpoint75237 exited0 in11m44s:856 tests,851 pass/five optional skips,
zero failures/errors; zero-error all-module debug/release-only lint and phone/TV packaging.
858 icon/Home-tap/Back follow-up91122 exited0 in7m5s:853 pass/five optional skips/no failures/errors,
all zero-error lint/packaging gates. Signature v2/alignment and exact packaged icon pixels passed.
23170 was deliberately stopped before optimization to include the Back fix. Gradle warned about
384-MiB metaspace on the successful858 build; subsequent checks restore the usual512-MiB metaspace.
Final861 source additionally namespaces hidden companion saved state by a digest of game/channel
identity: stream copies retain the game tab, another game/channel cannot inherit it, and no raw
provider identity/URL enters the state key. Complete861 gates5928 exited0 in8m34s:856 pass/five
optional skips/no failures/errors; all zero-error lint and debug/minified-phone/TV packaging.
Final v2 signature/16-KiB alignment pass. Release SHA-256:
`a383312ea799dbc2cf67afad3defee6ebf9b04fc8aac98d92606fdbe431f000b`.
Exact debug hashes, warnings and development-signing limits are in VALIDATION.md.
Icon re-import is byte-reproducible and rejects a non-pinned source without writing output.
The final861 packaged icon pixels match the original conversion exactly.

Actual resize, selected-tab, guide sheet/inline, large-text and player checks are not yet claimed.
The emulator was deliberately not started while host free disk dropped to~600MiB. The owner
explicitly chose to continue code checks and defer emulator QA. No unrelated user data/cache was removed.
No playlists/rosters, selected league, device settings or emulator data were changed in this pass.

## Remaining

Root app/navigation chrome, modal-window coordinate ownership and other secondary screens still need
a full hinge/cutout audit. Physical
fold posture, TalkBack, decoder/PiP/Cast/Auto hardware and long-session verification are unavailable
without devices. The new73bf44c engine delta is audited but unported in IOS_ENGINE_73BF_AUDIT.md.
Adaptive/monochrome icon layers, imported ESPN/Sleeper joins, nuanced sport/podcast/presentation
items and production billing/backend setup remain in PARITY_EXECUTION.md. Full iOS parity is open.
