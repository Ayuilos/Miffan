# Windows spike 实验脚本

仅用于 `docs/remote-screen/WINDOWS_SPIKE_SPEC.md` 指定测试机。不是 App helper。
第三轮已通过 Tailscale 严格加密 H.264/HEVC 与桌面操作。当前因 Sunshine 原生输入前的
独立记事本焦点租约 refused 暂停，已通知 Claude `wJ:p1`；等待用户激活实验窗口再恢复。
不要自行改规则、处理系统许可提示或向当前用户窗口发送输入。

- `ssh_ps.py`：UTF-16LE EncodedCommand，设置 UTF-8 stdout，保留 stdin。
  **禁止传入密码、PIN、配对/会话密钥**，EncodedCommand 只是编码，不是加密。
- `baseline.ps1`：只读采集 Session、GPU、软件、相关防火墙规则。
- `prepare-sunshine.ps1`：保存防火墙基线，再下载官方 latest stable MSI。
  基线文件存在时拒绝重跑。全量 CIM 导出较慢，应给 runner 至少 480 秒；
  runner 超时不保证远端进程结束，必须检查，不能直接重复。
- `download-status.ps1`：只读查看本实验临时目录文件和 PowerShell 进程信息。
- `install-sunshine.ps1`：固定此次已校验 MSI SHA-256，静默、不重启；安装完成
  第一时间检查 Sunshine 规则，然后返回。不会自行修改规则；出现需要用户点击
  或宽泛 TCP Allow 时，控制者必须停下并通知 Claude。
- `command-probe.py`：只读 exec、stdin、退出码、SFTP 路径验证。
  `encoded-utf8-stdin-exit` 已在第二轮验证，中文 stdin 与 exit 9 正常。

续测脚本：

- `sunshine-status.ps1`：只读 Sunshine/规则/公开证书/编码器诊断。
- `enforce-encryption.ps1`：无流检查、备份、设 2/2、仅重启 Sunshine 服务。
- `sunshine-admin.py`：固定 DER 指纹的 API，创建管理员/批准本测试配对；
  随机密码只放授权目录，PIN 只在内存。需要 57990 的 SSH 转发。
- `install-cua.ps1`：官方安装脚本、临时进程级代理、按用户安装，不注册 Highest 任务。
- `register-cua.ps1`：以实际 SID 注册 Limited/Interactive 按用户 daemon 任务。
- `cua-status.ps1` / `cua-diagnostics.ps1`：版本、Session、任务、管道诊断。
- `cua-mcp.py`：直接高权限 SSH → daemon MCP；Medium daemon 时 error 233，
  Highest/Session 1 隐藏启动包装后成功。`--out` 选证据目录，`--redact-window`
  对恢复历史窗口禁截图/剥离编辑值，不能把它当作对任意用户窗口的隔离许可。
- `start-cua-bridge.ps1` / `cua-session-bridge.ps1` / `cua-file-mcp.py`：
  临时普通用户 MCP 客户端桥，仅作定位权限边界。已完成握手/tools/list；
  首轮 list_apps 因子进程 stdout 按 CP936 解码使 JSON 损坏。
  第三轮显式 UTF-8 复验 list_apps=234/list_windows=11、中文正常；桥已经清理。
- `cleanup-cua-bridge.ps1`：只移除临时桥任务，保留 daemon 自启动任务。
- `emulator-receive.py`：复用已有 receive-only APK，临时替换测试身份并仅对
  fixture 固定目的地址做模拟器 NAT 映射；备份在授权 .deps 目录，finally 恢复。
  已验证恢复后规则结构与测试 prefs；不改变 Windows 防火墙。

第三轮脚本与已验证范围：

- `prepare-cua-bridge-resume.ps1`：续测前备份旧桥目录/脚本，拒绝覆盖仍在运行的桥。
- `cua-token-status.ps1`：实际完整性 RID 和 Session；Session 0 的 GetForegroundWindow=0
  不能作为用户前台证明。
- `elevate-cua-task.ps1`：先导出完整 Limited XML/哈希，再设同用户 Interactive/Highest。
- `start-highest-logged.ps1` / `cua-logged-daemon.ps1`：再次导出 XML，再改为隐藏日志包装；
  此组合已通过 SSH MCP/B3。裸 Highest 任务退出 1，原因未定。
- `cua-task-events.ps1` / `read-highest-logs.ps1`：只读任务/实验日志；不启用系统事件记录。
- `restore-cua-task.ps1`：默认只验证 Limited XML，显式 `-Apply` 才恢复并先备份当前任务；
  未执行远端回滚。停止任务后应核对残留的精确实验 daemon PID，不能泛杀 cua 进程。
- `cua-window-metrics.ps1`：只读实验 Notepad DPI/屏幕几何，须在 Session 1 执行；
  本机结果 96 DPI / 单屏 2560×1440，没有修改显示设置。
- `sunshine-media-diagnostics.ps1`：配置媒体键、实际会话编码器/音频日志/设备状态；不播放。
- `cua-input-target.ps1` / `input-lease-check.py`：短时实验 Notepad 前台租约，恢复前台只在
  当前仍为实验窗口时尝试；校验 PID/HWND/Session/状态新鲜度，失效则拒绝输入。
- `build-notepad-probe.py`：将正常 p6a 源码复制到 /private/tmp 后替换 opt-in input-test，
  每个事件前检查租约。只适用于本次 PID 24040/HWND 788388、2560×1440/96 DPI，
  不发送 Win 快捷键；构建不改 p6a 源码/正常二进制。首次租约 refused，未发输入，
  不能声称原生 VK/坐标通过。必须从本 worktree 根运行，重用前重新核验目标/坐标。

测试包、主机/客户端身份和版本必须逐项核对后才能重跑这些脚本。安装器返回的
`$LASTEXITCODE` 可能来自内部 schtasks 查询，不能作为整个 PowerShell 脚本的退出码。

示例（恢复授权后）：

```sh
python3 tools/windows-spike/ssh_ps.py tools/windows-spike/baseline.ps1
python3 tools/windows-spike/command-probe.py
```

安装和下载脚本不能当作默认检查运行。全部输出不应包含测试凭据。
