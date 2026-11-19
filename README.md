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
   ├─ tcp-relay.mjs            # 轻量无状态 TCP 密文中继转发服务
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
pnpm --filter @lework/hub cli engines

# 查看本机连接地址与访问令牌 (Access Token)
pnpm --filter @lework/hub cli pair

# 前台启动 Hub 守护进程 (默认 HTTPS/WSS 监听 0.0.0.0:64450，自动广播 _lework._tcp.local)
pnpm hub
```

> **中继与认证配置（可选，全部存放在本机 `~/.lework/`，不入库）**：
> - 自定义异地中继地址：在 `~/.lework/config.json` 中配置 `"relayUrls": ["https://<your-relay-host>:64450"]`，手机**认证通过后**才会学习并缓存；也可直接在手机登录页手动填写主机地址与访问令牌。
> - 可选 OAuth 设备流认证（RFC 8628 插件化）：在 `~/.lework/oauth.json`（或环境变量 `LEWORK_OAUTH_SECRET_FILE`，兼容旧 `feishu-secret.json`）配置任一 OAuth Provider（内置 `rfc8628` 通用 SSO 与 `feishu` 实现）与允许的用户列表；未配置时自动降级为纯访问令牌模式。

### 安全模型

| 层 | 机制 |
| --- | --- |
| 传输加密 | Hub 只提供 **HTTPS / WSS**（自签名证书，`~/.lework/tls/`）。TLS 在 Hub 进程内终止，中继 / SSH 隧道 / Overlay 网络只转发密文（端到端）。 |
| 主机身份 | App **钉扎**证书 SHA-256 指纹，不依赖 CA。首次连接用「令牌绑定证明」：Hub 必须用 `HMAC(SHA-256(token), 指纹‖nonce)` 证明自己知道令牌，**证明通过前令牌绝不发出**；冒充的 Bonjour 主机 / 被劫持的中继拿不到令牌。App 侧边栏显示的「主机指纹」应与 `hub pair` 输出一致。 |
| 凭证存储 | iOS 访问令牌与证书指纹存 **Keychain**（仅本机、首次解锁后可用、不进 iCloud）；旧版明文 `NSUserDefaults` 自动迁移并清除。 |
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

# 构建 Android Debug APK
./app/gradlew -p app :androidApp:assembleDebug
```
