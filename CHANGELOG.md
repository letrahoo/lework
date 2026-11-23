# Changelog

**English** | [简体中文](./CHANGELOG.zh-CN.md)

All notable changes to **Lework** since its public open-source launch are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

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

[Unreleased]: https://github.com/letrahoo/lework/compare/v0.7.10...HEAD
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
