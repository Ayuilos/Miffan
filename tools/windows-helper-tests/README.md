# P7b Windows helper tests

只测试 `miffan.ps1`，不接入 App。运行环境：Windows PowerShell **5.1**。
helper 和 PowerShell 测试文件均为 **UTF-8 with BOM**。公开的 `certificate.pem`
是离线 X509 夹具，私钥未保存；`certificate.sha256` 是其 DER SHA-256。

## 离线测试（Windows）

从仓库根目录运行：

```powershell
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File tools\windows-helper-tests\Test-Helper.ps1
```

也可显式传入 helper 路径：

```powershell
& .\Test-Helper.ps1 -HelperPath .\miffan.ps1
```

不依赖 Pester。测试读取公开夹具、在随机临时目录操作模拟配置/ACL、导入一个
仅返回标记文本的临时模拟模块；服务、计划任务及防火墙修改全部模拟。
防火墙测试替换 **内部 `Invoke-FirewallMutations` 边界**，不以覆盖
`New-NetFirewallRule` 等同名函数作为防写保证：NetSecurity 自动导入可覆盖它们。
包括 ActiveStore/PersistentStore 不一致、空计划零写入、计划不一致零写入的回归。
真实 mutation adapter 使用模块限定名；离线测试不会调用该 adapter。

## 规格测试机（Mac → Windows）

`remote.py` 默认读取规格指定的 `p7-test.properties`，仅把 `key` 的路径交给 SSH/SFTP，
不读取私钥内容。要求现有可信主机 key；`StrictHostKeyChecking=yes`，不会接受新 key。
固定 EncodedCommand 只负责 UTF-8，引导脚本、参数和数据走 stdin。测试传输用一行
JSON 脚本头，后面为原始 UTF-8 stdin；helper 保留已有 UTF-8 reader，避免丢失缓冲数据。
上传使用 SFTP，避免把整个脚本 Base64 放进大 stdin 包。

```sh
# 上传四个脚本/公开夹具到 LOCALAPPDATA\Miffan\p7b-tests，再运行离线测试。
python3 tools/windows-helper-tests/stage.py

# 只读命令 + start/enforce 的无变化路径；从不运行 firewall apply。
python3 tools/windows-helper-tests/smoke.py --display --out /private/tmp/p7b-smoke.json
```

`smoke.py` 不运行任何 `firewall apply`，包括空变更。运行 start/enforce 前会确认
服务运行且加密为 2/2；状态变化时取消。`--display` 会创建一个只读、普通权限的
交互任务，只采集显示器几何和 DPI，用完即删，不读窗口标题/内容。

剪贴板测试必须**单独显式运行**，会覆盖用户当前剪贴板，不会恢复旧内容：

```sh
# 必须在 herdr pane 中运行；每次自动先通知 Claude，再发送 Enter，之后才写剪贴板。
python3 tools/windows-helper-tests/clip-smoke.py
```

该命令只写一次中英文/emoji/换行/tab/引号文本，Session 1 中立即读回比对，
输出只有成功/verified/session_id，不输出剪贴板内容或摘要，不发粘贴键、不打开应用。
基础 probe 不创建任务。正式调用约定与实测输出见
[交付笔记](../../docs/remote-screen/P7B_NOTES.md)。

## P7c 桥

helper v4 还覆盖桥 C# 编译、一次性凭据/重放/过期、普通权限任务与 daemon 权限审阅。
真实协议、连接闲置、模拟器控制器见 `tools/remote-screen/p7c-*.py`；
[交付笔记](../../docs/remote-screen/P7C_NOTES.md) 记录调用顺序、安全边界和验证范围。
`p7c-mcp.py` 只读；`p7c-idle.py` 保持连接但不操作桌面。
`p7c-owned.py` 打开全新测试文件前必须先通知 Claude；`p7c-emulator.py` 的输入模式
要求两次剪贴板写入分别已通知。每条通知均 send-text 后 send-keys enter。
