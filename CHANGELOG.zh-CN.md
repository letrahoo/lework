# 更新日志（Changelog）

[English](./CHANGELOG.md) | **简体中文**

本文件记录 **Lework** 自正式开源以来的所有重要版本变更。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本（SemVer）](https://semver.org/lang/zh-CN/)。

---

## [未发布 / Unreleased]

---

## [v0.8.10] - 2026-11-26

### 修复
- **长流式回复跨分页完整保留 & AGY 完整历史日志按需回填**（[#36](https://github.com/letrahoo/lework/pull/36)）：
  - 将 Hub 单会话内存事件缓冲（`MAX_EVENTS_PER_SESSION`）从 `5,000` 扩容至 `20,000`，增强 `sliceSessionWindow`（`snapStartToIntactItems` / `snapEndToIntactItems`），确保首屏拉取、`beforeSeq` 逆向翻页与 `aroundSeq` 跳转窗口不会从流式 `message.assistant.delta` / `reasoning.delta` 或 `tool.call` / `tool.update` 中间切断。
  - 客户端 `ConsoleStore.loadOlderHistory` 用 `mergePrependedOlderItems` 替代原有按 ID 丢弃逻辑，跨页无损拼接被切分的回复/思考/工具项，并对重复重试保持幂等。
  - 当 AGY 宿主机紧凑日志 `transcript.jsonl` 命中 `truncated_fields` 或 `<truncated N bytes>` 截断标记时，按需懒加载同目录 `transcript_full.jsonl` 还原完整正文与思考过程，并将其 mtime/size 纳入历史缓存键。

---

## [v0.8.9] - 2026-11-26

### 修复
- **桌面端 `Shift + Enter` 换行、全局搜索全链路关键词高亮与重开自动全选**（[#35](https://github.com/letrahoo/lework/pull/35)）：
  - 修复桌面端输入框（`DesktopComposer` & `SessionComposer`）按下 `Shift + Enter`（及 `Option/Alt + Enter`）无法换行的问题，改为基于 `TextFieldValue` 选区显式插入 `\n`，兼容中文 IME 组合输入并保持普通 `Enter` 发送。
  - 新增共享高亮工具 `SearchHighlight.kt`（`LocalSearchHighlightQuery` / `LocalSearchHighlightPrimary`）：在 `DesktopSearchDialog` 中高亮命中片段、会话标题与工作区名称，并在点击跳转后的对话时间线（`ConversationTimeline` + `EverforestMarkdown` 标题、段落、列表、引用、表格、代码块及用户气泡）中以 Everforest 暖金主/副高对比度内联高亮关键词。
  - 再次触发全局搜索（`⇧⌘F`）或当前会话内查找（`⌘F`）时自动全选上一次的非空查询词，直接输入即可一键替换。

---

## [v0.8.8] - 2026-11-26

### 新增
- **AGY 风格实时执行步骤树（Live Steps）与时间线内联可点击 SubAgent 卡片**（[#33](https://github.com/letrahoo/lework/pull/33)）：
  - 协议与双端扩展 `PlanEntryStatus`（新增 `skipped` / `failed`）、`SubagentSummary`（`parentId`、`depth`、`stepsDone`、`stepsTotal`）以及 `stream.subscribe` 可选返回 `latestPlan`。
  - 新增 `hub/src/storage/host-history/plan-steps.ts`，统一解析 AGY `update_steps`、Claude `TodoWrite` / `TaskCreate` / `TaskUpdate` 与 Codex `update_plan` 为 `plan.updated` 事件；修复 `parseLiveSpinnerLine` 跳过多行步骤树并精准提取实时步数状态。
  - 新增底部锚定的 `ExecutionStepsCard`（修复原 `PlanCard` 在 `reverseLayout` 时间线中被倒置到最顶端的 Bug），展示动态 Spinner、微型进度条、树形缩进与全部完成后自动收起。
  - 将 `invoke_subagent` / `Task` / `Agent` / `swarm` / `run_workflow` 派发提升为时间线内联可点击卡片 `InlineSubagentDispatchCard`，一键直达 `SubagentInspectorDialog`；顶部 `SubagentStatusStrip` 与巡检弹窗同步展示步数进度、耗时与嵌套层级缩进。

---

## [v0.8.7] - 2026-11-26

### 新增
- **跨平台原生 LaTeX 行内与块级数学公式渲染**（[#34](https://github.com/letrahoo/lework/pull/34)）：
  - 新增纯 KMP `EverforestMath.kt` 并接入 `EverforestMarkdown.kt`，零 WebView 依赖，完整兼容 Desktop / Android / iOS 文本拖选复制与 Everforest Dark 视觉风格。
  - 行内公式（`$...$`、`\(...\)`、单行 `$$...$$`）全面覆盖正文、标题、列表、引用块与表格单元格，支持大小写希腊字母、运算/关系/箭头符号、黑板粗体、正体数学函数名、原生上下标、紧凑分式与根号，同时安全避开代码块、`\$` 转义及货币金额/环境变量（如 `$50`、`$200`）。
  - 块级公式（`$$...$$`、`\[...\]`）以 Everforest 暗色卡片居中展示，原生布局支持上下堆叠分式（`\frac`）、带上下限的大算子（`\sum` / `\prod` / `\int`）、带上横线的根号（`\sqrt`）、`cases` / `pmatrix` / `bmatrix` / `aligned` 多行矩阵环境、横向滚动与一键 **「复制 LaTeX」**。

---

## [v0.8.6] - 2026-11-26

### 新增
- **Android `tsnet` 本地转发器校验与原生拨号诊断**（[#30](https://github.com/letrahoo/lework/pull/30)）：
  - 在 Android client-only `tsnet` 转发器中按匿名本地端口关联 `preamble.accept/reject`、`dial.start` 与拨号结果，通过脱敏的 `LeworkTsDiag` logcat 白名单事件辅助排障，并将 Android 单测纳入 CI。

### 修复
- **远端宿主机工作区兜底、软链 `$HOME` 识别与 Claude 第 2 轮自锁误报**（[#31](https://github.com/letrahoo/lework/pull/31)）：
  - 过滤根目录 `/` 的误发现，兼容软链 `$HOME`（如 `/home/<user> -> /data00/home/<user>`），将无效根路径/通用家目录的 `host:*` 会话安全回退至允许的 `workspaceRoots`，同时对真实越界项目目录保留严格沙盒拦截与可执行 CLI 指引，并消除只读宿主机历史会话上的 `checkpoint.list` 报错。
  - 在 `TerminalBridge` 中忽略 Hub 自身派生的 `claude-agent-acp` / `claude-agent-sdk` 子进程（`ownedBySelf`、`sdk-*`、无 TTY），修复物化 `host:claude:*` 会话从第 2 轮起误报 `unreachable_terminal`（`-32003`）的问题。

---

## [v0.8.5] - 2026-11-26

### 新增
- **macOS 全局快捷键 `⌃⌘E` 唤起/隐藏窗口 & 全桌面弹层 `Esc` 关闭**（[#32](https://github.com/letrahoo/lework/pull/32)）：
  - 内置免辅助功能权限的 Carbon/AppKit JNI 桥接库（`libLeworkMacHotkey.dylib`），支持中英文输入法下按 `Ctrl + Cmd + E`（`⌃⌘E`）快速唤起、聚焦或隐藏主窗口，同时完善 Dock 点击恢复、`⌘W` 隐藏至后台、`⌘Q` 优雅退出、Deep Link 自动唤起与菜单栏入口。
  - 新增纯 KMP 后进先出（LIFO）`ModalEscapeDispatcher` 与 `Modifier.dialogEscapeDismiss`，接入桌面窗口顶层按键拦截及全部弹层（设置、工作区、用量、侧边巡检、模型选择器、全局搜索、SubAgent 巡检、模型服务编辑/筛选、图片灯箱、Mermaid 全屏、重命名、Deep Link 配对、会内查找条），即使输入框无焦点也可逐层按 `Esc` 关闭。

---

## [v0.8.4] - 2026-11-25

### 变更
- **Detekt / KMP 静态治理规则全面扩容与跨平台问题清零**（[#29](https://github.com/letrahoo/lework/pull/29)）：
  - 将 Detekt 静态检查（`maxIssues: 0`）扩展至 8 条 `lework:` 自定义 AST 规则（`IosUnsafeVariadicCall`、`IosMainThreadUiKit`、`KmpCommonPlatformApi`、`SuspendCatchSwallowsCancellation`、`SuspendBlockingSleep`、`UnsafeNonNullAssertion`、`ComposeNestedSelectionContainer`、`ComposeMutableCollectionState`）。
  - 系统修复 100+ 项 Lint 与跨平台隐患：iOS（`CFRelease` 判空、UIKit 主线程派发、Skia 资源确定性 `.close()`、`invokeOnCancellation`）、桌面 JVM（挂起重试替代 `Thread.sleep`、Skia 资源释放）、Android（`Bitmap` 回收）及 `commonMain`（消除生产代码 `!!`、正确重抛 `CancellationException`、统一 TLS 指纹大小写）。
  - 统一同步跨平台基线版本元数据至 `0.8.4`。

---

## [v0.8.3] - 2026-11-25

### 新增
- **对话消息自由划选与一键复制**（[#28](https://github.com/letrahoo/lework/pull/28)）：
  - 恢复 Assistant Markdown 回复（标题、段落、列表、引用、表格、代码块）及提示/报错/审批/计划/队列卡片的自由拖选复制，适配 Everforest Dark 选区配色（`LocalTextSelectionColors`），并用 `DisableSelection` 包裹代码块顶栏与 Mermaid 操作 chrome，防止复制结果掺入界面文字。
  - 提高 Markdown 虚拟化拆分阈值（桌面端 `16,000` 字符 / `32` 块、移动端 `3,200` 字符 / `10` 块），移除桌面端 `LazyColumn` 冗余点击拦截，保证跨段拖拽顺畅连续。
  - 为用户消息、AI 回复、超长虚拟化回复及网络恢复横幅增加时间戳旁的一键 **「复制 / 已复制」** 按钮。

---

## [v0.8.2] - 2026-11-25

### 修复
- **iOS 启动时原生可变参 `NSLog` 闪退修复 & Detekt AST 门禁**（[#27](https://github.com/letrahoo/lework/pull/27)）：
  - 用 Kotlin `println` 替换 iOS 运行日志中经由 cinterop 调用的 Foundation 可变参 `NSLog("%@", ...)`，保留原有脱敏与 `lework-runtime.log` 异步落盘。
  - 引入 Detekt（`detektAll`、`detektCommonMain` 及自定义 AST 规则 `lework:IosUnsafeVariadicCall`）接入 Gradle 与 CI，长期防回归并自动化保障 `commonMain` KMP 纯净性。

---

## [v0.8.1] - 2026-11-24

### 修复
- **AGY 第 2 轮起会话锁自锁误报**（[#26](https://github.com/letrahoo/lework/pull/26)）：
  - 当 Hub 已持有活跃的托管 AGY worker 时跳过外部终端锁拒绝，跟踪异步关闭中的 `pendingCloses` 防止重拉竞态，并新增 `inspectProcessAncestry()` 识别 Hub 自身直系/后代 `agy --input-format stream-json` 子进程，不再误判为外部 IDE 终端占用，同时保留对真实外部终端的锁保护。

---

## [v0.8.0] - 2026-11-24

### 新增
- **主机隔离的模型服务 Provider 架构、CPA 插件、模型筛选与同名区分**（[#25](https://github.com/letrahoo/lework/pull/25)）：
  - 引入可插拔 `ProviderAdapter` 架构（`hub/src/registry/provider-adapters.ts`），按每台主机独立存储 `<LEWORK_HOME>/providers.json`，新增 `provider.list` / `provider.upsert` / `provider.remove` / `provider.probe` RPC：
    - **CPA 插件（`cpa`）**：零配置自动发现本机 `:8317`、`cpa-config.yaml` 与 `LEWORK_CPA_KEY`，支持状态/延迟探测与 Usage 统计联动。
    - **通用标准服务适配器（`generic`）**：兼容 OpenAI / Anthropic / Ollama 标准接口（OpenRouter、LiteLLM、New-API、Ollama、自建网关），密钥仅落盘主机 `0600` 文件（RPC 仅返回 `hasApiKey`），支持 `apiKeyEnv` 与 `manualModels` 兜底。
  - 按协议兼容合并各引擎官方与外部对话模型（`claude`、`codex`、`pi`；`agy` 保持官方合规护栏），支持会话内跨 Provider 无缝切换与环境变量隔离清理。
  - 全链路按 `(engineId, providerId, modelId)` 三元组严格区分同名模型，提供来源徽标、自然排序、单服务模型勾选筛选，并在桌面端设置页新增 **「模型服务（当前主机）」** 管理面板（`DesktopProvidersSection.kt`）。

---

## [v0.7.15] - 2026-11-24

### 新增
- **跨引擎全文检索历史对话（`⇧⌘F`）与当前会话内查找（`⌘F`）**（[#22](https://github.com/letrahoo/lework/pull/22)）：
  - 新增 `session.search` RPC、`stream.subscribe` 的 `aroundSeq` / `hasMoreAfter` 历史窗口定位与 `feature.search.v1` 能力声明。
  - 基于 Node 22 内置 `node:sqlite` 构建零依赖 SQLite FTS5（`trigram`）增量索引（`~/.lework/search-index.db`），支持中文短词兜底检索与非正文噪点过滤。
  - 桌面端新增 `⇧⌘F` 全局搜索弹层（`DesktopSearchDialog`）、侧边栏全文检索桥接、`⌘F` 会话内查找条（`DesktopInSessionFindBar`）、命中消息跳转高亮及 **「回到最新消息 ↓」** 悬浮胶囊。
- **按当前连接主机的引擎入口与授权状态创建任务**（[#24](https://github.com/letrahoo/lework/pull/24)）：
  - 桌面端与移动端的新建任务引擎、搜索/收藏及草稿默认值直接取自当前连接 Hub 的实际 CLI 安装与限时非交互授权探测结果，拦截明确未授权路线并移除模拟成功兜底。

### 修复
- **聊天时间线垂直抖动与宿主机状态来回跳变**（[#23](https://github.com/letrahoo/lework/pull/23)）：
  - 将 `ClaudeLiveSpinnerRow` 固定为单行 `28.dp` 高度，并稳定 `LazyColumn(reverseLayout = true)` 中折叠态过程 trace 与 SubAgent 条高度。
  - 在 `host-history.ts` 引入 `3.5s` `terminalActiveUntil` 租约与 `3.2s` `liveStatusText` 迟滞保持，消除 `350ms / 1400ms / 2500ms` 多路轮询导致的状态乒乓。
  - 加固自动滚底与逆向翻页边界判断，收敛后台预热会话的冗余用量/产物刷新，并补充仅异常触发的 `[TIMELINE]` 诊断遥测。

---

## [v0.7.14] - 2026-11-24

### 修复
- **Tailscale 回环 WhoIs 严格 TCP 校验与后备主机继续探测**（[#20](https://github.com/letrahoo/lework/pull/20)）：
  - 回环 `WhoIs` 仅接受 TCP 端口映射，每次鉴权动态读取当前 Tailscale 账号 UID 防止切换账号后沿用旧身份；首批 Hub 无法完成同账号认证时继续探测后备节点，同时保留已有令牌的连接路径。

---

## [v0.7.13] - 2026-11-24

### 新增
- **全端无敏感信息滚动运行诊断日志**（[#21](https://github.com/letrahoo/lework/pull/21)）：
  - 统一实现自动脱敏的滚动运行日志（`RuntimeLog.kt`），覆盖桌面端（`~/.lework/desktop/runtime.log`、设置页一键复制路径与未捕获异常日志）、iOS（`Documents/lework-runtime.log`）、Android（`filesDir/lework-runtime.log`）以及 Hub 侧结构化 `[lework-ws]` 生命周期日志。

### 修复
- **重连与鉴权跳转死循环护栏**（[#21](https://github.com/letrahoo/lework/pull/21)）：
  - 隔离 RPC 解析异常避免误断健康 WebSocket，不再将桌面端「本机直连」（`127.0.0.1`）误判为内嵌 `tsnet`，新增 Bonjour 自动重试冷却与上限、桌面端窗口焦点短暂抖动防抖、OAuth 过期重启上限，并在 `tailscaleSameUser` 兜底前延迟切换 `NEEDS_AUTH`。

---

## [v0.7.12] - 2026-11-24

### 变更
- **SubAgent 默认优先展示进行中与未确认结束任务**（[#18](https://github.com/letrahoo/lework/pull/18)）：
  - 桌面端与移动端默认置顶展示运行中、等待审批及未确认结束的子任务，已结束记录收纳进可展开的历史入口；Hub 统一明确结束与静默超时判定，并在摘要条数受限时优先保留未结束任务。

---

## [v0.7.11] - 2026-11-24

### 修复
- **macOS `Tailscale.app` netstack 回环 WhoIs 握手与多节点 `tsnet` 并发探测饥饿**（[#19](https://github.com/letrahoo/lework/pull/19)）：
  - 为 `system-tailscale` 模式下的 `127.0.0.1` / `::1` 用户态 `netstack` 回环代理入站补齐 `tailscale whois --json` 身份核验（修复 macOS 主机返回 `tailscaleSameUser: false` / `403`），并修正 IPv6 ULA 参数格式。
  - 将 Android / JVM OkHttp `maxRequestsPerHost` 与 iOS `HTTPMaximumConnectionsPerHost` 提升至 `32` 以消除多节点 Tailnet 下的 `127.0.0.1:<port>` 槽位饥饿，新增 `prioritizeTsnetPeers` 优先探测已配对及桌面/服务器节点，并补齐沙盒目录与 `NetMon.InjectEvent()` 网络切换通知。

---

## [v0.7.10] - 2026-11-24

> 统一里程碑整合版本（[#16](https://github.com/letrahoo/lework/pull/16)）。

### 新增
- **官方引擎品牌图标**：在桌面端与移动端全面上线 Claude（官方放射星标）、OpenAI Codex（官方花结）、Google Antigravity（四色渐变拱门）、Pi（三色标）、DeepSeek、Cursor、Trae 与 ACP 的官方矢量图标。
- **统一 Artifacts 产物查看器**：新增 `artifact.list` / `artifact.read` RPC 与严格路径安全边界校验，统一聚合 AGY Brain、Claude Plan/Artifact 与 Codex Plan 产物，提供产物面板与一键「确认执行方案」。
- **粘贴图片全端展示 & 手机端触控粘贴**：
  - 解析 AGY 原生 `USER_INPUT.media`（`.user_uploaded/*`）用户上传图片，带会话目录边界校验并支持纯图片无文字消息。
  - 对超过 260KB 的 Claude / Codex 内联高清截图自动生成并缓存缩略图，桌面端与手机端对话气泡均可直接展示并点击放大查看。
  - 补齐 Codex 历史会话中的 `input_image` 与暂存图片头解析。
  - 手机端输入框「添加图片」菜单新增触控入口 **「粘贴剪贴板图片」**；桌面端优化含图用户气泡宽度与多图预览排版。
- **仓库 Agent 协作规范**：新增 `AGENTS.md`、`CLAUDE.md` 与 `.github/pull_request_template.md`，固化多会话冲突预检与 PR 交付规范。

### 修复
- **停止按钮「终端已停但 UI 未停」同步修复**：停止本机终端会话（`host:*`）后立即切换为 `idle` 并广播，引入取消水位线防止 75 秒内因文件 mtime 回弹回 `running`；正确识别 Claude `[Request interrupted by user]` 与 AGY 中断状态并收敛未完成工具；客户端 `cancelActiveTurn()` 增加即时停止反馈与失败报错提示。
- **Codex 审批回复与会话模式生效**：修复 Codex 审批响应处理，并确保会话模式（Mode）在每轮执行中正确生效。
- **Hub 启动自动迁移老配置序列号主机名**：当既有 `config.json` 中的主机名仍为默认生成的序列号形式且等于 `os.hostname()` 时，启动自动迁移为友好的 macOS `ComputerName`，且绝不覆盖用户手动改过的名称。

---

## [v0.7.9] - 2026-11-24

### 新增
- **队列消息取消按钮**（[#9](https://github.com/letrahoo/lework/pull/9)）：
  - 新增 `session.cancelQueued` 协议与双端实现，支持按 `index` 取消单条或 `all: true` 一键清空。
  - Hub 托管会话精确移除待执行队列项；本机终端会话（`host:*`）安全移除 Lework 侧排队展示并给出明确提示，不误发中断信号打断当前执行轮次。
  - `QueuedPromptsCard` 为每条队列消息增加右侧 `×` 取消按钮，多条时展示 **「全部取消」**，输入框队列提示条同步提供快捷取消，支持乐观更新与失败回滚。

---

## [v0.7.8] - 2026-11-23

### 新增
- **输入框剪贴板粘贴图片**（[#10](https://github.com/letrahoo/lework/pull/10)）：
  - 新增跨平台 `readImagesFromClipboard()` 能力（JVM / Android / iOS）。
  - 桌面端在输入框拦截 `⌘V` / `Ctrl+V`，支持截屏/内存位图（兼容 macOS Retina `MultiResolutionImage`）、Finder 复制的图片文件以及 `image/*` 原始数据流；纯文本剪贴板完全透传原生文本粘贴。
  - 复用 Skia 缩略图与渐进压缩管线，自动校验引擎图片能力与单条 4 张上限。

---

## [v0.7.7] - 2026-11-23

### 新增
- **SubAgents 实时状态条与钻取巡检**（[#7](https://github.com/letrahoo/lework/pull/7)）：
  - 协议 `SessionMeta` 新增 `SubagentSummary` 字段，支持发现并同步 AGY 与 Claude Code 的子智能体状态。
  - 桌面端与移动端父会话内展示实时 **SubAgents 状态条**，支持点击打开只读弹窗查看子智能体完整执行记录。

---

## [v0.7.6] - 2026-11-23

### 新增
- **断网 / 频繁网络报错提示与一键无缝重试**（[#6](https://github.com/letrahoo/lework/pull/6)）：
  - 识别主机断网、连接中断与上游频繁网络错误，在时间线折叠重复报错（`×N`）并弹出结构化恢复横幅。
  - 提供一键 **「重试继续 / 重连并继续」**，自动重连并无缝续跑上一轮中断任务，保留未发出的草稿与附带图片不丢失。

---

## [v0.7.5] - 2026-11-23

### 变更
- **全量开启 macOS / iOS 自动 CI 编译检查**（[#8](https://github.com/letrahoo/lework/pull/8)）：
  - 在开源公开仓库恢复每次 PR 与 `main` 推送自动运行 `iOS KMP Framework & Native Bridge Link (macOS)` 检查，与 Linux、Android、Desktop 组成完整必检闭环。

---

## [v0.7.4] - 2026-11-23

### 新增
- **跨平台原生 Mermaid 图表渲染**（[#4](https://github.com/letrahoo/lework/pull/4)）：
  - 基于 Compose Canvas 原生实现 Everforest Dark 风格 Mermaid 渲染器（支持流程图、时序图、状态图、类图、ER 图、饼图），支持 **图表 / 源码** 切换、全屏缩放查看与流式输出安全兜底。

### 变更
- **桌面端侧边栏 Pin 与操作按钮体验优化**（[#5](https://github.com/letrahoo/lework/pull/5)）：
  - Pin 图标改为实心图钉样式（`LeworkPinIcon`）。
  - 会话行操作按钮改为仅鼠标悬停显示，顺序调整为 `[重命名] [归档] [置顶]`。

### 修复
- **macOS `launchd` 下 `lsof` 路径解析与单文件锁探测**（[#2](https://github.com/letrahoo/lework/pull/2)）：
  - 为 Hub 搜索路径和 macOS LaunchAgent `PATH` 补齐 `/usr/sbin:/sbin`，新增 `resolveLsofBin()`，移除 macOS 单文件 `lsof` 探测中不兼容的 `-b` 参数。

---

## [v0.7.3] - 2026-11-23

### 新增
- **本机直连识别、主机自定义重命名与 macOS 友好默认主机名**（[#3](https://github.com/letrahoo/lework/pull/3)）：
  - 桌面端优先走 `127.0.0.1` 回环地址并展示 **「本机直连」** 通道标识。
  - 桌面端侧边栏、设置页及移动端抽屉均支持给已配对主机设置自定义备注名（`PairedHost.customName`）。
  - 全新初始化的 macOS Hub 自动将类似序列号的 MDM 主机名替换为 `scutil --get ComputerName` 或硬件型号名称。

---

## [v0.7.2] - 2026-11-23

### 修复
- **行内代码链接可点击 & 中文标点尾缀裁剪**（[#1](https://github.com/letrahoo/lework/pull/1)）：
  - 支持点击 Markdown 行内代码中的 URL，自动裁剪裸链接末尾粘连的中文标点与括号。
- **通用 Git 托管平台 SSH 主机过滤**（[#1](https://github.com/letrahoo/lework/pull/1)）：
  - 桌面端 SSH 配对列表通用过滤 `User git` 及 `git.*` / `code.*` 代码托管别名。
- **PR 必检 CI 触发对齐**（[#1](https://github.com/letrahoo/lework/pull/1)）：
  - 移除 `.github/workflows/ci.yml` 中的 `paths-ignore`，确保纯文档/配置类 PR 也能正常触发必检项。

---

## [v0.7.1] - 2026-11-23

### 新增
- **正式开源首个公开版本**：
  - 以 Apache-2.0 协议公开发布，完成全历史敏感信息脱敏、中英双语 `README.md` / `README.zh-CN.md` 以及跨平台自动化发版流水线（macOS DMG + Android APK + `SHA256SUMS.txt`）。
  - 全端重绘 Lework 品牌 Logo（macOS `.icns`、iOS、Android 及应用内矢量标）。
  - 新增本地一键增量构建安装脚本 `pnpm desktop:install`（`scripts/desktop-install.sh`）。

---

[未发布 / Unreleased]: https://github.com/letrahoo/lework/compare/v0.8.10...HEAD
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
