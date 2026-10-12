# P7a：Windows 命令层与工作区

日期：2026-10-12。分支：`feature/p7a-windows-shell`。

## 实现

- `RemotePlatform` 使用 SFTP `realpath .` 的返回值识别平台：`/X:/...` 为
  `windows`，其它为 `posix`。每次新 SSH 会话在执行任何 shell 命令之前识别，
  不运行失败的 POSIX 探测。会话同时暴露 SFTP 主目录。
- `remote_hosts.platform` 默认为 `posix`；Room 36→37 手动迁移已注册，包含
  导出的 37 schema。保存检测结果的 DAO 更新限定 `connection_revision`，
  避免旧连接把平台写到已修改的 SSH 身份。连接测试、工作区连接和主目录读取均保存结果。
- `RemoteShell` 集中生成 exec 和 POSIX PTY 命令。Windows 的 argv 只有
  `powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand`
  加固定引导代码的 UTF-16LE Base64；目录、用户脚本、stdin 数据均不进入 argv。
- 请求帧为 `目录 UTF-8 字节数\n脚本 UTF-8 字节数\n目录字节脚本字节原始 stdin`。
  目录和脚本各限 1 MiB；原始 stdin 通过流传送，不要求读入内存，也不会被当成脚本。
  引导从二进制 stdin 精确读取帧，随后设置 UTF-8 的 `Console.In` 读取器。
  文本数据可用 `[Console]::In.ReadToEnd()`；二进制数据可用
  `[Console]::OpenStandardInput()`。原生命令的管道输入需显式传送，例如
  `[Console]::In.ReadToEnd() | native.exe`。不能把 POSIX `cat > file` 当作上传脚本；
  Windows helper 应采用 PowerShell/.NET 或 SFTP 写入。
- 引导关闭进度输出，设置 InputEncoding、OutputEncoding 和 `$OutputEncoding`，
  用 `Set-Location -LiteralPath` 设置工作目录，然后同步
  `[Environment]::CurrentDirectory = (Get-Location).ProviderPath`。
  **实机发现只设置 Set-Location 时，.NET 的相对文件路径仍基于 SSH 登录主目录**。
  独立回归测试 `dotNetWorkingDirectoryMatchesPowerShellInChineseDirectory` 在中文、
  空格和单引号目录中比较 .NET cwd 与 PowerShell provider 路径；测试中的 .NET 文件
  写入全部使用明确的绝对路径。
- 显式 `exit`、最后一个原生命令的退出码、PowerShell 非终止错误和异常均返回可检测
  的进程状态。原生命令写 stderr 但退出 0 时不会被误判为失败。
  stdout/stderr 分开限流，超时返回 `exitCode=-1`、`timedOut=true`，保持原执行层语义。
  错误记录转为文本；`PowerShellOutput` 解码 CLIXML 的字符串转义、忽略进度元数据，
  支持序列化头与直接 Console.Error 文本混合的实际输出。
- 文件路径继续使用 `/C:/...`；`WindowsRemotePath` 仅在命令层转换为 `C:\...`。
  `cwd` 接受工作区相对路径、虚拟 `/workspace`、SFTP 绝对路径和工作区内 Windows
  绝对路径。盘符匹配忽略大小写，仍拒绝越出所选根的路径。
- Windows 主目录直接取 SFTP 的 `realpath .`；目录可用性使用连接时的 SFTP 验证，
  不再执行 `printf "$HOME"` 或 `pwd`。POSIX 仍执行原命令。
- 文件列举、读写、编辑、重命名和删除继续使用 SFTP；Windows 覆盖写入跳过 chmod。
  中途上传失败实测会让原 SFTP 通道残留未读回复及文件句柄，导致原有同通道清理失败。
  现在先关闭失败通道，再通过新的 SFTP 通道安全删除 staging；原文件及备份恢复机制保留。
- Windows `workspace_shell` 直接调用 `executeCommand`，不创建 PTY；
  `RemoteConversationShells` 也有单次执行分支，供其它调用方使用。
  工具描述注明 Windows PowerShell 5.1、每次独立进程和 PowerShell 示例。
  返回包含 `platform=windows`、`shell=Windows PowerShell 5.1`、`statePersists=false`、
  Windows 形式的 `cwd` 和 `outputType=separate_stdout_stderr`。
  Windows 工具列表不提供 `workspace_terminal`。

## Claude 需要接入的 UI / 字符串

未改动 `ui/` 下文件或字符串资源。

- `RemoteHostEntity.platform` 可用于平台图标；值为 `posix` / `windows`。
  这是命令平台，与原来的桌面平台 `screenPlatform` 独立。
- `RemoteHostEntity.supportsInteractiveTerminal` 和
  `WorkspaceRepository.supportsInteractiveTerminal(workspaceId)` 暴露终端能力；
  `RemoteWorkspaceSession.supportsInteractiveTerminal` 是实际连接的能力。
  Windows 的终端页入口可隐藏/禁用，提示“Windows 暂不支持交互式终端，请使用伙伴的
  PowerShell 命令”。强行打开会抛出 `RemoteTerminalUnsupportedException`。
- `RemoteHostEntity.supportsRemoteScreen` 在 Windows 返回 false。
  `RemoteScreenRepository.open` 及 helper 安装、探测和其它屏幕路径会抛出
  `WindowsRemoteScreenUnsupportedException`，不会执行 bash 或 `uname`。
  需要 UI 提示“Windows 屏幕和电脑操作暂不支持”。P7b/P7c 接入时应明确替换此能力
  标志与实际会话保护；辅助脚本未在 P7a 实现。
- `executeInConversationTerminal` 的 Windows 返回 `sessionOpen=false`、`reused=false`，
  不创建或展示持久会话；直接交互调用（无超时）明确拒绝。

## 验证

本机 CachyOS 测试机离线，POSIX 通过单元测试验证：带中文、空格和单引号路径的
exec、普通 PTY、持久 PTY、单命令 PTY 包装逐字节等于改动前字符串；本机 SSH/SFTP
假服务器继续验证 POSIX 执行、退出码、超时及文件操作。新增故障注入测试在原文件
已移到备份后拒绝 staging 提交，检查原文件恢复且无临时文件残留。

Windows 集成测试只有指定 properties 文件存在时才运行；默认位置为当前 checkout 的
`.deps/windows-spike/p7-test.properties`，可通过 `-Pp7aTestProperties` 指定实际位置：

```bash
./gradlew :workspace:testDebugUnitTest :app:testDebugUnitTest assembleDebug \
  -Pp7aTestProperties=/Users/chenxiansheng/miffan/.deps/windows-spike/p7-test.properties
```

私钥只把路径交给 JSch，测试代码不读取或输出私钥。properties 及私钥未复制到 worktree。
构建只引用主 checkout 的三个非秘密 Moonlight 源码归档，以及 FreeRDP/OpenSSL
预编译库；未复制 signing 配置或 `local.properties`。

Windows 检查范围：平台和主目录；中文 stdout、stderr、UTF-8 文本 stdin、二进制 stdin；
显式退出 7、原生命令退出 9、错误与异常；脚本已输出 started 后的超时、输出截断；
中文/空格/单引号 cwd 与 .NET cwd；终端拒绝；SFTP 列举、读、写、编辑、重命名、删除；
中断上传与 Windows 文件锁导致覆盖失败时的原文件保留和 staging 清理。
每次用 `C:\Users\Ekk\miffan-p7a-test\run-<UUID>`，结束后删除自己的子目录，
只删除空的测试父目录，不删除其它并发测试文件。最终通过只读的精确路径存在性检查确认
`C:\Users\Ekk\miffan-p7a-test` 已不存在。

最终验收命令成功（`BUILD SUCCESSFUL`）：

| 检查 | 结果 |
| --- | --- |
| `:workspace:testDebugUnitTest` | 187 项，0 失败；6 项既有、需要其它实机的测试跳过 |
| `:app:testDebugUnitTest` | 872 项，0 失败、0 跳过 |
| Windows 集成 | 两项均实际执行，0 失败、0 跳过 |
| `assembleDebug` | 通过，生成 arm64-v8a、x86_64 和 universal Debug APK |
| `git diff --check` | 通过 |

未运行 Android 真机 UI 测试；本阶段不改 UI。

### 测试期间的 cwd 问题核查

初次锁测试误用 .NET 相对路径，失败后可能产生登录主目录的 `lock-ready` 标记。
按用户额外授权，在 2026-10-12 10:09 UTC 只读核查精确路径
`C:\Users\Ekk\lock-ready`，返回 `exists=false`。因此未删除任何目录外文件，也未列目录
或读取其它用户文件。之后同步 .NET cwd，并将所有测试中的 .NET 写入改为绝对路径。

## 已知限制

- Windows 交互终端/持久 PTY、屏幕、Sunshine 和 cua 接入留给后续阶段。
- PowerShell 对象的普通输出是文本；需要机器读取的结果应由脚本显式 `ConvertTo-Json`。
  原生程序自行选择的 CP936/UTF-16 等输出编码不一定受 Console UTF-8 设置控制，
  需要各程序自己的 UTF-8 参数或显式解码。
- 第一版只支持 OpenSSH 的盘符 SFTP 路径，不承诺 UNC、跨驱动器工作区、junction 和
  reparse point 的全部行为；文件层仍按原有规则拒绝检测到的符号链接。
- 超时/取消关闭 SSH channel，不能保证已经启动的远端进程立即结束；不自动重试命令。
  SSH 会话断开或取消时 staging 清理仍可能不可完成，需按未知结果处理。
- 未修改 OpenSSH 配置、默认 shell、代码页、防火墙或注册表，未启动 GUI 程序。
