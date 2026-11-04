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
macOS 与 Linux 主机见本节；Windows 主机（helper 14 起）见下方“伙伴专属电脑（Windows `space`）”。所有 `space` 命令以 umask 077 运行，只依赖
POSIX sh 与系统自带工具（含 curl、awk），不需要 python3。

输出：stdout 最后一行是一行 JSON；诊断写 stderr；Space 的 token 只出现在 `info` 的 stdout，从不写入 stderr、
文件或任何进程参数。失败为 `{"error":"<code>","detail":<string|null>}` 并退出 1；Space 名不合法时
`{"error":"bad_name","detail":null}` 并退出 2。名字必须是 `miffan-[0-9a-f]{8}`。
错误码：`cua_missing`、`daemon_failed`、`needs_docker`（仅 Linux：无 docker 或无权限）、`download_failed`、
`disk_low`、`create_failed`、`not_found`、`start_failed`、`exec_failed`、`unsupported`、`bad_name`、`bad_input`。

`cua` 在 `~/.local/bin/cua`（非交互 PATH 里没有），helper 按 `PATH`、`~/.local/bin`、`/opt/homebrew/bin`、
`/usr/local/bin` 查找。除 `probe` 外的命令都会先确认 cua 守护进程在运行，没有则带着电脑的代理变量
（`cua_proxy`：macOS 系统代理或 systemd 用户环境）启动，并设 `NO_PROXY=127.0.0.1,localhost`、`CUA_TELEMETRY=0`。

| 调用 | stdin / stdout | 说明 |
|---|---|---|
| `space probe` | 无 / `{cua:{path,version},daemon:{running},docker,disk_free_gb,host:{cpus,memory_mb,runtime},spaces:[{id,state}]}` | 只读，不启动任何东西。`host` 是本机 CPU 数与内存（MiB，读不到为 null）；`runtime` 在 macOS 为 cua 的 Linux 虚拟机 `{cpus,memory_mb}`（所有 Space 共用，读自 `~/.cua/runtimes/linux/vm/cua/colima.yaml`），Linux 为 null（helper 26 起）。docker 在 Linux 为 `{ok,detail}`，macOS 为 null（用 cua 自带运行时）。守护进程没运行时 `spaces` 为空（列出会拉起运行时）。只列 `miffan-*` 的 Space |
| `space install` | 无 / `{cua:{path,version},daemon:{running:true}}` | cua 已装则不下载；否则经代理下载 `https://cua.ai/install.sh` 并以 `--cli-only --yes --no-modify-path --no-onboarding` 运行（安装器输出走 stderr），失败为 `download_failed`。然后确保守护进程在运行（`daemon_failed`） |
| `space create <名> <cpus> <内存 MiB>` | 无 / `{id:"local:<名>",state:"running"}` | cpus 1–64、内存 512–262144，否则 `bad_input`（helper 26 起必填）。Linux 先检查 docker（`needs_docker`）与磁盘（<2 GB 为 `disk_low`）。`cua spaces create linux --on local --kind container --runtime runc --name <名> --cpus <cpus> --memory-mb <内存> --json`，再等 `sb info` 为 running 且发布了回环端口（默认 90 秒，超时 `create_failed`）。cua 失败时 JSON 的 `error` 或退出码非 0 都算失败，按文本分类：磁盘/空间 → `disk_low`；pull/download/proxy/timeout/dial tcp → `download_failed`；docker → `needs_docker`；其余 `create_failed`，detail 为 cua 的原文 |
| `space info <名>` | 无 / `{id,state:"running",port,token,driver_version,proxy}` | Space 未运行先 `cua spaces start`（`start_failed`）。回环端口每次启动都变，每次现查，不缓存。token 来自 `~/.cua/spaces-credentials.json`。`driver_version` 是 Space 里 cua-driver 的版本，经 `http://127.0.0.1:<port>/mcp` 的 initialize 读取（token 通过 curl 配置走 stdin），读不到为 null。`proxy`（helper 27 起）：这个 Space 经主机网络共享上网时为 `http://<网关>:<端口>`（顺带确保共享在运行），否则为 null；App 把它作为代理环境变量交给自己在 Space 里启动的进程 |
| `space start\|stop\|delete <名>` | 无 / `{id,state}`；delete 为 `{id,deleted:true}` | 未知的名为 `not_found`。stop 只暂停容器（内存不释放）；delete 用 `--force` |
| `space reset <名> <cpus> <内存 MiB>` | 无 / 同 create | 先 delete 再用同名、给定配置 create；Space 不存在为 `not_found`。交接文件夹在宿主上，不受影响 |
| `space resize <名> <cpus> <内存 MiB>` | 无 / `{id,cpus,memory_mb}` | 原地改配置，文件保留（helper 26 起）。只支持 Linux：`docker update --cpus … --memory …m --memory-swap …m <名>`，失败为 `exec_failed`；macOS 的 Space 在 cua 自己的虚拟机里，返回 `unsupported` |
| `space netcheck <名>` | 无 / `{dns,https,detail,hint,via,…}` | 在 Space 里同时请求 `www.baidu.com` 与 `www.cloudflare.com` 的 HTTPS（连接 3 秒、总计 5 秒），第一个成功即返回（helper 28 起；之前按顺序各等超时）；DNS 只为失败时的说明另查（3 秒）。`detail` 列出失败项（如 `https:www.baidu.com`），全部成功为 null。检查在 Space 里没能跑完时也按离线输出。`via:"direct"` 表示 Space 自己的网络；此时 Linux 上未通过且 ufw 为 active 时 `hint:"ufw"`。**网络共享（helper 27 起，仅 Linux）**：直连 HTTPS 不通、有 `python3` 且 `docker inspect` 读得到 Space 的网桥时，改走主机网络共享，此后该 Space 的检查都走共享（标记在 `~/.miffan/netshare/spaces/<名>`）。共享是 helper 写出的 `~/.miffan/netshare/relay.py`（Python 标准库 HTTP 代理，`setsid nohup` 常驻，pid 在 `relay-<网关>.pid`），只监听网桥网关 `:32190`，只接受网桥子网的客户端，拒绝回环、链路本地、组播、主机自身地址和网桥上其他容器（按解析出的 IP 判断）；它发出的连接是主机自己的连接，所以主机上的 VPN、透明代理（如 v2rayA）照常生效。Space 里缺少设置时（新建或重置后；helper 28 起不再每次都写）以 `sudo -n` 写入 `/etc/environment` 的代理变量、`/etc/apt/apt.conf.d/95miffan-proxy`、`/etc/sudoers.d/miffan-proxy`（env_keep），以及浏览器的代理策略 `/etc/firefox/policies/policies.json`（同时跳过首次运行页与条款确认）和 `/etc/chromium/policies/managed/miffan-proxy.json`（helper 29 起；cua-driver 启动的桌面应用读不到 `/etc/environment`）。输出 `{dns,https,detail,hint,via:"share",proxy,bridge,port}`，`dns` 与 `https` 同为经共享的 HTTPS 结果；Space 连不到共享时 `hint` 为 `ufw`（ufw active，App 给出 `sudo ufw allow in on <bridge> to <网关> port <port> proto tcp`）或 `firewall`；共享起不来或 Space 设置写不进时 `hint:"share_failed"`（只有 `dns,https,detail,hint,via`）。`space delete` 删掉最后一个用共享的 Space 时停止共享；`space reset` 重建后重新写入设置。**Mac 的系统代理（helper 32 起）**：Mac 上 Space 跑在 cua 的 Linux 虚拟机里，经 `192.168.5.2` 访问 Mac 的回环地址。`scutil --proxy` 里开着 HTTPS（否则 HTTP）代理时，netcheck 先经它检查（地址是回环的换成 `192.168.5.2`，只接受主机名加端口），代理有应答就按上面同样的方式写入 Space 的设置并记标记，输出 `{dns,https,detail,hint:null,via:"share",proxy}`；设置写不进时 `hint:"share_failed"`。Mac 不再设代理或 Space 连不到它时，去掉 Space 里的代理设置（保留 Firefox 的首次运行与条款设置）和标记，回到直连检查。`space info` 的 `proxy` 读标记。helper 从不修改防火墙 |
| `space handoff out\|in <名>` | `{"folder":"<宿主绝对路径>","path":"<相对路径，仅 out>"}` / `{copied:[…]}` | Space 里的交接文件夹为桌面上的 `/home/cua/Desktop/Shared`（helper 26 起；名字固定为英文），两侧缺失时创建（宿主 `<folder>` 为 700）。冲突不覆盖：在扩展名前加 ` (2)`、` (3)`…（`.tar.*` 算一个扩展名，点开头的文件无扩展名）。`copied` 是写到宿主（out）或 Space 交接文件夹（in）里的相对名 |

handoff 细节：stdin 是单个 JSON 对象，字符串里只接受 `\"`、`\\`、`\/` 三种转义，非 ASCII 字符须原样 UTF-8（App 的
JSON 编码默认如此），其他转义（含 `\n`、`\u`）为 `bad_input`。`folder` 必须是不含 `..`、`.`、空段的路径：绝对路径，或（helper 30 起）相对 SSH 账号家目录的路径（App 存的就是这种），
`path` 必须是不含 `..`、`.`、空段、不以 `/` 开头的相对路径，否则 `bad_input`。

- `out`：`path` 指向文件或目录；目录递归拷贝其中的普通文件，保持相对路径，宿主已有的子目录合并，冲突按文件重命名。符号链接或不存在为 `not_found`。
- `in`：拷贝 `<folder>` 里（递归）比上一次 `in` 更新的普通文件；跳过符号链接与 `.DS_Store`。App 在伙伴每轮开始时调用（同一 Space 至多每 20 秒一次）；没有新文件时不启动、不进入 Space，直接返回 `{"copied":[]}`（helper 30 起）。`out` 写到宿主的文件记在 `~/.miffan/space/<名>.handoff-out`（相对路径、大小与修改时间）；`in` 跳过其中未被改动的文件，否则伙伴交出的文件会在下一轮以 ` (2)` 回到 Space（helper 31 起）。宿主上改过的文件照常拷入。记录只保留之后的 `in` 仍可能列出的条目。上一次的时间戳是 `~/.miffan/space/<名>.handoff` 的 mtime（本次开始时刻写入，成功后才替换；中途失败下次会重来，已拷贝的文件会以 ` (2)` 再出现一份）。

## 伙伴专属电脑（Windows `space`，helper 14 起）

`miffan.ps1 space …` 与 POSIX 版同名同参（`space create <名> <cpus> <内存 MiB>`、`space handoff out|in <名>` 等），
输出、错误码、退出码（不合法的名为 2）和 handoff 的 stdin 也相同；源码在 `src/windows/Space.ps1`（构建时 include）。
Space 由 cua 跑在 Docker Desktop（WSL 2 后端）上，一律 `--runtime runc`。差别：

- 进程：Windows OpenSSH 会在会话结束时结束它启动的所有进程，所以 Docker Desktop 与 cua 守护进程都以计划任务
  （`Miffan-docker-desktop`、`Miffan-cua-spaces`，当前用户、Interactive、Limited、无触发器、不限时）在已登录的桌面
  会话里启动，需要时由 helper 拉起。守护进程任务运行 `miffan.ps1 space daemon`，它无窗口地运行
  `cua.exe daemon start --foreground`。控制台没有登录这个 SSH 账户时为 `needs_login`。
- `probe`：`docker` 为 `{ok,detail,running}`：装了 Docker Desktop 即 `ok:true`（用时再启动），`running` 表示此刻
  `docker info` 是否应答，`detail` 是不应答的原因；没装为 `{ok:false,detail:"docker not installed",running:false}`。
  `host.runtime` 在 Docker 运行时为 `docker info` 的 `{cpus,memory_mb}`（所有 Space 共用的 WSL 2 虚拟机）。
  `disk_free_gb` 是 `%LOCALAPPDATA%` 所在盘（Docker Desktop 的数据在那里）。
- `install`：Docker Desktop 没装时，由后台任务 `Miffan-docker-install`（当前用户、S4U、Highest，与 SSH 会话无关）
  安装：先装 cua（没装时），再从 `desktop.docker.com` 断点续传下载安装包（约 600 MB，失败最多重试 8 次），校验
  Authenticode 签名为 Docker Inc，以 `install --quiet --accept-license --backend=wsl-2 --always-run-service` 运行，之后
  删掉安装包。代理取 SSH 会话里读到的系统代理，作为参数交给任务。进度写在
  `%LOCALAPPDATA%\Miffan\docker-install\status.json`：`{stage,bytes,total,error}`，stage 为 `cua`、`download`、
  `install`、`done`、`restart`（安装器退出码 3010，刚开启 WSL，要重启）或 `failed`；任务已不在运行而 stage 仍在
  进行中时按 `failed`（`error:"interrupted"`）报告。此时 `install` 立即返回 `{cua,daemon:{running:false},docker_install}`，
  任务没在运行就（重新）启动它；SSH 会话不是管理员为 `needs_admin`，stage 为 `restart` 时为 `needs_restart`。`probe`
  在 Docker Desktop 没装时也带 `docker_install`（没有为 null）。App 每 3 秒 `probe` 一次直到不再进行中。
  Docker Desktop 已装时：cua 没装就在前台下载 `https://cua.ai/install.ps1` 并以 `-CliOnly -Yes -NoModifyPath -NoOnboarding`
  运行（不登录、不改 PATH，装到 `%LOCALAPPDATA%\Programs\cua\bin\cua.exe`），清掉后台任务与其目录，然后启动
  Docker Desktop（首次启动最多等 240 秒，`needs_docker`）和守护进程，返回 `{cua,daemon:{running:true}}`。
- 其余命令先确保 Docker Desktop 与守护进程在运行。`resize` 支持（`docker update`）。Space 里的脚本以 base64
  传给 `cua sb exec <名> "echo … | base64 -d | sh"`，命令行里没有需要转义的内容。
- `netcheck`：Windows 系统代理（`HKCU\…\Internet Settings` 的 `ProxyEnable`/`ProxyServer`，`https=` 优先，否则
  `http=` 或单一地址）开着时，回环地址换成 `host.docker.internal`（Docker Desktop 把它交给电脑的回环），之后与
  Mac 的系统代理相同：代理有应答就写入 Space 的设置并记标记（`%USERPROFILE%\.miffan\netshare\spaces\<名>`），
  输出 `via:"share"`；否则去掉设置与标记，直连检查，`hint` 总为 null（没有 ufw 修法）。
- `handoff`：`folder` 是相对 `%USERPROFILE%`、以 `/` 分段的路径（或 `X:\…` 绝对路径）。从 Space 拿出的文件名里
  Windows 不接受的字符换成 `_`。`in` 跳过 `.DS_Store`、`desktop.ini`、`Thumbs.db` 与重解析点。记录与状态文件在
  `%USERPROFILE%\.miffan\space\`，大小与修改时间（Unix 秒）同 POSIX 版。
- 失败时 `detail` 是 cua 或 Docker 的原文；不是 helper 自己的失败只给出行号与异常类型，不输出异常文本。

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

## 中转 connector（`relay`，POSIX helper 33 起、Windows helper 15 起）

安装、配对和移除电脑端的 `miffan-connector`（[relay/PROTOCOL.md](../relay/PROTOCOL.md) 的 connector 一侧，
实现在 `relay/connector/`）。两个平台命令、stdin、输出相同；每条命令 stdout 一行 JSON，失败为
`{"success":false,"reason":"<code>","detail":"<text>"}` 并退出 1（用法错误 POSIX 退出 2）。用户数据只走
stdin，**原样**交给 connector（由它解析并校验），不进入命令行；不需要 root / 管理员。

| 调用 | stdin | stdout（成功） | 行为 |
|---|---|---|---|
| `relay status` | 无 | 未安装：`{"installed":false}`。否则 connector `status` 的 JSON 加 `installed:true,service:"running\|stopped\|missing",pinnedVersion,linger` | `linger` 仅 Linux 为布尔（systemd 用户服务能否在未登录时运行），其他平台 null。connector 无法回答（例如尚未 init）时没有 connector 字段，改带 `error:<reason>` |
| `relay install` | `{"relay":"https://…","accessToken":"…"?}` | `relay status` 的结果 | 下载本平台二进制 → 校验 SHA-256 → 安装 → `init` → 安装并启动常驻服务；已安装的版本等于内置版本时跳过下载 |
| `relay upgrade` | 无 | `relay status` 的结果 | 只把已安装的 connector 换成内置版本：下载 → 校验 SHA-256 → 替换 → 让服务管理器重启服务（macOS `launchctl kickstart -k`，Linux `systemctl --user restart`，Windows 计划任务）；中转、电脑身份和已配对设备不变。版本已等于内置版本时什么都不做；未安装时 `not_installed` |
| `relay pair` | `{"device":"<b64u SPKI>","name":"…"}` | connector `pair` 的结果：`{success,hostId,relay,deviceId}` | 要求已安装；失败原样返回 connector 的失败 JSON |
| `relay unpair` | `{"deviceId":"<26 位 a-z2-7>"}` | connector `unpair` 的结果：`{success,removed,devices:[{id,name,addedAt}]}` | id 在 helper 里先按正则校验，不合法时 `bad_input`，不会调用 connector |
| `relay uninstall` | 可选 `{"forgetIdentity":true}` | `{"success":true,"identityKept":<bool>}` | 停止并移除服务、二进制、日志、`state.json` 与锁文件。默认**保留** `host.key` 和 `config.json`（同一个 hostId、已配对手机不受影响）；`forgetIdentity` 时删除整个数据目录 |

失败原因码：`unsupported_platform`、`bad_input`、`download_failed`、`checksum_mismatch`、`install_failed`、
`login_required`（macOS 没有登录会话，launchd 无法装入）、`no_service_manager`（Linux 无可用的 systemd 用户服务）、
`service_failed`、`not_installed`、`init_failed`、`connector_failed`，以及 connector 自己的原因码（`bad_relay`、
`bad_target`、`bad_device`、`bad_name`、`already_running` 等，见 `relay/connector/README.md`）。

下载与校验：helper 内置固定的 connector 版本与每个平台产物的 SHA-256。顺序尝试 GitHub Release
（`https://github.com/Ayuilos/Miffan/releases/download/relay-connector-v<版本>/miffan-connector-<os>-<arch>[.exe]`）
与预留的镜像地址（目前为空）。校验失败删除下载文件、`checksum_mismatch`、不安装。发版前这些摘要是占位值，
不可能匹配，所以未发布的版本装不上。`MIFFAN_CONNECTOR_FILE=<本地路径>` 跳过下载（仍校验），
`MIFFAN_CONNECTOR_SHA256` 覆盖期望摘要，仅用于测试与开发。

文件：macOS/Linux 二进制 `~/.miffan/bin/miffan-connector`，数据 `~/.miffan/connector/`；Windows
`%LOCALAPPDATA%\Miffan\bin\miffan-connector.exe` 与 `%LOCALAPPDATA%\Miffan\connector\`。数据目录里的
`host.key`、`config.json`、`state.json`、`run.lock` 由 connector 管理，helper 只在 uninstall 时删除。
connector 的日志写 `connector.log`（helper 在安装和启动时把超过 1 MiB 的日志截成末尾 128 KiB）。

常驻服务（随用户登录运行）：macOS 为 `~/Library/LaunchAgents/me.ayuilos.miffan.connector.plist`
（`RunAtLoad`、`KeepAlive`，`launchctl bootstrap gui/<uid>`）；Linux 为 `~/.config/systemd/user/miffan-connector.service`
（`Restart=always`，`systemctl --user enable --now`，并尝试 `loginctl enable-linger`，失败不算错误，`status` 报告
`linger:false`）；Windows 为当前用户的登录计划任务 `Miffan-connector`（普通权限、交互登录、失败自动重启），
它运行 `powershell.exe -WindowStyle Hidden … miffan.ps1 relay run`，后者无控制台窗口地启动 connector 并把它的
stderr 追加到 `connector.log`。`relay run` 只供计划任务使用，不输出 JSON。

## 截图模式（`snapshot serve`，POSIX helper 37 起、Windows helper 19 起）

经中转看屏幕时，App 用 cua-driver 截图（`get_desktop_state` 的 `screenshot_out_file`），再由这个常驻进程把
图片发回。整个屏幕页只开一个进程，每张图不再启动新命令；图片是**原始字节**，不经 base64 或 JSON。
两个平台协议相同；用法错误时 POSIX 退出 2，Windows 抛错。

1. 启动后先输出一行 `ready <路径>`：cua-driver 写截图的位置（macOS/Linux `~/.miffan/snapshot/desktop.png`，
   目录权限 700；Windows `%LOCALAPPDATA%\Miffan\snapshot\desktop.png`）。路径为 UTF-8，可含空格。目录建不出来时
   输出 `error io_error` 并结束。
2. 之后每读到一行请求 `<质量> [<摘要>]` 就回复一次。质量是 JPEG 质量，钳到 20–90，不是数字时按 55。
   摘要是上一张图回复里的 `<摘要>`。回复是一行头，有图时紧跟 `<长度>` 个字节：

| 头 | 之后 | 含义 |
|---|---|---|
| `same <摘要>` | 无 | 截图与请求里的摘要相同，不发图 |
| `jpeg <摘要> <长度>` / `png <摘要> <长度>` | `<长度>` 字节图片 | 有编码器时转成 JPEG（macOS `sips`；Linux 依次 ImageMagick、ffmpeg、Pillow；Windows System.Drawing），否则原样发 PNG |
| `error <原因码>` | 无 | `no_snapshot`（还没有截图）或 `io_error`；进程继续服务下一行 |

摘要：macOS/Linux 为 `cksum` 的 `<crc>-<大小>`，Windows 为 MD5 十六进制；App 只接受 `[0-9a-f-]{1,80}`。
stdin EOF 结束进程并退出 0。编码器的 stdin 接 `/dev/null`，不会读走后续请求。

## 单个窗口（POSIX `window`：helper 39 起支持 niri，40 起支持 KDE Plasma，41 起支持 GNOME 和 X11，42 修复切换桌面会话后 KDE 打不开，44 起 KDE 优先用 KWin 自己的单窗口录制和输入，45 修正 KDE 弹出菜单的点击位置、按用户键盘布局输入快捷键，并补上撤销命令）

在手机上只看、只操作 Linux 电脑的一个窗口。画面来自合成器的 `org.gnome.Mutter.ScreenCast`
（`RecordWindow`，不弹选择框，也无需授权），经 PipeWire 由 GStreamer 缩放并编码为 H.264
（依次选用 `nvh264enc`、`vah264lpenc`、`vah264enc`、`vaapih264enc`、`x264enc`、`openh264enc`）。
输入走 `/dev/uinput`：一个绝对坐标指针，加一个键盘。文本优先用 `wtype` 输入，没有时用 `wl-copy`
加 Ctrl+V 粘贴。niri 下的窗口列出、聚焦、改尺寸走它的 IPC 套接字 `NIRI_SOCKET`。

**按键（helper 45 起，所有桌面相同）**：`key` 行里是单个字符的键（`a`、`!`、`é`）按 keysym 发送，由合成器在用户
当前的键盘布局里找到对应的键，所以 AZERTY 下 Ctrl+A 仍是 Ctrl+A、Dvorak 下粘贴仍是 Ctrl+V；有名字的键（修饰键、
Enter、方向键、F1 等）按键码发送，它们在各布局下是同一个键。KDE 用 fake_input 的 `keyboard_keysym`（portal 路线用
`NotifyKeyboardKeysym`），GNOME 用 `NotifyKeyboardKeysym`，X11 本来就用 keysym。niri 装了 `wtype` 时字符键交给它
（`wtype -M 修饰键… -k U+码点 -m 修饰键…`，按下时一次敲完，带上手机按住的修饰键），否则仍按美式布局的键码经
uinput 发送。粘贴快捷键也是 Ctrl 键码加 `v` 的 keysym。helper 调用的工具（`wtype`、`wl-copy`、`gsettings` 等）的
stdout/stderr 都接到 `/dev/null`，不会混进帧流；`wl-copy` 在自己的会话里常驻。
需要真机确认：GNOME 下若键盘只有一个非拉丁布局（如单独的俄文布局），Mutter 找不到 `c` 的 keysym 时会丢弃这个键，
Ctrl+C 这类快捷键可能不生效（KWin 会临时换 keymap，没有这个问题）；niri 下 `wtype` 的虚拟键盘与 uinput 键盘的
修饰键是否叠加也要在真机上看。

KDE Plasma（`XDG_CURRENT_DESKTOP` 含 KDE，且没有 niri）各版本的情况（KWin 版本取自 `kwin_wayland --version`）：

| KWin | 路线 | 说明 |
|---|---|---|
| 5.x（Plasma 5，如 Debian 12、Kubuntu 24.04） | 不支持 | 脚本用的 `workspace.windowList()` 等是 KWin 6 的接口。`probe` 报 `supported:false`、`error:"plasma5"`；`list` 立即回 `{"error":"unsupported"}`，`serve` 回 `closed unsupported`，`shots` 回 `error unsupported`。版本读不到时按 `KDE_SESSION_VERSION=5` 判断 |
| 6.0–6.2 | portal，每次弹框 | 没有 `kde-authorized` 表，预授权无效：每次开 portal 会话电脑上都弹确认框，8 秒没人点就 `not_authorized`。`probe` 报 `prompt_every_time:true`，`authorized` 为 false；`authorize` 回 `{"authorized":false,"error":"prompt_every_time"}` 并退出 1 |
| 6.3–6.7 | portal，预授权一次 | KWin 直到 6.7 都只把下面两个全局对象给 desktop 文件里 `X-KDE-Wayland-Interfaces` 列出的程序，helper 拿不到，走 portal；`authorize` 后不再弹框 |
| ≥ 6.8 | KWin 直连 | 6.8 起 KWin 只对沙箱里的程序限制这些接口，普通程序也能用，不需要授权 |

是否走直连只看 KWin 有没有把两个全局对象给 helper（和以前一样）；另外版本已知且低于 6.5 时不走直连（有人用
`X-KDE-Wayland-Interfaces` 放行了 python3 的情况）：6.4 及以前窗口流按 `clientGeometry` 录，不含标题栏，6.5 之前
也不含弹出菜单，输入换算会对不上。不建议用 `X-KDE-Wayland-Interfaces` 给 python3 加白名单：KWin 按可执行文件匹配，
等于放行所有 Python 程序。

直连路线：helper 自己用 Wayland 线协议连 KWin（不依赖额外的库）：
`zkde_screencast_unstable_v1.stream_window(窗口 UUID, 指针嵌入)` 给出这个窗口（含标题栏）的 PipeWire 流，
在别的桌面上或被遮挡时也照常出画面，走和 niri 相同的 GL 缩放管线。KWin 会把这个窗口的弹出窗口（右键菜单、
下拉框，以及它们的子菜单：`popupWindow` 且 transient 链一路是弹出窗口直到这个窗口）一起录进来，画面范围是这些窗口
`frameGeometry` 的并集，原点是并集的左上角；菜单伸出窗口时画面会变大，向左或向上伸出时原点也会动。所以 helper 45
起输入按同一个并集换算：KWin 脚本返回并集（`x`、`y`、`width`、`height`）和主窗口框（`frame`），流的尺寸一变就立刻
重读一次。流的尺寸变了（菜单弹出或关掉、窗口改大小）时 pipewiresrc 跟不上：它守着最初协商的尺寸，报
`no more input formats` 后流就停了（helper 44 因此在一弹菜单时就断开）。helper 46 起遇到这个错误不结束推流，而是关掉
旧流、按新尺寸重新 `stream_window` 一次并换上新管线（约 0.2 秒），手机只看到一个新的关键帧和 `geometry`；10 秒内超过
8 次才当作采集失败。尺寸变化时 KWin 有时不报错，而是隔两秒多才按新尺寸重新协商，之后就不再出帧（二级菜单打开时
常见），所以 helper 47 起不等错误：常驻 KWin 脚本报来的「窗口加弹出窗口」尺寸一变（150 毫秒内的连续变化合并成一次），
就主动按新尺寸重开流；这种主动重开不计入失败次数。helper 48 起 KDE 直连路线的 `opened W H ENCODER` 和
`geometry W H` 行末多四个数 `X Y W H`：这一版画面在屏幕上的位置（全局逻辑像素，即窗口加弹出窗口的并集），旧版 App
只读前面的字段。App 据此在画面变大或原点移动时把指针留在屏幕上的同一点，不跳到别的菜单项上。输入的换算也改为按新尺寸的
画面真正发出那一刻切换（之前按 KWin 报来位置的那一刻），手机在旧画面上点的位置不会被按新画面换算。输入用 `org_kde_kwin_fake_input` 6（`pointer_motion_absolute` 按全局逻辑坐标，`button`、`axis` 每格 15，
修饰键等用 `keyboard_key`，字符键和文字用 `keyboard_keysym`），不需要任何授权；`shots` 用
`org.kde.KWin.ScreenShot2.CaptureWindow`（含标题栏，不含阴影），任何没有最小化的窗口都有图（HDR 下的 64 位 RGBA
也认；其他格式退回裁剪路线）。KWin 没有提供这两个全局对象或建流失败时，退回下面的 portal 路线。

portal 路线：helper 通过 RemoteDesktop portal 录窗口所在的输出，再用 `videocrop` 按 KWin 给出的窗口范围（同上，
含标题栏和弹出菜单）裁出窗口。选流先看哪个流的 `position`/`size` 包含窗口中心（KDE 的 portal 不一定给接口名），
再看 `mapping_id`，最后用第一个。输入也走同一个 portal 会话：`NotifyPointerMotionAbsolute`、修饰键等按键码、
字符键和文本用 keysym（非 Latin-1 字符用 `0x1000000+码点`），滚轮用一次带 `finish` 的 `NotifyPointerAxis`，每格 15，
y 取反（Plasma 会再反一次）；离散滚动在 Plasma 里会被当成像素，所以不用。每张 portal 截图和每次推流用完都会关掉
`OpenPipeWireRemote` 给的 fd。

跟随窗口（两条路线相同，helper 45 起）：推流时加载一个常驻的 KWin 脚本，连上窗口及其弹出窗口的
`frameGeometryChanged`、`minimizedChanged`、`desktopsChanged` 和 `workspace` 的 `windowActivated`、
`currentDesktopChanged`、`windowAdded`、`windowRemoved`，有变化就用 `callDBus` 推回位置，窗口关闭时推
`{"gone":true}`；不再每 0.5 秒加载、编译、卸载一次脚本。另有每 5 秒一次的读取兜底；常驻脚本起不来时退回每 0.5 秒
读一次。只有 KWin 明确说窗口不在了（脚本返回 null、推 `gone`，或直连路线的 `closed` 事件）才以 `closed window_closed`
结束；脚本超时（KWin 忙）或出错只发一次 `info {"kwin":"no_answer",...}`，然后接着试。推流开始后会发一次
`info {"kwin_watch":true|false}` 说明用的是哪种方式。打开时先把窗口提到最前；最小化时发 `suspended minimized`，
还原后发 `resumed`。

窗口的列出、聚焦、改尺寸由临时加载的 KWin 脚本完成，脚本通过 `callDBus` 把 JSON 结果发回 helper 自己持有的
D-Bus 名字。脚本文件写在 `$XDG_RUNTIME_DIR/miffan-kwin/`（0700）下，不放 `/tmp`：SSH 会话的 `/tmp` 可能按会话
隔离（pam_namespace、sshd 的 PrivateTmp），KWin 会读不到。窗口 id 是 KWin 的 `internalId`（UUID，不带花括号）。
`shots` 处理一批请求时，KWin 的窗口快照沿用 1.5 秒。

预授权（Plasma 6.3–6.7 的 portal 路线需要）：`authorize` 会写入隐藏的
`~/.local/share/applications/me.ayuilos.miffan.desktop`（portal 的 Registry 注册 app_id 时需要它），
并在权限库 `kde-authorized` 表中写入 `remote-desktop` → `me.ayuilos.miffan` = `yes`。App 只会在用户在手机上
点了「允许」之后才调用它；`revoke` 撤销（见下表）。没有授权时 Plasma 会在电脑上弹确认框，Start 8 秒内得不到答复，
`serve` 就以 `closed not_authorized` 结束，`shots` 回 `error not_authorized`。注意：任何不在沙箱里的本用户程序都能
用 `Registry.Register` 冒用这个 app_id 拿到这项授权；KWin 6.8 上非沙箱程序本来就能直接用 fake_input，新增的风险很小。

从 niri 或 GNOME 注销后直接登录 Plasma（不重启）时，portal 服务可能还是为上一个桌面启动的，只有那个桌面的后端，
Plasma 下的 RemoteDesktop 用不了。所以 helper 42 起，开 portal 会话前会先看服务进程的 `XDG_CURRENT_DESKTOP`，
不含 KDE 时先 `systemctl --user restart xdg-desktop-portal.service`。**副作用**：重启会断掉其他程序正在用的 portal
会话（浏览器会议里的屏幕共享、OBS 等）。helper 45 起只在重启有用时才重启：重启后的服务继承 systemd 用户管理器的
环境，所以先看 `systemctl --user show-environment` 的 `XDG_CURRENT_DESKTOP` 含不含 KDE；不含（例如 Plasma 不是经
systemd 启动的）就不重启，`serve` 以 `closed portal_not_kde` 结束、`shots` 回 `error portal_not_kde`，避免每次推流
都重启一次。用户可以重新登录，或执行 `systemctl --user import-environment XDG_CURRENT_DESKTOP` 后再试。
需要真机确认：portal 路线在探针里用 `pin_size` 固定尺寸，GNOME 那边见过这样做让 pipewiresrc 在停止时死锁，
KDE portal 路线的停止流程是否同样会卡住，还没有在真机上验证过。

GNOME（Wayland）上，Mutter 自己的 `org.gnome.Mutter.ScreenCast` 和 `org.gnome.Mutter.RemoteDesktop` 对普通程序开放，
不弹框：`serve` 建一个 RemoteDesktop 会话，把 ScreenCast 会话挂在它下面（`remote-desktop-session-id`），按窗口
`RecordWindow`。Mutter 给的缓冲区和显示器一样大，窗口只占其中 `GstVideoCropMeta` 标出的区域（PyGObject 读不了
这个 meta 的字段，所以从它的 C 结构里读），helper 据此用 `videocrop` 裁剪。帧走共享内存（`video/x-raw`），
因为 Mutter 可能在流中途更换 DMA-BUF 修饰符，GL 导入会拒绝；也不在探针里固定尺寸（会让 pipewiresrc 在停止时死锁）。
输入用同一个会话：`NotifyPointerMotionAbsolute` 用窗口自己的逻辑像素（扩展报告的缓冲区大小），修饰键等按键码、
字符键用 keysym，离散滚轮；ASCII 文本用 keysym，其他文本经会话的剪贴板（`EnableClipboard`、`SetSelection`、响应
`SelectionTransfer`）后按 Ctrl+V。Mutter 的虚拟键盘和指针在第一次使用时才创建，并吞掉那次事件，所以第一次之前先轻点一下
Shift，或先移动一次指针。helper 45 起管线先 `videocrop` 再 `videoconvert`：Mutter 的缓冲区和整个显示器一样大，只转换
窗口那一块能省下大部分开销。扩展没有及时答复（shell 忙、正在重载）时不再当成窗口关闭，只发一次
`info {"extension":"no_answer",...}`。已知限制（需真机确认后再定）：非 ASCII 文本靠 Ctrl+V 粘贴，在终端里不生效
（终端的粘贴是 Ctrl+Shift+V），而且会留在用户的剪贴板里；niri 的 `wl-copy` 回退也一样。
GNOME 不让普通程序列出窗口（`org.gnome.Shell.Introspect` 只对白名单开放），由 helper 安装的小扩展
`miffan-windows@ayuilos.me`（`install-extension`）在会话总线上以 `me.ayuilos.Miffan.Windows` 提供 `List`、
`Activate`（会先退出概览）和 `Resize`。GNOME 只在登录时加载新扩展，所以安装后要重新登录一次；在此之前 `list`
返回 `{"error":"needs_extension","extension":"missing"|"installed"|"disabled"}`，App 据此提示安装或重新登录。
helper 45 起不再改 `org.gnome.shell disable-user-extensions`：它是用户或管理员关掉**所有**用户扩展的总开关，
打开它会把别的扩展也一并重新启用。这个开关为 true 时 `extension` 是 `"disabled"`，`install-extension` 什么也不写，
回 `{"installed":false,"extension":"disabled","error":"extensions_disabled"}` 并退出 1，由用户自己决定要不要打开它
（App 目前把它当成安装失败）。扩展的 `shell-version` 列到 52。**发版检查项**：每个新的 GNOME 大版本（约每年 3 月、
9 月）发布前，把它的版本号加进 `GNOME_METADATA_JSON`，否则新 GNOME 会拒绝加载扩展，`list` 会一直回
`needs_extension`。

X11 会话（XFCE、Cinnamon、MATE，以及运行在 Xorg 上的 GNOME 或 KDE，即任何遵循 EWMH 的窗口管理器）上，helper 通过
ctypes 直接调用 libX11 和 libXtst，不依赖 xdotool 等工具：用 `_NET_CLIENT_LIST_STACKING` 等属性列出窗口，用
`_NET_ACTIVE_WINDOW` 和 `_NET_MOVERESIZE_WINDOW` 客户端消息聚焦、改尺寸，用 `ximagesrc xid=` 录窗口（有合成器时被遮挡的
窗口也能录），用 XTest 注入输入。键盘映射里没有的字符会临时借空闲键码：helper 45 起一段文本里每个这样的字符各借
一个、整段打完后稍等 0.1 秒再还（目标程序收到 MappingNotify 后才刷新映射，立刻还会丢字或打错字），空闲键码不够时
先还再借。推流时每 0.5 秒只查目标窗口本身，不再把所有窗口都查一遍。窗口最小化时管线暂停；另外装了一个忽略
X 错误的处理函数，因为 Xlib 默认遇到错误就退出进程，而窗口在 ximagesrc 读取时消失会触发这种错误。没有 h264parse 时，
x264enc 用 `repeat-headers=1` 自己重复 SPS/PPS。

程序是
内嵌在 helper 里的 Python，需要 `python3` 和 PyGObject（Gst、GstVideo）。macOS 上、没有图形会话、
或者没有 python3 时，`probe` 输出 `{"supported":false}` 并退出 0，其他子命令退出 1。

| 子命令 | 输出 |
|---|---|
| `probe` | 一行 JSON：`supported`、`desktop`（`"niri"`、`"kde"`、`"gnome"`、`"x11"` 或 null）、`encoder`、`capture`、`input`（niri 时是 `/dev/uinput` 是否可写，KDE 总为 true）；KDE 另有 `kwin`（`"6.2"` 这样的版本，读不到时为 null）、`direct`（KWin 是否提供单窗口录制和输入，是则不需要授权）、`prompt_every_time`（6.0–6.2 且不走直连：每次都会在电脑上弹框）和 `authorized`（`direct` 为真时也为真；`prompt_every_time` 时为假），Plasma 5 时 `supported` 为 false 并带 `error:"plasma5"`；GNOME 另有 `extension`（`active`、`installed`、`disabled`、`missing`） |
| `list` | 一行 JSON：`windows[]`（`id` 字符串、`pid`、`app` 为 desktop 文件里按 LANG 取的名称、`app_id`、`title`、`workspace`、`focused`、`width`、`height` 逻辑像素；KDE 另有 `minimized` 和 `visible`，后者表示窗口在当前桌面上没有被别的窗口挡住），`workspaces[]`（`id`、`index`、`output`、`name`、`active`），`current`（聚焦输出上的活动工作区），`outputs[]`；不支持时为 `{"error":"unsupported"}` |
| `focus ID` | 聚焦窗口（KDE 会先还原最小化的窗口，并切到它所在的桌面）；退出码表示成败 |
| `authorize` | 仅 KDE：写入上述预授权，输出 `{"authorized":bool}`；6.0–6.2 上不写，输出 `{"authorized":false,"error":"prompt_every_time"}` 并退出 1 |
| `install-extension` | 仅 GNOME：写入扩展文件，并加进 `org.gnome.shell enabled-extensions`，输出 `{"installed":bool,"extension":状态}`，失败时另有 `error`（`extensions_disabled`，或 `no_gsettings`） |
| `uninstall-extension` | helper 45 起：`gnome-extensions disable`，从 `enabled-extensions` 里去掉，删掉扩展目录；输出 `{"uninstalled":bool}`（有没有东西可删），退出 0 |
| `revoke` | helper 45 起，不论当前是哪种桌面：权限库 `DeletePermission("kde-authorized","remote-desktop","me.ayuilos.miffan")`、删掉隐藏的 `.desktop`、卸载 GNOME 扩展；输出 `{"revoked":true,"kde_grant":bool,"desktop_entry":bool,"extension":bool}`（各项是否真的撤销了东西），退出 0 |
| `resize ID W H` | 设置窗口的逻辑尺寸（平铺时宽度作用于整列），等尺寸稳定（每 50 毫秒读一次，两次相同，最多 1 秒；Wayland 客户端是异步改尺寸的）后输出实际得到的 `W H`；helper 45 起 KDE、GNOME 也这样等，KDE 输出的是窗口框（不含弹出菜单） |
| `shots` | 每读到一行 `ID 最长边 JPEG质量`，就回一行 `jpeg 长度` 再跟字节，或者回 `error no_window`、`error no_picture`、`error usage`；KDE 还可能回 `error not_authorized`、`error portal_not_kde`，Plasma 5 回 `error unsupported`，GNOME 没有扩展时回 `error needs_extension`。等第一帧最多 2.5 秒：niri 只在重绘时出帧，完全静止的窗口可能拿不到；KDE 用 KWin 截图时最小化的窗口回 `error no_picture`，退回 portal 路线时只给 `visible` 的窗口截图 |
| `locate ID` | 输出窗口在全局逻辑坐标中的 `X Y W H`，找不到时退出 1（见下文） |
| `serve ID 最长边 FPS KBPS` | 推流，并接收输入（见下文） |

`serve` 开始前会先聚焦窗口：niri 对不在屏幕上的窗口大约每秒只重绘一次。stdout 每行一个头：

- `opened W H 编码器`：会话已建立（尺寸是起始值）
- `geometry W H`：紧跟着的帧尺寸变了，`codec_epoch` 加一。helper 45 起在新尺寸的帧真正编码出来、写出之前才发
  （以前在源端尺寸一变就发，编码器里还没出来的旧尺寸帧会带上新 epoch，解码器多重置一次）
- `frame 序号 epoch 关键帧(0/1) W H 时间戳微秒 长度`，后面跟着 `长度` 字节的 H.264 Annex B 访问单元（关键帧带 SPS/PPS）。
  helper 45 起帧由单独的线程写出：SSH 通道跟不上、积压超过约半秒的帧（至少 4 帧）时，丢掉积压的帧并跳到下一个关键帧
  （同时请求一个），手机上看到的是跳一下而不是越来越大的延迟；序号对写出的帧连续
- `resumed`：第一帧之前发一次；之后在 `suspended` 结束时发
- `suspended locked`：屏幕锁着的时候有人试图操作，输入被丢弃（logind 的 `LockedHint`）；解锁后会发 `resumed`
- `suspended minimized`：窗口最小化了（KDE、GNOME、X11；X11 同时暂停管线），还原后发 `resumed`
- `info JSON`：诊断信息，例如 `{"located":true,"ms":420}`、`{"kwin_watch":true}`、`{"kwin":"no_answer","detail":"…"}`、
  `{"extension":"no_answer","detail":"…"}`、`{"input":"unavailable"}`；App 不依赖其内容
- `closed 原因`：`window_closed`（窗口关了；App 当作 4410）、`capture_failed`、`no_stream`、`no_encoder`、`ended`、
  `no_window`（KDE 的窗口 id 不是 UUID）、`not_authorized`（KDE portal 未授权）、`portal_not_kde`（helper 45，见上文）、
  `needs_extension`（GNOME 没有扩展）、`unsupported`（helper 45，Plasma 5）；随后进程退出

stdin 每行一条，坐标是窗口内 0–1 的比例：`move X Y`、`down 按键 X Y`、`up 按键 X Y`（按键为
`left`、`middle`、`right`）、`scroll X Y DX DY`（像素，100 为一格，DY 小于 0 表示向上）、`key 名称 1|0`（DOM 键名；
单个字符按 keysym 发送，见上文「按键」，多字符的键名不分大小写）、`text Base64(UTF-8)`、`kf`（请求关键帧）。
输入线程每次取出积压的行时，连续的多条 `move` 只保留最后一条（helper 45 起），聚焦窗口等慢操作之后不会一次重放
一大串移动。stdin EOF 时结束并销毁 uinput 设备。输入落在
指针下方的窗口上，所以每次输入前（至少间隔 1 秒）会确认目标窗口在前台，不在就先聚焦它。niri
不提供平铺窗口的位置，helper 会另起一个进程运行 `locate`：各取一张缩小的灰度帧（窗口一张、所在输出
一张），用边缘加权采样做由粗到细的匹配，至少 30% 的采样点吻合才接受。之所以另起进程，是因为同一个进程里
用完即弃的 GL 管线会终止共享的 EGL display，把正在推流的管线也弄坏。浮动窗口直接用 niri 给的位置。
窗口所在工作区的布局没变时，沿用上一次的位置。

## 版本与安装

当前 POSIX helper 为 49，Windows 为 20。POSIX 39 新增 `window` 命令，40 让它支持 KDE Plasma，41 支持 GNOME 和 X11，42 在 KDE 下重启为别的桌面启动的 portal，43 找会话环境时只认已登录（active/online）会话里的进程，44 在 KDE 下优先用 KWin 自己的单窗口录制和输入，45 修正 KDE 弹出菜单伸出窗口时的点击位置并改用常驻 KWin 脚本跟随窗口、字符键按 keysym 发送以跟随用户的键盘布局、按版本区分 Plasma 5 和 6.0–6.2、不再打开 GNOME 的用户扩展总开关、新增 `uninstall-extension` 和 `revoke`、只在有用时重启 portal 服务，并且 systemd 用户管理器的环境只在用户在这台电脑上登录着（有座席或图形会话，手机的 SSH 登录不算）时才用（不认 systemd 256 起的 class=manager 会话）、Wayland 套接字取最新的，46 在 KDE 窗口流因弹出菜单等改变尺寸时重建流而不是断开，47 在尺寸一变时就主动重建（二级菜单下 KWin 会卡住而不报错），48 在 `opened`、`geometry` 行末附上画面在屏幕上的位置、输入改按手机正在看的那一版画面换算，49 在 GNOME 远程桌面仍指向 `~/.miffan/rdp` 里已经不存在的证书和密钥（目录被清空）时重新配置它，而不是当作别人的配置拒绝。POSIX 21 新增 `task` 命令，24 新增 `space` 命令，33 新增 `relay` 命令；Windows 15 新增 `relay` 命令。

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
