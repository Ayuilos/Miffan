# App ↔ helper 协议

本文件根据迁移基线 `6f4eb1777` 的脚本及 Kotlin 消费者整理；不是新协议。源码位于
`src/miffan.sh`、`src/windows/miffan.ps1` 及 include 的 C#，运行 Windows 模板前必须构建。
下表的“无”指该命令不消费 stdin；没有保证的行为明确写为“未定义”。

## 通用调用与解析

POSIX 调用 `"$HOME/.miffan/bin/miffan" <command>`，Windows 调用
`& "$env:LOCALAPPDATA\Miffan\bin\miffan.ps1" <command>`。
Kotlin `screenHelperCommand` 仅接受固定单词操作，用户数据放 stdin；unpair 的证书
SHA-256 经本地计算后作为第三参数追加。shell 无统一额外参数校验，见各分支实现；
Windows `Invoke-Helper` 对不允许的 Action/Operation 抛错。

除流式命令和 version 外，输出通常是一行 JSON。Kotlin 的 `parseHelperProbe`、
`parseSunshineProbe`、`parseSunshineCommand`、`parseSunshineFirewallCheck`、
`parseSunshineFirewallResult`、`parseWindowsClipboard` 都读取 trim 后最后一行，
忽略未知 JSON 字段。`parseRdpStart` 使用同样 framing，但把错误映射为 App problem。
`RemoteSunshineUnpair.unpair` 对整个 trim 后 stdout 用严格 `Json` 解析，不忽略未知字段。
`startHelperVnc` 内联解析最后一行 `RemoteMachineProbe.Vnc`；`RemoteScreenRepository.probe`
也内联解析 `RemoteMachineProbe`，`open` 则调用 `parseHelperProbe`。

Windows 外层将普通返回值用 `ConvertTo-PlainJson`（JSON depth 100、compress）输出；返回
字典含 `success:false` 时退出 1，否则 0。异常输出
`{success:false,detail,error_type,backup,cleanup_task,cleanup_directory}` 并退出 1：detail
只允许 SafeFailures 中的消息，其他消息变为 `Helper command failed`。后面三个字段是
本次命令的备份或待检查清理对象，可为 null。`cua` 的 stdio/bridge/daemon 异常仅在
stderr 输出固定诊断并退出 1。没有通用错误码枚举以外的稳定系统错误码约定。

## 公共 JSON 字段

### 机器 probe

`helper`：整数脚本版本；`os`：linux/macos/windows/unknown；`arch`：系统架构字符串；
`hostname`：展示名称，可 null。`session` 包括 `present`、`type`（wayland/x11/none/quartz/
console）、`desktop`（可 null）。Windows 额外有 id/user/domain/sid、`lock_state`
（locked/unlocked/unknown）；App 只消费 present/type/desktop/lock_state。

`cua`：path/version（可 null）、min（最低支持版本 0.34.0）、ok（版本及权限可用性）。
Windows 额外有 installed、autostart_run_level、daemon_session_ids、daemon_integrity_rids、
bridge_available、permission_review_needed。`vnc` 见下节；`rdp` probe 包含 server/version/
running。`clipboard` 是检测到的工具名或 null，Windows 固定 interactive-task。
Windows 还输出 os_version/os_version_number/os_build、gpu 字符串数组、displays:null、
display_state:unknown，当前 Kotlin probe 模型忽略这些附加字段。

### VNC

状态为 `{server,running,endpoint}`，Linux 无可用服务时 server=none，仍可输出 unix endpoint（不代表服务可用）；
x11vnc 的启动成功响应额外包含 password（只有 start 返回，状态查询不返回）。
endpoint 为 wayvnc 的 `unix:<socket>` 或 x11vnc 的 `tcp:<port>`，macOS 固定 tcp:5900。
启动错误为 `{error}` 或 `{error,session,desktop}`、`{error,log}`：error 为
no_graphical_session/no_vnc_server/vnc_start_failed。App 从 error 区分可恢复条件；
无 error 时仍要求 endpoint 可解析，不能是 helper 自引用。

### RDP start

`server`：gnome-remote-desktop/krdp；`port`：随机高位端口；`username`：miffan-<uid>；
`desktop`：当前桌面；`mode`：user/headless；`error`、`log`：可 null 的固定诊断；
`width`/`height`：可 null 的桌面尺寸；`certificate_sha256`：成功启动的自有证书 DER
SHA-256，失败时 null。headless 默认 1920×1080。
`parseRdpStart` 接受端口 1024..65535、非空 username、上述 server/mode；将 pin 去冒号
转小写并要求 64 个十六进制字符。error 映射：no_graphical_session、no_rdp_server、
rdp_already_configured、keyring_locked、credential_setup_unavailable 各对应同名 App
问题，其余及无效 JSON/字段/pin 都映射 RDP_START_FAILED。

### Sunshine probe

`installed`、`running`、`active_stream`：布尔；`version`：包版本或 null；
`lan_encryption_mode`/`wan_encryption_mode`：配置整数或 null（默认 0/1）；只有两者都 2
时 App 认为强制加密。`certificate_sha256`：服务端证书 DER pin 或 null；`candidates`：
本地私网/Tailscale IPv4 地址，App 再过滤为数字地址并去重。
`display_asleep`、`screen_locked`：macOS 上可判定的布尔，否则 null；Windows 只报告锁屏状态。
`permissions`：macOS 为 `{screen_recording,accessibility}`（各可 null），其他为 null；
`permissions_from_log`：是否从最近一次启动日志发现明确权限拒绝，其他平台为 null。
没有拒绝证据并不表示已授权。Windows 额外输出 stream_state_known、service_state、
console_session_id、daemon_session_ids、listeners（address/port/pid）、listeners_known、
configuration_known；Kotlin 忽略这些诊断字段。

### 普通 Sunshine 命令与 pair/unpair

start/enforce-encryption/wake/open-settings 返回 `{success,detail}`，wake 可另带
`display_asleep`；Windows start/enforce-encryption 另带 changed/backup，App 普通命令
解析器忽略这两个字段。detail 是可展示诊断，不是枚举。
pair 只从 stdin 接收唯一 JSON 对象 `{name,cert}`，名称非空、最多 64 字符且无控制字符，
cert 恰为一个可解析的 X.509 PEM 块；拒绝额外内容/多证书/私钥，写入 DER 重新编码的规范 PEM。
成功的整段 stdout 是一行 `{added:true,replaced:<非负数量>,restarted:<bool>,client_sha256:<小写64hex>}`，
client_sha256 是写入的客户端证书 DER 摘要。App 严格检查完整输出和摘要。
状态文件存在时必须为普通文件；顶层对象缺 root 时新建 root，已有 root 必须是对象。
root.uniqueid 缺失或不是字符串时补大写 UUID4，已有字符串原样保留；Sunshine load_state
依赖这个服务端身份才能读取 named_devices。顶层 username/password/salt 等凭据及其他字段均保留。
named_devices 缺失时新建数组，已有项必须具有字符串 name/cert/uuid。按证书 DER 删除所有旧项后
追加 name/cert/大写 UUID4/enabled:true。运行中先停服务、重读 shutdown state；仍无文件时新建
上述 root/uniqueid/named_devices，否则修改最后刷出的文件。新文件父目录必须已存在且为普通目录，
否则返回 Unrecognized Sunshine state format。POSIX 新文件归当前用户、权限 0600，临时文件经
os.replace 发布；Windows 新文件继承目录 ACL，以 File.Move 发布。已有文件保留属主/权限/ACL，
原子替换（Windows File.Replace）；最后恢复原运行状态。未运行时只写状态、不启动。
安装和服务模型无法确认则失败。
pair 失败走普通 success:false/detail 输出（Windows 仍带外层诊断字段）。可用于 PIN 兜底的 detail 为
`Unrecognized Sunshine state format`、`Cannot read Sunshine state or configuration; check administrator rights`、
`Cannot edit Sunshine state or control its service; check administrator rights`，以及
`Could not stop Sunshine to change pairing`（App 映射为 `SUNSHINE_DID_NOT_STOP`）。
POSIX 只有实际 `EACCES/EPERM` 才返回 check administrator rights；systemctl stop 非零、
其他停止失败或退出等待超时使用 Could not stop Sunshine to change pairing，不据此推定权限不足。
POSIX pair/unpair 共用停止函数；macOS 的 enforce-encryption 也复用该函数。macOS 只对
mac_processes() 确认的当前用户 Sunshine 进程发送 SIGTERM，等待退出最多 10 秒；
不调用 osascript/Apple Events，不依赖 Automation 授权，不发送 SIGKILL。
进程在发信号前已退出（ESRCH）可继续检查。pair/unpair 在发起停止前就标记需要恢复原运行状态；
停止失败或退出确认超时不写配对状态，但 finally 仍尝试 open -a（macOS，保留原配置参数）或
systemctl --user start（Linux），以免 SIGTERM 后延迟退出使 Sunshine 一直停着。
enforce-encryption 在停止前已保存带备份的配置；macOS 停止失败或超时后仍执行 open -a，
然后返回 `Encryption saved with backup; Sunshine graceful quit failed`（包括这次 open 失败的情况），
不使用配对场景的 detail，也不发送 SIGKILL。
`Sunshine pair attempted; could not restart Sunshine` 表示重启失败，文件可能已经写入；App 抛错。
其他未知失败也抛错；不将未经确认的配对记录保存为信任。
unpair 成功专用响应 `{removed:<非负数量>,restarted:<是否重新启动>}`；无匹配或文件不存在为 0/false，不创建文件、不重启。
失败走各平台的普通错误输出，App 在解析前检查退出码/timeout。

## Linux/macOS 子命令

脚本使用 `/bin/sh`，普通用户身份。Linux 从已有 display、systemd user environment
或该用户 `/proc/<pid>/environ` 恢复图形会话。它不创建或解锁会话。Shell 除 `set -u`
外没有 `set -e`，下表未明确保证退出码的分支不能依赖统一错误处理。

| 调用及参数 | stdin / stdout | 退出码 | 副作用、前置条件 | App 调用 → 解析 |
|---|---|---|---|---|
| `version` | 无 / 版本十进制文本加 LF | 0 | 无 | `RemoteMiffanHelper.ensureInstalled` → stdout.trim 与内置版本比较 |
| `probe` | 无 / 机器 JSON | 一般 0；系统命令异常未定义 | 会话/版本/服务探测；VNC 状态可创建私有 runtime 目录 | `open`、`probeHost`、`probe` → `parseHelperProbe` 或相同内联 decoder |
| `env` | 无 / `export KEY='value'` 文本，单引号转义 | 没有会话时最终管道状态未定义 | 仅读当前用户会话白名单变量 | 当前无 Kotlin 调用/解析 |
| `cua` | MCP JSON-RPC stdio / 原样转发 | 无会话 1；无 driver 127；其余由 exec driver 决定 | 需要图形会话及 cua-driver；Linux Wayland 设置开关与 UTF-8 字符类型；执行 `cua-driver mcp` | `openCuaProcess` → `RemoteComputerRegistry.connection` 的 MCP transport/client |
| `cua check-update` | 无 / cua-driver `check-update --json --no-cache` 的 JSON 压成一行（`current_version,latest_version,update_available,release_notes_url,error`…） | 总是 0；未安装 `{"error":"not_installed"}`，失败或无输出 `{"error":"check_failed"}`；只读，访问 GitHub | `checkCuaUpdate` → `RemoteCuaUpdateCheck` |
| `cua install` | 无 / 安装器输出（stdout/stderr） | 安装/更新成功 0；失败为安装器退出码；下载过慢或连不上 1 并在 stderr 写 `Cua download failed; nothing was changed` | 先取电脑自己的代理（macOS `scutil --proxy` 的 HTTPS 代理，Linux 图形会话环境里的 `https_proxy`/`all_proxy`；已设置的环境变量优先）；20 秒内须下到 GitHub release 的 1 MiB，否则不运行安装器；已安装时 `update --apply`，失败或未安装时运行官方 install.sh；重启已启用的 systemd user `cua-driver.service` | `installCuaDriver`/`installCuaDriverOnHost` → 退出码及文本摘要 |
| `vnc [status]` | 无 / VNC 状态 JSON | 正常 0；失败未定义 | 探测当前用户服务；runtime 目录可创建 | 无直接状态调用，probe 嵌套使用 |
| `vnc start` | 无 / VNC 启动 JSON | 无会话/无服务/启动失败 1；成功 0 | macOS 只探测已有 Screen Sharing；Wayland 需 wayvnc 与 wlroots 协议；X11 需 x11vnc；启动自有实例、私有 socket/pid/log；X11 随机密码和 loopback TCP | `startHelperVnc` → 内联 Vnc decoder |
| `vnc stop` | 无 / `{"stopped":true}` | 正常 0，kill 失败不保留为退出码 | 停自有 pid，删 socket/port/secret/passwd；不关闭系统 Screen Sharing | `RevokeMiffanKeyCommand` 生成撤销命令 → 忽略 stdout |
| `rdp start` | 一行至少 32 字符密码 / RDP JSON | 成功 0；拒绝/启动失败 1 | Linux GNOME/KDE 图形会话、Python3、OpenSSL 与对应服务/凭据工具。仅接受自有配置；GNOME 经 stdin 配置凭据；KRDP 用 Secret Service 默认且已解锁的非 session collection、QtKeychain/libsecret。写 ~/.miffan/rdp 中证书、私钥、owner、锁及配置，启/重启自有 user unit；不解锁 keyring | `startHelperRdp` → `parseRdpStart` |
| `sunshine [probe]` | 无 / Sunshine probe JSON | 正常 0；缺 Python3 1 | Python3；Linux 通过包管理器/user unit、macOS 通过 bundle/plist/Homebrew 探测；不启动 Sunshine | `sunshineProbe` → `parseSunshineProbe` |
| `sunshine start` | 无 / success/detail JSON | 成功 0；失败 1 | Linux 启已识别 user unit；macOS `open -a` 已有 bundle；已运行则幂等 | `startSunshine` → `sunshineCommand` → `parseSunshineCommand` |
| `sunshine enforce-encryption` | 无 / success/detail JSON | 成功 0；拒绝/失败 1 | 需已有正规配置、无活跃或无法确认的 UDP stream、无命令行加密覆盖；备份配置、原子写 2/2；重启前再次查流；macOS 复用 SIGTERM 停止函数，最多等 10 秒再 open -a 重开，不发 SIGKILL | `enforceStreamEncryption` → `sunshineCommand` → `parseSunshineCommand` |
| `sunshine pair` | 一行 `{name,cert}` JSON / added/replaced/restarted/client_sha256；失败 success/detail | 成功 0；失败 1 | 仅 stdin；已确认安装和服务；规范证书、按 DER 去重；停服务重读后补建缺失状态/root/uniqueid，保留顶层凭据及已有身份/ACL；新文件当前用户 0600，原子发布并恢复原运行状态 | `pairStreamDirectly` → `RemoteSunshineUnpair.pair` → 严格 PairResult decoder |
| `sunshine unpair <client-cert-sha256>` | 无 / removed/restarted，错误 success/detail | 成功 0；失败 1 | 64 位 hex；只按客户端证书 DER 摘要删除配对记录；保留未知字段/权限；必要时先停 Sunshine，重读 shutdown state，原子保存并恢复先前运行状态；卸载时直接 0/false | `RemoteSunshineUnpair.unpair` → 严格 Result decoder |
| `sunshine wake` | 无 / success/detail/display_asleep | 成功 0；失败 1 | 仅 macOS，`caffeinate -u -t 5` 后重查显示状态；不解锁屏幕 | `open` 的 `prepareStreamProbe` → `parseSunshineCommand`；显式命令也走 `sunshineCommand` |
| `sunshine open-settings screen\|accessibility` | 无 / success/detail | 成功 0；失败 1 | 仅 macOS，打开相应隐私设置，有新设置 URL fallback；不改 TCC | `openSunshinePermissionSettings` → `sunshineCommand` → `parseSunshineCommand` |
| `clip` | 原始 UTF-8 文本 / 工具输出（无 JSON 保证） | 无会话/工具 1；其余底层工具退出码 | 当前桌面 pbcopy/wl-copy/xclip/xsel 写剪贴板；Linux 工具输出重定向 | `writeStreamClipboard` → POSIX 仅检查退出码 |
| `mac-shortcut desktop-left\|desktop-right\|mission-control\|app-windows` | 无 / stdout 丢弃 | 参数/非 Mac 2；无 driver 127；否则 driver 退出码 | macOS 可用且有权限的 driver；发送 Ctrl+fn+对应方向键 | `pressMacShortcut` → 只检查进程结果 |

未识别顶级/VNC/RDP/Sunshine 参数退出 2 并在 stderr 提示 usage。

## 定时任务（POSIX `task`，helper 21 起）

App 把任务渲染成文件交给 helper；helper 只安装系统定时器并运行 `run`，不解析任务 JSON，
也不认识 agent。所有 task 命令以 umask 077 运行，只依赖 POSIX sh 与系统自带工具，不需要
python3。任务 id 为 1–64 位 `[A-Za-z0-9-]`；失败统一输出 `{"success":false,"detail":...}`
并退出 1，下表只列成功输出。

| 调用 | stdin / stdout | 副作用、前置条件 |
|---|---|---|
| `task probe` | 无 / `{scheduler,linger,login_required,timezone,utc_offset}` | 只读。scheduler：macOS 为 launchd；Linux 用户 systemd 可用时为 systemd，否则有 crontab 为 cron，再否则 none。linger 仅 systemd 有值；launchd 或 linger=false 时 login_required=true。timezone 为 IANA 名称或 null，utc_offset 形如 `+0800` |
| `task which <name-or-path> [version-arg]` | 无 / `{path,path_env,version}` | 在 `$SHELL -lic` 中解析（fish/nu/xonsh/elvish 改用 /bin/sh），15 秒上限，startup 输出以 `\036` 分隔丢弃。别名、函数、目录或不可执行时 path=null。version 是 `<path> <version-arg>` 输出的首个非空行（≤200 字符），version-arg 只允许 `-` 开头的字母和连字符 |
| `task stage <id> meta\|run\|prompt\|env` | 文件内容（≤256 KiB） / `{success}` | 写 `~/.miffan/tasks/.staging/<id>/`（meta 存为 meta.json）。四者都必须暂存：prompt 为空表示 run 的 stdin 为 /dev/null；env 为每行一个变量名（`[A-Za-z_][A-Za-z0-9_]*`，最多 64 个，可为空），不含值 |
| `task commit <id> <timeout> <schedule...>` | 无 / `{success,backend,enabled}` | timeout 为 60–86400 秒。schedule：`daily HH:MM`、`weekly D[,D...] HH:MM`（0=周日，不重复）、`interval N`（N∈15,30,60,120,180,240,360,480,720 分钟）。无论成败都清空该 id 的暂存目录。逐个原子替换任务文件；同 id 为更新，保留运行记录与启用状态；新任务默认启用。启用时复制 helper 为 `~/.miffan/bin/miffan-task-runner` 并安装定时器；安装失败时新任务整体回滚，旧任务改为停用。launchd 无登录会话时 detail 为 `login_required` |
| `task list` | 无 / `{scheduler,tasks:[{id,enabled,timer_ok,backend,schedule,timeout,meta_b64,last_run}]}` | 只读。timer_ok 为定时器当前确实在系统中注册；meta_b64 为 meta.json 原文 base64；last_run 为最新运行记录或 null |
| `task enable\|disable <id>` | 无 / `{success}` | 启用时按 schedule 重新安装定时器；停用时移除定时器（launchd 删除 LaunchAgents 中的 plist），保留任务文件 |
| `task run <id>` | 无 / `{success}` | 不等结束。systemd 为 `systemctl --user start --no-block` 对应 service；launchd 在任务目录写 `now.plist`（RunAtLoad，不放入 LaunchAgents）后 bootstrap，无登录会话为 `login_required`；cron 为 nohup 后台执行。停用的任务也可运行 |
| `task sync [cursor]` | 无 / `{runs:[record+excerpt_b64],cursor}` | 只读。返回 run_id 字典序大于 cursor 的运行记录；excerpt_b64 为日志末尾 16 KiB 的 base64（可能截断 UTF-8 字符）。cursor 为已返回的最大 run_id，没有任何记录时为 null。调用方应回退游标并按 run_id 去重 |
| `task log <id> <run-id>` | 无 / 原始日志字节 | 只读 |
| `task remove <id>\|--all` | 无 / `{success}` | 移除定时器、unit/plist/crontab 行与任务目录；最后一个任务删除后同时删除 runner |
| `task exec <id>` | 无 / 无 | 仅供定时器调用，见下 |

运行记录 `runs/<run_id>.json` 为单行 JSON：`{run_id,task_id,started_at,ended_at,exit_code,status,log_bytes,truncated}`。
run_id 为结束时刻 `YYYYMMDDTHHMMSSZ-<pid>`（UTC），时间字段为 ISO-8601 UTC。status：ok、failed、
timeout、skipped_overlap（exit_code 为 null、log_bytes 为 0、无日志）。log_bytes 是截断前的输出字节数。

`task exec`：以 `mkdir` 锁防重叠（持锁 pid 已不存在，或无 pid 且锁超过 1 天，视为陈旧锁）；env 非空时在
`$SHELL -lic`（同 which 的 shell 选择，15 秒上限）中读取这些变量的当前值并导出，只导出已设置且在清单内的变量，
值不写入任何任务文件；读到时另外导出 `MIFFAN_LOGIN_ENV=ok`，登录 shell 无输出时保持定时器原环境。然后在 `$HOME`
中以 `/bin/sh run` 运行，stdin 为非空的 prompt 或 /dev/null，stdout+stderr 写日志；超时后对进程树先 TERM、
10 秒后 KILL；日志超过 1 MiB 时只保留末尾 1 MiB；每个任务保留最近 30 条记录。

定时器：systemd 为 `~/.config/systemd/user/miffan-task-<id>.{service,timer}`（oneshot，
`TimeoutStartSec=infinity`，timer `AccuracySec=1s`；每天/每周 `OnCalendar` + `Persistent=true`，间隔 `OnActiveSec` +
`OnUnitActiveSec`）；launchd 为 `~/Library/LaunchAgents/me.ayuilos.miffan.task.<id>.plist`
（`StartCalendarInterval`，每周为数组；间隔为 `StartInterval`），装入 `gui/<uid>`；cron 为带
`# miffan-task:<id>` 尾注的一行，修改时整体重写 crontab 并保留其他行。三者都执行
`/bin/sh ~/.miffan/bin/miffan-task-runner task exec <id>`：旧版 App 之后重装旧 helper 时，已有任务不受影响。

Windows helper 不实现 task 命令。

## 伙伴专属电脑（POSIX `space`，helper 24 起）

封装 `cua` CLI，管理伙伴自己的 cua Space（本机上的 Linux 容器，一律 `--runtime runc`：gVisor 重启会丢全部文件）。
只在 macOS 与 Linux 主机上实现，Windows helper 没有 `space` 命令。所有 `space` 命令以 umask 077 运行，只依赖
POSIX sh 与系统自带工具（含 curl、awk），不需要 python3。

输出：stdout 最后一行是一行 JSON；诊断写 stderr；Space 的 token 只出现在 `info` 的 stdout，从不写入 stderr、
文件或任何进程参数。失败为 `{"error":"<code>","detail":<string|null>}` 并退出 1；Space 名不合法时
`{"error":"bad_name","detail":null}` 并退出 2。名字必须是 `miffan-[0-9a-f]{8}`。
错误码：`cua_missing`、`daemon_failed`、`needs_docker`（仅 Linux：无 docker 或无权限）、`download_failed`、
`disk_low`、`create_failed`、`not_found`、`start_failed`、`exec_failed`、`bad_name`、`bad_input`。

`cua` 在 `~/.local/bin/cua`（非交互 PATH 里没有），helper 按 `PATH`、`~/.local/bin`、`/opt/homebrew/bin`、
`/usr/local/bin` 查找。除 `probe` 外的命令都会先确认 cua 守护进程在运行，没有则带着电脑的代理变量
（`cua_proxy`：macOS 系统代理或 systemd 用户环境）启动，并设 `NO_PROXY=127.0.0.1,localhost`、`CUA_TELEMETRY=0`。

| 调用 | stdin / stdout | 说明 |
|---|---|---|
| `space probe` | 无 / `{cua:{path,version},daemon:{running},docker,disk_free_gb,spaces:[{id,state}]}` | 只读，不启动任何东西。docker 在 Linux 为 `{ok,detail}`，macOS 为 null（用 cua 自带运行时）。守护进程没运行时 `spaces` 为空（列出会拉起运行时）。只列 `miffan-*` 的 Space |
| `space install` | 无 / `{cua:{path,version},daemon:{running:true}}` | cua 已装则不下载；否则经代理下载 `https://cua.ai/install.sh` 并以 `--cli-only --yes --no-modify-path --no-onboarding` 运行（安装器输出走 stderr），失败为 `download_failed`。然后确保守护进程在运行（`daemon_failed`） |
| `space create <名>` | 无 / `{id:"local:<名>",state:"running"}` | Linux 先检查 docker（`needs_docker`）与磁盘（<2 GB 为 `disk_low`）。`cua spaces create linux --on local --kind container --runtime runc --name <名> --json`，再等 `sb info` 为 running 且发布了回环端口（默认 90 秒，超时 `create_failed`）。cua 失败时 JSON 的 `error` 或退出码非 0 都算失败，按文本分类：磁盘/空间 → `disk_low`；pull/download/proxy/timeout/dial tcp → `download_failed`；docker → `needs_docker`；其余 `create_failed`，detail 为 cua 的原文 |
| `space info <名>` | 无 / `{id,state:"running",port,token,driver_version}` | Space 未运行先 `cua spaces start`（`start_failed`）。回环端口每次启动都变，每次现查，不缓存。token 来自 `~/.cua/spaces-credentials.json`。`driver_version` 是 Space 里 cua-driver 的版本，经 `http://127.0.0.1:<port>/mcp` 的 initialize 读取（token 通过 curl 配置走 stdin），读不到为 null |
| `space start\|stop\|delete <名>` | 无 / `{id,state}`；delete 为 `{id,deleted:true}` | 未知的名为 `not_found`。stop 只暂停容器（内存不释放）；delete 用 `--force` |
| `space reset <名>` | 无 / 同 create | 先 delete 再用同名 create；Space 不存在为 `not_found`。交接文件夹在宿主上，不受影响 |
| `space netcheck <名>` | 无 / `{dns,https,detail,hint}` | 在 Space 里对 `www.cloudflare.com`（全球）与 `www.baidu.com`（国内）各做 DNS（5 秒）与 HTTPS（6 秒）。任一主机成功即为 true；`detail` 列出失败项（如 `https:www.baidu.com`），全部成功为 null。检查在 Space 里没能跑完时（helper 25 起）也按离线输出（两者为 false），不再报 `exec_failed`。Linux 上检查未通过且 `systemctl is-active ufw` 为 active 时 `hint:"ufw"`，其余为 null；helper 从不修改防火墙 |
| `space handoff out\|in <名>` | `{"folder":"<宿主绝对路径>","path":"<相对路径，仅 out>"}` / `{copied:[…]}` | Space 里的交接文件夹为 `/home/cua/交接`，两侧缺失时创建（宿主 `<folder>` 为 700）。冲突不覆盖：在扩展名前加 ` (2)`、` (3)`…（`.tar.*` 算一个扩展名，点开头的文件无扩展名）。`copied` 是写到宿主（out）或 Space 交接文件夹（in）里的相对名 |

handoff 细节：stdin 是单个 JSON 对象，字符串里只接受 `\"`、`\\`、`\/` 三种转义，非 ASCII 字符须原样 UTF-8（App 的
JSON 编码默认如此），其他转义（含 `\n`、`\u`）为 `bad_input`。`folder` 必须是不含 `..`、`.`、空段的绝对路径，
`path` 必须是不含 `..`、`.`、空段、不以 `/` 开头的相对路径，否则 `bad_input`。

- `out`：`path` 指向文件或目录；目录递归拷贝其中的普通文件，保持相对路径，宿主已有的子目录合并，冲突按文件重命名。符号链接或不存在为 `not_found`。
- `in`：拷贝 `<folder>` 里（递归）比上一次 `in` 更新的普通文件；跳过符号链接与 `.DS_Store`。上一次的时间戳是 `~/.miffan/space/<名>.handoff` 的 mtime（本次开始时刻写入，成功后才替换；中途失败下次会重来，已拷贝的文件会以 ` (2)` 再出现一份）。

## Windows 子命令（Windows PowerShell 5.1）

以当前 SSH 用户运行；交互桌面要求 SSH 用户 SID 与 console 用户一致，session 非 0，
并且 lock_state 确认 unlocked。普通 cua 任务只接受 Limited/Interactive，不自动提升权限。
服务/防火墙操作还需当前用户有相应管理权限；脚本不自动提权。

| 调用 | stdin / stdout | 退出码与副作用/前置条件 | App 调用 → 解析 |
|---|---|---|---|
| `version` | 无 / JSON 整数版本加换行（数字文本） | 0；无副作用 | `RemoteMiffanHelper.ensureInstalled` → trim 比版本 |
| `probe` | 无 / 机器 probe JSON | 通用 0/1；WTS/CIM、cua 版本/任务/完整性只读探测，可能编译内联类型 | `open`、`probeHost`、`probe` → `parseHelperProbe` 或相同 decoder |
| `probe display` | 无 / `{success,detail,session_id,display_count,displays:[{name,primary,x,y,width,height,dpi_x,dpi_y,scale_percent}]}` | 通用 0/1；以 ACL 私有目录和短期 Limited 交互任务探测显示器；DPI 不可确认时 null；清理失败导出 task/directory 诊断 | 当前无 Kotlin 调用/解析 |
| `sunshine [probe]` | 无 / Sunshine probe JSON | 通用 0/1；CIM 查询 SunshineService、sunshine.exe、配置、证书、UDP/TCP、会话；不改变安装 | `sunshineProbe` → `parseSunshineProbe` |
| `sunshine start` | 无 / `{success,detail,changed,backup:null}` | 通用 0/1；只接受已确认服务模型；已运行不变，否则 Start-Service SunshineService | `startSunshine` → `sunshineCommand` → `parseSunshineCommand` |
| `sunshine enforce-encryption` | 无 / `{success,detail,changed,backup}` | 通用 0/1；配置、父路径必须非 reparse；无活跃且可确认 stream；已有 2/2 不写，否则私有 backup、保留 BOM/换行/其他配置、同目录原子替换、重启服务；流状态变化时可已保存但不重启 | `enforceStreamEncryption` → `sunshineCommand` → `parseSunshineCommand` |
| `sunshine pair` | 一行 `{name,cert}` JSON / `{added,replaced,restarted,client_sha256}` | 通用 0/1；仅 stdin；严格公共 X.509/名称校验，已确认安装/服务模型；停服务重读后补建缺失状态/root/uniqueid，保留顶层凭据及已有身份；PS 5.1 depth 100；已有文件保留 ACL 并 File.Replace，新文件继承目录 ACL 并 File.Move，恢复原运行状态 | `pairStreamDirectly` → `RemoteSunshineUnpair.pair` → 严格 PairResult decoder |
| `sunshine unpair <client-cert-sha256>` | 无 / `{removed,restarted}` | 通用 0/1；64 hex、已知安装/服务模型、正规状态路径；按证书 DER pin 删本机配对，必要时停服务并等进程退出，重读、保留未知 JSON/ACL 原子替换，finally 重启先前运行的服务 | `RemoteSunshineUnpair.unpair` → 严格 Result decoder |
| `sunshine firewall check` | 无 / firewall check JSON（见下） | 通用 0/1；只读检查 ActiveStore 及关联 filters、Profiles、网络来源，给出建议而不授予 apply 权限；需可读取防火墙策略 | `checkSunshineFirewall`/连接预检 → `firewallCheck` → `parseSunshineFirewallCheck` |
| `sunshine firewall apply` | 唯一 JSON 对象 `{disable_rules,allowed_sources,add_rule}` / firewall result JSON | 通用 0/1；只执行调用者确认的精确计划；检查当前 ActiveStore、写前备份 PersistentStore、再检查快照，变更仅限本地 Sunshine 入站规则及固定 UDP rule。空计划拒绝，不自动部分修复策略规则 | `applySunshineFirewall` → `parseSunshineFirewallResult` |
| `clip` | 全部原始 UTF-8 文本 / `{success,verified,detail,session_id[,transport]}` | 通用 0/1；拒绝 NUL。优先 authenticated pipe → cua clipboard_write 并读回；无可用 bridge 时一次性桌面任务 fallback，写后不重放。transport=cua-bridge 只在 bridge 路径出现 | `writeStreamClipboard` → `parseWindowsClipboard`，要求 success 与 verified |
| `clip serve` | 每行 base64 UTF-8 文本 / 每条一行 `{"success":bool,"verified":bool}` | EOF 结束；建立一条持续 MCP clipboard session，逐次写并读回。启动异常输出额外 `transport:cua-bridge-session` 的 false/false 后退出 0；正常会话完成也退出 0 | `open` 打开进程 → `WindowsClipboardChannel.write`：读取一行 JSON，`success` 与 `verified` 都为 true 才算成功，否则关闭并 fallback |
| `cua status` | 无 / 与机器 probe 的 cua 对象相同 | 通用 0/1；driver `--version` 上限 5 秒、daemon session/integrity、任务权限与 pipe 可用性探测 | 当前无直接 Kotlin 调用；嵌套 probe 消费 |
| `cua install` | 无 / `{success,detail,version,previous,autostart_run_level[,failed_version,rolled_back]}` | 通用 0/1；官方安装器下载，隔离 per-user 安装官方 stable（不低于 0.34.0），拒绝已有 elevated/其他用户任务；注册/启动当前用户 Limited/Interactive `cua daemon` 任务后，确认 console 中普通权限 daemon 为新版本，桌面解锁时再经 bridge 做一次 MCP initialize + tools/list。安装前须在 20 秒内经系统代理下到 GitHub release 的 1 MiB，否则抛出 `Cua download failed; nothing was changed` 且不运行安装器；检查失败且原版本可用时重装原版本：success=false、rolled_back 表示原版本恢复可用 | `installCuaDriver`/`installCuaDriverOnHost` → 退出码、stdout 末行 JSON 及文本摘要 |
| `cua check-update` | 无 / `{current_version,latest_version,update_available,release_notes_url,error}` | 通用 0/1；cua-driver `check-update --json --no-cache`，上限 30 秒；未安装 `{error:"not_installed"}`，失败 `{error:"check_failed"}`；只读 | `checkCuaUpdate` → `RemoteCuaUpdateCheck` |
| `cua warm` | 无 / `{success,ready,detail}` | 通用 0/1；driver 不可用时 success=true/ready=false；否则确认 console 权限后可创建/启动版本化 bridge task 与私有目录 | `prepareWindowsTyping` → 忽略返回（best effort） |
| `cua` | MCP stdio / MCP stdio | 成功流结束 0；bridge 失败固定 stderr + 1 | `openCuaProcess` → MCP client/transport；Ensure-CuaBridge 可创建 Limited 任务，命名管道与一次性认证票据，启动 MCP child |
| `cua bridge` | 无 stdin/正常 stdout；认证本地 pipe 内原始字节转发 | 成功 0；失败 1 | 当前无直接 Kotlin 调用；由内部 console bridge task 调用，需当前 console session/unlocked，120 秒 idle 后退出 |
| `cua daemon` | 无 / cua-driver serve 的输出 | driver 子进程退出码；外层失败 1 | 当前无直接 Kotlin 调用；由安装的 Limited 非 session-0 autostart task 调用 |

Windows 不实现 env/vnc/rdp/mac-shortcut、Sunshine wake/open-settings，调用这些命令走通用错误。
`openSunshinePermissionSettings` 在 Windows 返回本地不可用结果，不发送命令。

### Windows firewall JSON

check 字段：`success`（检查成功）、`detail`（说明）、`firewall_model_ok`（profiles 安全、
无不安全有效规则且有对应 UDP 规则）、`udp_rule_present`、`rules`、`profiles`、
`unsafe_rules`（id/reasons）、`unmanageable_rules`（无法本地修复的 id 数组）、
`suggested_changes`（旧内部计划，Kotlin 忽略）、`allowed_sources`（当前 LAN/Tailscale
网段）、`suggested_plan`（可完整修复的计划或 null）、`plan_detail`（不可计划原因或 null）。

rule 字段：id/program/direction/enabled/effective/action/protocol/ports/sources/policy_source/
profile；snapshot 还可附 status/enforcement/primary_status 等策略诊断。profile 字段来自
Get-FirewallSnapshot 的 name/enabled/default_inbound，作为自由 JSON
对象保留；App 不对它们做强类型解析。风险原因含 tcp_or_other_protocol、
unrestricted_or_unverifiable_source、udp_ports_outside_47998_48000、
sources_differ_from_proposed_networks。

stdin plan 必须恰好有三个字段：`disable_rules` 字符串数组、`allowed_sources` 字符串数组、
`add_rule`（null 或恰好 id/program/protocol/ports/sources/replace）。允许的 add_rule 固定
id=Miffan-Sunshine-UDP-LAN、protocol=UDP、ports=["47998-48000"]、sources=确认的网段、
replace=是否替换当前固定规则，program 必须匹配已发现的 sunshine.exe。仅 local inbound
Sunshine 规则可 disable；不改 SSH/RDP/TCP 或 GPO。

result 在 check 的基础上返回 `success`、`detail`、`changed`、`backup`、`applied_changes`
（实际计划，额外含 changed；Kotlin 忽略该嵌套附加字段）。成功表示变更后再验证通过；
失败时可能已经写入，backup 用于人工核查，脚本没有自动 rollback 协议。
`parseSunshineFirewallResult` 只消费 success/detail/changed/backup/firewall_model_ok/
applied_changes，其他保留诊断不进入强类型模型。

## 版本与安装

当前 POSIX helper 为 24，Windows 为 13。POSIX 21 新增 `task` 命令，24 新增 `space` 命令；Windows 版本和摘要保持不变。

`RemoteMiffanHelper.ensureInstalled` 每次读取打包脚本原始 bytes，用
`MIFFAN_HELPER_VERSION=(\d+)` 或 `HelperVersion = (\d+)` 抽版本。远端 version 查询
10 秒内正常退出、未 timeout 且 stdout.trim 等于内置数字，就跳过上传；不比较远端摘要。
其他任何情况均上传 bundled bytes（30 秒）。`versions.lock` 在构建测试中补足内容与版本
的关联，远端安装协议不改变。

POSIX 安装命令为 `mkdir -p "$HOME/.miffan/bin" && cat >
"$HOME/.miffan/bin/miffan.tmp" && chmod 755 ... && mv -f .../miffan.tmp .../miffan`，
脚本 bytes 走 stdin。Windows 的 `windowsHelperInstallCommand` 创建
`%LOCALAPPDATA%\Miffan\bin`、检查所有父目录/目标非 reparse，以随机临时文件接收
二进制 stdin 并 Flush(true)，现有文件用 File.Replace（保留 ACL），新文件用 Move；
保留脚本 UTF-8 BOM。安装命令只检查退出码/timeout，不解析 JSON。

`windows-setup.ps1` 是用户手动以管理员运行的独立 onboarding，不是已安装 helper。
`WindowsSetupScript.fromAssets` 读取模板，`render` 要求唯一的单引号
`'__MIFFAN_PUBLIC_KEY__'`，只接受单行 ssh-ed25519/ssh-rsa/ecdsa-sha2-nistp256/384/521
公钥形状，插入算法与 base64 key，丢弃注释；不把注释插入代码，不进行密钥解码验证。
脚本安装/启用 OpenSSH Server，检查受支持的 sshd 配置，选择管理员或用户 authorized_keys、
去重公钥并设置受限 ACL，配置仅私网/Tailscale 的 Miffan-SSH TCP 入站规则，展示主机名、
用户名、地址、端口和各 host key SHA256 指纹。不会安装 miffan helper；helper 在后续 SSH
会话按 ensureInstalled 安装。
setup stdout 是 Write-Host 人类可读提示，App 不解析；管理员检查失败/安装失败/其他异常
以 return/catch 输出说明，**退出码未定义**，已完成步骤保留。

## 明确未定义与兼容边界

- Shell 系统命令失败时 probe/env/vnc status 的统一退出码、stderr 格式和所有额外参数处理未定义。
- POSIX clip 底层工具的完整 stdout/stderr 格式与具体失败码未定义，App 只依赖成功退出。
- setup 的机器可解析输出和成功/失败退出码未定义；只供用户阅读。
- Sunshine detail、Windows error_type 不是稳定枚举；未知字段扩展在多数 Kotlin parser 中可忽略，
  但 pair/unpair 的严格 decoder 不接受未知字段。
- MCP 内容遵循所安装 cua-driver 的协议；本 helper 只桥接，完整 tool schema 不由本目录定义。
- Windows `clip serve` 启动失败也以 0 退出；App 不看该退出码，只看每行回复的
  `success` 与 `verified`（两者都为 true 才算写入成功）。

共享正常/失败分支样例在 `fixtures/sh` 与 `fixtures/ps1`；环境归一化与测试方式见 README。
