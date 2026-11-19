# Lework

移动端多引擎 Agent 控制台（Kotlin Multiplatform + Compose Multiplatform 客户端 + TypeScript Host Hub 守护进程）。

核心设计目标：**零特定云/内网基建强绑定**，开箱支持局域网 mDNS/Bonjour 免配直连、访问令牌（Access Token）配对、可选 OAuth Device Flow 认证，以及任意自定义中继通道（Tailscale / WireGuard / SSH 反向隧道 / 云主机 Relay）。

## 目录结构

```text
lework/
├─ docs/                       # 架构设计与规划方案 (docs/PLAN.md)
├─ protocol-schema/            # 双端协议单一来源 (Zod v4 -> JSON Schema + Kotlin @Serializable 模型)
├─ hub/                        # Host Hub 守护进程 (多引擎适配器、终端 TUI 桥接、历史会话接管)
├─ app/                        # KMP + Compose Multiplatform 客户端 (shared / iosApp / androidApp)
└─ scripts/
   ├─ ios-install.sh           # XcodeGen + 自动签名验签 + devicectl 真机/模拟器构建安装
   ├─ deploy-master-slave.sh   # 本机 LaunchAgent 与可选 SSH 反向隧道中继一键部署
   ├─ devbox-master-relay.mjs  # 轻量无状态 TCP/WebSocket 中继转发服务
   └─ ssh-tunnel-keepalive.sh  # 自愈式 SSH 反向隧道保活脚本
```

## 快速开始

### 1. 协议生成与四层防回归门禁

```bash
# 一键执行：协议生成 -> TS 类型检查 -> Hub 单测与集成测试 -> KMP 状态机与无头 Compose UI 测试
pnpm verify

# 单独执行高频 UI 自动化压测
pnpm stress:ui
```

### 2. 启动本机 Host Hub 与查看配对令牌

```bash
# 查看本机已安装的 Agent 引擎状态
pnpm --filter @qprc/hub cli engines

# 查看本机连接地址与访问令牌 (Access Token)
pnpm --filter @qprc/hub cli pair

# 前台启动 Hub 守护进程 (默认监听 0.0.0.0:64450，自动广播 _qprc._tcp.local)
pnpm hub
```

> **中继与认证配置（可选，全部存放在本机 `~/.qprc/`，不入库）**：
> - 自定义异地中继地址：在 `~/.qprc/config.json` 中配置 `"relayUrls": ["http://<your-relay-host>:64450"]`，手机首次连接后会自动学习并缓存；也可直接在手机登录页手动填写主机地址与访问令牌。
> - 可选 OAuth 设备流认证：在 `~/.qprc/feishu-secret.json`（或环境变量 `LEWORK_OAUTH_SECRET_FILE`）配置应用凭证与允许的用户列表；未配置时自动降级为纯访问令牌模式。

### 3. 构建与安装移动端 App（iOS / Android）

```bash
# 构建 iOS 模拟器包 (arm64)
./scripts/ios-install.sh --simulator

# 编译链接 iOS 真机包 (iphoneos arm64)
./scripts/ios-install.sh --device-compile

# 真机签名并无线/有线安装到已配对的 iPhone（可传 UDID，留空自动选择首台已配对真机）
APPLE_TEAM_ID=<YOUR_TEAM_ID> ./scripts/ios-install.sh --install [DEVICE_UDID]

# 构建 Android Debug APK
./app/gradlew -p app :androidApp:assembleDebug
```
