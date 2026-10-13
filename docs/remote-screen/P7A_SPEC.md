# P7a：Windows 命令层与工作区（Codex 开发任务）

先读：`docs/remote-screen/P7_WINDOWS_PLAN.md`（已确定的决策）、`docs/remote-screen/research/WINDOWS_SPIKE.md` 的 C 部分（命令层实测，含 POSIX 依赖清单和文件行号）。

## 目标

让 Windows 主机能作为远程工作区：连接成功，文件工具可用，伙伴能执行 PowerShell 命令。Linux / macOS 的行为保持不变。

## 范围

1. **平台识别**：远程主机增加平台字段（`posix` / `windows`），连接时自动识别并保存（Room 迁移，当前 DB 版本 36）。建议的识别办法：SFTP `realpath .` 返回 `/X:/...` 形式即为 Windows；其余情况仍按 POSIX 处理。识别不能依赖“先跑一条 POSIX 命令看是否失败”的副作用。
2. **执行层**：`NativeSshWorkspaceTransport.execute` / `openTerminal` 和 `RemoteTerminalCommandSpec` 按平台分别生成命令。Windows 实现的要求：
   - Windows OpenSSH 的默认 shell 是 cmd，**不修改**它。
   - 用 `powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand <固定引导代码>`。引导代码先设 UTF-8 的 InputEncoding、OutputEncoding 和 `$OutputEncoding`，并关闭进度输出，再从 stdin 读取帧化的请求：工作目录、用户脚本、可选的原始 stdin 数据。然后切换目录、执行脚本，把脚本的退出码作为进程退出码。
   - 用户脚本和数据只走 stdin，不进命令行参数。实测直接用 `-Command -` 时，中文会按 CP936 解码而损坏。
   - stdout 和 stderr 分开返回；超时、输出截断与 POSIX 一致。PowerShell 的错误流和 CLIXML 要转成可读文本。
   - 原有 `stdin` 参数（例如 `cat > file` 这类上传用法）在 Windows 上要有等价的语义，或者明确拒绝。调用方见 `RemoteScreenRepository.ensureHelper`，它在 P7b 会改用 Windows 的方式，这里只需保证不会静默出错。
3. **路径**：SFTP 路径是 `/C:/Users/Ekk` 形式，PowerShell 的本地路径是 `C:\Users\Ekk`。`RemoteWorkspacePath` 继续使用 SFTP 形式；只在交给 PowerShell 的那一层做转换。要覆盖：盘符大小写、中文、空格、单引号。
4. **工作区**：`WorkspaceRepository.remoteHome`（`:555`）和目录检查（`:613`）在 Windows 上改用 SFTP 或 PowerShell 实现。`remoteHome` 现在要求路径以 `/` 开头，Windows 返回 SFTP 形式的 `/C:/Users/Ekk` 即可满足。
5. **文件工具**：继续走 SFTP，`chmod` 在 Windows 上跳过（`NativeSshWorkspaceTransport.kt:459`）。要实测列目录、读、写、编辑、删除、重命名，包括中文和带空格的文件名，以及写入失败时的回滚。
6. **伙伴的终端命令**：Windows 上，伙伴的命令工具（`WorkspaceTools.kt` 中的命令执行，以及 `RemoteConversationShells` 的持久 shell）改为**每次单独执行**（`executeCommand` 路径），不使用持久 PTY 协议。工具描述和返回信息要让模型知道这是 Windows PowerShell 5.1：示例用 PowerShell 语法，路径用 Windows 形式。“交给用户在交互终端里运行”的工具（`WorkspaceTools.kt:116`）在 Windows 上先不提供，或返回明确的“暂不支持”。
7. **终端页**：Windows 主机暂不支持交互式终端。只在数据层暴露“该工作区不支持终端”的能力标志，并让打开终端的入口抛出明确的异常；界面怎么显示由 Claude 负责，不要改 UI 文件。
8. **远程屏幕**：本阶段不做。确保 Windows 主机上的屏幕入口在数据层返回明确的“暂不支持”，不要执行 bash 命令（`RemoteScreenRepository.kt:375`、`:382`）。

## 不做

- 不改 Compose UI 文件（`ui/` 下），需要的字符串或状态通过数据层暴露，在笔记里写清楚。
- 不实现 `miffan.ps1`、Sunshine、cua，这些属于 P7b / P7c。
- 不改 Windows 上的 OpenSSH 配置、防火墙、注册表。

## 测试机

- 连接信息在 `/Users/chenxiansheng/miffan/.deps/windows-spike/p7-test.properties`（host、port、user，以及测试专用私钥 `key` 的路径）。**不要读取或输出私钥内容**，只把路径交给 SSH 或 JSch。
- Windows 11 专业版，账号 `ekk` 是管理员，登录后是提升权限的令牌，默认 shell 是 cmd，代码页 936。
- 用户可能正在使用这台电脑。测试只能在 `C:\Users\Ekk\miffan-p7a-test\` 下建文件和目录，测完删除。不要碰其他目录，不要启动 GUI 程序。

## 验收

- 单元测试：引导代码与请求帧的编码、路径转换、平台识别、退出码和超时，跑 `./gradlew :workspace:testDebugUnitTest :app:testDebugUnitTest`。
- 对测试机的集成测试（只有 `.deps/windows-spike/p7-test.properties` 存在时才运行，参照 `rdp/src/androidTest` 的做法），覆盖：
  - 平台识别；
  - 主目录；
  - `executeCommand`：中文 stdout 与 stdin、退出码、超时、工作目录里有中文和空格；
  - SFTP 读写中文和带空格的文件名。
- Linux 回归：CachyOS 测试机目前离线，所以要用单元测试证明 POSIX 生成的命令字符串和改动前逐字节一致。
- `./gradlew assembleDebug` 通过。
- 在 `docs/remote-screen/P7A_NOTES.md` 写实现说明：设计、测试结果、需要 Claude 做的 UI 和字符串、已知限制。

## 交付

在本 worktree 的 `feature/p7a-windows-shell` 分支提交，不要合并到 `feature/im-4.0`。完成或遇到需要决定的问题时，用 `herdr pane send-text wJ:p1 "..."` 通知 Claude。
