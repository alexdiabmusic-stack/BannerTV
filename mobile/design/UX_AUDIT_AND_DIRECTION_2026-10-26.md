# Banner TV: UI/UX audit and proposed direction

Date: 26 October 2026. Status: **proposal for review; no application changes made**.

Recommendation: **Live Desk** — a precise, personal sports scoreboard with a quiet viewing layer. Put followed live games, reliable viewing actions and the day's schedule ahead of promotional or administrative content. Express the Banner identity through navy surfaces, excellent score typography, authentic team marks and a restrained blue accent.

This review follows the user's request to present the audit and direction before substantial changes. The seven supplied research-pack documents were read in full, including AGENTS.md and CLAUDE.md as supplied reference documents. They were not installed as repository instructions. Relevant external documentation, articles, transcripts and public product images were studied; access and evidence limits are recorded in [the research review](UX_RESEARCH_REVIEW_2026-10-26.md).

## Scope and evidence

The current checkout contains an Android phone application (`mobile`), Android/Fire TV application (`app`), and shared data/playback code (`core`). The primary design target is the Android phone; TV findings are included because the pack and prior requirements explicitly cover remote navigation. There is no local Mac product being redesigned in this pass. Apple interfaces are pattern references, not an instruction to reproduce iOS controls on Android.

Evidence combines current source inspection with archived, actual emulator screenshots. **There are no new screenshots or interaction tests of the latest 861 checkpoint.** Emulator QA remains deferred at the user's request. Source evidence can establish a conditional or layout order, but cannot certify rendered appearance, touch targets, TalkBack output or physical D-pad behavior.

The earlier recorded 861 gate passed 856 tests with five optional skips, builds and zero-error lint; that gate was not rerun for this documentation-only review. It does not establish a visual-quality score. See [validation](../VALIDATION.md).

### Visual baseline inspected

| Actual archived image | What it supports | Limit |
| --- | --- | --- |
| [807 Home](../docs/screenshots/fantasy807-home-compact.png) | Large sports-day and fantasy containers precede Live Now; live cards require horizontal browsing. | Already scrolled; not proof of the initial viewport or latest adaptive layout. |
| [830 Guide, 150% text](../docs/screenshots/guide830-second-offline-cold-150.png) | Three control rows, date controls and search consume substantial height above the guide. Programme titles truncate at this size. | Earlier cold/offline build, not current 861 rendering. |
| [814 player](../docs/screenshots/provider814-listed-feed-player.png) | Crowded channel header, several playback controls, companion shortcuts and rich match context compete for space. | A moment during an advertisement; no claim that a screenshot verifies correct event video or playback reliability. |
| [Following overview](../docs/screenshots/following-overview-1.7.png) | Several layers of filters and summary precede the next-game action. | Historical 1.7 image; later behavior may differ. |
| [716 LaLiga](../docs/screenshots/laliga716-pressure-real.png) | Rich, sport-specific context and a restrained scoreboard already exist. | Earlier checkpoint. |
| [Game Centre 1.1](../docs/screenshots/gamecentre-1.1.png) | Score/time dominance, team marks and a clear viewing action are useful existing foundations. | Old enough that individual content and controls must not be treated as current defects. |

## Current-state audit

No P0 was reproduced in this read-only review. P1 denotes a core-journey, trust or accessibility problem to address early; P2 denotes hierarchy, consistency or polish work. Runtime confirmation remains necessary where specified.

| ID / priority | Finding and consequence | Current source evidence | Proposed correction |
| --- | --- | --- | --- |
| UX01 / P1 | Home's Live Now link selects the Live tab, whose initial subsection is Channels. The destination can contradict the link's promise. | [Home:97](../src/main/kotlin/com/alexdiab/stadiatv/mobile/PhoneHomeSections.kt#L97), [Live:39](../src/main/kotlin/com/alexdiab/stadiatv/mobile/PhoneLive.kt#L39), MobileViewModel:241. | Give links explicit destinations such as LiveSports or TVGuide, including subsection and filter. Preserve that destination on return. |
| UX02 / P1 | Compact Home orders greeting, feature, favourite channels, sports-day and optional fantasy ahead of Live Now. Continue watching follows news. Watching and resuming compete with secondary information. | PhoneHomeSections:57–130; PhoneAdaptiveLayout:180. | Lead with followed live events and the current/last channel. Use a compact schedule immediately after. Keep fantasy and editorial content reachable lower down. |
| UX03 / P1 | Live Sports displays “Nothing live right now” whenever its filtered list is empty, without distinguishing loading or unavailable scores. A failed refresh can resemble a factual absence of games. | [Live:87](../src/main/kotlin/com/alexdiab/stadiatv/mobile/PhoneLive.kt#L87). Home already uses `phoneFeedUnavailable`. | Reuse a common loading/verified-empty/cached/unavailable state contract. If only a filter is empty, offer All games. |
| UX04 / P1 | Full player hides the controller's error reason behind “Stream interrupted” and always offers “Try another source.” The source list can contain only the current channel. Mini-player has no error branch and can say “Playing live” after failure. | [Player:202](../src/main/kotlin/com/alexdiab/stadiatv/mobile/PhonePlayerModern.kt#L202), MobileViewModel:278, [mini-player:193](../src/main/kotlin/com/alexdiab/stadiatv/mobile/PhoneApp.kt#L193). | One shared presentation of playback state; sanitize and explain the failure; show Restart stream, Change stream only with a usable alternate, otherwise Choose channel. Keep a failed mini-player visibly recoverable. |
| UX05 / P1 | TV hidden-chrome key routing can consume D-pad/Select while the error panel is visible. Auto-hide can also return focus to the video root during recovery. | [TV player:183](../../app/src/main/kotlin/com/alexdiab/stadiatv/ui/player/PlayerScreen.kt#L183), same file:252; PlayerChrome:240. | Recovery owns focus and input until dismissed/resolved. Suspend auto-hide for error, pause and accessibility interaction. This is a source-established competing path, not a reproduced physical-remote failure. |
| UX06 / P1 | Light-mode status colors are fixed rather than theme-specific. Small LIVE text calculates about 2.91:1 on its tinted white card; Connected green on white about 2.35:1. | [Theme:11](../src/main/kotlin/com/alexdiab/stadiatv/mobile/PhoneTheme.kt#L11), PhoneComponents:78, PhoneSetup:85. | Semantic foreground/container pairs per appearance, tested together. Keep words/icons so status does not depend on color. Calculations are not rendered screenshot measurements. |
| UX07 / P1 | TV theme sets `fontScale = 1f`, preventing the user's text enlargement from propagating. Custom toggle/filter controls also communicate state mainly through paint. | [TV theme:176](../../app/src/main/kotlin/com/alexdiab/stadiatv/ui/theme/Theme.kt#L176), SettingsComponents:93, Chips:44. | Honor text scaling with reflow; provide switch/tab/selection semantics and a visible check/selection marker. Preserve existing TV density handling until its replacement is verified. |
| UX08 / P1 | Guide's subsection/group/category/date/search stack reduces space for the core timeline, especially with large text. | [Guide:145](../src/main/kotlin/com/alexdiab/stadiatv/mobile/PhoneLive.kt#L145), archived 830 image. | One compact toolbar for date/Now/filter summary; expandable search; one filter sheet for groups/categories. Retain the real time grid and a pinned channel column. |
| UX09 / P2 | Following introduces several configuration rows and counts before the relevant game. | [Following:58](../src/main/kotlin/com/alexdiab/stadiatv/mobile/PhoneFollowing.kt#L58). | One entity filter and date strip; followed live/next games first. Put management in a clearly labelled destination rather than repeating it above the content. |
| UX10 / P2 | Colors and some typography are centralized, but cards, spacing, radii, headers and states are still local. Default bordered cards make unrelated information equally prominent. Navigation labels are forced to 10sp. | PhoneTheme:32; PhoneComponents:47; PhoneApp:140; TV FocusCard:79. | Define semantic components, spacing/shape/type roles and interaction states. Use dividers for score lists, contained panels for related detail, and a single featured surface. |
| UX11 / P2 | `SegmentedChoices` uses plain clickable styling for selection and a 42dp minimum; semantics are less explicit than existing Game Centre tabs. | PhoneHomeSections:143; PhoneGameCentre:213. | Reuse an accessible segmented/tab primitive with state and role; explicitly allocate 48dp touch targets so expanded hit regions do not overlap. Current small visual bounds alone do not prove an actual undersized Compose hit region. |
| UX12 / P2 | Playlist setup exposes User-Agent alongside basic fields, gives limited field-level guidance, and ends with “Start watching” even when the user chose scores without a playlist. | PhoneSetup:46,187–215; TV PlaylistForm:101. | Basic connection first, Advanced disclosure, actionable validation and contextual completion copy. Retain every supported playlist option and TV editor focus behavior. |
| UX13 / P2 | Settings combines destinations and many detailed switches in one long surface. Game Centre repeats individual provider notices between tabs and content. | PhoneSetup:233; PhoneGameCentre:119. | Group settings by task with value summaries. Use one compact data-status strip plus detailed diagnostics on demand; urgent score uncertainty remains visible. |
| UX14 / bounded trust audit | Following labels a direct cached count “confirmed streams” while other surfaces use `sourcesFor(match)` validation. | PhoneFollowing:148–155. | Reuse the shared, current evidence presentation before restyling availability. No wrong-stream playback was reproduced here. |
| UX15 / P2 | The Home header uses a generic play-circle while the launcher has the approved Banner mark. Brand identity is inconsistent at two prominent entry points. | PhoneApp:119; design/ICON_PROVENANCE.md. | Use the approved mark in an appropriate header asset, or a restrained wordmark alone; do not redraw a new logo or place the entire launcher tile in every toolbar. |

Line numbers refer to the reviewed source and can move. File links identify the affected files; `#L` anchors are for repository viewers.

### Preserve these strengths

Explicit Restart stream already exists on phone and TV. The work is to make recovery truthful and reachable, not to add a duplicate restart engine. Preserve retained playback, DVR/catch-up eligibility, captions/audio, PiP/Cast, source selection, saved state and current EPG evidence policy.

Real team marks, multi-sport score/state models, spoiler controls, offline provenance and retry paths are valuable. Phone controls already remain visible for TalkBack, pause and several other interaction states; its settings use full-row semantic switches. TV has editor/card focus separation and Back/panel restoration foundations. These should survive the redesign.

## Research translated into decisions

| Studied principle | Concrete Banner application |
| --- | --- |
| Hierarchy comes from content priority, scale, contrast and grouping, not decorating every section. [NN/g](https://www.nngroup.com/articles/visual-hierarchy-ux-definition/) | Give scores and Watch one clear emphasis; reduce repeated bordered summary cards. |
| Advanced features remain discoverable but need not all be visible initially. [NN/g](https://www.nngroup.com/articles/progressive-disclosure/) | Keep restart immediate during failure; move aspect/audio/diagnostics into labelled options. Do not hide the everyday Watch action behind them. |
| Interaction cost includes scanning, memory, scrolling and waiting, not simply tap count. [NN/g](https://www.nngroup.com/articles/interaction-cost-definition/) | Measure time to identify the correct game and start it; fix misdirected links before chasing a universal “three taps” rule. |
| Apple Sports organizes personal scoreboards and day-based lists. [Official guide](https://support.apple.com/en-ca/guide/apple-sports-app/apd5a38547d1/web) | Followed live games first, consistent score columns, obvious date navigation; scores never imply a playable IPTV stream. |
| Native navigation can coexist with distinctive content. [Apple branding transcript](https://developer.apple.com/videos/play/wwdc2026/251/) | Use Android navigation, sheets and semantics; put Banner identity into scoreboards, imagery, type and content rhythm. No imported Liquid Glass requirement. |
| Focus has default/focused/pressed states and selection is separate. [Android TV](https://developer.android.com/design/ui/tv/guides/styles/focus-system) | One restrained ring/lift, persistent selected marker, intentional D-pad paths and focus restoration. |
| TV needs distance legibility, low input burden and overscan-safe controls. [Amazon](https://www.developer.amazon.com/docs/fire-tv/design-and-user-experience-guidelines.html) | Different TV layout, larger text, safe margins and remote-accessible recovery; no stretched phone UI. |
| Multiview is contextual and resource-dependent. [MultiViewer](https://multiviewer.app/) | Default to one retained video plus an optional context panel. Preserve existing multiview access, but do not promote additional simultaneous decoders without provider/device verification. |

## Three distinct directions

All three preserve existing functionality and use the same evidence-based viewing rules. Their hierarchy, rather than merely their colors, differs.

| Direction | Content hierarchy | Visual language / core components | Strength | Tradeoff |
| --- | --- | --- | --- | --- |
| **A. Live Desk — recommended** | Followed live games → other live games → today's schedule → team context → stories/highlights | Compact aligned event rows; strong score type; navy structure; quiet dividers; one blue viewing action; date/sport filter | Designed for fast scanning across simultaneous games and equal treatment of multiple sports; task testing must confirm the advantage | Less cinematic; an occasional authentic editorial feature supplies warmth without displacing live games |
| **B. Matchday Editorial** | One relevant featured event → live scoreboard → schedule → stories/highlights | One licensed/available photograph; clear matchup block; editorial section titles; restrained image cards | Strong emotional identity and event storytelling | Art dependency; a hero can bury more relevant games. Followed live content must override a generic promotion |
| **C. Watch & Follow** | Watch: current/last channel → playable events → guide. Follow: teams → scores/schedule → fantasy/context | Larger tune/resume controls; channel/time workspace; compact companion panel; separate scorekeeping area | Good for heavy IPTV viewing and switching channels | More conceptual separation between checking a score and watching; requires excellent cross-links |

### A: proposed phone hierarchy

```text
Home                         Search
  Today · date selector      All sports / My teams
  Your live games            score + phase + Watch / View game
  Other live games           compact list; See all when long
  Today's schedule           chronological, grouped by league
  Your teams                 next event / standings link
  Stories & highlights       authentic imagery, contextual relevance

Following | Live | Discover | Settings remain reachable
Game → Game Centre → Watch → retained player → return to same game/list
TV Guide remains a direct destination; no forced sports detour
```

For the first slice, retain the existing five primary destinations. Make Home's links open explicit Sports/Guide/Channels subsections and default the Live section according to the link. This fixes intent without a premature shell migration. A later, separately reviewed navigation simplification could rename Home to Today and Live to TV, with sports consolidated under Today/Following; that is **not part of the first slice**.

When there are no followed teams, show all live games and one quiet “Choose teams” invitation. When no game is live and fresh data confirms that, show the next followed game and today's schedule. With 15+ live games, show a bounded overview and an obvious full list; do not make a horizontal carousel the only way to compare them. With unknown data, say so and preserve the last known timestamp.

### B and C hierarchy sketches

```text
B: Home → one current event feature → Live scoreboard → Today → Stories
   Feature → Game Centre → Watch; feature collapses/loses priority when irrelevant

C: Watch → current/last channel → playable events → Channels / Guide
   Follow → personal scoreboard → Schedule → Teams / Fantasy
   Event identity links Watch and Follow; each remembers its own place
```

## Proposed design system

These are concrete starting values for the selected direction, **not implemented or visually certified tokens**. Keep system/light/dark support. Use semantic names in components rather than calling a color “blue” or “green.” Share role names across phone/TV, with platform-specific size and interaction values.

### Color roles

| Role | Dark | Light |
| --- | --- | --- |
| Background | `#090F1B` | `#F3F6FA` |
| Surface / raised | `#111C2E` / `#1A2940` | `#FFFFFF` / `#E8EEF6` |
| Primary / secondary text | `#EEF4FC` / `#A6B4C8` | `#142136` / `#46566D` |
| Action / on-action | `#79B8FF` / `#071524` | `#005EC4` / `#FFFFFF` |
| Live foreground / container | `#FF8997` / `#3A1824` | `#B4233A` / `#FDE8EC` |
| Success foreground / container | `#69D2A1` / `#113528` | `#126D48` / `#E3F4EA` |
| Warning foreground / container | `#F0C778` / `#372C18` | `#785100` / `#FFF1CC` |
| Stale foreground / container | `#BFC9D7` / `#273344` | `#46566D` / `#E8EDF4` |
| Error foreground / container | `#FFB4AB` / `#3A1B1C` | `#A82924` / `#FDECEA` |

Calculated sRGB contrast for the proposed opaque text/container pairs: light Live 5.53:1, success 5.56:1, warning 6.29:1, stale 6.35:1, error 6.11:1; dark equivalents 6.94–9.14:1. Action text pairs are 8.86:1 dark and 6.18:1 light. These calculations do not cover imagery, opacity, pressed overlays or every rendered state; those require measurement. Team colors are identity accents, never the only indication of selection, status or readability.

### Space, shape, typography and motion

- Space scale: 4 / 8 / 12 / 16 / 24 / 32dp. Phone compact gutters 16dp, comfortable gutters 24dp. Reserve 32dp for major section changes rather than every row.
- Shape roles: controls 8dp; grouped panels 12dp; featured cards/sheets 20dp where appropriate; pill only for compact status/chips. Dense score and guide rows do not each need a large rounded border.
- Phone type: score 32/36sp semibold with tabular numerals; screen title 28/34sp bold; section 20/26sp semibold; team/body 16/22sp; secondary 14/20sp; compact status 12/16sp minimum. These are scalable text roles, not fixed-height containers. Use the Android system sans family; no new display-font dependency is needed to establish identity.
- Long names may wrap; scores keep an aligned column. At large text, switch horizontal action groups to vertical and reduce column count. Never solve overflow by disabling font scale.
- TV uses its own type/layout values and 10-foot review. Plan safe important-content margins of at least 5% of the viewport, including focused bounds. Reconcile the existing TV coordinate/density scheme before translating example dp values.
- Motion: 120ms press/focus response; 180ms simple state changes; about 220ms panel transitions as initial tuning values. Respect the system animation scale/reduced-motion behavior. No continuous score pulsing, auto-rotating promos or ambient shimmer after loading has ended. Functional video scrims remain allowed.
- Focus: a clearly contrasting 2dp ring plus modest lift/scale (up to 1.025 as a starting point); no layered neon halo. Selection keeps a check/underline when focus moves away. Test focused geometry for clipping.

### Component and state contract

| Component | Required behavior and states |
| --- | --- |
| EventRow / GameCard | Stable team/score/status positions; scheduled/live/intermission/delayed/postponed/suspended/final/replay/unknown; favourite and spoiler states; missing logo fallback; long names. Entire card opens details; a labelled Watch action stays distinct. |
| AvailabilityAction | Live event plus current confirmed playlist evidence → Watch live. Scheduled event → viewing information or explicitly labelled Watch channel. Completed event → eligible replay only; otherwise View game or Watch channel with a current-broadcast explanation. Unconfirmed possibilities → Choose stream. No playlist → Add playlist. No match → View game plus availability explanation. Loading/disabled/stale states are explicit. Revalidate at activation. |
| Button / filter / tab | Default, pressed, keyboard/TV focused, selected, disabled, loading and error. Minimum 48dp phone target; role/state semantics; disabled explanation where needed; loading does not silently become success. |
| FreshnessNotice | Fresh/refreshing/cached timestamp/unavailable; action Retry where useful. Concise visible summary with detailed provider/resource information behind Data status. |
| ProgrammeCell | Accurate duration and channel association; selected/focused/current/past/future; replay availability separate from current broadcast. Never call a reair a live event solely because its programme is on now. |
| SourceOption | Exact channel identity, guide evidence and current/alternate state; advanced match diagnostics disclosed separately. “Matched in your guide” is evidence, not a guarantee that the provider sends the correct video. |
| PlayerControls / RecoveryPanel | Connecting, playing, paused, buffering, recovering, failed, ended; consistent full/mini state. Clear Restart stream, Live edge, Start over and Change stream distinctions. Focus and Back belong to the topmost visible layer. |
| Dialog / sheet | Purposeful title and primary action, clear dismissal, restored origin focus/scroll, keyboard insets, large-text reflow, loading/error retention. No stacks of unnecessary modals. |

## Core flow and first implementation slice

The first slice should be **Home → Game Centre → existing player → return**, including recovery. Keep providers, stream matching thresholds, caches, decoder lifetime, credentials and billing behavior outside the visual refactor. Presentation fixes may consume existing state; engine work remains separately tracked in [the parity ledger](../PARITY_EXECUTION.md).

1. Establish tokens and shared EventRow, AvailabilityAction, FreshnessNotice and RecoveryPanel contracts. Review representative multi-sport states before spreading styling.
2. Reorder Home, deduplicate competing summary surfaces, repair named destinations and empty/unavailable messages. Preserve fantasy, favourites, history, podcasts and news routes.
3. Refine Game Centre into score/status → viewing action → tabs/content. Keep sport-specific tabs and explicit data uncertainty.
4. Refine player hierarchy while retaining its decoder: essential controls immediately available, secondary settings grouped, recovery explicit, mini/full state consistent. Back closes the current layer and restores the previous location.
5. Validate this slice against current screenshots and real touch/focus paths when runtime QA resumes. Only then extend the same system to Guide, Following, setup and TV.

A later Guide slice keeps the familiar channel/time grid. Phone gets compact controls and expandable details; TV gets predictable horizontal time / vertical channel movement, stable row focus, a time cursor, clear date/Now action and overscan-safe detail region. The goal is familiar television-guide behavior, not a pixel copy of another product.

### Success criteria

At a standard compact phone size, the first viewport should expose a relevant game and its state/action without scrolling past a greeting, promotion and setup summaries. Large-text acceptance prioritizes complete readable content over that viewport target. The fastest supported path from a visible confirmed game to playback should remain a single deliberate Watch activation; detailed inspection should remain one obvious card tap. Test actual task time and wrong-destination errors rather than treating tap count as universal proof.

Returning from playback must preserve event, filter/date, scroll and selected detail tab. An error must never leave “Playing live” visible or advertise a nonexistent alternate. A data outage must never be disguised as an empty sports day. One primary action should dominate each decision area.

## Quality status and decision

The pack's weighted target is at least 4.2/5 with no critical failures. **Current score: not assigned.** Device/emulator proof is required by the pack, and the user explicitly deferred emulator QA. Code review, reference study and historical screenshots cannot substitute for that proof.

The full state/flow matrix and evidence requirements are in [the QA plan](UX_QA_PLAN_2026-10-26.md). No app source, dependency, asset, build artifact, playlist, account or saved preference was changed in this review. No implementation direction has been approved yet.

**Decision requested: select A (Live Desk), B (Matchday Editorial), or C (Watch & Follow). Recommendation: A, with one restrained editorial feature borrowed from B when there is relevant authentic imagery.**
