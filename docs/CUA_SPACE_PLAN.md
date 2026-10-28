# 伙伴专属电脑（cua Space）方案

## 背景与目标

有些用户只有一台电脑。他们想让伙伴能用电脑，又担心伙伴误删文件、改坏设置，后果落在唯一的这台机器上。现有方案只能把用户的真实桌面交给伙伴。

本方案在用户自己的电脑里，为伙伴开一台隔离的电脑：cua Space（容器或虚拟机，由 cua 管理）。伙伴在里面装软件、跑命令、操作桌面，弄坏了重建即可，用户的真实桌面和文件不受影响。用户随时能在手机上看这台电脑、接手操作。

参考：cua 在 2026-10-20 发布了 Hermes Agent 插件（`trycua/hermes-cua-spaces`），Hermes 需要电脑时在用户的 Mac、Windows 或 Linux 上创建 Space，做完即删。

不在本方案内：cua 的中转服务（relay）与云端 Space、在 Space 里运行模型或 API Key、多个伙伴共用一台 Space。

## 已确定的决策（2026-10-28）

- 每个伙伴一台常驻的独立电脑，保留文件和装好的软件；不按任务创建、用完即删。
- 多个伙伴各自一台，不共用。
- 操作电脑的三档权限（先问我 / 直接操作 / 不允许）保留不变。独立电脑新建时默认“直接操作”，用户随时可改；真实桌面默认仍是“先问我”。
- 文件交接两种都做：手机上传下载，以及宿主电脑上的交接文件夹。
- Windows 主机暂缓，第一版只支持 Mac 和 Linux 主机。
- 第一版画面用 cua-spacesd 自带的画面流，不用 Sunshine。

## 已确认的事实（2026-10-28 验证）

在本机 Mac（M1 Pro，16 GB，cua 0.4.4 / 守护进程 0.9.0）和 CachyOS（RTX 3060，cua 0.9.0）上验证，测试 Space 已删除。

### 账号与连接

- 本地 Space 不需要 cua 账号。官方文档：直连方式 “no account is needed”。
- 每个 Space 内运行 cua-spacesd，在 TCP 3211 提供 gRPC（HTTP/2）与 gRPC-Web，需 `authorization: Bearer <token>`。容器 Space 的 3211 只发布到主机回环地址的随机端口（如 `127.0.0.1:32768`），UDP 3212（QUIC）不发布。
- 协议公开：`trycua/cua` 仓库 `libs/cua/proto`（`cua.env.v1`，MIT），媒体协议见同目录 `MEDIA.md`（rcdp wire v2）。服务包括 `ProcessService`、`FilesystemService`、`ComputerService`、`StreamService`、`WindowsService` 等。

### 画面

- `StreamService.OpenMedia` 协商编码并返回一次性票据，画面走同一端口的 WebSocket `/media?ticket=…`。二进制包为长度前缀（`header_len`、`payload_len`，大端），视频为 H.264 Annex B，关键帧带 SPS/PPS，新连接的第一帧一定是关键帧。输入（`InteractiveInput`）也走这个 WebSocket。
- 只在画面变化时编码：静止桌面约 0 帧/秒。
- Mac 实测（终端持续滚动，1280×800）：约 28 帧/秒，平均 3.5 Mbps，首帧 0.5 秒。Space 内编码为软件 OpenH264，约占 0.8 个 vCPU。
- 用户在手机浏览器打开官方网页查看器（经局域网），评价“看着感觉效果还行”。网页查看器依赖 WebCodecs，只在 HTTPS 或 localhost 下可用；App 原生接入不受影响。

### 资源

| 项目 | Mac | CachyOS |
| --- | --- | --- |
| Space 形态 | gVisor 容器，跑在 cua 自带的 Linux 虚拟机（Lima/colima）里 | Docker 容器（gVisor 或 runc） |
| 首次准备 | 下载 Linux 运行时约 455 MB；镜像下载约 1.3 GB，解压后 4.4 GB | 镜像同左 |
| 创建耗时（镜像已缓存） | 15 秒 | 43 秒 |
| 内存 | 虚拟机预留约 4 GB，空闲时实际约 0.4 GB | 空闲约 0.7 GB |
| 暂停 | — | `cua spaces stop` 对容器只是暂停，内存不释放 |
| 上网 | 正常 | 不通：主机 ufw 挡住了 docker0 网桥 |

### 显卡与 Sunshine

- Linux + NVIDIA 主机上，`--runtime runc --gpu nvidia` 可以让 Space 拿到显卡，不需要 root；gVisor 拿不到显卡。即：要显卡就要放弃更强的隔离。
- Space 内 NVENC 可用，Sunshine 在 Space 内换端口启动并找到 NVENC 编码器（未配对、未实测串流）。
- Sunshine 进 Space 的障碍：容器里没有 `/dev/uinput`，Sunshine 无法注入键鼠；把 uinput 给容器会让虚拟键鼠出现在主机桌面上，破坏隔离。手机连入还需要主机转发和 root 级防火墙规则。
- 结论：第一版不用 Sunshine，用 cua-spacesd 自带的画面流。

### 坑

- Mac 上 cua 守护进程由图形界面的 Cua Spaces.app 启动，不继承用户的代理，首次下载运行时失败。
- cua 自带的 Linux 虚拟机缺 `/etc/resolv.conf`，dockerd 没有代理，拉镜像失败；cua 拉镜像约 1 分钟后放弃。验证时在虚拟机内给 dockerd 加了代理（`/etc/systemd/system/docker.service.d/miffan-spike-proxy.conf`，仍保留），并在虚拟机里直接 `docker pull`。
- `--embedded` 模式下 CLI 退出后虚拟机可能随之停止；正常应通过守护进程管理（见 P0 结论）。
- CachyOS 上 Space 无法出网，伙伴的电脑不能上网基本不可用。

## P0 补充验证结论（2026-10-28）

| 问题 | 结论 |
| --- | --- |
| 非交互 SSH 下能否管理 Space | 能。用精简环境（无代理、最小 PATH）调用 `cua spaces create`，由守护进程完成，17 秒（镜像已缓存）。CLI 退出、守护进程停止后 Space 仍在运行 |
| 文件能否常驻 | **gVisor 不行**：容器重启后家目录和系统目录的改动全部丢失（Mac 上 cua 默认就是 gVisor）。**runc 可以**：Mac 与 Linux 上重启后文件都在。所以独立电脑一律用 `--runtime runc` |
| 主机重启后恢复 | 容器没有自动重启策略。运行时虚拟机或容器停止后，`cua spaces start` 能把同一个 Space 拉起来、数据仍在；但回环端口每次都会变，helper 必须每次现查端点，不能缓存 |
| Space 里的电脑操作 | 3211 端口上的 `/mcp` 就是 Space 内 cua-driver 的 streamable HTTP MCP（Mac 镜像为 0.38，63 个工具，带 `skill://cua-driver/*` 文档），截图正常。现有 `RemoteComputerRegistry` 只需换成 HTTP 传输 |
| Space 是否自带 sshd | 不带（无 `/etc/ssh`）。SSH 跳板方案需要额外安装维护，放弃 |
| 交接文件夹 | cua 没有挂载主机文件夹的选项；`volume.binds` 属于 cua 自己带版本管理的 Cua Volume，Space 内默认只读，写入需要用户用 Touch ID 授权，不采用。`cua sb cp` 双向拷贝一个小文件约 40 毫秒，交接由 Miffan 自己同步 |
| Android 端能否经 JSch 通道访问 | 能。用 App 同款 JSch 2.28.7 + OkHttp 5.4.0 做 JVM 原型，自定义 `SocketFactory` 把 socket 换成 direct-tcpip 通道，经 Tailscale 连 CachyOS：gRPC（h2c）`Health`、`OpenMedia`（111 毫秒）成功；WebSocket `/media` 50 毫秒收到第一个视频包，约 28.7 帧/秒、2.9 Mbps；`/mcp` 初始化成功。一共 3 个 SSH 通道，手机不监听端口 |
| 镜像版本 | CachyOS 上缓存的旧镜像里 cua-driver 是 0.33.1，低于现有最低版本 0.34。创建前要检查镜像版本，必要时先拉新镜像 |

据此确定：伙伴的命令、文件、电脑操作和画面都走 Space 的原生接口（gRPC、`/mcp`、`/media`），不走 SSH 跳板。

## 产品设计

### 入口与用户心智

- 设置电脑流程在“连接成功”之后多一个选择：
  - 「直接用这台电脑的桌面」（现有方案）；
  - 「给伙伴一台独立的电脑」：说明“在这台电脑里单独运行，伙伴在里面做什么都不会碰到你的文件和桌面”。
- 伙伴资料页的“电脑”分组显示“它的电脑（独立）”，以及所在的主机。“我的电脑”列表里，独立电脑挂在宿主电脑下面，不单列为一台电脑。
- 隔离边界要讲清楚：隔开的是文件、桌面和已安装的软件；网络没有隔开，伙伴在里面照样能上网、登录网站。

### 生命周期

每个伙伴一台常驻的独立电脑，像它自己的电脑一样保留文件和装好的软件。

- 「重置」：删除后用同一镜像重建，所有内容清空，需二次确认。
- 「删除」：伙伴不再有这台电脑。
- 空闲时是否暂停：容器暂停不释放内存，意义不大；第一版不自动暂停，详情页显示占用。
- 主机重启后的恢复：待 P0 验证。

### 文件交接

伙伴在独立电脑里的产出，用户需要拿到；用户也要能把文件交给伙伴。两种都做：

- 手机直接上传、下载：经 `FilesystemService`，与现有工作区文件页一致。
- 宿主电脑上的交接文件夹（例如 `~/Miffan/<伙伴名>`），对应 Space 里的 `~/交接`。cua 不能直接挂载主机文件夹，由 Miffan 同步：
  - 伙伴交出：伙伴把文件放进 `~/交接` 后，用工具“交给用户”，helper 拷到宿主电脑的交接文件夹。
  - 用户交入：用户放进宿主文件夹的新文件，在伙伴每轮开始时拷进 Space 的 `~/交接`。
  - 同名冲突时不覆盖，加后缀。伙伴在宿主电脑上只碰得到这个文件夹。重置独立电脑不清空交接文件夹。

### 权限

三档权限（先问我 / 直接操作 / 不允许）不变，只是默认值按电脑类型区分：

- 真实桌面：默认“先问我”（现状）。
- 独立电脑：可以随时重置，新建时默认“直接操作”，首次开启时说明“截图会发给模型提供商；它在里面能上网”。用户随时可以改回“先问我”或“不允许”。

### 网络检查

创建后在 Space 内检测出网（DNS 与 HTTPS）。失败时按主机给出原因与修复办法，例如 Linux 上 ufw 挡住了 docker 网桥时，给出需要用户自己执行的命令。

## 架构

### 连接方式

手机仍只与主机建立一条 SSH 会话，复用现有连接池、主机指纹和凭据：

```
手机 ── SSH（主机） ──┬─ exec: miffan space …（创建、删除、查询、读取 token）
                    └─ direct-tcpip → 127.0.0.1:<Space 的 3211>
                          ├─ gRPC：进程、文件、窗口、剪贴板、打开媒体会话
                          └─ WebSocket /media：画面与输入
```

- Space 的 token 由 helper 现读现给，不进数据库、备份和模型上下文。
- 手机不监听任何端口（与现有屏幕方案一致）。gRPC 和 WebSocket 需要跑在 JSch direct-tcpip 通道上：给 OkHttp 提供一个基于通道流的 `SocketFactory`，或者走 gRPC-Web（HTTP/1.1）。P0 验证。

### 伙伴的命令、文件和电脑操作

走 Space 的原生接口（P0 已验证）：

- 命令：`ProcessService`（含 PTY），对应现有的按对话持久 shell。
- 文件：`FilesystemService`。
- 电脑操作：Space 内 cua-driver 的 `/mcp`（streamable HTTP），沿用 `RemoteComputerRegistry` 的工具精选、Skill 读取和截图处理。
- 通道：一个基于 JSch direct-tcpip 的 `SocketFactory`，供 OkHttp（gRPC 用 h2c，WebSocket 与 MCP 用 HTTP/1.1）和 Ktor（MCP SDK）共用。

需要为工作区新增一种传输，与 `NativeSshWorkspaceTransport` 并列。

### 数据模型

- 新增 `cua_spaces` 表：`id`、`host_id`（宿主电脑）、`space_id`（如 `local:miffan-<短 id>`）、`image`、`kind`/`runtime`、`assistant_id`、`created_at`、`last_status`。
- 工作区新增 kind `SPACE`，指向 `cua_spaces`。注意 `WorkspaceEntity.isRemote` 目前把所有非 LOCAL 都当作 SSH 远程，需要显式区分。
- `WorkspaceToolTargetSnapshot` 加入 Space 身份；重置后的 Space 视为新目标，旧的工具调用不能落到新 Space 上。
- 屏幕：`RemoteDesktopProtocol` 增加 `CUA_SPACE`，实现 `RemoteDesktopSession`；H.264 解码复用 `stream` 模块的 `StreamDecoder`。
- 电脑操作仍走 `ComputerToolbox` / `RemoteComputerRegistry`，只换 MCP 传输；`RemoteComputerControl` 的控制权仲裁按 Space 计算，而不是按宿主电脑。

### helper

`miffan.sh` 新增 `space` 子命令，封装 `cua` CLI 并输出 JSON：

- `space probe`：cua 是否安装及版本、守护进程状态、可用运行时与显卡、磁盘余量、已缓存镜像。
- `space install`：经用户确认后安装 cua（官方安装脚本），准备运行时。
- `space create|start|stop|delete|reset|info`：管理 Space，一律 `--runtime runc`；`info` 每次现查回环端口与 token（端口在每次启动后都会变），Space 已停止时先启动。
- `space handoff out|in`：交接文件夹的双向拷贝。
- `space netcheck`：在 Space 内检测 DNS 与 HTTPS。

代理：helper 运行时带上用户 shell 的代理变量；镜像拉取失败时给出明确原因（含“需要代理”）。Windows 主机的 `miffan.ps1` 对应部分在 Windows 支持确定后再做。

## 开发阶段

| 阶段 | 内容 | 交付 |
| --- | --- | --- |
| P0 补充验证 | 已完成，见上文 | 伙伴工具走原生接口；独立电脑用 runc |
| P1 创建与绑定 | helper `space` 子命令、数据表、设置流程中的“独立电脑”分支、网络检查、伙伴的命令与文件跑在 Space 里 | 伙伴能在独立电脑里跑命令、读写文件 |
| P2 画面 | cua 媒体客户端（OpenMedia + rcdp v2）、输入、剪贴板，接入现有屏幕页 | 用户在手机上看和操作独立电脑 |
| P3 电脑操作 | 经 Space 内 driver MCP 的电脑操作、控制权按 Space 仲裁、独立电脑的默认权限 | 伙伴在独立电脑里完成“打开应用—输入—验证” |
| P4 管理 | 重置、删除、占用显示、交接文件夹、宿主电脑删除时的处理 | 完整的轻松模式体验 |
| P5 扩展 | Linux + NVIDIA 主机上可选的 Sunshine 高性能画面；macOS / Windows Space 镜像 | 按需求再定 |

## 验收

1. 只有一台 Mac 或 Linux 电脑的新用户，能在轻松模式中给伙伴开一台独立电脑，不需要 cua 账号。
2. 伙伴在独立电脑里删除文件、改系统设置，宿主电脑的文件与桌面不受影响。
3. 用户在手机上能看和操作独立电脑；伙伴操作时用户触摸即接管。
4. 重置后，独立电脑恢复到干净状态，旧对话里的工具调用不会落到新电脑上。
5. Space 的 token 不进数据库、模型上下文和备份。
6. 创建失败（下载失败、磁盘不足、不能上网）时，用户看到原因和下一步。

## 待定问题

- Linux 主机的隔离强度：runc 容器与主机共用内核（Mac 上外面还隔着 cua 的虚拟机）。防伙伴误操作足够；如果要防恶意代码逃逸，需要改用 QEMU 虚拟机形态（`linux:24.04-disk`，更重，未测）。
- Mac 上 cua 守护进程不继承用户代理、cua 虚拟机缺 DNS 与 dockerd 代理：helper 如何在不改用户系统的前提下处理，P1 再定。
- 资源提示：Mac 上多台容器共用一台 cua 虚拟机，增量内存较小；Linux 上每台约 0.7 GB。主机内存不足时是否限制独立电脑的数量。
- Windows 主机何时支持（cua 镜像目录里有 Windows Space，但那是完整虚拟机，资源占用大得多）。
