# Miffan 中转服务器（relay server）

让手机在不同网络下，经过一台"中转"连到家里/公司的电脑。中转只转发密文，看不到内容，也冒充不了你的电脑。
协议见 [../PROTOCOL.md](../PROTOCOL.md)，设计背景见 [RELAY_PLAN](../../docs/remote-screen/RELAY_PLAN.md)。

同一份代码有两种部署方式，行为完全一致（由同一套一致性测试保证）：

| | Cloudflare Workers | VPS（Bun） |
|---|---|---|
| 适合 | 不想管服务器；个人使用几乎免费 | 想要固定出口 IP、更宽松的额度，或者手边已有服务器 |
| 费用 | 免费方案可用（见"额度"） | 一台能跑 Docker 的小机器，成本主要是流量 |
| 需要 | Cloudflare 账号 + 一个托管在 Cloudflare 的域名 | 一个指向服务器的域名 + HTTPS 反向代理（下面给 Caddy 示例） |
| 配置 | `worker/wrangler.toml` 里的 `RELAY_CONFIG` | `relay.config.json` |

> **国内用户请注意**：`*.workers.dev` 域名在中国大陆无法访问，Cloudflare 部署**必须绑定你自己的域名**（见下）。

## 一、配置（两种部署通用）

配置是一份 JSON。不写的字段取默认值；数值 `0` 表示"不限"。

```json
{
  "accessMode": "open",
  "registrationsPerIpPerDay": 5,
  "policy": {
    "control": { "kbps": 1000, "burstKb": 2048 },
    "media":   { "allowed": true, "kbps": 4000, "burstKb": 4096 },
    "limits":  { "sessionsPerHost": 4, "idleTimeoutS": 300 },
    "quotas": {
      "hostDailyMb": 500,
      "hostDailyActiveMin": 0,
      "hostMonthlyControlMb": 2048,
      "hostMonthlyMediaMb": 10240
    },
    "budget": { "globalDailyMb": 0, "globalDailyActiveHours": 0, "mediaReserve": 0.3 }
  },
  "edges": []
}
```

| 字段 | 含义 |
|---|---|
| `accessMode` | `open`：任何人的 connector 都能注册；`token`：必须带访问码（见下）。只给自己用就选 `token` |
| `registrationsPerIpPerDay` | 同一个来源 IP 每天最多注册几台新电脑（防滥用） |
| `policy.control` | 控制通道（SSH、AI 操作电脑）的限速：持续速率 `kbps`、突发 `burstKb`。超过 1.5 倍会被中转断开 |
| `policy.media` | 画面通道。`allowed: false` 表示本中转不提供画面；`kbps`/`burstKb` 同上 |
| `policy.limits` | 每台电脑同时最多几个会话；会话多少秒没有数据就关闭 |
| `policy.quotas` | 每台电脑的额度：每天总流量（MB）、每天活跃分钟数、每月控制/画面流量（MB）。用完后新会话返回 `quota_exhausted` |
| `policy.budget` | 整个中转的每日预算：总流量（MB）、活跃小时数。剩余比例低于 `mediaReserve` 时先停画面，用完则拒绝所有新会话（`relay_busy`） |
| `edges` | 入口 IP 候选（Cloudflare 部署用，帮手机选线路；VPS 留空） |

说明：

- 计量按 UTC 的日和月；"MB"是 1024×1024 字节；只统计转发的二进制帧。
- **活跃分钟**：该电脑任一会话转发过数据的不同 UTC 分钟数。Cloudflare 免费方案的成本主要是 Durable Object 的运行时长，而它只在有数据经过时才醒着，所以按活跃分钟限额比按流量更贴近成本；VPS 的成本是流量，用 MB 限额即可。
- `accessMode` 为 `token` 时需要两个口令（都是环境变量/secret，**不要写进配置文件**）：
  - `ACCESS_TOKEN`：访问码。手机和 connector 请求中转时带 `Authorization: Bearer <访问码>`。
  - `ADMIN_TOKEN`：可选。设置后 `GET /v1/admin/stats` 可查看当天全局用量。

  生成一个随机口令：`openssl rand -base64 32`。
- 访问码模式下没设 `ACCESS_TOKEN`：Bun 版拒绝启动；Cloudflare 版对所有 WebSocket 请求返回 401。

## 二、部署到 Cloudflare

前置：一个 Cloudflare 账号；你的域名已托管在 Cloudflare（DNS 由 Cloudflare 管理）。

```bash
cd relay/server
bun install              # 或 npm install
bunx wrangler login      # 浏览器里授权
```

1. 编辑 `worker/wrangler.toml`：
   - 把 `routes` 里的域名改成你自己的，例如 `relay.example.com`（`custom_domain = true` 会让 Cloudflare 自动创建 DNS 记录和证书）；
   - 需要时把 `name` 改成你喜欢的 Worker 名；
   - 在 `RELAY_CONFIG` 里改策略（见上）。个人使用建议 `"accessMode": "token"`。
2. 设置口令（访问码模式才需要访问码）：

   ```bash
   bunx wrangler secret put ACCESS_TOKEN -c worker/wrangler.toml
   bunx wrangler secret put ADMIN_TOKEN  -c worker/wrangler.toml   # 可选
   ```
3. 部署：

   ```bash
   bunx wrangler deploy -c worker/wrangler.toml
   ```
4. 验证：`curl https://relay.example.com/v1/info` 应返回 JSON（含 `"impl":"worker"`）。
5. 回到电脑所在的网络，在 Miffan 开启“不在家也能用”时（或电脑详情“出门在外 > 换一个中转”）点“用自己的中转”，填 `https://relay.example.com` 和访问码；也可以扫描内容为 `miffan-relay://add?url=https://relay.example.com&token=<访问码>` 的二维码。访问码建议用 `openssl rand -hex 24` 生成。

给不熟悉命令行的用户的分步教程见 <https://miffan.ayuilos.me/docs/relay-self-hosting>。

`wrangler.toml` 里已经配好 Durable Object（必须是 SQLite 存储，免费方案只支持这种）和迁移，不用手动创建。里面还有一个 `[env.dev]`（`bunx wrangler deploy --env dev`）用于测试环境。

> **一键部署按钮**：把仓库公开后可以用 Cloudflare 的 "Deploy to Cloudflare" 按钮（根目录选 `relay/server/worker`），部署完成后仍需在 Cloudflare 控制台的 Worker 设置里添加自定义域名和 secret。按钮流程尚未在本仓库实测。

### Cloudflare 免费方案的额度

免费额度是整个账号共用的：用超后 Cloudflare 会直接拒绝所有请求（App 会显示"免费中转今天的额度用完了"），北京时间早上 8 点（UTC 0 点）恢复。要避免这种"一起断线"，请设置 `policy.budget`，让中转在额度用完之前先停画面、再拒绝新会话。个人部署只服务你自己的几台电脑，默认额度已经绰绰有余。

## 三、部署到 VPS

### Docker

```bash
# 在仓库根目录
docker build -t miffan-relay relay/server

# 镜像里的中转以 uid 1000 的 bun 用户运行，数据目录要归它所有
sudo mkdir -p /srv/miffan-relay
echo '{ "accessMode": "token" }' | sudo tee /srv/miffan-relay/relay.config.json
sudo chown -R 1000:1000 /srv/miffan-relay

docker run -d --name relay --restart unless-stopped \
  -p 127.0.0.1:8787:8787 -v /srv/miffan-relay:/data \
  -e ACCESS_TOKEN="$(openssl rand -base64 32)" -e ADMIN_TOKEN="$(openssl rand -base64 32)" \
  -e TRUSTED_IP_HEADER=x-forwarded-for \
  miffan-relay
```

记下生成的 `ACCESS_TOKEN`（`docker inspect relay` 里能看到），用于手机上填写。计量数据在 `/srv/miffan-relay/relay.sqlite`。

### 单文件 + systemd

```bash
cd relay/server && bun install
bun build --compile --minify bun/server.ts --outfile dist/miffan-relay     # 产出单个可执行文件
sudo install -m 755 dist/miffan-relay /usr/local/bin/miffan-relay
sudo mkdir -p /etc/miffan-relay /var/lib/miffan-relay
echo '{ "accessMode": "token" }' | sudo tee /etc/miffan-relay/relay.config.json
```

`/etc/systemd/system/miffan-relay.service`：

```ini
[Unit]
Description=Miffan relay
After=network.target

[Service]
ExecStart=/usr/local/bin/miffan-relay
WorkingDirectory=/var/lib/miffan-relay
Environment=PORT=8787 HOST=127.0.0.1 TRUSTED_IP_HEADER=x-forwarded-for
Environment=RELAY_CONFIG_FILE=/etc/miffan-relay/relay.config.json
Environment=RELAY_DB=/var/lib/miffan-relay/relay.sqlite
EnvironmentFile=/etc/miffan-relay/secrets.env
DynamicUser=yes
StateDirectory=miffan-relay
Restart=always

[Install]
WantedBy=multi-user.target
```

`/etc/miffan-relay/secrets.env`（权限 600）：

```
ACCESS_TOKEN=...
ADMIN_TOKEN=...
```

`sudo systemctl enable --now miffan-relay`。

### 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `RELAY_CONFIG_FILE` | `./relay.config.json` | 配置文件。默认路径不存在时用全部默认值；显式指定却不存在则报错退出 |
| `RELAY_DB` | `./relay.sqlite` | 计量数据库 |
| `ACCESS_TOKEN` | — | 访问码；`accessMode` 为 `token` 而没设置时拒绝启动 |
| `ADMIN_TOKEN` | — | 管理口令，启用 `/v1/admin/stats` |
| `PORT` / `HOST` | `8787` / `127.0.0.1` | 监听端口与地址（Docker 镜像里 `HOST` 为 `0.0.0.0`） |
| `TRUSTED_IP_HEADER` | — | 在反向代理后面时，从哪个请求头取来源 IP（用于"每天注册次数"限制），例如 `x-forwarded-for`（取最后一个值）。不设置则使用直连地址——此时所有人都是反向代理的地址 |

### HTTPS：Caddy 反向代理

中转本身只说 HTTP/WebSocket，TLS 交给反向代理。Caddy 会自动申请证书，WebSocket 无需额外配置：

```
relay.example.com {
    reverse_proxy 127.0.0.1:8787
}
```

> 反向代理必须**原样保留 `Host` 请求头**（Caddy 默认就是；nginx 要写 `proxy_set_header Host $host;`），因为 connector 的签名里包含它。nginx 还需要 `proxy_http_version 1.1; proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade"; proxy_read_timeout 3600s;`。

验证：`curl https://relay.example.com/v1/info`（含 `"impl":"bun"`）。

## 四、日常运维

- 用量：`curl -H "Authorization: Bearer $ADMIN_TOKEN" https://relay.example.com/v1/admin/stats`
- 日志（Cloudflare 控制台的 Workers Logs / VPS 的标准输出）只有 JSON 行：事件名、`hostId` 的前 8 位、原因码。**不记录**帧内容、口令、完整 IP。来源 IP 只用于注册频率限制：存的是加盐哈希，盐每天更换，隔天清除。
- 更新：重新 `wrangler deploy`，或重新构建镜像/二进制并重启。协议向后兼容，旧 App 与旧 connector 不受影响。

## 五、开发与测试

```bash
bun install
bun run typecheck            # core/bun/test 与 worker 两套类型
bun test test/unit           # 单元测试（含用假平台和假时钟测试的引擎）
bun run conformance          # 一致性测试：同一套用例分别对 wrangler dev --local 和 Bun 服务运行
bun run conformance -- --impl bun       # 只测一个实现
bun run dev:bun              # 本地启动 Bun 版（8787）
bun run dev:worker           # 本地启动 Worker 版（wrangler dev --local）
```

代码结构：

```
core/      与平台无关：配置、协议常量、加密、限速、计量、预算、策略、路由，以及 Engine（每台电脑的全部会话逻辑）
worker/    Cloudflare：Worker 入口、HostRelay（每个 hostId 一个 Durable Object，Engine 的外壳）、Budget（全局单例）
bun/       VPS：Bun.serve 外壳 + bun:sqlite
test/      unit、conformance（黑盒，两个实现共用）、client（协议参考客户端）
```
