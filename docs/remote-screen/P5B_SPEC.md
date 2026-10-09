# P5b：把 RDP 接入 App（Codex 规格，非界面部分）

先读 `P5_RDP_PLAN.md`、`P5A_NOTES.md`。P5a 的 `rdp` 模块已经能在模拟器上连 KDE/GNOME。本阶段把它接进数据层、远端脚本和 CI；界面（屏幕页、设置对话框、VM、证书首次确认的展示）由 Claude 做，你只提供下面约定的接口。

## 用户已确定的决策

- GNOME（gnome-remote-desktop）无法只监听本机：使用**随机高位端口 + 32 位以上随机密码 + 强制 NLA**，并关闭端口协商。App 仍经 SSH 隧道连接。不加防火墙规则，不需要 sudo。
- KDE（krdp）只监听 `127.0.0.1`。
- GNOME 与 KDE 都走 RDP（包括 Arch 上带 VNC 的 gnome-remote-desktop），不维护两条路。wlroots/niri、X11、macOS 保持现有 VNC。
- 不在键盘布局里的字符（中文等）用剪贴板粘贴，与 VNC 现状一致。

## 你负责的文件

- `rdp/`（可修改 P5a 的代码）。
- `app/src/main/assets/remote/miffan.sh`（升 `MIFFAN_HELPER_VERSION`）。
- `app/src/main/java/me/ayuilos/miffan/data/`：`RemoteScreenRepository`、`RemoteHostEntity` 及迁移、`RemoteScreenCredentialStore`、新的会话抽象。
- `app/build.gradle.kts`（依赖 `:rdp`）、`.github/workflows/*.yml`、`rdp/scripts/`。
- 相关测试与 `docs/remote-screen/P5B_NOTES.md`。
- **不改** `app/src/main/java/me/ayuilos/miffan/ui/` 下的任何文件。需要界面配合的地方，在 notes 里写清楚接口和期望行为。
- 在 `feature/rdp-client` 上提交；不要合并、rebase 或推送其他分支。

## 1. 会话抽象

在 `app/.../data/repository/`（或 `workspace` 模块，由你判断）定义一个协议无关的接口，VNC 的 `RemoteScreenSession` 与 RDP 的 `RdpSession` 各有一个适配器：

```kotlin
interface RemoteDesktopSession : Closeable {
    val protocol: RemoteDesktopProtocol            // VNC / RDP
    val state: StateFlow<RemoteScreenState>
    val clipboard: SharedFlow<String>              // 远端复制的文本
    val bytesReceived: Long
    fun start(scope: CoroutineScope)
    fun setPaused(paused: Boolean)
    fun setMaxFps(fps: Int)                         // RDP 可以忽略
    fun pointer(x: Int, y: Int, buttons: Int)
    fun key(keysym: Int, down: Boolean)
    fun tapKey(keysym: Int)
    /** false 表示有字符无法直接输入，调用方应改用 [sendClipboard] + 粘贴快捷键。 */
    fun typeText(text: String): Boolean
    fun sendClipboard(text: String)
}
```

- `RemoteScreenConnection.session` 改为这个接口。现有 VNC 行为（包括性能统计 `stats` 与 `statsLogger`）不得退化；VNC 专有能力可以通过适配器上的可选属性暴露，由你设计，在 notes 中说明。
- RDP 的 `typeText`：只有当全部字符都能可靠输入时才发送并返回 true。判断规则：ASCII 可打印字符、Tab、换行可以直接走 Unicode 事件；其他字符返回 false，让上层走剪贴板。不能因为文本长而关闭会话：长文本分批或返回 false。
- RDP 剪贴板：接上 cliprdr 的 Unicode 文本双向读写（`sendClipboard` 设置远端剪贴板，远端复制时发到 `clipboard`）。限制长度（与 VNC 相同上限），处理取消与会话关闭。

## 2. 主机配置与凭据

- `RemoteHostEntity` 增加 RDP 所需字段，并写 Room 迁移（数据库版本 +1，附迁移测试）：协议选择（自动 / VNC / RDP）、RDP 证书 SHA-256 固定值。端口不入库，每次由远端脚本报告。
- 密码只存在 `RemoteScreenCredentialStore`（不进数据库、备份、日志、模型上下文）。RDP 用户名由脚本生成或固定，可以入库。
- 证书：首次连接没有固定值时，`RdpSession` 会暴露指纹。仓库层提供 `pinRdpCertificate(hostId, sha256)` 与“指纹变化”的明确错误类型（`RemoteScreenProblem` 增加一项），由界面让用户确认。**不得自动更新已固定的指纹**。首次连接是否需要用户确认由界面决定，仓库层只需在首次连接成功后返回指纹、未固定。
- “自动”端点（现在的 `Helper`）按远端脚本的探测结果选择协议：GNOME / KDE 会话 → RDP，其他 → 现有 VNC。

## 3. 远端脚本 `miffan.sh`

新增 `miffan rdp start`，输出一行 JSON：`{server, port, username, desktop, mode, error, log}`。要求：

- **密码不出现在任何进程的命令行参数里**（同机其他用户可通过 `/proc/*/cmdline` 看到），也不写入其他用户可读的文件。手机生成密码，经 SSH 的 stdin 传给脚本；脚本再经 stdin 或用户自己的钥匙串交给服务端。
- GNOME：使用 `grdctl`（真实会话用用户模式，无头会话用 `--headless`，由脚本判断）。证书与私钥放在 `~/.miffan/rdp/`（0700/0600），用 openssl 生成。`grdctl rdp set-credentials` 不带参数时从 stdin 读取，用这个方式传密码。随机高位端口、`disable-port-negotiation`、`disable-view-only`、启用 RDP，并启动对应的用户服务。用户模式下凭据存在 GNOME 钥匙串，钥匙串锁定时返回明确的错误，不要绕过。
- KDE：krdp 只监听 `127.0.0.1`，端口固定或随机由你决定。**不要用 `-u/-p` 传密码**。调查并采用 krdp 自己的用户配置方式（krdpserverrc + qtkeychain/KWallet，或 krdp 的系统用户 PAM 认证）；如果确实只有 `-p` 可用，先停下来在 notes 里写明各方案的取舍，不要上线这一种。顺便确认用了配置方式之后 NLA 是否可用。
- 证书文件、配置变化后，只重启脚本自己启动的那个服务实例；不要动用户已经在用的远程桌面配置之外的东西。如果用户自己已经配置并在运行 gnome-remote-desktop / krdp，脚本应检测到并报告，不要覆盖其凭据（返回一个明确的 `error`，让界面提示）。
- `miffan probe` 增加 `rdp` 字段：可用的服务端、版本、是否在运行。
- 用 `shellcheck`（如可用）检查脚本；保持 POSIX sh。

## 4. 连接

- `RemoteScreenRepository.open` 根据协议选择 VNC 或 RDP。RDP 走 `ssh.openLoopbackStream(port)`（P5a 已验证 4 MiB 窗口的流）。
- RDP 的 `RdpOptions` 宽高：先用远端报告的会话尺寸；无头会话（GNOME 会为每个客户端新建显示器）用 1920×1080。动态尺寸（disp）可以留到以后，在 notes 中说明。

## 5. CI 与构建

- `app` 依赖 `:rdp` 之后，所有 assemble/test/lint 都需要原生产物。在 `ci.yml`、`daily-build.yml`、`release.yml` 中加入 `rdp/scripts/build-native.sh` 步骤：源码包按固定 URL 下载并校验 sha256（与脚本中相同），按“源码哈希 + 补丁 + NDK 版本 + 脚本内容”缓存 `rdp/build/native`。
- 本地开发者没有产物时，错误信息要指向脚本（P5a 已有）。
- 工作流的改动没有办法在本地跑，请在 notes 里写明你如何静态检查过（例如 actionlint，如可用）。

## 验收

1. `./gradlew :rdp:testDebugUnitTest :app:testDebugUnitTest :app:compileDebugKotlin` 通过；新增迁移测试、会话适配器测试、`typeText` 判定测试、脚本输出解析测试。
2. 在 `emulator-5560` 上，用 App 的仓库层（instrumented 测试或调试入口，不做 UI）经 **SSH 隧道**（不是 P5a 的 Mac 端口转发）连上 CachyOS 测试账号 `miffanrdp` 的 GNOME 无头会话：脚本生成凭据、启动服务、连接、收到画面、`typeText("Miffan P5B")` 与剪贴板粘贴 `中文输入` 都到达 zenity。SSH 私钥：测试可以在模拟器的 debug 应用里生成新密钥，公钥由 Claude 加到测试账号；不要读取或复制 Mac 上的私钥。需要 Claude 在远端做的事（加公钥、切换桌面、打开 zenity、看日志），按 P5a 的方式通知。
3. KDE：如果 krdp 的安全凭据方案可行，同样验证一遍；不可行时停在调查结论，不要用 `-p`。
4. 检查 `ps -eo args` 在远端看不到任何 RDP 密码（在 notes 里贴脱敏后的检查命令与结论）。
5. `docs/remote-screen/P5B_NOTES.md`：接口说明（供 Claude 接界面）、迁移、脚本行为、安全检查、CI 改动、未覆盖的情况。

## 约束

- 只用 `emulator-5560`。不读取签名配置、私钥或 `local.properties` 以外的凭据文件；测试账号密码只来自 `.deps/rdp-test.properties`，且 P5b 的流程应该由脚本自己生成新凭据。
- 需要联网时沙箱会弹审批，由用户在面板里批准。
