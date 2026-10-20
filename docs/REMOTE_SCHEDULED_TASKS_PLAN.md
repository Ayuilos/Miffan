# 远程电脑定时任务开发计划

## 目标与边界

让伙伴在用户连接的远程电脑上创建定时任务：到点由电脑自己执行，手机不在线也照常运行；结果在用户打开 Miffan 时同步回伙伴的聊天。

任务分两种：

- **命令任务**：到点运行一条确定的 shell 命令，例如备份、拉取日志、检查磁盘。
- **AI 任务**：到点调用电脑上已安装的 agent 命令行（用户自定义，内置 Claude Code、Codex CLI、Gemini CLI 预设），让它按提示词完成一件事，例如“整理下载文件夹并汇报”。

第一版支持 Linux 和 macOS。Windows 暂不支持，但界面和伙伴都要明确说明（见“用户侧提示”）。

不在本计划内：Miffan 自己的常驻运行时（在电脑上复制一套伙伴推理循环、工具和记忆）；后台定时拉取结果；推送通道（ntfy、FCM 等）；Windows 计划任务。

## 已确定的决策（2026-10-20）

- 到点触发交给电脑系统自己的定时器（systemd 用户定时器 / launchd / crontab），不在电脑上另起 Miffan 守护进程。
- 任务定义以电脑上的文件为准，App 只存缓存；手机丢失、重装或换机后可重新同步，任务不受影响。
- AI 任务走电脑上已有的 agent 命令行，使用它自己的登录与计费；用哪个 agent 由用户定义，Claude Code、Codex CLI、Gemini CLI 只是内置预设。**Miffan 不向电脑复制任何提供商 API Key**，这一点与远程屏幕“云电脑不持有 API Key”的原则保持一致：模型调用来自用户自己在电脑上装好的工具，不来自 Miffan。
- 结果只在打开 App 时拉取（回到前台时，加节流），不做后台拉取；因此用户侧必须讲清楚“结果什么时候能看到”。
- 伙伴创建或修改任务一律弹审批卡，不允许长期授权，聊天中的文字同意不算批准（沿用审批卡规则）。
- Windows 暂不支持，入口不隐藏，而是明确告知原因。

## 架构

### 职责划分

| 环节 | 位置 | 说明 |
| --- | --- | --- |
| 到点触发 | 电脑 | Linux：systemd 用户 timer（`Persistent=true` 补跑错过的运行），没有 systemd 用户实例时退回 crontab；macOS：`~/Library/LaunchAgents` 中的 LaunchAgent，`StartCalendarInterval`/`StartInterval` |
| 执行与记录 | 电脑 | `miffan task exec <id>`：flock 防重叠、超时、日志上限、运行记录 JSON、保留最近 N 次 |
| 任务定义 | 电脑 | `~/.miffan/tasks/<id>.json`（0600），目录 0700 |
| 同步结果 | 手机 | App 回到前台或进入任务页时调用 `miffan task sync`，按游标增量拉取 |
| 理解结果 | 手机 | 运行记录以 `MessageOrigin.Event(kind = "scheduled_run")` 写入任务所属伙伴的聊天，伙伴按任务的回应策略决定是否发言 |

### Agent 工具定义

AI 任务用哪个 agent 由用户决定，不限于内置的几个。Agent 工具是 App 里的一条配置（随设置一起备份），helper 对 agent 一无所知：

| 字段 | 说明 |
| --- | --- |
| 名称 | 显示用，例如“Claude Code”“我的 aider” |
| 可执行文件 | 命令名（在电脑上解析成绝对路径）或绝对路径 |
| 参数模板 | 参数列表；含 `{prompt}` 的参数在运行时替换为提示词，没有 `{prompt}` 时提示词经 stdin 传入 |
| 权限档位 | 0 个或多个命名档位，每档是追加的参数，例如“只读”“可改文件”；没有档位时审批卡写明“权限由这条命令的参数决定” |
| 结果提取 | 纯文本（整段输出）/ JSON 字段 / JSONL 中最后一条匹配事件的字段 |
| 版本参数 | 可选，例如 `--version`，用于检测与展示 |
| 继承的环境变量 | 变量名清单，每次运行时从电脑的登录 shell 读取当前值。默认 `PATH` 与 `http_proxy`、`https_proxy`、`all_proxy`、`no_proxy`（大小写各一份）；用户可追加，例如某个工具需要的 base URL 或 key 变量 |

内置预设：Claude Code、Codex CLI、Gemini CLI，各自带“只读”“可改文件”两档。用户可以复制预设修改，也可以从零新建。

| 预设 | 参数模板 | 只读 | 可改文件 | 结果提取 | 状态 |
| --- | --- | --- | --- | --- | --- |
| Claude Code | `-p --output-format json --no-session-persistence` | `--permission-mode dontAsk` | `--permission-mode acceptEdits` | JSON `result` | 2.1.304，P1 在 Mac launchd 下实测成功（需继承代理变量） |
| Codex CLI | `exec --skip-git-repo-check --json -` | `-s read-only` | `-s workspace-write` | JSONL 最后一条 agent 消息；失败取 `turn.failed.error.message` | 0.160.0，P1 在 CachyOS systemd 下实测（失败路径） |
| Gemini CLI | `-p --output-format json` | （默认审批模式） | `--approval-mode auto_edit` | JSON `response` | 两台测试机都未安装，未验证 |

- 日志里 stdout 与 stderr 混在一起（命令任务的用户需要按时间顺序看），agent 的 stderr 常有无关告警。因此“JSON 字段”提取取最后一行能解析为 JSON 对象的行，“JSONL”提取逐行解析并跳过非 JSON 行，不把整段输出当成一个 JSON。
- 环境变量必须继承：定时器启动的进程没有用户在 `~/.zshrc` 等文件里设置的变量。P1 实测，Mac 上的 Claude Code 在 launchd 下返回 403 “Request not allowed”，原因是缺少写在 `~/.zshrc` 里的代理变量（Claude Code 不读系统代理）；带上代理变量后成功。变量只传名字，值在运行时由 helper 从登录 shell 读取，不经过手机、不写入任务文件，用户改了代理端口任务也会跟着变。命令任务同样使用默认清单，可在任务里编辑。
- 运行脚本只在 helper 没有读到登录 shell 环境（`MIFFAN_LOGIN_ENV` 不为 `ok`）时，才使用 `task which` 返回的 PATH 兜底，避免覆盖继承来的 PATH。
- `--skip-git-repo-check` 必需：任务工作目录不一定是 git 仓库，Codex 默认拒绝在非仓库目录运行。
- 内置预设不提供“完全放开”档（bypassPermissions / danger-full-access / yolo），无人值守下风险过高。用户自定义的工具可以自己写这类参数，审批卡会原样显示完整命令行，并对含已知危险参数的命令加醒目提示。
- 创建 AI 任务时，App 先用 `task which` 在电脑的登录 shell 里解析可执行文件的绝对路径和 PATH（定时器启动的进程不读 shell profile，npm/nvm 安装的 CLI 常常不在默认 PATH 里），再渲染运行脚本。
- 登录状态无法可靠地静态检测。审批通过后立即试跑一次（`task run`），用试跑结果确认登录与权限都没问题。
- macOS 上部分 CLI 的凭据在登录钥匙串中，LaunchAgent 只有在用户已登录时才能访问。P1 实测并写入用户侧提示。
- AI 任务默认超时 30 分钟，命令任务默认 10 分钟。

### 任务在电脑上的形态

helper 不解析任务 JSON，也不认识 agent；App 把任务渲染成几份文件交给 helper，因此 helper 只依赖 POSIX sh 与系统自带工具，不需要 python3（macOS 未装命令行工具时调用 python3 会弹安装框，无人值守的执行器绝不能碰）。

`~/.miffan/tasks/<id>/`（目录 0700，文件 0600）：

| 文件 | 内容 |
| --- | --- |
| `meta.json` | App 的任务描述（名称、类型、agent、权限档位、owner、回应策略等），helper 原样保存、原样返回 |
| `run` | App 渲染的 sh 脚本：`cd` 到工作目录、导出解析好的 PATH、执行命令或 agent（参数逐个单引号转义） |
| `prompt` | 非空时作为 `run` 的 stdin |
| `env` | 要从登录 shell 继承的变量名，每行一个，只有名字没有值 |
| `schedule`、`timeout`、`backend` | commit 写入：schedule 一行（如 `daily 09:00`、`weekly 0,3,5 09:00`、`interval 30`）、超时秒数、所用定时器 |
| `enabled` | 存在即启用 |
| `runs/<run_id>.json`、`runs/<run_id>.log` | 运行记录与输出 |

- schedule 只支持每天、每周几（0 = 周日，与 cron/launchd 一致）、每隔 N 分钟（N ∈ 15、30、60、120、180、240、360、480、720，三种后端都能精确表达）。时间按电脑本地时区，App 同时显示换算后的手机时间。
- `run_id` 取结束时刻（UTC，`YYYYMMDDTHHMMSSZ-pid`），按字典序就是完成顺序，作为同步游标。App 同步时把游标回退 2 分钟并按 `run_id` 去重，覆盖“记录写入时刚好跨过一次同步”的边界。

### 执行器 `task exec <id>`

- 防重叠：`mkdir` 锁目录 + pid 文件，持锁进程不在时视为陈旧锁（macOS 没有 flock）。重叠时写一条 `skipped_overlap` 记录。
- 超时：后台运行子进程，到时对子进程及其后代（`pgrep -P` 递归）先 TERM、10 秒后 KILL（macOS 没有 GNU timeout）。
- 输出：stdout+stderr 写 `.log`，超过 1 MB 只保留末尾 1 MB（结果通常在最后）。
- 记录：`{run_id, task_id, started_at, ended_at, exit_code, status: ok|failed|timeout|skipped_overlap, log_bytes, truncated}`，写临时文件后 `mv`。
- 清理：每个任务只保留最近 30 次。

### helper 协议扩展（POSIX）

完整定义见 `remote-helper/PROTOCOL.md` 的“定时任务”一节（helper 21）。要点：

- 创建/更新：`task stage <id> meta|run|prompt`（prompt 必须暂存，空文件表示没有提示词），再 `task commit <id> <timeout> <schedule...>`。commit 无论成败都清空暂存，更新时保留运行记录与启用状态。
- 其余：`task probe`（scheduler、linger、login_required、timezone、utc_offset）、`task which`、`task list`、`task enable|disable`、`task run`、`task sync [cursor]`、`task log`、`task remove <id>|--all`、`task exec`（仅定时器调用）。
- 定时器调用的是 commit 时复制出的 `~/.miffan/bin/miffan-task-runner`，不是 `~/.miffan/bin/miffan`。原因：`ensureInstalled` 只要版本号不同就覆盖，另一台装着旧版 App 的设备连上来会把 helper 降级，不能因此让已有任务悄悄失效。
- “下次运行时间”不由 helper 计算，App 用 schedule 与 probe 返回的 IANA 时区自行推算。
- `RevokeMiffanKeyCommand` 生成的撤销命令同时执行 `task remove --all`（P2）。Windows helper 不实现 task 命令，App 在 Windows 主机上不发送这些命令。

### App 侧

- **数据**：Room 表 `remote_task`（主机 id、任务 JSON 缓存、owner）与 `remote_task_run`（运行记录、是否已投递到聊天），以及每台主机的同步游标。
- **工具** `schedule_task`（create / update / list / delete / run_now），只对有可用 helper 的 Linux/macOS 主机提供；Windows 主机上工具描述说明不支持，调用时返回固定错误，伙伴能据此向用户解释。
- **Agent 工具管理**：设置里的“Agent 工具”列表（预设 + 自定义），编辑页可以选一台电脑“检测”，显示解析到的路径与版本。伙伴创建 AI 任务时只能引用已定义的工具，不能临时拼命令行。
- **审批卡**：显示电脑、频率（含手机时间换算）、任务类型、命令或提示词全文、AI 任务的 agent、权限档位与完整命令行、结果交给哪位伙伴，以及“这次调用会使用电脑上 <agent> 登录的账号计费”。
- **同步**：`ProcessLifecycleOwner` 进入前台时，对有任务的主机做同步，同一主机 5 分钟内不重复；任务页支持下拉刷新。连不上时只记录状态，不弹错。
- **回应策略**（每个任务可设）：
  - 只记录：时间线折叠事件卡，不调用模型。
  - 失败时让伙伴看看（命令任务默认）。
  - 每次都让伙伴汇报（AI 任务默认，AI 的最终回复作为伙伴的材料）。
  一次同步回来多条运行记录时，同一伙伴合并成一次回应，避免离开几天后被刷屏、重复消耗 token。
- **入口**：轻松模式在「我的电脑 → 电脑详情」（`ComputerDetailPage`）新增“定时任务”区块，伙伴资料页列出该伙伴名下的任务；专业模式放在工作区详情，复用同一组件。

## 用户侧提示

结果只在打开 App 时同步，以及 Windows 暂不支持，这两点都必须让用户在需要时看到，而不是等出了问题才知道。

### 结果同步时机

- 定时任务区块顶部常驻一行说明：“任务在电脑上按时运行，结果会在你打开 Miffan 时同步过来。”
- 审批卡底部同样写一句，让用户在批准前就知道不会实时收到。
- 事件卡同时显示运行时间和同步时间，例如“09:00 运行 · 10:32 同步”，不把迟到的结果伪装成刚发生。
- 打开 App 时如果同步到离开期间的运行记录，在相应聊天顶部显示一次汇总提示：“你不在时，电脑跑了 6 次任务，1 次失败”，点击跳到失败那条。
- 连不上电脑时，任务区块显示“暂时连不上这台电脑，上次同步于 昨天 21:14；任务仍会在电脑上照常运行”。
- 失败无法即时提醒：在“失败时让伙伴看看”的选项说明里写明“打开 Miffan 后才会看到”。

### 平台与运行条件

- **Windows 电脑**：任务区块不隐藏，显示“Windows 电脑暂不支持定时任务”，附一句原因（Windows 计划任务在未登录时运行需要额外的账户权限，还在适配）。伙伴通过工具错误得知同样的信息并转述，不会假装创建成功。
- **Linux 未开启 linger**：显示“只在你登录这台电脑时运行”，并提供“允许退出登录后继续运行”的操作（执行 `loginctl enable-linger`，失败时给出用户需要自己运行的命令）。
- **macOS**：显示“电脑睡眠时不会运行，醒来后补跑一次”；如果 P1 验证到 AI 任务需要用户已登录，则 AI 任务另加“需要你已登录这台 Mac”。
- **AI 任务**：任务详情显示所用 agent、版本和权限级别；agent 被卸载或升级后路径失效时，事件卡写明“电脑上找不到 <工具名>”，并提供重新检测的入口。
- **没有可用定时器**（极少数既无 systemd 用户实例也无 crontab 的系统）：说明原因，不提供创建入口。

## 分期

### P1 helper

`task` 子命令、systemd / launchd / cron 三种后端、执行器、`task which`。在 CachyOS 与 Mac 上实测：开机与睡眠补跑、linger、三个内置预设的非交互参数与权限档位、macOS 钥匙串访问。补充 PROTOCOL.md 与 fixtures。

验收：两台机器上命令任务和 AI 任务都按时运行；重启后仍在；重叠运行被跳过；超时被终止；`task remove` 不留下 timer、plist 或 crontab 行。

### P2 App 管理

数据表、Agent 工具定义（预设 + 自定义）与渲染、`schedule_task` 工具与审批卡（含试跑）、电脑详情的任务区块、Windows 与 linger 等平台提示。

验收：伙伴在模拟器上为 CachyOS 创建命令任务与 AI 任务，审批卡信息完整，试跑结果回到聊天；Windows 主机上入口显示不支持说明，伙伴能正确转述。

### P3 结果回流

前台同步与节流、事件卡、按策略合并触发伙伴、离开期间汇总提示、连不上时的状态。

验收：App 关闭期间任务运行多次，打开后一次同步到位、按策略合并回应；事件卡时间正确；断网时提示得当。

### 以后

Windows（`Register-ScheduledTask`，处理未登录运行的权限）；推送通道；AI 任务的“完全放开”权限级别。
