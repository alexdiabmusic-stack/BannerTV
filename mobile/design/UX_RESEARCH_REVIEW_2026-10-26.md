# Banner TV research review and evidence register

Date: 26 October 2026. Supports [the audit and proposed direction](UX_AUDIT_AND_DIRECTION_2026-10-26.md).

## Supplied pack

All seven documents in `/Users/alexdiab/Downloads/BannerTV-UX-Research-Pack/` were read completely before preparing the proposal:

| Document | Applied to this review |
| --- | --- |
| README.md | Audit → three directions → system → one vertical slice → evidence-based iteration. |
| SOURCES.md | Actual retrieval of relevant pages/transcripts and inspection of public reference images; source age and access limits recorded below. |
| BANNERTV_DESIGN_PRINCIPLES.md | Sports-first relevance, restrained navy/blue identity, sport-specific states, distinct phone/TV interaction, honest availability. |
| PROMPT.md | Prioritized findings, affected files, three competing hierarchies, exact proposed tokens, preserved functionality and a scoped implementation sequence. |
| QA_CHECKLIST.md | Weighted areas, edge cases, actual screenshot/input evidence and no unsupported pass score. |
| AGENTS.md | Reviewed as supplied design-process guidance; not copied into the repository root or treated as new user authority. |
| CLAUDE.md | Same distinction; its content duplicates the supplied repository-guidance template. |

The user's explicit request to present findings before substantial changes determines this pass's boundary. Earlier authorization for iOS parity does not make an unreviewed redesign necessary. Emulator testing remains deferred by the user's explicit choice.

## How the references were studied

Relevant official guidance and research articles were retrieved through Firecrawl. Apple session transcripts were read, either fully for the principal design sessions or in relevant passages for technical sessions. The Android I/O transcript and selected token-architecture passages from the long UI Collective tutorial were read. **No video playback was watched.** NN/g video pages exposed descriptions rather than transcripts; those were not treated as watched content.

Public product images were visually inspected for Apple Sports, DAZN's 2021 TV schedule, NBA/theScore/FotMob public Mobbin previews, the DAZN concept, MultiViewer and the independent F1 concept. These were visual references, not interactive end-to-end product tests. Mobbin's full flows were not accessed behind sign-in/payment. No product subscription was bought and no access restriction was bypassed.

Retrieval dates and the pack's labels are not proof that an image represents a current shipped product. Firecrawl may return recently indexed content. This proposal uses the documented design principles, not claims that every source's release/version is current on every device.

## Foundations

| Source / evidence read | Principle extracted | Banner decision |
| --- | --- | --- |
| [NN/g: visual hierarchy](https://www.nngroup.com/articles/visual-hierarchy-ux-definition/) — article body | Establish content priority before using size, contrast and grouping. Too many competing emphasis treatments flatten importance. | Event/Watch first; quiet score rows; fewer bordered summary containers. Evaluate real team art, not only an empty template. |
| [NN/g: visual hierarchy video](https://www.nngroup.com/videos/visual-hierarchy/) — page description only | The available description reinforces hierarchy through size/color and grouping. | Supporting pointer only; the full article supplies the actual rationale. No claim of video inspection. |
| [NN/g: progressive disclosure](https://www.nngroup.com/articles/progressive-disclosure/) — article body | Keep frequently needed choices visible; clearly label access to advanced detail. Hiding everything does not produce simplicity. | Watch/recovery remain immediate. Playback preferences and detailed source diagnostics live in a secondary layer. |
| [NN/g: interaction cost](https://www.nngroup.com/articles/interaction-cost-definition/) — article body | Scanning, memory, scrolling, waiting and attention shifts all contribute to effort. | Fix Home's misleading live destination; reduce guide filter stacks; measure correct-game-to-play tasks rather than a universal click limit. |
| [NN/g: recognition and recall](https://www.nngroup.com/articles/recognition-and-recall/) — article body | Visible familiar actions, history and context reduce memory burden; gesture tutorials are not enough. | Keep labelled source/restart actions, recent channel and stable return position. Do not make gestures the only access path. |
| [NN/g: IA study guide](https://www.nngroup.com/articles/ia-study-guide/) — index and method descriptions | Navigation presents an underlying organization; tree testing/card sorting can expose poor category labels. | Explicit destinations and a route inventory before shell changes. Proposed navigation remains a hypothesis until task testing. Linked child articles were not all independently read. |
| [NN/g: vague prototyping prompts](https://www.nngroup.com/articles/vague-prototyping/) — article body | Generic styling requests often produce repeated elements and misplaced visual emphasis. Specific content, states and critique improve decisions. | Define exact event/availability/recovery contracts and realistic edge-case fixtures before screen styling. |
| [NN/g: context libraries](https://www.nngroup.com/articles/context-libraries/) — article body | Context needs scope, ownership and maintenance; stale guidance can misdirect later work. | Keep this dated proposal separate from approved implementation and historical validation. Do not install supplied instruction templates silently. |
| [NN/g: handmade designs](https://www.nngroup.com/articles/handmade-designs/) — article body | Perceived authorship and truthful brand behavior matter; fashionable visual warmth can misrepresent poor reliability. | Distinctive typography, authentic marks and careful copy; no artificial hand-drawn treatment, paper texture or decorative imperfection added to scoreboards. |
| [NN/g: information scent](https://www.nngroup.com/videos/information-scent/) — description and linked-article synopsis only | Label plus context shapes expectations about a destination. | “Live games” must open live games; “Restart stream” must not imply event rewind. No claim that the video was watched. |

## Official platform references

| Source / evidence read | Principle extracted and application |
| --- | --- |
| [Apple HIG](https://developer.apple.com/design/human-interface-guidelines/) — landing index | Used as a route to relevant platform references, not claimed as an exhaustive reading of the entire HIG. |
| [Designing for tvOS](https://developer.apple.com/design/human-interface-guidelines/designing-for-tvos/) — page body | Viewing distance, immersion and visible focus require a separate TV layout. Share identity, not phone geometry. |
| [Remotes](https://developer.apple.com/design/human-interface-guidelines/remotes) — page body | Standard directional, Back and media-button behavior should stay predictable. Audit the topmost overlay's focus/input ownership and restore the originating item. Translate principles to Android rather than copying Siri Remote gestures. |
| [Live Activities](https://developer.apple.com/design/human-interface-guidelines/live-activities) — relevant anatomy/best-practice passages | Glanceable event updates prioritize concise state and adapt to limited space; they are not promotional surfaces. Apply to Android score widgets/notifications, preserving Android platform constraints. No literal Dynamic Island clone proposed. |
| [Apple Design Resources](https://developer.apple.com/design/resources/) — resource index | Native controls, text roles and platform-specific symbols are references for consistency. No Apple font/template download or cross-platform asset-license assumption. |
| [Android TV design](https://developer.android.com/design/ui/tv) — index/component descriptions | Choose layout and components by task: lists, tabs, drawers and details have distinct jobs. A generic media carousel is not automatically the correct sports home. |
| [Android TV focus](https://developer.android.com/design/ui/tv/guides/styles/focus-system) — page body and state matrix | Focus, press, selection and disabled states are distinct. Use one clear ring/lift and explicit selection, with only one focus owner at a time. |
| [Android TV layouts](https://developer.android.com/design/ui/tv/guides/styles/layouts) — page body | Align navigation axes with the task and leave space for focused bounds. Keep critical UI inside the overscan-safe region. The page contains differing example dp margins; use its 5% principle and validate against Banner's actual density scheme. |
| [Compose for TV](https://developer.android.com/training/tv/playback/compose) — page body | TV components provide remote/focus behavior; mixing phone/TV theme systems needs care. Audit existing custom components before introducing another styling dependency. No version upgrade made. |
| [Compose TV codelab](https://developer.android.com/codelabs/compose-for-tv-introduction) — relevant catalog/detail/navigation/theme sections | Lifecycle-aware data collection, structured browse/details and theme reuse support maintainability. Its movie carousel is an example, not Banner's recommended information architecture. Sample not built or executed. |
| [Amazon Fire TV UX](https://www.developer.amazon.com/docs/fire-tv/design-and-user-experience-guidelines.html) — substantive guidelines | Important content first, all actions D-pad reachable, clear focus/selection, limited typing and 10-foot legibility. Use safe margins including focus growth. Historical launcher imagery is not a modern skin to copy. |

Additional official Android sources were consulted because the pack is relatively TV-heavy:

- [Make apps more accessible](https://developer.android.com/guide/topics/ui/accessibility/apps): contrast, meaningful labels and at least 48×48dp touch targets. Used to assess fixed light-mode status colors and propose explicit target allocations.
- [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults): built-in semantics, parent toggle/select roles and automatic hit-region enlargement. A 42dp visual control is a review concern, not proof of a 42dp effective touch target; overlap and actual semantics still need testing.

## Video/transcript study

| Session | Access and scope | Application |
| --- | --- | --- |
| [WWDC26: UI prototypes with agents](https://developer.apple.com/videos/play/wwdc2026/227/) | Full textual transcript read, not video watched. | Compare genuinely different hierarchies, test realistic/empty/long-content states and tune interactions through rendered iterations. Three proposals precede implementation. |
| [WWDC26: principles of great design](https://developer.apple.com/videos/play/wwdc2026/250/) | Full textual transcript read. | Agency, clear purpose, familiarity, flexibility and craft inform predictable navigation, recovery, accessibility and truthful state. Delight follows useful behavior rather than extra effects. |
| [WWDC26: brand identity on iOS](https://developer.apple.com/videos/play/wwdc2026/251/) | Full textual transcript read. | Conventional navigation and distinctive content can coexist. Apply the principle with Android-native controls; retain theme/text preferences and use color meaningfully. |
| [WWDC25: design foundations](https://developer.apple.com/videos/play/wwdc2025/359/) | Textual transcript read. | Organize features before styling; orient every screen; use lists for scan-heavy content and semantic colors/text roles for adaptable hierarchy. |
| [WWDC23: focus cookbook](https://developer.apple.com/videos/play/wwdc2023/10162/) | Relevant focus/state/section transcript passages read. | Treat focus as the current input target and make group transitions intentional. No SwiftUI API transplanted into Compose. |
| [WWDC21: tvOS playback](https://developer.apple.com/videos/play/wwdc2021/10191/) | Relevant control/context transcript passages read. | Put common playback tasks in discoverable controls and preserve consistent contextual focus behavior. Android retains Media3 and its own player integration. |
| [WWDC26: tvOS Dynamic Type](https://developer.apple.com/videos/play/wwdc2026/221/) | Relevant large-text/reflow transcript passages read. | Reflow custom layouts rather than freezing scale or truncating every label. This reinforces the TV font-scale audit; not a claim that all Android TV devices expose identical settings. |
| [WWDC26: Xcode, agents, and you](https://developer.apple.com/videos/play/wwdc2026/259/) | Selected project exploration, preview, review and testing transcript passages read. | Keep proposal, implementation, preview evidence and test evidence distinct. Xcode workflow does not change this Android project's toolchain. |
| [WWDC20: UI typography](https://developer.apple.com/videos/play/wwdc2020/10175/) | Relevant optical size, line height and text-style passages read. | Use a disciplined scalable type system and aligned scores; do not stretch tracking or shrink text to rescue rigid layouts. |
| [Google I/O 2024 TV update](https://www.youtube.com/watch?v=s0jUt5LTtpI) | Retrieved transcript read; historical 2024 presentation. | Resource-constrained TV testing, media-session lifecycle and accessible controls belong in quality checks. Historical API availability is not treated as current guidance. |
| [UI Collective: design-system variables](https://www.youtube.com/watch?v=L-tpK7Eeuow) | Description/chapter list and selected token-architecture transcript passages; not the entire two-hour tutorial. | Separate primitive values, semantic roles and component use. A modest two-layer system is adequate here; no enterprise multi-brand machinery or Figma project is necessary for this pass. |

## Sports and media references

| Source | Evidence and limits | Applied principle |
| --- | --- | --- |
| [Apple Sports App Store](https://apps.apple.com/ca/app/apple-sports/id6446788829) | Public listing read; not an installed-app session. | Personal teams/leagues, live state and useful detail should lead. Use one event-summary contract across screens. |
| [Apple Sports user guide](https://support.apple.com/en-ca/guide/apple-sports-app/apd5a38547d1/web) | Guide read and official schedule image visually inspected. | Day navigation, favourite-first groups, aligned score/time positions and quiet separators. |
| [Apple Sports announcement](https://www.apple.com/ca/newsroom/2024/02/introducing-apple-sports-a-new-app-for-sports-fans/) | Historical 2024 design intent, read. | Fast personal score access and a clear watch handoff; no assumption that scores prove playlist availability. |
| [Mobbin Sports](https://mobbin.com/explore/mobile/app-categories/sports) | Public collection plus selected NBA, theScore and FotMob previews visually inspected. Full flows gated; no sign-in/payment bypass. | NBA separates score/date/state/action; FotMob supports chronological league-group scanning; theScore's editorial hierarchy belongs in Banner's news surface. |
| [DAZN in Apple's 2021 awards](https://www.apple.com/newsroom/2021/12/app-store-awards-honor-the-best-apps-and-games-of-2021/) | Historical official TV schedule image visually inspected. | Separate destination, day, sport filter and event choice; keep selected navigation readable. Not evidence about current DAZN focus/playback behavior. |
| [Les Moires DAZN concept](https://les-moires.com/dazn-en) | Portfolio concept, 2023–24; text, wireframes and selected mockups inspected. Vimeo not watched. | Event-phase context can be useful below viewing. Do not adopt speculative rewards/betting, intrusive notifications or decorative composites. Not proof of shipped features. |
| [MultiViewer](https://multiviewer.app/) | Unofficial desktop product page read; main/onboard image inspected. | Optional context and saved setups; one dominant video; spoiler handling across art and text. Do not claim synchronization without timing evidence. |
| [MultiViewer showcase](https://multiviewer.app/showcase) | Public community multi-display images inspected. | Density is chosen by the viewer and spread across available space; not a rationale for four equal phone videos. |
| [F1 TV Premium](https://f1help.formula1.com/article/using-f1-tv-premium) | Official help read. | Explicit layout/feed/audio selection and return to one feed. Its four-tile product limit is not a Banner hardware-capacity guarantee. |
| [Apple TV F1 support](https://support.apple.com/guide/tv/watch-formula-1-atvbc353f9e7/tvos) | Official guide read; retrieved default identified tvOS 27. | Distinguish live, start-over, replay and feed choice; Back dismisses current controls. Use precise Banner action labels. |
| [FotMob / Seven Peaks](https://sevenpeakssoftware.com/case-studies/app-for-football/) | 2020 engineering case read, chiefly web. | Incremental audit and refactor preserving existing operation. Its performance percentages and dated images are not Android targets. |
| [Independent F1 preparation concept](https://medium.com/@fahrbachadrian/ui-ux-case-study-the-process-of-designing-a-f1-app-for-race-preparation-e189857dfca3) | 2018 concept article read; final prototype image inspected. One reported interview, usability testing still future work. | Circuit/session context is sport-specific; speculative race-excitement predictions and opaque indices are not validated product requirements. |

Specific public screen previews inspected:

- [NBA Game Day](https://mobbin.com/explore/screens/fc9aeace-83d3-479b-84d1-1362d05937b7): calendar strip, matchup state and viewing labels.
- [FotMob Home](https://mobbin.com/explore/screens/5c07269f-2df2-46cd-a829-ad850a70e323): compact league-headed matches with date selection.
- [theScore News](https://mobbin.com/explore/screens/fb8cc41f-4fe0-4981-8f15-1e056a0f7244): featured story followed by smaller timestamped stories. Their screenshot dimensions do not prove compliant touch targets.

Additional supporting reads: [MultiViewer requirements](https://multiviewer.app/docs/getting-started/requirements) describes desktop CPU/GPU demands; [Apple TV multiview controls](https://support.apple.com/guide/tv/control-video-playback-atvb7944597f/27/tvos/27#atvb4f9aada0) supplies a visually inspected dominant/subordinate video arrangement and visible audio owner. Banner must preserve provider concurrency limits and verify device capacity before expanding simultaneous playback.

## Implementation and workflow links

[Android TV samples](https://github.com/android/tv-samples) and [TV codelabs](https://github.com/android/tv-codelabs) repository READMEs/structure were read. They identify JetStreamCompose, TvMaterialCatalog and accessibility examples for later component verification. No sample app was executed or copied wholesale.

The remaining links are workflow references, not evidence for the UI findings. They were not needed or studied in this audit because no Figma integration, Claude configuration or Codex setup is being changed:

- [Figma MCP introduction](https://developers.figma.com/docs/figma-mcp-server/)
- [Figma file structure](https://developers.figma.com/docs/figma-mcp-server/structure-figma-file/)
- [Figma agent prompts](https://developers.figma.com/docs/figma-mcp-server/write-effective-prompts/)
- [Claude context guidance](https://support.claude.com/en/articles/14553240-give-claude-context-claude-md-and-better-prompts)
- [Codex AGENTS.md reference](https://learn.chatgpt.com/docs/agent-configuration/agents-md)
- [Codex iteration cookbook](https://developers.openai.com/cookbook/examples/codex/iterating-development-workflows-with-codex)

Their contents were not inferred from their URLs. They remain available if a future selected implementation actually needs those workflows.

## Resulting design judgments

The research favors **event relevance, understandable state, familiar interaction and careful hierarchy**. It does not prescribe a single visual template. Live Desk is a Banner-specific synthesis: the speed of a scoreboard, the clear viewing action of an event service and optional context beside a retained player.

The proposed navy palette, radii, type sizes, motion values and exact Home order are design choices for this project, not numbers asserted by the references. They must be evaluated with actual current renders and tasks. Source study is complete for this proposal's relevant scope; visual/product quality certification is not.
