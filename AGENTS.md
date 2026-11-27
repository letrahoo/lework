# Lework Agent Collaboration & Engineering Rules

本文件是 Lework 仓库的单一规范源（Single Source of Truth），由 Antigravity (AGY)、Codex、Pi 及 Claude Code（通过 [CLAUDE.md](CLAUDE.md) 引入）共同加载。

---

## 1. 仓库架构速览

- `protocol-schema/`：Zod 单一协议真相源，负责生成 JSON Schema、事件 fixtures 与 Kotlin `@Serializable` 模型。
- `hub/`：Host Hub 守护进程（TypeScript / Node 22+），负责多引擎适配器（Claude / Codex / AGY / Pi / DSH / ACP）、宿主机历史与终端桥接、事件流与权限审批。
- `tsnet-bridge/`：内嵌 Tailscale `tsnet` Go 桥接层（Host / Android / iOS）。
- `app/`：Compose Multiplatform 客户端（`shared`、`desktopApp`、`androidApp`、`iosApp`）。

---

## 2. 多会话并行开发、冲突预检与 PR 策略（核心）

Lework 经常由多个 AI 会话或 git worktree 并行迭代。**开工前**与**推送 / 提 PR 前**必须执行以下流程：

### 2.1 强制前置巡检（Pre-flight Audit）
1. **同步最新主干**：
   ```bash
   git fetch origin && git log --oneline -n 5 origin/main
   ```
2. **盘点所有活跃 PR 与 worktree**：
   ```bash
   gh pr list --state open --json number,title,headRefName,files
   gh pr list --state merged --limit 5 --json number,title,mergedAt
   git worktree list
   ```
3. **无损三方合并预演（提 PR 前必跑）**：
   对所有存在文件交集的 OPEN PR，执行不修改工作区的三方合并检查：
   ```bash
   git merge-tree --write-tree --name-only HEAD origin/<pr-branch>
   ```
   明确区分：
   - **0 冲突**（自动干净合并）
   - **轻微尾部冲突**（如仅测试文件末尾追加新用例）
   - **核心逻辑 / 协议冲突**（状态机、协议字段、同区域 UI 或引擎适配逻辑实质冲突）

### 2.2 PR 原子性 vs 聚合合并决策
- **默认坚持单 PR 原子性**：功能边界独立、无功能重合、核心代码零冲突（或仅测试尾部极轻冲突）的改动，应保持独立原子 PR，便于独立审查、CI 排错与回滚。
- **以下情况必须提前聚合，禁止各提各的互相打架**：
  1. **功能重合 / 重复造轮子**：若发现已有 OPEN PR 或活跃会话在实现同类能力（如同一检查器面板、同一协议事件、同一交互入口），立即横向对比两边实现优缺点，**取优融合后聚合到同一 PR 提交**。
  2. **对方已合入 `main`**：绝对不要提议关闭或覆盖已合入 PR；应先 `git fetch origin`，用 `git rebase --onto origin/main <duplicate-base>` 丢弃本地重复提交，仅以已合入方案为基准保留干净增量。
  3. **多处核心热点实质冲突**：若多个并行改动在下方“高冲突热点文件”上存在多处实质逻辑冲突，优先**聚合为同一个集成 PR**，或明确串行合入顺序（先合基础 PR，后 rebase 增量 PR）。

### 2.3 Lework 高冲突热点文件（Red Zone）
修改以下文件前须格外关注 OPEN PR 交集，尽量做小范围、低侵入的增量改动：
- **协议与代码生成链路**：`protocol-schema/src/index.ts`、`protocol-schema/scripts/gen-json-schema.ts`、`protocol-schema/schema/protocol.schema.json`、`app/shared/src/commonMain/kotlin/dev/lework/protocol/ProtocolModels.kt`
  - **严禁手动编辑生成产物**；任何 rebase / 冲突解决后必须重跑 `pnpm --filter @lework/protocol gen` 并确认 `git diff --exit-code` 为 0 drift。
- **Hub 核心路由与宿主机同步**：`hub/src/server.ts`、`hub/src/session-manager.ts`、`hub/src/storage/host-history.ts`、`hub/src/storage/host-history/agy-history.ts`、`hub/src/storage/host-history/claude-history.ts`
- **客户端核心状态与顶层界面**：`app/shared/src/commonMain/kotlin/dev/lework/store/ConsoleState.kt`、`ConsoleStore.kt`、`app/shared/src/commonMain/kotlin/dev/lework/ui/ConsoleApp.kt`、`app/shared/src/commonMain/kotlin/dev/lework/ui/desktop/DesktopConsoleApp.kt`
- **尾部追加热点测试**：`app/shared/src/jvmTest/kotlin/dev/lework/UiAutomationStressTest.kt`、`ConsoleStoreStateTest.kt`、`protocol-schema/test/protocol.test.ts`
  - **防冲突最佳实践**：独立新功能的单测 / UI 测试**优先新建独立测试文件**（如 `hub/test/artifacts.test.ts`、`app/shared/src/jvmTest/kotlin/dev/lework/SubagentInspectorTest.kt`），减少往巨型测试文件末尾集中追加。

---

## 3. 跨平台与 UI 工程约束

- **用户体验至高无上（主原则）**：**「复杂应该留给系统，不要留给用户，用户体验至高无上。」**
  - 所有交互方案、界面文案与功能设计必须遵循 [docs/UX_PRINCIPLES.md](docs/UX_PRINCIPLES.md) 定义的 **8 条铁律**：
    1. **系统能判断的，不留给用户**（按平台裁剪能力与提示，自动识别剪贴板链接/格式/上下文）
    2. **此刻没用的，就不出现**（非 Git 仓库隐藏快照、空白会话隐藏无内容入口、单一选项隐藏冗余开关）
    3. **能替人做的，就直接做**（弹窗自动聚焦与键盘直达、已登录网络自动发现、取消排队保留草稿）
    4. **同一件事，处处一样**（统一术语如 **Lework Hub 主机**、**断开连接**、**Token**、**子任务**，跨端同义同形）
    5. **按下去有回应，再按能收回**（所有异步操作有状态反馈、可逆操作可撤销、切出面板可无损返回）
    6. **不打断人手上的事**（连接新主机不中断当前连接与草稿、非阻塞通知、严禁移动端自动读剪贴板）
    7. **常用的好做，会丢东西的难误碰**（高频操作支持快捷键直达；清凭证/断连/移除主机/移除服务等高危动作必须二次确认）
    8. **界面自己说明自己**（空态区分具体原因，用人话替代底层术语与个人硬编码示例）
- **KMP `commonMain` 纯净性**：`app/shared/src/commonMain/` 中严禁使用 JVM 专属 API（如 `java.*`、`String.format`、`System.currentTimeMillis`），平台能力必须通过 `dev.lework.platform` 的 `expect`/`actual` 封装，避免破坏 Android / iOS CI。
- **语义化中文 UI 文案**：面向用户的按钮、标签和提示使用直观业务词汇（如“Lework Hub 主机”、“会话产物”、“确认并开始执行”、“取消排队”），避免暴露底层实现黑话；视觉风格保持 **Everforest Dark** 一致。
- **测试隔离**：涉及宿主目录或元数据持久化的 Hub 测试，必须通过临时目录隔离 `LEWORK_HOME` / `HOME`，禁止读写开发者真实 `~/.lework/` 数据。

---

## 4. 提 PR 前本地验证清单（对齐 CI）

改动对应模块时，推送与开 PR 前需确认以下检查全部通过：

1. **版本一致性**：
   ```bash
   node scripts/resolve-version.mjs --check
   ```
2. **协议生成 0 Drift & TypeScript 检查**：
   ```bash
   pnpm --filter @lework/protocol gen
   git diff --exit-code protocol-schema/ app/shared/src/commonMain/kotlin/dev/lework/protocol/
   pnpm -r check
   ```
3. **Protocol & Hub 测试**：
   ```bash
   pnpm -r test
   ```
4. **KMP 共享模块测试与桌面编译**：
   ```bash
   ./app/gradlew -p ./app :shared:jvmTest :desktopApp:compileKotlinJvm
   ```
   若修改了 `commonMain` UI/平台桥接或 Gradle 配置，建议同步验证 `:androidApp:assembleDebug`（及 macOS 上的 `:shared:compileKotlinIosSimulatorArm64`）。
5. **用户体验自查**：凡涉及 UI、交互流程、提示文案或 CLI 输出的改动，提 PR 前须按 [docs/UX_PRINCIPLES.md](docs/UX_PRINCIPLES.md) 第 4 节逐条完成 8 项 UX 自查。
6. **分支纪律**：严禁直接推送 `main` 或覆盖他人分支；rebase 更新己方分支时使用 `git push --force-with-lease`。
7. **双语 Changelog 同步**：常规功能/修复 PR **不要**各自去改 `CHANGELOG.md` / `CHANGELOG.zh-CN.md`（避免并行 PR 争抢 `Unreleased` 同一行且合入前无法预知最终 tag）。每次 `Release Android & Desktop` 发版后，`.github/workflows/changelog-sync.yml` 会自动运行 `node scripts/update-changelog.mjs --write` 并在独立的滚动分支 `docs/changelog-sync` 同步双语条目（带 `[skip release]`，不触发空发版）；本地亦可随时执行 `pnpm changelog:sync` / `pnpm changelog:check`。
