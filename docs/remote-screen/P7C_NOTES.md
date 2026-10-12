# P7c：Windows 伙伴操作交付

日期：2026-10-12；分支 `feature/p7c-windows-cua`。依据 P7C_SPEC 及其中列出的
P7_WINDOWS_PLAN、WINDOWS_SPIKE B、P7A/B/B2_NOTES 和现有 POSIX cua 实现。

## 实现与安全边界

- Windows helper v4，仍为 UTF-8 with BOM，安装位置不变：
  `%LOCALAPPDATA%\Miffan\bin\miffan.ps1`。`cua` 的 stdout 是原始 MCP 字节流；
  `cua status/install` 和其它原有命令输出一行 JSON。stdio 失败只写固定 stderr，
  不向 MCP stdout 混入 JSON 诊断、令牌、更新提示或 PowerShell 格式化输出。
- `cua` 按需注册/启动 `Miffan-cua-<SID>-<console session>-v4` 任务，无登录触发器，
  Interactive/Limited、隐藏窗口。已有任务只验证并启动，不覆盖异样的任务。
  任务调度器会把 SID 规范化为账户名，验证时重新解析 SID。worker 再核对实际
  SID、Session 和 Medium 完整性级别（8192）；不硬编码 Session 1。
- 桥的字节型 named pipe 使用 `CreateNamedPipe`，受保护 DACL 只授予当前 SID，
  `PIPE_REJECT_REMOTE_CLIENTS`，第一实例防名称占位；后续实例建立时保留原实例。
  同时支持 Registry 与 clip 的独立连接。没有 TCP listener，也没有文件 MCP 桥。
- 每次连接使用 CSPRNG 256-bit 令牌和随机 GUID。令牌文件在只授予该 SID 的
  `%LOCALAPPDATA%\Miffan\cua-bridge` 中，文件名严格验证，拒绝 reparse parent/file。
  认证帧限长、5 秒超时，令牌 30 秒过期；通过原子 rename 领取，比较后立即删除，
  错误令牌也消费凭据。SSH 客户端 finally 删除未消费的自身凭据。无有效凭据不启动 MCP。
- 每个已认证连接只启动一个同为普通权限的 `cua-driver mcp --socket \\.\pipe\cua-driver`。
  stdout 原样转发，stderr 单独排空。连接结束清理自己的 MCP；120 秒无 MCP 活动则
  桥退出，包括连接仍打开时，并关闭自己的连接/MCP 子进程。共享 daemon 始终保留。
  锁屏、UAC input desktop 或 console session 切换时同样停止桥，不尝试操作安全桌面。
- `cua status` 与基础 probe 报告安装路径、固定最低版本 0.34.0、autostart RunLevel、
  **serve 进程**所在会话、实际 daemon integrity RID、bridge_available 和
  permission_review_needed。最高权限任务、不同用户任务、高权限或无法确认完整性
  的 console daemon 都要求人工审阅，`cua` 不自动修改或降低它们的权限。
- `cua install` 只在现有安装/升级确认后执行官方 install.ps1，固定 0.34.0、按用户安装。
  官方脚本当前即使 `-NoAutoStart` 仍会把已有任务重注册为 Highest，因此同时显式设置
  默认的用户 HOME/install dir，使用官方 isolated 模式跳过所有共享 autostart 改动。
  完成后核对版本，再显式注册 Interactive/Limited 的 `cua-driver-serve`；隐藏 helper
  wrapper 核对 Medium 后启动 serve。安装失败或已有高权限任务不会被报告为成功。
  **共享测试机没有运行 install，也没有重注册、停止或提升其 cua-driver-serve。**
- `clip` 在已有桥可用时通过独立 MCP 调用 clipboard_write，立即 clipboard_read 核对
  刚写入的值；只有 success/verified/session/transport 出现在结果中。桥不可用时保留
  P7b 普通权限一次性任务。桥写入可能已执行之后不做回退重试，不读取或恢复旧剪贴板。

当前用户 SID 是本地信任边界。同 SID 程序本来即可操作该用户桌面/读取私有文件；
桥不试图防御同 SID 恶意程序或本地管理员取得所有权。它避免让普通桌面进程通过
Miffan 获得管理员 daemon。没有放宽 cua-driver 自身的 ACL 或权限配置。

API 依据：[CreateNamedPipe](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-createnamedpipea)
的远程客户端拒绝/第一实例选项，以及
[Windows overlapped I/O](https://learn.microsoft.com/en-us/windows/win32/fileio/synchronous-and-asynchronous-i-o)。
安装行为按本轮读取的 [官方 install.ps1](https://cua.ai/driver/install.ps1) 验证。

## App 执行层与伙伴工具

- `RemoteWorkspaceSession.openProcess` 保留 POSIX 原命令；Windows 使用固定
  EncodedCommand 和 P7a 的长度前缀 UTF-8 cwd/script 帧，不把数据或凭据放进 argv。
  引导读取后交出原始 stdio，不格式化 stdout、不预读 MCP 内容。
- 实测 Windows OpenSSH 的 inherited stdin 是 OVERLAPPED 管道。ConsoleStream 的
  同步读取只能收第一批数据，独立 native echo 也复现；异步 FileStream echo 连续三轮通过。
  最终引导使用 event-based OVERLAPPED 精确读帧，无 IOCP 绑定或预读；stdio owner
  再绑定一次异步 FileStream，ReadAsync/WriteAsync 转发。固定引导约 5.3K argv 字符，
  有小于 cmd 8191 字符的回归断言。状态的 --version 子进程显式关闭 stdin。
- openCuaProcess/installCuaDriver 已走 Windows helper，不再拒绝 Windows；Mac shortcut
  仍仅用于 Mac。probe 已有真实 cua 状态，不再是 P7b 的未实现占位。
- Registry 使用实际 SSH 平台；远端提供什么 schema/resources 就使用什么，不从本机
  替换 skill。只有工具 schema 接受 session 才注入 label。测试版远端有 59 个工具、
  11 个 resources（也包含其它平台文档），WINDOWS.md 从该远端读取。
- `clipboard_write` 加入既有动作工具集合，沿用控制权仲裁、动作审批和操作摘要。
  摘要复用已有“Write clipboard”资源，没有修改 `ui/`、Compose、ViewModel 或本地化。
- Windows 的三条伙伴指引追加在现有 guide 注入位置：后台 Ctrl+V 可能输入 v，中文
  优先 type_text；应用可能恢复旧文档，只观察任务相关内容；锁屏/UAC 时停止并告知用户。

## Claude 需要接的 UI

1. Windows 伙伴入口/环境检查可使用真实 `probe.cua.ok`；移除“Windows 伙伴暂不支持”的
   说明。现有 computer_start 延迟加载、审批卡片、控制权和摘要路径继续使用。
2. 展示 `Cua.autostartRunLevel/daemonSessionIds/daemonIntegrityRids/bridgeAvailable/
   permissionReviewNeeded`。RID -1 表示未知；需要审阅时明确人工处理，不自动降级/提权。
   未登录、锁屏、UAC、daemon 不在 console 或桥任务异常时给出用户需要处理的事项。
3. 安装确认中的准确命令使用 `cuaDriverCommand(upgradePath, RemotePlatform.WINDOWS)`，
   显示 helper 的 `cua install`、官方固定 0.34.0、普通权限 Interactive/Limited 自启动。
   不用旧的一参数 POSIX bash 描述来说明 Windows 安装。确认后仍调用现有 install API。
4. 将 clipboard_write 的动作摘要放入现有摘要展示集合（标题映射已在 utils 实现）。
   Windows 前台粘贴继续要求显式前台授权；不要在后台 Ctrl+V 失败后自动切前台重试。

## 验证

- Windows PowerShell 5.1 AST 与 `tools/windows-helper-tests/Test-Helper.ps1` **32 项**离线测试通过：
  原 P7b 回归、桥 C# 编译、一次性凭据/错误令牌/过期/重放/路径校验、普通权限任务注册
  与重用、未登录/不同 SID/锁屏拒绝、高权限安装零 mutation、clip 回退和权限审阅。
  防火墙/服务/任务/剪贴板 mutation adapter 均模拟；真实权限/窗口配置不改。
- 真实 SSH `initialize/tools/list/resources/list/list_windows` 通过；实际 schema 存在
  Windows aumid/additional_arguments 等参数。无有效凭据的本地 pipe 连接被关闭。
  不创建其他用户、不开放 SMB/防火墙，所以未实测其它 SID/真正远程 SMB 客户端拒绝；
  依据是实际创建时的单 SID DACL、私有目录 ACL 及每实例 PIPE_REJECT_REMOTE_CLIENTS。
- `p7c-idle.py` 特意保持 SSH stdin/连接打开，最后一个请求后 **120.01 秒**收到 EOF。
  只读核查桥任务 Ready、桥不可用、pending_tickets=0；daemon 仍是 PID 22124、Session 1、
  RID 8192、cua-driver-serve Limited，未重启或修改。
- emulator-5560 / RikkaHub_API_35_16K：通过 Mac loopback 58022 → Windows SSH 的转发，
  App 独立校验固定 Windows 主机 key。测试私钥仅由文件描述符交给 adb stdin；Android
  测试导入加密凭据后立即删除 cache 文件，finally 删除导入密钥、主机和工作区。
  不读取/输出本机私钥，不接触 emulator-5554。另有 cleanupAudit 检查没有测试记录/缓存。
- Registry 只读测试通过（首个最终通过轮 9.355 秒）；完整输入测试 **OK (1 test)，17.2 秒**：
  initialize、远端 skill/schema、list_windows、自有窗口截图、后台 type_text 中英文、click、
  clipboard_write/read；同一 Registry 连接仍在时 `miffan.ps1 clip` 返回
  `transport=cua-bridge/success=true/verified=true`，验证多连接剪贴板加速。
- 打开前创建全新的 `%LOCALAPPDATA%\Miffan\p7c-tests\p7c-owned-abdb64be3c1e.txt`，
  通过 launch_app 的明确文件路径启动，只在唯一标题匹配后读取该文件的 Document。
  PID 21544 / HWND 1705592。它作为独立新标签打开在先前 p7b2 测试窗口中；没有读取
  历史文档正文或操作其它标签。后台中英文输入后重新观察，截图已查看，正文一致。
  click 调用及后续观察通过；不把此项说成所有像素坐标/DPI/应用的命中验收。
- 新窗口启动前通知 Claude；两次剪贴板写入各自提前通知 Claude，**每条 send-text
  后都 send-keys enter**。没有前台输入/粘贴操作，不需要用户把窗口切到前台。
  最终剪贴板保留 `P7c helper 米饭剪贴板加速`；自有测试标签保留供核验，输入未保存。
- `./gradlew :app:testDebugUnitTest assembleDebug :app:assembleDebugAndroidTest
  :workspace:testDebugUnitTest` 通过：App **906 项、0 失败/错误/跳过**；workspace **188 项、0 失败/错误、8 跳过**。
  workspace 条件集成测试未传测试 properties，既有环境条件跳过不被报告为实测通过。
  最终只读 Registry 轮 **10.297 秒**，包含 computer_start 的 ensureSession/start_session；
  cleanupAudit **OK (1 test)，0.063 秒**。
- Python controller AST 与 `git diff --check` 通过。只复用公开 Moonlight 源码包和非秘密
  FreeRDP/OpenSSL 预编译库；未复制 signing 配置或 local.properties。

产物仅在 `app/build/test-output/p7c/`，不提交凭据、窗口截图或窗口树：schema.json、
owned.json/owned-state.json、p7c-registry.png/.txt、input.txt、readonly.txt、idle.txt、audit.txt。
开发阶段的诊断 stderr/字节计数不在产品脚本中；四个自有 Session 0 sync echo
进程已按完整路径核对后结束，诊断 exe/计数日志已删除。TEMP 的实验包装脚本/XML 保持原样。

## 限制

- 本轮只有 Windows 11、单屏 100% DPI 和 SSH/console 同用户。锁屏/UAC 的实际切换、
  多屏/缩放、真实手机和普通 SSH 账户留给 P7e；没有主动锁屏或触发 UAC。
- 共享测试机已有 0.34.0，本轮不运行安装器；安装器状态和普通权限任务参数有离线覆盖，
  真正安装/升级需在独立 Windows VM 或用户批准的安装流程中进一步验收。
- idle/安全桌面关闭、SSH 取消或未知操作结果都不自动重放动作；重新连接后先重新观察。
  同 SID 进程是信任边界，遗留异常 Miffan 任务需要定向人工审阅，不能泛删任务。
- UI 接线留给 Claude；没有合并到 feature/im-4.0。
