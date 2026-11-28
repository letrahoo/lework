# Changelog

**English** | [简体中文](./CHANGELOG.zh-CN.md)

All notable changes to **Lework** since its public open-source launch are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

---


## [v0.9.3] - 2026-11-28

### Added
- **support picking and sending local file attachments** ([#61](https://github.com/letrahoo/lework/pull/61))

---

## [v0.9.2] - 2026-11-27

### Added
- **simplify mobile composer action icons and context-aware placeholder** ([#60](https://github.com/letrahoo/lework/pull/60))

### Changed
- **pin Android release signing across CI and local builds** ([#57](https://github.com/letrahoo/lework/pull/57))

### Fixed
- **align sidebar controls and declutter mobile drawer chrome** ([#59](https://github.com/letrahoo/lework/pull/59))

---

## [v0.9.1] - 2026-11-27

### Added
- **persist tsnet device identity across reinstalls and self-heal state** ([#58](https://github.com/letrahoo/lework/pull/58))

---

## [v0.9.0] - 2026-11-27

### Added
- **support read-only /skills and /skill inspection in embedded Pi sessions** ([#52](https://github.com/letrahoo/lework/pull/52))
- **allow unrestricted host file preview and multi-language syntax highlighting** ([#53](https://github.com/letrahoo/lework/pull/53))
- **structured auto titles, spinning sidebar loading indicators & project-first sidebar UX** ([#54](https://github.com/letrahoo/lework/pull/54))
- **Lework Hub host switcher, non-destructive pairing & 8-principle UX polish** ([#55](https://github.com/letrahoo/lework/pull/55))

### Changed
- **bump Lework milestone to v0.9.0** ([#56](https://github.com/letrahoo/lework/pull/56))

---

## [v0.8.23] - 2026-11-27

### Added
- **native AGY ACP parity, slash commands, 8-principle UX overhaul & strict session isolation** ([#51](https://github.com/letrahoo/lework/pull/51))

---

## [v0.8.22] - 2026-11-27

### Fixed
- **hide draft badge and preview for the currently active session** ([#50](https://github.com/letrahoo/lework/pull/50))

---

## [v0.8.21] - 2026-11-27

### Fixed
- **enforce strict AGY session isolation across terminal bridge, stream-json & desktop warm subscriptions** ([#49](https://github.com/letrahoo/lework/pull/49))

---

## [v0.8.20] - 2026-11-27

### Added
- **add GitHub Release update notifier and Desktop one-click DMG updater** ([#48](https://github.com/letrahoo/lework/pull/48))

---

## [v0.8.19] - 2026-11-27

### Added
- **per-session drafts, in-app local file preview, auto-focus composer & session shortcuts** ([#47](https://github.com/letrahoo/lework/pull/47))

---

## [v0.8.18] - 2026-11-27

### Changed
- **add bilingual system architecture diagrams and overview** ([#46](https://github.com/letrahoo/lework/pull/46))

---

## [v0.8.17] - 2026-11-27

### Added
- **add explicit multi-host picker for Tailscale tsnet discovery** ([#45](https://github.com/letrahoo/lework/pull/45))

---

## [v0.8.16] - 2026-11-27

### Added
- **upgrade tailscale.com to v1.104.7 and add Desktop global lework CLI registration** ([#43](https://github.com/letrahoo/lework/pull/43))

---

## [v0.8.15] - 2026-11-27

### Fixed
- **model execution steps card lifecycle, clear stale plans across turns, and serialize cancel/prompt** ([#44](https://github.com/letrahoo/lework/pull/44))

---

## [v0.8.14] - 2026-11-27

### Fixed
- **修复桌面端导入本机 Hub TLS 证书路径导致无法免配直连** ([#42](https://github.com/letrahoo/lework/pull/42))

---

## [v0.8.13] - 2026-11-27

### Added
- **redesign InlineApprovalBanner with refined Everforest adaptive options** ([#41](https://github.com/letrahoo/lework/pull/41))

---

## [v0.8.12] - 2026-11-27

### Fixed
- **sort model reasoning efforts in canonical low-to-max order** ([#40](https://github.com/letrahoo/lework/pull/40))

---

## [v0.8.11] - 2026-11-27

### Fixed
- **support multi-term model search, strip namespace prefix in family chips, and auto-refresh filter dialog** ([#39](https://github.com/letrahoo/lework/pull/39))

---

## [v0.8.10] - 2026-11-26

### Fixed
- **Preserve Split Streaming Replies Across Pagination & Hydrate AGY Full Transcript** ([#36](https://github.com/letrahoo/lework/pull/36)):
  - Expanded Hub's per-session in-memory event buffer (`MAX_EVENTS_PER_SESSION`) from `5,000` to `20,000` and updated `sliceSessionWindow` (`snapStartToIntactItems` / `snapEndToIntactItems`) so initial loads, reverse pagination (`beforeSeq`), and search-jump slices (`aroundSeq`) never cut through the middle of a streaming `message.assistant.delta` / `reasoning.delta` or `tool.call` / `tool.update` sequence.
  - Replaced drop-by-ID deduplication in `ConsoleStore.loadOlderHistory` with `mergePrependedOlderItems`, losslessly stitching split Assistant/Reasoning/Tool items across page boundaries while remaining idempotent on retries.
  - Added lazy hydration from AGY's companion `transcript_full.jsonl` whenever `transcript.jsonl` contains `truncated_fields` or `<truncated N bytes>` placeholders, and included full-transcript mtime/size in host-history cache keys.

---

## [v0.8.9] - 2026-11-26

### Fixed
- **Desktop Composer `Shift + Enter` Newline, Search Term Highlighting & Auto-Select on Reopen** ([#35](https://github.com/letrahoo/lework/pull/35)):
  - Fixed `Shift + Enter` (and `Option/Alt + Enter`) failing to insert newlines in Compose Desktop (`DesktopComposer` & `SessionComposer`) via `TextFieldValue` selection insertion while respecting active IME composition and keeping plain `Enter` for submission.
  - Added shared `SearchHighlight.kt` (`LocalSearchHighlightQuery` / `LocalSearchHighlightPrimary`) to highlight matched search terms in `DesktopSearchDialog` (snippets, session titles, workspace names) and inline across jumped timeline messages (`ConversationTimeline` + `EverforestMarkdown` headings, paragraphs, lists, quotes, tables, code fences, and user bubbles) using Everforest gold primary/secondary contrast.
  - Reopening Global Search (`⇧⌘F`) or In-Session Find (`⌘F`) now auto-selects the previous non-empty query so typing immediately replaces it.

---

## [v0.8.8] - 2026-11-26

### Added
- **AGY-Style Live Execution Steps Tree & Inline Clickable SubAgent Cards** ([#33](https://github.com/letrahoo/lework/pull/33)):
  - Extended `PlanEntryStatus` (`skipped`, `failed`), `SubagentSummary` (`parentId`, `depth`, `stepsDone`, `stepsTotal`), and `stream.subscribe` (`latestPlan`) across `protocol-schema`, `hub`, and KMP clients.
  - Added unified `hub/src/storage/host-history/plan-steps.ts` mapping AGY `update_steps`, Claude `TodoWrite` / `TaskCreate` / `TaskUpdate`, and Codex `update_plan` to `plan.updated` events, and fixed `parseLiveSpinnerLine` to skip multiline tree glyphs and accurately capture live step counters.
  - Added bottom-anchored `ExecutionStepsCard` (fixing inverted `PlanCard` placement in `LazyColumn(reverseLayout = true)`) with live spinner, step progress bar, tree indentation, and automatic collapse upon completion.
  - Promoted `invoke_subagent` / `Task` / `Agent` / `swarm` / `run_workflow` calls into inline clickable `InlineSubagentDispatchCard` timeline entries linked directly to `SubagentInspectorDialog`, and surfaced step progress, elapsed duration, and nesting depth in `SubagentStatusStrip` and inspector views.

---

## [v0.8.7] - 2026-11-26

### Added
- **Native Cross-Platform LaTeX Inline & Block Math Rendering** ([#34](https://github.com/letrahoo/lework/pull/34)):
  - Added pure KMP `EverforestMath.kt` integrated into `EverforestMarkdown.kt` with zero WebView overhead and full drag-selection compatibility across Desktop, Android, and iOS.
  - Renders inline math (`$...$`, `\(...\)`, single-line `$$...$$`) in paragraphs, headings, lists, quotes, and table cells — supporting Greek letters, operators/arrows, blackboard-bold symbols, upright math functions, native super/subscripts, compact fractions, and roots while protecting code spans, escaped `\$`, and currency/environment tokens (`$50`, `$200`).
  - Renders block math (`$$...$$`, `\[...\]`) in Everforest Dark cards with stacked fractions (`\frac`), big operators with centered limits (`\sum` / `\prod` / `\int`), overline roots (`\sqrt`), `cases` / `pmatrix` / `bmatrix` / `aligned` environments, horizontal scrolling, and a one-click **Copy LaTeX** button.

---

## [v0.8.6] - 2026-11-26

### Added
- **Android `tsnet` Forwarder & Native Dial Diagnostics** ([#30](https://github.com/letrahoo/lework/pull/30)):
  - Correlated `preamble.accept/reject`, `dial.start`, and dial results by anonymous local port in the Android client-only `tsnet` forwarder, exposing sanitized `LeworkTsDiag` logcat telemetry and wiring Android unit tests into CI.

### Fixed
- **Host Session Workspace Fallback, Symlinked `$HOME` & Claude Turn-2 Self-Lock** ([#31](https://github.com/letrahoo/lework/pull/31)):
  - Filtered filesystem root (`/`) from host workspace discovery, handled symlinked `$HOME` paths (e.g., `/home/<user> -> /data00/home/<user>`), safely fell back unusable root/generic `host:*` session `cwd`s to allowed `workspaceRoots` while preserving strict sandbox rejection with actionable CLI guidance for real out-of-root projects, and avoided spurious `checkpoint.list` errors on read-only host sessions.
  - Ignored Hub's own `claude-agent-acp` / `claude-agent-sdk` subprocesses (`ownedBySelf`, `sdk-*`, non-TTY) in `TerminalBridge` so Turn 2+ on materialized `host:claude:*` sessions no longer fails with false `unreachable_terminal` (`-32003`).

---

## [v0.8.5] - 2026-11-26

### Added
- **System-Wide macOS `⌃⌘E` Window Toggle & Universal `Esc` Modal Dismissal** ([#32](https://github.com/letrahoo/lework/pull/32)):
  - Bundled a permissionless Carbon/AppKit JNI bridge (`libLeworkMacHotkey.dylib`) registering `Ctrl + Cmd + E` (`⌃⌘E`) to show, hide, or activate Lework across English & Chinese IMEs, alongside Dock reopen, `⌘W` hide-to-background, clean `⌘Q` quit, deep-link auto-show, and MenuBar fallback.
  - Added KMP-pure LIFO `ModalEscapeDispatcher` and `Modifier.dialogEscapeDismiss` wired across Desktop Window preview key events and all modal dialogs/overlays (Settings, Workspace, Usage, Inspector, Model Picker, Global Search, Subagent Inspector, Provider Editor/Filter, Image Lightbox, Mermaid Fullscreen, Rename, Deep-Link Pairing, In-Session Find Bar) so pressing `Esc` reliably closes the topmost layer even without text focus.

---

## [v0.8.4] - 2026-11-25

### Changed
- **Expanded Detekt / KMP Static Lint Governance & Cross-Platform Hardening** ([#29](https://github.com/letrahoo/lework/pull/29)):
  - Expanded Detekt static analysis (`maxIssues: 0`) to 8 custom AST rules under `lework:` (`IosUnsafeVariadicCall`, `IosMainThreadUiKit`, `KmpCommonPlatformApi`, `SuspendCatchSwallowsCancellation`, `SuspendBlockingSleep`, `UnsafeNonNullAssertion`, `ComposeNestedSelectionContainer`, `ComposeMutableCollectionState`).
  - Resolved 100+ lint and cross-platform reliability issues across iOS (`CFRelease` null guards, main-queue UIKit dispatch, deterministic Skia resource `.close()`, `invokeOnCancellation`), JVM Desktop (suspending direct/SSH retries without `Thread.sleep`, Skia resource close), Android (`Bitmap` recycling), and `commonMain` (eliminating production `!!`, preserving `CancellationException`, normalizing TLS fingerprint casing).
  - Synced baseline cross-platform version metadata to `0.8.4`.

---

## [v0.8.3] - 2026-11-25

### Added
- **Free Conversation Text Selection & One-Click Message Copy** ([#28](https://github.com/letrahoo/lework/pull/28)):
  - Restored free drag-to-select text selection across Assistant Markdown replies (headings, paragraphs, lists, quotes, tables, code blocks) and conversation notice/error/approval/plan/queue banners with Everforest Dark `LocalTextSelectionColors`, wrapping code headers and Mermaid chrome in `DisableSelection` to keep copied text clean.
  - Raised Markdown virtualization thresholds (`16,000` chars / `32` blocks on Desktop; `3,200` chars / `10` blocks on Mobile) and removed Desktop `LazyColumn` click interception so multi-paragraph drag selection remains uninterrupted.
  - Added one-click **Copy** buttons alongside timestamps on User messages, Assistant replies, virtualized long replies, and `NetworkErrorRecoveryBanner`.

---

## [v0.8.2] - 2026-11-25

### Fixed
- **iOS Launch Crash from Native Variadic `NSLog` & Detekt AST Guardrails** ([#27](https://github.com/letrahoo/lework/pull/27)):
  - Replaced Foundation variadic `NSLog("%@", ...)` calls in iOS runtime logging with Kotlin `println` while retaining sanitized rolling `lework-runtime.log` file logging.
  - Integrated Detekt (`detektAll`, `detektCommonMain`, and custom AST rule `lework:IosUnsafeVariadicCall`) into Gradle and CI to permanently prevent unsafe iOS variadic calls and non-KMP imports in `commonMain`.

---

## [v0.8.1] - 2026-11-24

### Fixed
- **AGY Turn-2+ Presence Lock Self-Lock False Positive** ([#26](https://github.com/letrahoo/lework/pull/26)):
  - Skipped external-terminal lock rejection when Hub already owns a live managed AGY worker, tracked async `pendingCloses` before respawning, and added `inspectProcessAncestry()` so Hub's own direct/descendant `agy --input-format stream-json` workers are never mistaken for external IDE terminals while keeping genuine external lock protection intact.

---

## [v0.8.0] - 2026-11-24

### Added
- **Host-Scoped Model Providers, CPA Plugin, Model Curation & Same-Name Disambiguation** ([#25](https://github.com/letrahoo/lework/pull/25)):
  - Introduced a pluggable `ProviderAdapter` architecture (`hub/src/registry/provider-adapters.ts`) with per-host isolated `<LEWORK_HOME>/providers.json` storage and `provider.list` / `provider.upsert` / `provider.remove` / `provider.probe` RPCs:
    - **CPA plugin (`cpa`)**: zero-config auto-discovery of local `:8317` / `cpa-config.yaml` / `LEWORK_CPA_KEY` with latency probing and Usage statistics linkage.
    - **Generic adapter (`generic`)**: supports OpenAI / Anthropic / Ollama-compatible gateways (OpenRouter, LiteLLM, New-API, Ollama, custom proxies) with `0600` key storage (`hasApiKey`-only RPC), `apiKeyEnv`, and `manualModels` fallback.
  - Merged official and protocol-compatible external chat models per engine (`claude`, `codex`, `pi`; `agy` remains strictly official-only) with seamless in-session cross-provider switching and isolated environment-variable injection/cleanup.
  - Upgraded end-to-end model identity to `(engineId, providerId, modelId)` across Protocol, Hub, and KMP clients with source chips, natural sorting, per-provider model curation filtering, and a new **Model Providers (Current Host)** management panel in Desktop Settings (`DesktopProvidersSection.kt`).

---

## [v0.7.15] - 2026-11-24

### Added
- **Cross-Engine Full-Text Conversation Search (`⇧⌘F`) & In-Session Find (`⌘F`)** ([#22](https://github.com/letrahoo/lework/pull/22)):
  - Added `session.search` RPC, `stream.subscribe` `aroundSeq` / `hasMoreAfter` historical windowing, and `feature.search.v1` capability.
  - Implemented an incremental, zero-dependency Node 22 `node:sqlite` FTS5 (`trigram`) search index (`~/.lework/search-index.db`) with CJK short-query fallback and noise filtering.
  - Added Desktop `⇧⌘F` Global Search Palette (`DesktopSearchDialog`), Sidebar quick-search bridge, `⌘F` In-Session Find Bar (`DesktopInSessionFindBar`), matched-message jump highlight, and a floating **Jump to Latest** pill.
- **Host Engine Readiness-Driven Task Creation** ([#24](https://github.com/letrahoo/lework/pull/24)):
  - Derived Desktop and Mobile task creation engines, search/favorites, and draft defaults directly from the connected Hub's installed CLI entrypoints and non-interactive authorization status, blocking unauthenticated routes and removing simulated fallback messages.

### Fixed
- **Chat Timeline Vertical Bounce & Host Session State Oscillation** ([#23](https://github.com/letrahoo/lework/pull/23)):
  - Pinned `ClaudeLiveSpinnerRow` to a single-line fixed `28.dp` height and stabilized collapsed trace/subagent row heights in `LazyColumn(reverseLayout = true)`.
  - Added a `3.5s` `terminalActiveUntil` lease and `3.2s` `liveStatusText` hold hysteresis in `host-history.ts` to eliminate `350ms / 1400ms / 2500ms` state ping-pong.
  - Hardened auto-scroll and reverse-pagination guards, scoped background warm-session usage/artifact refreshes to the active session, and added anomaly-only `[TIMELINE]` diagnostics.

---

## [v0.7.14] - 2026-11-24

### Fixed
- **Strict TCP Tailscale Loopback WhoIs & Fallback Hub Probing** ([#20](https://github.com/letrahoo/lework/pull/20)):
  - Restricted loopback `WhoIs` verification strictly to TCP socket mappings, refreshed the active Tailscale account UID on every authentication check (preventing stale UID reuse across account switches), and continued probing fallback peers when initial hubs cannot authenticate under the same account while preserving saved-token connections.

---

## [v0.7.13] - 2026-11-24

### Added
- **Cross-Platform Secret-Free Runtime Diagnostic Logs** ([#21](https://github.com/letrahoo/lework/pull/21)):
  - Added unified token/secret-redacted rolling diagnostic logging (`RuntimeLog.kt`) across Desktop (`~/.lework/desktop/runtime.log`, Settings copy-path row, and uncaught crash logger), iOS (`Documents/lework-runtime.log`), Android (`filesDir/lework-runtime.log`), and Hub (`[lework-ws]` structured lifecycle logs).

### Fixed
- **Reconnect & Redirect Loop Guards** ([#21](https://github.com/letrahoo/lework/pull/21)):
  - Prevented RPC decode errors from tearing down healthy WebSocket connections, stopped misclassifying Desktop Local Direct (`127.0.0.1`) as embedded `tsnet`, added Bonjour auto-retry cooldown/cap, debounced brief Desktop window focus flickers, capped expired OAuth restarts, and deferred `NEEDS_AUTH` transitions during `tailscaleSameUser` fallback.

---

## [v0.7.12] - 2026-11-24

### Changed
- **Active-First SubAgent Ordering & Collapsible History** ([#18](https://github.com/letrahoo/lework/pull/18)):
  - Prioritized running, waiting-approval, and unconfirmed SubAgents by default across Desktop and Mobile while moving completed runs into a collapsible history section; aligned Hub explicit-finish vs silent-timeout classification and summary retention caps.

---

## [v0.7.11] - 2026-11-24

### Fixed
- **macOS `Tailscale.app` Netstack Loopback WhoIs & Multi-Peer `tsnet` Probe Starvation** ([#19](https://github.com/letrahoo/lework/pull/19)):
  - Added `tailscale whois --json` verification for `127.0.0.1` / `::1` userspace `netstack` proxy connections in `system-tailscale` mode (fixing `tailscaleSameUser: false` / `403` on macOS hosts) and corrected IPv6 ULA formatting.
  - Raised OkHttp `maxRequestsPerHost` and iOS `HTTPMaximumConnectionsPerHost` to `32` to eliminate `127.0.0.1:<port>` slot starvation across multi-node Tailnets, added `prioritizeTsnetPeers` to probe paired and desktop/server hosts ahead of mobile peers, and wired sandbox paths + `NetMon.InjectEvent()`.

---

## [v0.7.10] - 2026-11-24

> Consolidated milestone release ([#16](https://github.com/letrahoo/lework/pull/16)).

### Added
- **Official Engine Brand Icons**: Authentic official vector brand marks across Desktop and Mobile for Claude, OpenAI Codex, Google Antigravity (4-color gradient arch), Pi (3-color emblem), DeepSeek, Cursor, Trae, and ACP.
- **Unified Per-Engine Artifacts Inspector**: Added `artifact.list` and `artifact.read` RPCs with strict path containment, unified artifact discovery across AGY brain, Claude plans/artifacts, and Codex plans, plus an in-app inspector panel with one-click plan execution approval.
- **Full-Stack Pasted Image Display & Mobile Touch Paste**:
  - Parses AGY native `USER_INPUT.media` (`.user_uploaded/*`) images with session-brain containment and image-only turn support.
  - Downscales and caches thumbnails for oversized (`> 260KB`) Claude and Codex inline screenshots so high-DPI captures render cleanly on both Desktop and Mobile.
  - Parses Codex `input_image` blocks and staged image prompt headers.
  - Adds a touch-friendly **Paste Clipboard Image** action in the Mobile composer image menu (`SessionComposer`) and enlarges Desktop multi-image user bubbles with click-to-zoom lightbox support.
- **Repository Agent Collaboration Rules**: Added `AGENTS.md`, `CLAUDE.md`, and `.github/pull_request_template.md` for pre-flight conflict checks and cleaner parallel PR hygiene.

### Fixed
- **Stop Button Instant UI Sync for Host Terminal Sessions**: Immediately transitions stopped `host:*` sessions to `idle`, introduces cancel watermarks to prevent 75s transcript mtime bounce-back, parses Claude `[Request interrupted by user]` and AGY cancelled step statuses, and upgrades `ConsoleStore.cancelActiveTurn()` with immediate feedback and visible error reporting.
- **Codex Approvals & Session Mode Enforcement**: Properly honors Codex approval responses and applies active session modes to Codex turns.
- **Auto-Migrate Legacy Serial-Like macOS Hub Names**: Automatically upgrades existing `config.json` names that match serial-like `os.hostname()` defaults to the friendly macOS `ComputerName` on startup without overwriting user-customized names.

---

## [v0.7.9] - 2026-11-24

### Added
- **Queued Prompt Cancellation** ([#9](https://github.com/letrahoo/lework/pull/9)):
  - Added `session.cancelQueued` RPC (`index` single-item removal or `all: true` clear) across `protocol-schema`, `hub`, and KMP clients.
  - Hub-managed sessions cleanly splice or clear pending turns in FIFO order; live host-terminal (`host:*`) sessions safely dismiss entries from Lework's queue without sending disruptive terminal interrupts and display a clear notice.
  - Added per-item `×` cancel buttons and a multi-item **Clear All** action to `QueuedPromptsCard`, plus a quick cancel action on the composer queue strip, with instant optimistic updates and failure rollback.

---

## [v0.7.8] - 2026-11-23

### Added
- **Clipboard Image Paste in Composer** ([#10](https://github.com/letrahoo/lework/pull/10)):
  - Added cross-platform `readImagesFromClipboard()` support across JVM/macOS, Android, and iOS.
  - Desktop composer intercepts `⌘V` / `Ctrl+V` when the clipboard contains screenshots/bitmaps (including macOS Retina `MultiResolutionImage`), copied Finder image files, or raw `image/*` MIME streams, while transparently passing text-only clipboards through to native text paste.
  - Reuses the Skia thumbnail & progressive downscale pipeline, enforcing engine image capabilities and the 4-image attachment cap.

---

## [v0.7.7] - 2026-11-23

### Added
- **Live SubAgents Status Strip & Drill-Down Inspector** ([#7](https://github.com/letrahoo/lework/pull/7)):
  - Added `SubagentSummary` metadata to `SessionMeta` and live subagent discovery for AGY and Claude Code host sessions.
  - Displays a live **SubAgents** activity strip inside parent conversations on both Desktop and Mobile, with click-to-inspect read-only SubAgent transcript modals.

---

## [v0.7.6] - 2026-11-23

### Added
- **Network Error Alert Banner & One-Tap Seamless Turn Retry** ([#6](https://github.com/letrahoo/lework/pull/6)):
  - Detects host offline conditions, transient network failures, and upstream disconnects across engine adapters and Hub turns.
  - Collapses repeated timeline error notices (`×N`) and surfaces a clear recovery banner with a one-tap **Retry & Continue** button that reconnects and seamlessly resumes the interrupted turn without losing unsent prompts or attached images.

---

## [v0.7.5] - 2026-11-23

### Changed
- **Always-On macOS / iOS CI Verification** ([#8](https://github.com/letrahoo/lework/pull/8)):
  - Re-enabled automatic `iOS KMP Framework & Native Bridge Link (macOS)` CI checks on every pull request and `main` push so Linux, Android, Desktop, and iOS builds are continuously verified on the public repository.

---

## [v0.7.4] - 2026-11-23

### Added
- **Native Cross-Platform Mermaid Diagram Rendering** ([#4](https://github.com/letrahoo/lework/pull/4)):
  - Implemented a native Compose Canvas Mermaid renderer in Everforest Dark (`flowchart`, `sequence`, `state`, `class`, `er`, `pie`) with a **Diagram / Source** toggle, fullscreen zoomable lightbox, and streaming-safe fallback.

### Changed
- **Desktop Sidebar Pin & Row Action Polish** ([#5](https://github.com/letrahoo/lework/pull/5)):
  - Replaced the hollow pin stroke with a solid filled `LeworkPinIcon`.
  - Switched inline row actions to hover-only visibility and reordered buttons to `[Rename] [Archive] [Pin]`.

### Fixed
- **macOS `launchd` `lsof` Resolution & Lock Probe** ([#2](https://github.com/letrahoo/lework/pull/2)):
  - Added `/usr/sbin` and `/sbin` to Hub search paths and the macOS LaunchAgent `PATH`, introduced `resolveLsofBin()`, and removed the incompatible `-b` flag on macOS single-file `lsof` lock probes.

---

## [v0.7.3] - 2026-11-23

### Added
- **Local Direct Detection, Custom Host Rename & Friendly macOS Defaults** ([#3](https://github.com/letrahoo/lework/pull/3)):
  - Added `127.0.0.1` loopback prioritization on Desktop and surfaced a clear **Local Direct** connection indicator.
  - Added custom host alias renaming (`PairedHost.customName`) across Desktop sidebar, Desktop settings, and Mobile drawer.
  - Improved fresh macOS Hub initialization to replace serial-number-like MDM hostnames with `scutil --get ComputerName` or hardware model labels.

---

## [v0.7.2] - 2026-11-23

### Fixed
- **Clickable Inline-Code URLs & CJK Punctuation Trimming** ([#1](https://github.com/letrahoo/lework/pull/1)):
  - Made URLs inside inline code spans clickable in `EverforestMarkdown` and trimmed trailing CJK or closing punctuation from bare URLs.
- **Generic Git Forge SSH Host Filtering** ([#1](https://github.com/letrahoo/lework/pull/1)):
  - Filtered Git forge aliases (`User git`, `git.*` / `code.*` prefixes) cleanly from the Desktop SSH pairing host picker.
- **PR CI Trigger Alignment** ([#1](https://github.com/letrahoo/lework/pull/1)):
  - Removed `paths-ignore` from `.github/workflows/ci.yml` so required status checks always run on PRs.

---

## [v0.7.1] - 2026-11-23

### Added
- **Initial Public Open-Source Release**:
  - Published Lework under the Apache-2.0 license with bilingual documentation (`README.md` and `README.zh-CN.md`), full repository history sanitization, and automated cross-platform release pipelines (macOS DMG + Android APK + `SHA256SUMS.txt`).
  - Redesigned the Lework brand mark across macOS (`.icns`), iOS, Android, and in-app UI (`assets/brand/lework-logo.svg`).
  - Added `pnpm desktop:install` (`scripts/desktop-install.sh`) for incremental local macOS `/Applications/Lework.app` builds and installs.

---

[Unreleased]: https://github.com/letrahoo/lework/compare/v0.9.3...HEAD
[v0.9.3]: https://github.com/letrahoo/lework/compare/v0.9.2...v0.9.3
[v0.9.2]: https://github.com/letrahoo/lework/compare/v0.9.1...v0.9.2
[v0.9.1]: https://github.com/letrahoo/lework/compare/v0.9.0...v0.9.1
[v0.9.0]: https://github.com/letrahoo/lework/compare/v0.8.23...v0.9.0
[v0.8.23]: https://github.com/letrahoo/lework/compare/v0.8.22...v0.8.23
[v0.8.22]: https://github.com/letrahoo/lework/compare/v0.8.21...v0.8.22
[v0.8.21]: https://github.com/letrahoo/lework/compare/v0.8.20...v0.8.21
[v0.8.20]: https://github.com/letrahoo/lework/compare/v0.8.19...v0.8.20
[v0.8.19]: https://github.com/letrahoo/lework/compare/v0.8.18...v0.8.19
[v0.8.18]: https://github.com/letrahoo/lework/compare/v0.8.17...v0.8.18
[v0.8.17]: https://github.com/letrahoo/lework/compare/v0.8.16...v0.8.17
[v0.8.16]: https://github.com/letrahoo/lework/compare/v0.8.15...v0.8.16
[v0.8.15]: https://github.com/letrahoo/lework/compare/v0.8.14...v0.8.15
[v0.8.14]: https://github.com/letrahoo/lework/compare/v0.8.13...v0.8.14
[v0.8.13]: https://github.com/letrahoo/lework/compare/v0.8.12...v0.8.13
[v0.8.12]: https://github.com/letrahoo/lework/compare/v0.8.11...v0.8.12
[v0.8.11]: https://github.com/letrahoo/lework/compare/v0.8.10...v0.8.11
[v0.8.10]: https://github.com/letrahoo/lework/compare/v0.8.9...v0.8.10
[v0.8.9]: https://github.com/letrahoo/lework/compare/v0.8.8...v0.8.9
[v0.8.8]: https://github.com/letrahoo/lework/compare/v0.8.7...v0.8.8
[v0.8.7]: https://github.com/letrahoo/lework/compare/v0.8.6...v0.8.7
[v0.8.6]: https://github.com/letrahoo/lework/compare/v0.8.5...v0.8.6
[v0.8.5]: https://github.com/letrahoo/lework/compare/v0.8.4...v0.8.5
[v0.8.4]: https://github.com/letrahoo/lework/compare/v0.8.3...v0.8.4
[v0.8.3]: https://github.com/letrahoo/lework/compare/v0.8.2...v0.8.3
[v0.8.2]: https://github.com/letrahoo/lework/compare/v0.8.1...v0.8.2
[v0.8.1]: https://github.com/letrahoo/lework/compare/v0.8.0...v0.8.1
[v0.8.0]: https://github.com/letrahoo/lework/compare/v0.7.15...v0.8.0
[v0.7.15]: https://github.com/letrahoo/lework/compare/v0.7.14...v0.7.15
[v0.7.14]: https://github.com/letrahoo/lework/compare/v0.7.13...v0.7.14
[v0.7.13]: https://github.com/letrahoo/lework/compare/v0.7.12...v0.7.13
[v0.7.12]: https://github.com/letrahoo/lework/compare/v0.7.11...v0.7.12
[v0.7.11]: https://github.com/letrahoo/lework/compare/v0.7.10...v0.7.11
[v0.7.10]: https://github.com/letrahoo/lework/compare/v0.7.9...v0.7.10
[v0.7.9]: https://github.com/letrahoo/lework/compare/v0.7.8...v0.7.9
[v0.7.8]: https://github.com/letrahoo/lework/compare/v0.7.7...v0.7.8
[v0.7.7]: https://github.com/letrahoo/lework/compare/v0.7.6...v0.7.7
[v0.7.6]: https://github.com/letrahoo/lework/compare/v0.7.5...v0.7.6
[v0.7.5]: https://github.com/letrahoo/lework/compare/v0.7.4...v0.7.5
[v0.7.4]: https://github.com/letrahoo/lework/compare/v0.7.3...v0.7.4
[v0.7.3]: https://github.com/letrahoo/lework/compare/v0.7.2...v0.7.3
[v0.7.2]: https://github.com/letrahoo/lework/compare/v0.7.1...v0.7.2
[v0.7.1]: https://github.com/letrahoo/lework/releases/tag/v0.7.1
