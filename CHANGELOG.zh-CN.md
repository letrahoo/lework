# 更新日志（Changelog）

[English](./CHANGELOG.md) | **简体中文**

本文件记录 **Lework** 自正式开源以来的所有重要版本变更。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本（SemVer）](https://semver.org/lang/zh-CN/)。

---

## [未发布 / Unreleased]

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

[未发布 / Unreleased]: https://github.com/letrahoo/lework/compare/v0.7.10...HEAD
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
