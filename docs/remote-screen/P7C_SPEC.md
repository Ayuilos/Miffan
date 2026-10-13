# P7c：Windows 上的伙伴操作（cua 桥）（Codex 开发任务）

先读：
- `docs/remote-screen/P7_WINDOWS_PLAN.md`：第 5 节，以及“已确定的决策”第 1 条：**方案 A，普通权限 daemon 加普通权限的桥**。
- `docs/remote-screen/research/WINDOWS_SPIKE.md`：B 部分，包括权限对照、命名管道、两种方案的安全差异，以及“后台 Ctrl+V 只输入 v”“记事本恢复历史文档”这两个发现。
- `docs/remote-screen/P7A_NOTES.md`、`P7B_NOTES.md`、`P7B2_NOTES.md`：Windows 执行层、`miffan.ps1` 的约定、屏幕接入。
- 现有的伙伴操作实现：`app/src/main/assets/remote/miffan.sh` 的 `cua` 命令、`RemoteScreenRepository.openCuaProcess` / `installCuaDriver`、`data/ai/computer/RemoteComputerRegistry.kt`、`data/ai/tools/ComputerTools.kt`。

## 问题

SSH 会话是高完整性级别的管理员令牌。cua-driver 的 daemon 以普通权限运行在用户的桌面会话（Session 1），它会读取客户端的令牌；高权限的客户端直连时读取失败，daemon 就断开连接（实验 B2 的 os error 233）。用户已决定**不提升 daemon 的权限**。

## 目标

伙伴（AI）可以在 Windows 上通过 cua-driver 操作用户的真实桌面，体验和 Linux、Mac 一致：`computer_start` 之后按需加载工具、控制权仲裁、审批卡片、操作摘要。

## 范围

1. **桥**：运行在用户 Session 1 里的普通权限进程，负责在 SSH 会话和 cua-driver daemon 之间转发 MCP stdio。
   - 由 `miffan.ps1` 注册为普通权限、交互式登录的计划任务，名字以 `Miffan-` 开头；按需启动，空闲一段时间后自己退出。
   - 桥创建只允许当前用户 SID 访问的命名管道，并拒绝远程客户端（`PIPE_REJECT_REMOTE_CLIENTS`）。每次连接使用一次性令牌，令牌通过只有该用户能读的文件传递。桥自己以普通权限连接 `\\.\pipe\cua-driver`，因为同为普通权限，daemon 能读到它的令牌。
   - SSH 这一侧用 `miffan.ps1 cua` 连接桥的管道，原样转发 stdin / stdout。stdin、stdout 都要显式使用 UTF-8，实验中 CP936 曾把 JSON 弄坏。
   - 实验中用过的“文件桥”只是诊断手段，不要用到产品里。
2. **App 接入**：
   - `openCuaProcess` 在 Windows 上通过 PowerShell 执行层启动 `miffan.ps1 cua`，返回长连接的 stdio。P7a 的 `openProcess` 目前只支持 POSIX 命令，需要一个 Windows 版本：引导代码读完请求帧后，交出原始的 stdin / stdout。
   - 去掉 cua 路径上的 `WINDOWS_NOT_SUPPORTED` 拒绝（`openCuaProcess`、`installCuaDriver`、环境检查），`pressMacShortcut` 仍然只用于 Mac。
   - `RemoteComputerRegistry`：Windows 的工具参数和 Linux、Mac 有差异（见实验 B2 的表格），按远端返回的 schema 处理；引导资源（skill）也按远端提供的为准。
3. **安装与状态**：
   - `miffan.ps1 cua status` 报告 cua-driver 是否安装、版本、自启动任务的 RunLevel、daemon 所在会话、桥是否可用。
   - `miffan.ps1 cua install` 用官方 `install.ps1` 安装固定版本（最低 0.34.0），按用户安装。**自启动任务必须注册为普通权限**：官方安装器默认会注册最高权限，要显式覆盖。如果发现已有任务是最高权限，要在状态里报告出来，不要自行降级，由 App 提示用户。
   - App 里现有的“安装 / 升级 cua-driver”确认流程在 Windows 上同样适用。
4. **剪贴板加速**：桥可用时，`miffan.ps1 clip` 改走桥，用 cua-driver 的 `clipboard_write` 写入，以减少屏幕页打字的延迟（现在每次都要建一个一次性计划任务，大约 3 秒）。桥不可用时退回现在的做法。
5. **伙伴指引**：Windows 版的提示词或指引要补充三点，写在现有注入指引的位置，不要另起一套：
   - 粘贴需要前台模式：后台模式下的 Ctrl+V 只会输入一个 `v`。输入中文优先用 `type_text`。
   - 打开应用时不要假设会得到空白窗口，Windows 11 的记事本会恢复上次的文档；只读取任务需要的窗口内容。
   - 锁屏、UAC 安全桌面上的内容无法读取，也无法操作，遇到时停下来，告诉用户需要做什么。

## 不做

- 不提升 daemon 的权限，不放宽 cua-driver 命名管道的 ACL，不改 cua-driver 的权限配置。
- 不改 `ui/` 下的 Compose 文件和 ViewModel。需要的状态和文案写进笔记。
- 不改 OpenSSH、RDP、防火墙。

## 测试机

- 连接信息在 `/Users/chenxiansheng/miffan/.deps/windows-spike/p7-test.properties`，其中含测试专用私钥的路径。**不要读取或输出私钥内容。**
- 测试机上已装好 cua-driver 0.34，`cua-driver-serve` 任务为普通权限、交互式登录，daemon 运行在 Session 1。实验时的 TEMP 包装脚本和 XML 备份放在 `%TEMP%\miffan-windows-spike\`，不要删除。
- 允许的操作：
  - 注册和删除 `Miffan-` 开头的任务；
  - 安装和升级 `%LOCALAPPDATA%\Miffan\bin\miffan.ps1`；
  - 用 cua-driver 只读地列出窗口和应用；
  - **只操作你自己打开的窗口**。打开应用前先确认不会恢复用户的历史文档：用 `%LOCALAPPDATA%\Miffan\p7c-tests\` 下的新文件，或者用“文件 → 新建窗口”。
- 每次写剪贴板、需要用户把窗口切到前台、需要前台模式操作时，先通知 Claude。
- 端到端测试在模拟器 `emulator-5560` 上做（AVD `RikkaHub_API_35_16K`，不要碰 `emulator-5554`），方式参照 `tools/remote-screen/p7b2-emulator.py`：测试私钥临时导入、用完删除；SSH 走 Mac 本机的转发。用真实模型的端到端测试不是必须的：用测试代码直接调用 `RemoteComputerRegistry` 的工具，验证 `list_windows`、截图、`type_text`（中英文）、`click`、`clipboard_write` 即可。

## 验收

- `miffan.ps1` 的离线测试（`tools/windows-helper-tests/Test-Helper.ps1`）补上桥和 cua 命令的用例，并全部通过。
- 测试机上：
  - 桥能启动，SSH 端能完成 `initialize` 和 `tools/list`；
  - 空闲后桥自动退出；
  - 用其他用户名或远程客户端连接桥的管道会被拒绝（能测就测，测不了就在笔记里说明依据）。
- 模拟器端到端：通过 App 的 `RemoteComputerRegistry` 在你自己打开的记事本里完成上面列的工具调用，截图核对。
- `./gradlew :app:testDebugUnitTest assembleDebug` 通过。
- 写 `docs/remote-screen/P7C_NOTES.md`：设计、安全边界、测试结果、需要 Claude 接的 UI、已知限制。

## 交付与沟通

- 在本 worktree 的 `feature/p7c-windows-cua` 分支提交，不要合并到 `feature/im-4.0`。
- 通知 Claude：先 `~/.local/bin/herdr pane send-text wJ:p1 "消息"`，**再** `~/.local/bin/herdr pane send-keys wJ:p1 enter`。
