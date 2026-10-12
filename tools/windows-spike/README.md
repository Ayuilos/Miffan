# Windows spike 实验脚本

仅用于 `docs/remote-screen/WINDOWS_SPIKE_SPEC.md` 指定测试机。不是 App helper。
第一轮防火墙暂停已经由用户解除；第二轮因 Mac 与 emulator-5560 均 UDP 握手
失败再次暂停。已通知 Claude `wJ:p1` 安排 Tailscale。不要自行改规则或继续远端测试。

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
- `cua-mcp.py`：直接高权限 SSH → daemon MCP；本机试验失败 error 233。
- `start-cua-bridge.ps1` / `cua-session-bridge.ps1` / `cua-file-mcp.py`：
  临时普通用户 MCP 客户端桥，仅作定位权限边界。已完成握手/tools/list；
  首轮 list_apps 因子进程 stdout 按 CP936 解码使 JSON 损坏。
  显式 UTF-8 的子进程读写修正已保存，**尚未恢复后实测**。
- `cleanup-cua-bridge.ps1`：只移除临时桥任务，保留 daemon 自启动任务。
- `emulator-receive.py`：复用已有 receive-only APK，临时替换测试身份并仅对
  fixture 固定目的地址做模拟器 NAT 映射；备份在授权 .deps 目录，finally 恢复。
  已验证恢复后规则结构与测试 prefs；不改变 Windows 防火墙。

测试包、主机/客户端身份和版本必须逐项核对后才能重跑这些脚本。安装器返回的
`$LASTEXITCODE` 可能来自内部 schtasks 查询，不能作为整个 PowerShell 脚本的退出码。

示例（恢复授权后）：

```sh
python3 tools/windows-spike/ssh_ps.py tools/windows-spike/baseline.ps1
python3 tools/windows-spike/command-probe.py
```

安装和下载脚本不能当作默认检查运行。全部输出不应包含测试凭据。
