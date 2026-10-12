# Windows spike 实验脚本

仅用于 `docs/remote-screen/WINDOWS_SPIKE_SPEC.md` 指定测试机。不是 App helper。
当前已因安装器新增对外 TCP Allow 规则暂停。不要重新安装或继续远端操作，
先等 Claude `wJ:p1` 与用户处理防火墙并通知恢复。

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
  `encoded-utf8-stdin-exit` 是初次结果后增加的待验证用例，尚未远端运行。

示例（恢复授权后）：

```sh
python3 tools/windows-spike/ssh_ps.py tools/windows-spike/baseline.ps1
python3 tools/windows-spike/command-probe.py
```

安装和下载脚本不能当作默认检查运行。全部输出不应包含测试凭据。
