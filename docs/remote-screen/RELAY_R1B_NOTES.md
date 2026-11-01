# R1b 笔记：中转服务器（relay/server）

状态：2026-11-01 完成，分支 `feature/relay-server`（基于 `feature/relay`），未合并、未推送、未部署。规格见 [RELAY_R1B_SERVER_SPEC.md](RELAY_R1B_SERVER_SPEC.md)，契约见 [relay/PROTOCOL.md](../../relay/PROTOCOL.md)。使用说明（中文）在 [relay/server/README.md](../../relay/server/README.md)。

## 1. 实现概要

**一个引擎，两个外壳。** 每台电脑的全部会话逻辑（控制连接鉴权、配对、转发、限速监督、计量、强制关闭、定时）只写了一份：`core/engine.ts` 的 `Engine`。它只依赖一个很小的 `Platform` 接口（按 tag 取 socket、socket 附件、单个 alarm、SQL、全局预算端口）。

- `worker/`：`HostRelay`（每个 hostId 一个 Durable Object）是 `Engine` 的外壳，`Budget`（全局单例 DO）放全局预算和按 IP 的注册计数；`index.ts` 只做路由和访问码检查。
- `bun/`：`Bun.serve` + `bun:sqlite`，每个活跃 hostId 一个 `Engine`，空闲时从内存丢弃；`bun build --compile` 出单文件。
- `core/` 其余：配置解析、路由与访问码（`http.ts`）、策略检查顺序（`policy.ts`，对应 §6.4）、限速窗口（`ratelimit.ts`）、计量账本与用量对象（`ledger.ts`，SQL 两边共用）、全局预算与按 IP 注册计数（`budget.ts`）、WebCrypto 的 base64url/base32/hostId/Ed25519 验签/常量时间比较（`crypto.ts`）。

因为引擎共用，两个实现的差别只剩平台接口那一层；一致性测试再从外部确认。

休眠设计（Cloudflare）：

- 全部 WebSocket 用 `ctx.acceptWebSocket` + tag（`host`、`c`、`h`、`c:<sid>`、`h:<sid>`），`setWebSocketAutoResponse("ping","pong")`，代码里没有任何 `setInterval`/长 `setTimeout`。
- 所有定时（挑战 10 秒、配对 10 秒、空闲、计量落盘）只用一个 alarm：`reschedule()` 取所有 socket 的最早截止时间重设；alarm 触发后重新计算。
- 休眠会清掉内存，所以**未落盘的计量放在 socket 附件里**（每个 socket 记自己转发的未落盘字节、记入的活跃分钟数和最后一次记入的 UTC 分钟）。唤醒时 `Engine` 构造函数从附件重建待写计数和"最后一次计入的分钟"，因此休眠不会丢数据，也不会重复计分钟。落盘后清零附件。
- 落盘：距上次落盘不足 30 秒就等 alarm；会话关闭、跨 UTC 日（先把前一天的计数写入前一天）时立即写。上次落盘时间存在 SQLite，唤醒后仍能遵守 30 秒。
- 全局预算：落盘时只把增量（字节、活跃分钟）报告给 Budget DO，用返回的状态更新缓存，并据此强制关闭画面会话。不逐条消息调用。新会话开始时若缓存超过 10 秒才主动查询一次；预算上限都为 0 时完全不查询。
- 没有注册过的 hostId 不建任何表（连接中转但从未通过注册检查的垃圾 hostId 不留下存储）。

## 2. 与协议的偏差

**零。** 没有改动任何端点、消息字段、签名内容、原因码、关闭码、检查顺序或大小上限。下面 §3 是协议没有写死、我选了最保守解释的地方，不属于偏差，但新增或修改协议时需要知道。

## 3. 协议没写清楚的地方（选择了什么）

1. **未知路径/方法**：`404`（路径）或 `405`（方法）+ `{"reason":"bad_request"}`。表里 `bad_request` 是 400，但 404/405 对应的 HTTP 语义更自然，且客户端按"JSON 含 reason"识别即可。
2. **缺少 `ch`** 视为非法 → 400 `bad_request`；缺少 `Upgrade: websocket` → 400 `bad_request`。
3. **数据连接的 sid 不存在/已被使用/已关闭** → 404 `bad_request`（表里没有更合适的原因码）。数据连接不检查访问码（§3：sid 是凭据）。
4. **App 在 `ready` 之前发帧**：以标准码 1008 `bad_request` 关闭（协议没定义这种情形）。
5. **`host_offline` 带 `retryAfter: 30`**（取 §3.2 的示例）。`relay_busy` 的 `retryAfter` 是到下一个 UTC 0 点的秒数。
6. **配额口径**：日配额（`hostDailyMb`、`hostDailyActiveMin`）对两个通道都适用；月配额只对各自通道适用（控制月配额用完不挡画面会话，反之亦然）。`quota_exhausted` 的 `retryAfter` 是**所有已耗尽配额里最晚的重置时间**。
7. **"MB"** 是 1024×1024 字节（与 §6.2 里 `burstKb × 1024` 一致）。用量里的 MB 保留 3 位小数。
8. **只统计二进制帧的负载字节**（同 §6.2 的监督口径）；文本帧不计字节也不算活动。活跃分钟只由二进制帧触发。
9. **`usage.resetsAt`**：有配额已耗尽时是其中最晚的重置时间，否则是下一个 UTC 0 点。
10. **`usage` 推送的阈值**（§4 没给）：任一非 0 的主机配额跨过 50%、80%、100%，或预算状态改变时推给 connector。只在落盘时判断；连接建立时记下当前档位，不重复推。
11. **注册计数的时机**：§3.2 要求拒绝在 WebSocket 升级前，而签名在升级后才有，所以"首次出现的 hostId"在**升级时**计入按 IP 的计数并标记为已注册，不等验签。后果：随机公钥最多耗尽攻击者自己 IP 的额度。预算服务不可达时放行（防滥用不应拖垮服务）。
12. **`rate_limited`、会话内的 `quota_exhausted`/`relay_busy`** 两侧都以该码关闭；`frame_too_large` 违规一侧以 4413 关闭，另一侧 1000 `peer_closed`；其他一侧关闭一律 1000 `peer_closed`（无论对端用什么码，含 4403）。
13. **配额/预算在会话内的强制**只在计量落盘时判断（至多每 30 秒一次，会话关闭时也会），因此画面会话可能超出几十秒的用量才被切断。
14. **未知的 `hint`** 被忽略而不是拒绝（以后加区域不会让旧中转报错）。
15. **来源 IP**：Worker 取 `CF-Connecting-IP`；Bun 取直连地址，或 `TRUSTED_IP_HEADER` 指定的头（取逗号分隔的最后一个值）。取不到时归入同一个 "unknown" 桶。
16. **访问码模式没配 `ACCESS_TOKEN`**：Worker 对 `/v1/host`、`/v1/connect` 返回 401（`/v1/info` 仍可用）；Bun 拒绝启动。`ADMIN_TOKEN` 没设时 `/v1/admin/stats` 一律 401。
17. **只有控制连接断开而会话还在**时，已建立的会话保持到自然结束（协议没说；与 R0 原型一致）。新会话返回 `host_offline`。
18. **Bun 背压**：`ws.send` 返回 0（被丢弃）时以 1013 关闭该 socket，避免字节流悄悄损坏。属内部保护，协议里没有对应码。

## 4. 已知限制

- **没有在真实 Cloudflare 上跑过。** 全部验证是 `wrangler dev --local`（workerd）+ Bun。真实环境里休眠时机、alarm 精度、DO RPC 计费、`Ed25519` 支持、Workers Logs 是否记录请求元数据（含 IP）都要在部署后核实；日志里我们自己只输出 hostId 前 8 位和事件名。
- **本地 workerd 的关闭握手怪癖**：由服务器代对端发起的关闭（`peer_closed`、`idle` 等）会让客户端停在 CLOSING（已收到 Close 帧）约 10 秒才断 TCP；在只含 Worker 和 DO 的最小例子里同样复现，与引擎代码无关。测试客户端（`test/client/conn.ts`，自写的 RFC 6455 客户端）因此以收到 Close 帧作为连接结束，并且 `HostRelay.webSocketClose` 在远端先关闭时显式回关闭帧（本地 workerd 不会自动回复）。真实 Cloudflare 上是否有同样现象未验证；OkHttp（`onClosing`）和 Go 的 WebSocket 库收到 Close 帧即视为关闭，不受影响。
- 控制连接上"待验签"的连接数没有上限（每个至多存活 10 秒）。
- Bun 版是单进程；崩溃时最多丢最近 30 秒的未落盘计量（`SIGTERM`/`SIGINT` 会先落盘）。
- 本机 Docker 守护进程未运行，**未执行 `docker build`**（见 §5）。
- 一键部署按钮的流程没有实测；README 里已注明。
- DO 的位置由第一次创建时决定：如果有人在真正的 connector 注册之前对一个有效 hostId 发 `connect`，对象会建在默认位置（hostId 不可猜，实际不会发生）。
- 日志里的 `session_open`/`session_ready` 等事件按会话记录，量大时可以在 Cloudflare 控制台关掉 Observability。

## 5. 验收命令与结果（2026-11-01，macOS，Bun 1.4.4，wrangler 4.156.0）

| 命令（在 `relay/server`） | 结果 |
|---|---|
| `bun test test/unit` | 57 通过，0 失败（core 的配置/加密/路由/限速/账本/预算，以及用假平台和手动时钟测试的 Engine：挑战超时、配对超时、空闲、落盘节奏、休眠恢复、活跃分钟去重、跨日、强制关闭、预算缓存、日志不含内容和 IP） |
| `bun run conformance` | 两个实现各 35 个用例全部通过：Bun 与 `wrangler dev --local` 各启动 3 个配置（A 开放/小配额、B 访问码+无画面、C 全局预算），`relay.test.ts` 31 + `slow.test.ts` 3（含 10 秒协议超时和 30 秒落盘切断画面）+ `registrations.test.ts` 1，总计约 1.5 分钟/实现 |
| `bun run typecheck`（`tsc --noEmit`，两个 tsconfig：core/bun/test 与 worker） | 无错误 |
| `bun build --compile --minify bun/server.ts --outfile dist/miffan-relay` | 产出 89 MB 单文件；实际运行：`/v1/info`、`/v1/admin/stats` 正常；缺少 `ACCESS_TOKEN`、配置不存在、配置非法时均以退出码 1 拒绝启动 |
| `docker build` | **未执行**：本机 Docker 守护进程没有运行，没有擅自启动 Docker Desktop。已按 Dockerfile 的步骤在临时目录里手工复现（只复制 Dockerfile 里 `COPY` 的文件，运行 `bun install --frozen-lockfile` 和 `bun build --compile --minify`），成功。基础镜像 `oven/bun:1`、`oven/bun:1-slim` 未拉取验证 |
| `wrangler dev --local -c worker/wrangler.toml`（默认环境与 `--env dev`） | 两个环境都能启动，绑定、变量、迁移被接受，`/v1/info` 正常（未执行任何 deploy/secret 命令） |

一致性测试覆盖：`/v1/info` 字段；主机鉴权（正确/错误签名、错误 Host、乱码、10 秒超时、新连接验签后才替换旧连接）；`host_offline`、配对与 `ready`、双向二进制与文本转发、文本 `ping` 由中转回复且不转发、`host_timeout`、一侧关闭传播、`frame_too_large`（二进制与文本，含恰好 65,536 字节通过）、空闲超时（二进制重置计时、ping 不算）；端到端握手报文逐字节转发（含奇怪的空格和键序）并用参考实现验证 ECDSA P-256 DER 签名及重放失败；`media_disabled`、`quota_exhausted`（含 `retryAfter`）、`too_many_sessions`、`rate_limited`、`relay_busy`（`media_paused`→`closed`，运行中的控制会话不受影响）、`too_many_registrations`（已注册的主机不受影响）；访问码 401；计量（字节、活跃分钟、两个通道、`hello` 与 `ready` 一致）；运行中的画面会话在 30 秒落盘时被 4429 切断并向 connector 推送 `usage`；`/v1/admin/stats` 需要管理口令。

## 6. 给后续的备忘

- connector（R1c）可以直接参考 `test/client/client.ts`、`keys.ts`：包含主机签名、挑战应答、ECDSA P-256 的 DER 编码/解码和端到端握手的完整流程。
- 官方实例的策略取值还没定（RELAY_PLAN §12.1）；`worker/wrangler.toml` 里现在是默认值。
- `wrangler.toml` 的迁移保留 R0 已经应用过的 `v1`（`HostRelay`），新增 `v2`（`Budget`），所以向 `miffan-relay-dev` 部署只会应用 `v2`。R0 原型留在该 DO 里的旧表（`usage`、`wakes`）不会被读取，也不会被清理。
