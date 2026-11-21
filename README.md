# Lework

移动端多引擎 Agent 控制台（Kotlin Multiplatform + Compose Multiplatform 客户端 + TypeScript Host Hub 守护进程）。

核心设计目标：**零特定云/内网基建强绑定**，开箱提供三层渐进式连接与安全握手体系：
1. **同一局域网免配直连（macOS Bonjour / Linux & Windows 纯 UDP mDNS / Android NSD）**：自动发现 `_lework._tcp.local` 节点。
2. **Tailscale 同账号内置组网（双端内嵌 `tsnet`）**：电脑与手机无需安装系统级 VPN、不接管全局路由、不与办公 VPN 冲突；两端登录**同一个 Tailscale / Headscale 账号**即可跨网段点对点自动发现并完成免扫码双向身份握手。
3. **一次性二维码配对（`hub pair`）与自定义中继**：生成 5 分钟有效、单次消费的 `lework://pair` 配对码，手机相机扫码即完成证书指纹钉扎与独立设备令牌签发，并自动同步主机全部可用地址（LAN / Tailscale / 自定义 Relay）。

## 目录结构

```text
lework/
├─ docs/                       # 架构设计与规划方案 (docs/PLAN.md)
├─ protocol-schema/            # 双端协议单一来源 (Zod v4 -> JSON Schema + Kotlin @Serializable 模型)
├─ tsnet-bridge/               # 双端内嵌用户态 Tailscale tsnet 模块 (Host 侧进程 + iOS C-Archive 静态库)
├─ hub/                        # Host Hub 守护进程 (多引擎适配、TUI 审批桥接、历史会话接管、增量用量扫描与快照)
├─ app/                        # KMP + Compose Multiplatform 客户端 (shared / iosApp / androidApp)
└─ scripts/
   ├─ setup.sh                 # 一键初始化：依赖安装 + 协议生成 + tsnet 桥接编译 + 全局命令挂载
   ├─ build-tsnet.sh           # 编译主机侧 lework-tsnet 与 iOS C-Archive 静态库
   ├─ ios-install.sh           # XcodeGen + 自动签名验签 + devicectl 真机/模拟器构建安装
   ├─ deploy-master-slave.sh   # 本机 LaunchAgent 与可选 SSH 反向隧道中继一键部署
   ├─ tcp-relay.mjs            # 轻量无状态 TCP 密文中继转发服务
   └─ ssh-tunnel-keepalive.sh  # 自愈式 SSH 反向隧道保活脚本
```

## 快速开始

### 1. 一键初始化与四层防回归门禁

```bash
# 首次克隆一键初始化（幂等）：安装依赖 -> 生成协议 -> 编译内嵌 tsnet 模块 -> 注册全局 lework / hub 命令
pnpm setup

# 一键执行门禁：协议生成 -> TS 类型检查 -> Hub 单测与集成测试 -> KMP 状态机与无头 Compose UI 测试
pnpm verify

# 单独执行高频 UI 自动化压测
pnpm stress:ui
```

### 2. 启动本机 Host Hub 与设备配对（`pnpm setup` 后可在任意目录执行）

```bash
# 查看本机已安装的 Agent 引擎与模型通道状态
lework engines
lework status

# 生成一次性扫码配对二维码 (lework://pair，5 分钟单次有效)
lework pair

# 可选：启用内置 Tailscale 同账号组网（支持 --control-url 接入自建 Headscale）
lework tsnet login

# 可选：配置锁屏离线推送（待审批 / 长任务完成自动推送，点击直达对应会话）
lework notify bark https://api.day.app/<YOUR_BARK_KEY>
# 或飞书机器人 / Telegram / 通用 Webhook：
# lework notify feishu https://open.feishu.cn/open-apis/bot/v2/hook/<ID> [--secret <SEC>]
lework notify test

# 前台启动 Hub 守护进程 (默认 HTTPS/WSS 监听 0.0.0.0:64450，自动广播 _lework._tcp.local)
lework serve
```

> **中继与认证配置（可选，全部存放在本机 `~/.lework/`，不入库）**：
> - 自定义异地中继地址：在 `~/.lework/config.json` 中配置 `"relayUrls": ["https://<your-relay-host>:64450"]`，手机**认证通过后**才会学习并缓存；也可直接在手机登录页手动填写主机地址与访问令牌。
> - 可选 OAuth 设备流认证（RFC 8628 插件化）：在 `~/.lework/oauth.json`（或环境变量 `LEWORK_OAUTH_SECRET_FILE`，兼容旧 `feishu-secret.json`）配置任一 OAuth Provider（内置 `rfc8628` 通用 SSO 与 `feishu` 实现）与允许的用户列表；未配置时自动降级为纯访问令牌模式。
> - 可选代理统计与单价覆盖：在 `~/.lework/config.json` 配置 `"cpaManagementKey"`（或环境变量 `LEWORK_CPA_MANAGEMENT_KEY`）即可读取本机 CLIProxyAPI 转发统计；在 `~/.lework/pricing.json` 可自定义各模型单价。

### 核心工作台能力

- **多引擎与本机终端无缝接管**：支持 Claude Code、Codex、AGY、DSH 与内置 Pi 引擎；自动发现电脑 Shell 中正在运行或历史的会话，支持手机代点 `tmux` / `iTerm2` 终端审批、同步排队指令与断点续传。
- **多模态输入与 `@文件` 引用补全**：移动端输入框支持一键添加相册截图直传多模态引擎分析，输入 `@` 实时模糊检索工作区文件（自动过滤敏感凭证文件）；iOS 个人开发描述文件剩余不足 36 小时自动温和提醒续签。
- **离线审批与任务完成推送**：可插拔对接 Bark、飞书机器人、Telegram 或自定义 Webhook；手机锁屏期间若 Agent 卡在待审批或长耗时任务执行完毕，自动推送带 `lework://session` 深链的通知，点击直接跳转对应会话。
- **iOS 与 Android 双端全能力对齐**：双端均内置硬件级加密凭证库（iOS Keychain / Android Keystore AES-256-GCM）、原生相机扫码配对（AVFoundation / CameraX + ZXing）、局域网发现（Bonjour / NSD）及用户态 `tsnet` 同账号自动组网。
- **上下文窗口与多引擎用量看板**：会话内实时显示当前上下文容量占用 %、缓存命中率与估算账单；全局看板按字节偏移增量扫描本机 Claude / Codex / AGY 日志（零后台轮询开销），展示按日趋势、5 小时计费窗口与周额度重置倒计时，以及本机 CPA 代理统计。
- **变更对比与版本快照回退**：查看当前工作区未提交的 Git 变更列表与逐文件红绿对比，编辑类工具调用自动生成行级前后对比；每轮对话前自动打无损快照（`git stash create`），支持手动存快照、跨快照对比差异与一键安全回退（回退前自动备份）。

### 安全模型

| 层 | 机制 |
| --- | --- |
| 传输加密 | Hub 只提供 **HTTPS / WSS**（自签名证书，`~/.lework/tls/`）。TLS 在 Hub 进程内终止，中继 / SSH 隧道 / Overlay 网络只转发密文（端到端）。 |
| 主机身份 | App **钉扎**证书 SHA-256 指纹，不依赖 CA。首次连接用「令牌绑定证明」：Hub 必须用 `HMAC(SHA-256(token), 指纹‖nonce)` 证明自己知道令牌，**证明通过前令牌绝不发出**；冒充的 Bonjour 主机 / 被劫持的中继拿不到令牌。App 侧边栏显示的「主机指纹」应与 `hub pair` 输出一致。 |
| 凭证存储 | iOS 存 **Keychain**（仅本机、首次解锁后可用、不进 iCloud）；Android 存 **AndroidKeyStore AES-256-GCM** 硬件密钥加密存储（拒绝明文回退）。 |
| OAuth | 设备流带 PKCE 式校验（只有发起方能领取令牌）、限流；签发的令牌绑定 `aud=hubId`，仅存哈希，可吊销：`hub tokens [list | revoke <kid> | revoke-all]`。 |
| 访问面 | 拒绝带 `Origin` 的浏览器请求（防 DNS rebinding / 跨站 WS）；未认证连接 10 秒超时、数量上限、1 MiB 帧上限；认证失败延迟并断开；未认证接口不返回中继地址。 |
| 文件与终端 | `fs.read/list` 硬性屏蔽 `~/.ssh`、`~/.lework`、`.env`、私钥等凭证；手机代点审批只允许当前屏幕上同一条审批（哈希校验）中的数字选项，杜绝注入任意按键。 |
| Slave | 使用独立的入网令牌（`hub slave-token`），`hub join` 必须钉扎 Master 证书指纹。 |

常用命令：`hub pair`（显示令牌与指纹）、`hub token rotate`（轮换访问令牌）、`hub tokens revoke-all`（吊销全部 OAuth 令牌）。

### 3. 构建与安装移动端 App（iOS / Android）

```bash
# 构建 iOS 模拟器包 (arm64)
./scripts/ios-install.sh --simulator

# 编译链接 iOS 真机包 (iphoneos arm64)
./scripts/ios-install.sh --device-compile

# 真机签名并无线/有线安装到已配对的 iPhone（可传 UDID，留空自动选择首台已配对真机）
APPLE_TEAM_ID=<YOUR_TEAM_ID> ./scripts/ios-install.sh --install [DEVICE_UDID]

# 构建 Android Debug / Release APK（Release 默认读取 ~/.lework/android-release.properties 签名）
./app/gradlew -p app :androidApp:assembleDebug
./app/gradlew -p app :androidApp:assembleRelease
```

## 许可证 (License)

本项目基于 [Apache License 2.0](./LICENSE) 许可协议分发。二进制发行包涉及的第三方开源组件声明见 [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)。
