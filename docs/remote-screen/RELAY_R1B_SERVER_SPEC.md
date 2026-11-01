# R1b 规格：中转服务器（relay/server）

负责人：herdr agent（Claude Sonnet，high）。审核与部署：主会话。

先读：[relay/PROTOCOL.md](../../relay/PROTOCOL.md)（契约，以它为准）、[RELAY_PLAN.md](RELAY_PLAN.md) §4–§7、[research/RELAY_R0.md](research/RELAY_R0.md)、R0 原型 `relay/spike/worker/src/index.ts`（已验证可用的 Durable Object 写法：hibernatable WebSocket、tag、attachment、auto-response、alarm）。

## 1. 交付范围

在 `relay/server/` 实现协议 v1 的中转服务器：

```
relay/server/
  package.json, tsconfig.json, bun.lock
  core/        与平台无关：协议类型、配置解析与默认值、策略检查顺序（PROTOCOL §6.4）、
               限速监督（§6.2）、计量（§6.3）、原因码与关闭码、主机签名验证、hostId 计算
  worker/      Cloudflare：wrangler.toml、Worker 入口、HostRelay（每个 hostId 一个 DO）、
               Budget（全局单例 DO：全局预算、按 IP 的注册计数）
  bun/         VPS：Bun.serve WebSocket 服务 + bun:sqlite；单文件构建（bun build --compile）与 Dockerfile
  test/
    unit/         core 的单元测试（bun test）
    conformance/  黑盒一致性测试：同一套用例分别对 `wrangler dev` 与 Bun 服务运行
    client/       测试用的协议参考客户端（connector 一侧与 App 一侧，WebCrypto 实现 Ed25519 / ECDSA P-256）
  README.md    中文：两种部署方式（Cloudflare 一键部署、VPS Docker/单文件）、配置说明
```

不在范围内：R2 数据报、二维码添加、真实部署。

## 2. 配置

两个适配层使用同一份 JSON 配置（核心负责解析和补默认值），结构：

```json
{
  "accessMode": "open",
  "policy": { …与 PROTOCOL §6.1 的 policy 对象相同的结构，access 字段除外… },
  "registrationsPerIpPerDay": 5,
  "edges": []
}
```

- Worker：`wrangler.toml` 的 `[vars] RELAY_CONFIG`（JSON 字符串）；访问码与管理口令是 secret：`ACCESS_TOKEN`、`ADMIN_TOKEN`。
- Bun：`relay.config.json`（路径可用 `RELAY_CONFIG_FILE` 指定）；`ACCESS_TOKEN`、`ADMIN_TOKEN` 来自环境变量；监听地址与端口来自 `PORT`/`HOST`，TLS 由前置反代负责（README 给 Caddy 示例）。
- 缺省值即 RELAY_PLAN §5.1 的示例值；`accessMode="token"` 但未设 `ACCESS_TOKEN` 时拒绝启动（Bun）/ 所有 WS 请求返回 401（Worker）。
- 口令比较必须常量时间。

`wrangler.toml` 提供两个环境：默认环境 `miffan-relay` 绑定 `relay.miffan.ayuilos.me`；`[env.dev]` 名为 `miffan-relay-dev` 绑定 `relay-dev.miffan.ayuilos.me`（会替换 R0 的原型 Worker）。Durable Object 类必须用 `new_sqlite_classes`（免费方案只支持 SQLite 存储）。

## 3. 实现要点

- **严格按 PROTOCOL.md**：端点、消息字段、签名内容字符串、原因码、关闭码、检查顺序、帧大小上限。发现协议有歧义或无法实现之处，写进 `docs/remote-screen/RELAY_R1B_NOTES.md` 并选最保守的解释，不要自行扩展协议。
- **hostId 与签名**：`hostId = base32lower(SHA-256(hostPub))[:26]`；Ed25519 验签用 WebCrypto（Workers 与 Bun 都支持 `Ed25519`）。挑战 10 秒超时。验签通过后才替换旧控制连接。
- **休眠（Cloudflare）**：全部 WebSocket 用 `ctx.acceptWebSocket` + tag；`setWebSocketAutoResponse("ping","pong")`；会话状态放 attachment；不得使用让对象常驻的定时器（`setInterval`、长 `setTimeout`）。空闲超时、配对超时、计量落盘统一用 alarm（参考原型里"一个 alarm 兼管多件事并按需重设"的写法）。
- **计量落盘**：每个 DO 内存累计，最多每 30 秒写一次 SQLite（另在 alarm、会话关闭时写）。活跃分钟按 UTC 分钟去重计数，最后一次计入的分钟要随 attachment 或存储保存，休眠唤醒后不重复计。
- **全局预算（Cloudflare）**：HostRelay 在落盘时把增量（字节、活跃分钟）报告给 Budget DO，并取回当前预算状态（`ok`/`media_paused`/`closed`）缓存到下次报告。不得每条消息都调用 Budget。按 IP 的注册计数也在 Budget DO 中，只保留当天，次日清除。
- **限速监督**：每个会话每个方向 10 秒滑动窗口（可用 1 秒粒度的环形计数）。
- **转发**：`ready` 之后原样转发文本与二进制帧（文本 `ping` 除外）；一侧关闭即以 1000 `peer_closed` 关闭另一侧。
- **Bun 适配层**：语义与 Worker 完全一致（同一套 core），状态在进程内存 + `bun:sqlite`；`locationHint` 忽略。
- **日志**：只记录 hostId 前 8 位、会话事件、原因码；不得记录帧内容、口令、完整 IP。

## 4. 验收

1. `bun test test/unit` 全部通过。
2. 一致性测试脚本（例如 `bun run conformance`）自动启动 `wrangler dev --local` 与 Bun 服务（各用测试配置：小配额、小限速、短空闲超时），对两者运行同一套用例，全部通过。用例至少覆盖：
   - `/v1/info` 字段；
   - 主机鉴权：正确签名、错误签名、超时、新连接验签后替换旧连接；
   - 会话：`host_offline`、配对与 `ready`、双向转发二进制和文本、文本 `ping` 由中转回复且不转发、`host_timeout`、一侧关闭传播、`frame_too_large`、空闲超时；
   - 端到端握手报文被原样转发（中转不解析）；
   - 策略：`media_disabled`、`quota_exhausted`（含 `retryAfter`）、`too_many_sessions`、`rate_limited`、`relay_busy`（全局预算）、`too_many_registrations`；
   - 访问码模式下的 401；平台层错误不在此测；
   - 计量：传输一定字节后，`hello`/`ready` 中的 `usage` 正确反映字节数与活跃分钟数；
   - `/v1/admin/stats` 需要管理口令。
3. `bun build --compile` 能产出单文件；`docker build` 能通过（若本机没有 Docker，在 NOTES 里说明并只验证 Dockerfile 语法）。
4. `npx tsc --noEmit`（或等价的类型检查）无错误。
5. README 中文说明完整，能让普通用户照着在 Cloudflare 或 VPS 上部署，并说明 `workers.dev` 在国内不可用、需绑定自有域名。

## 5. 规则

- 工作目录：worktree `.claude/worktrees/relay-server`，分支 `feature/relay-server`（基于 `feature/relay`）。只改 `relay/server/` 和 `docs/remote-screen/RELAY_R1B_NOTES.md`。
- 可以在本分支提交（提交信息末尾加 `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`），不得合并到其他分支，不得推送。
- **不得**运行 `wrangler deploy`、`wrangler secret put` 或任何改动 Cloudflare 账号的命令；只用 `wrangler dev --local`。
- 安装 npm 依赖需要网络，按正常权限提示由用户批准；依赖尽量少（Cloudflare 类型定义、wrangler、TOML 解析如有需要），不引入大型框架。
- 结束时在 NOTES 中写：实现概要、与协议的偏差（应为零）、已知限制、验收命令及结果。
