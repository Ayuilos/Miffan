# P7b 第二部分：Windows 屏幕数据层

日期：2026-10-12；分支 `feature/p7b2-windows-screen`。
依据：P7B2_SPEC、P7_WINDOWS_PLAN、P7A/B_NOTES、P6C/F_NOTES。

## 设计与接口

- `RemoteScreenPlatform.WINDOWS`；实际 SSH 会话的 `RemotePlatform.WINDOWS`
  （SFTP 检测）优先于旧数据库值和 helper 的 os。Windows 即使 `streamEnabled=false`
  或请求 `skip=true` 也请求 Sunshine；任何失败都抛异常，不启动 VNC/RDP。
- `supportsRemoteScreen=true`。Windows cua 的 `openCuaProcess`、`installCuaDriver`
  仍先拒绝；`RemoteComputerRegistry.capabilities` 经前者建立连接，也无法绕过。
  未实现 P7c，未修改 cua、OpenSSH 默认 shell、RDP 配置。
- Windows asset 保留 UTF-8 BOM，helper v3。安装到
  `%LOCALAPPDATA%\Miffan\bin\miffan.ps1`，版本不同即上传原始字节；同目录随机 staging、
  `File.Replace`/`File.Move` 原子发布，替换保留 ACL，拒绝 reparse 文件和祖先目录，
  finally 清理自己的 staging。所有调用经 P7a 固定 EncodedCommand 引导，
  脚本/参数在请求帧中，输入数据在 stdin；没有运行 bash/uname 或修改 OpenSSH。
- Sunshine probe/start/enforce、公开证书核对和配对与 POSIX 复用模型和 pin CAS。
  Windows 首次配对也将 SSH probe 的公开证书指纹交给 StreamHost 核对。
  probe 预算 15 秒；Windows clip 预算 30 秒，成功同时要求 helper 的
  `success=true/verified=true`，才发 Ctrl+V。没有 Session 0 剪贴板降级。
  Windows 不 wake；`openSunshinePermissionSettings` 返回明确不适用。
- 候选地址仍用既有 Linux/macOS 路由、权限、网络缓存及两候选逻辑；Windows probe
  给出 LAN/Tailscale 数字地址，SSH 地址补充。SSH TCP 保持 loopback，UDP 直连。
- `startStreamPairing` 新增可选 `clientName`，默认原名称；测试客户端使用
  `miffan-p7b2-emulator`，名称不含控制字符、最多 64 字符，PIN 不进命令行。

## 防火墙契约

- `checkSunshineFirewall(hostId, expectedRevision)` 为只读，返回
  `RemoteSunshineFirewallCheck`：完整 rules/profiles、unsafeRules/unmanageableRules、
  allowedSources、suggestedPlan、planDetail。Windows 每次建流前检查。
- helper v2 追加 `allowed_sources/suggested_plan/plan_detail`：从活动且有 IPv4
  默认网关的 LAN 网卡读取真实 prefix，规范化 CIDR；检测到活动 Tailscale 网卡且有
  100.64/10 地址才加入 `100.64.0.0/10`。不根据 IP 猜 /24，不放行 Any/LocalSubnet。
  v3 还将既有窄 UDP 规则里不属于提案的显式来源列为风险、列入禁用计划；GPO 不给
  部分修复计划。apply 的重新计算也拒绝遗留这些额外来源。
  App 对 helper 的只读网卡证据提出完整计划，schema 恰为
  `disable_rules/allowed_sources/add_rule`。原 `suggested_changes` 为兼容保留。
- `firewall_model_ok=false` → `FIREWALL_REVIEW_NEEDED`，异常 `firewallCheck` 附风险和计划。
  check 失败/无效输出 → `FIREWALL_CHECK_FAILED`。未知来源、GPO 或关闭的 profile 等
  无法由本地规则修复时 suggestedPlan=null，不能自动补来源或修改 profile。
- UI 展示 `suggestedPlan` 全部内容并取得用户确认后调用 `plan.approve()`，生成
  `ApprovedSunshineFirewallPlan`，序列化快照防止后续集合变化；拒绝空计划。
  再调用 `applySunshineFirewall(hostId, expectedRevision, approvedPlan)`。
  只下发该快照一次，不合成、不重试、不自动审批；返回
  `RemoteSunshineFirewallResult`（success/detail/changed/backup/firewallModelOk/appliedChanges）。
  helper 仍重新计算 ActiveStore、核对批准计划，先备份再修改，失败保留备份路径。
- **本轮测试机从未执行 firewall apply，包括空计划。** App apply 接入与脚本 mutation
  仅用 MockK/离线数据验证。真实非空 apply 留给独立 Windows VM 或后续用户验收。

## Claude 需要接的 UI

1. 防火墙确认：按风险列出当前规则和完整建议变更/来源；每次明确确认后才 approve/apply。
   无可执行计划时显示 planDetail 和人工处理建议；计划过期/执行失败重新 check、重新确认，
   不自动调用 apply，不用空 apply 做心跳。展示 backup 路径，便于定向恢复。
2. Windows 设置卡片：只提供 Sunshine；高性能开关对 Windows 没有效果，不提供 VNC/RDP、
   Mac wake/授权页。伙伴操作入口继续显示“暂不支持”。Win 键、Ctrl 快捷键等由 Claude 接。
3. 异常文案：新增 `FIREWALL_REVIEW_NEEDED/FIREWALL_CHECK_FAILED/STREAM_UNAVAILABLE`。
   后者 `streamFailure.reason` 保留 Sunshine 缺失/未启动/未配对/加密/UDP/解码/主机拒绝等
   原因；detail 的 UDP 说明同一 LAN 或 Tailscale 放行 UDP 47998–48000。证书变化仍独立抛出，
   不覆盖 pin。当前 UI 通用错误映射能编译，但专用引导文案需补。
4. `ComputersPage.kt` 的新增枚举编译分支由 Claude 提交 `3fb172c32`；Codex 没有修改 ui/
   或 ViewModel，也未合并到 `feature/im-4.0`。

## 验证结果

### 构建与回归

```sh
./gradlew :app:testDebugUnitTest assembleDebug :app:assembleDebugAndroidTest
./gradlew :workspace:testDebugUnitTest :stream:testDebugUnitTest
python3 tools/windows-helper-tests/stage.py
python3 tools/windows-helper-tests/smoke.py --out /private/tmp/p7b2-smoke.json
```

- 最终规格构建 `BUILD SUCCESSFUL`（`/private/tmp/p7b2-delivery-build.log`）。
  App JVM **897 项，0 失败/错误/跳过**；新增 Windows 命令、BOM 上传/版本升级、JSON、
  Windows 优先于旧平台/无效旧 endpoint/禁用开关/skip、防火墙问题/失败退出码、
  只读 check 不 apply、批准后精确 stdin、空计划拒绝、冻结批准值、revision 和 cua 拒绝。
- workspace **187 项，0 失败/错误、8 跳过**（环境条件：六项既有实机测试及两项 P7a
  Windows 集成，未传 p7aTestProperties）；stream **8 项，0 失败/错误/跳过**。
  Linux/macOS 的现有解析、输入、路由、fallback 测试保持通过。
- Windows PowerShell 5.1 AST 解析和 **25 项离线测试通过**；新增网卡/网段、完整建议计划、
  未知来源和 profile 风险覆盖。实际防火墙 mutation adapter 始终被内部模拟边界替换，
  未运行 helper 的 `sunshine firewall apply` 命令。六项实机 smoke 通过，start/enforce
  经预检均为无变化，未重启 Sunshine。只读 check 返回模型正常、无风险、无建议变更。
- Python controller AST、`git diff --check` 通过。构建只复用主 checkout 的三个公开
  Moonlight 源码归档及 FreeRDP/OpenSSL 非秘密预编译库；未复制 local.properties/签名配置。

### emulator-5560 端到端步骤

只使用 `RikkaHub_API_35_16K`，Android 15 arm64、16 KiB。模拟器中途退出，默认图形
后端重启失败；精确停止自己启动的失败实例后，以 `-gpu swiftshader_indirect -no-snapshot
-no-window -port 5560` 冷启动成功，没有操作 emulator-5554。

模拟器直连 Windows LAN SSH 探测没有取得主机 key，因此测试用 Mac loopback TCP
58022 → SSH direct-tcpip → Windows 127.0.0.1:22，再由 App 进行独立 SSH 登录，固定
已知 Windows ED25519 key。App 的 Sunshine HTTP/HTTPS/RTSP 仍经过这条 App SSH；
UDP **直接使用 100.64.0.8**。不改模拟器 iptables、不改 Windows OpenSSH/防火墙/RDP。
测试转发每个阶段在 finally 关闭；这个安排只验证 App 数据层，真机直接 SSH/LAN 仍需 P7e。

```sh
python3 tools/remote-screen/p7b2-emulator.py install
python3 tools/remote-screen/p7b2-emulator.py pair
# 每次先通知 Claude：send-text wJ:p1，然后 send-keys wJ:p1 enter
python3 tools/remote-screen/p7b2-emulator.py screen --stream-notified
# 用户确认自己的测试记事本前台；本次 clipboard 写入先通知 Claude
python3 tools/remote-screen/p7b2-emulator.py input --stream-notified --clipboard-notified --foreground-confirmed
python3 tools/remote-screen/p7b2-emulator.py cleanup
# 最终 v3 的单独自动升级验收，随后清理；不配对/串流/写剪贴板
python3 tools/remote-screen/p7b2-emulator.py helper
python3 tools/remote-screen/p7b2-emulator.py cleanup
```

1. 测试专用 SSH 私钥仅将文件描述符交给 adb stdin，放 App 私有 cache（umask 077），
   不读取/输出本机私钥内容、不进 worktree；androidTest 导入加密凭据后 **立即删除缓存文件**。
   控制器 finally 也删除临时 key/PIN。安装 helper、验证完整 asset 字节/BOM、SFTP Windows
   平台、只读 firewall check、配对 `miffan-p7b2-emulator` 均通过：**OK (1 test)，18.580 秒**。
   PIN 仅经 App 私有文件与进程内固定证书的测试管理 API 传递，不进 argv/日志。
2. `receiveScreen`：**OK (1 test)，18.282 秒**，实际 Sunshine H.264 图像非黑屏；截取并查看
   `p7b2-screen.png`。开关 false 且请求 skip=true，仍返回 WINDOWS/STREAM，地址为 Tailscale。
3. 自有文档 `%LOCALAPPDATA%\Miffan\p7b2-tests\p7b2-notepad.txt` 通过一次性 Interactive/
   Limited 任务打开，启动任务已注销；用户在 Codex 明确确认前台。输入仅作用于该文档。
   首轮英文键码被当前中文 IME 组合成“配吧-keyboard”，中文粘贴和保存读回成功，但英文断言
   失败；因此改用 F5 时间戳作与 IME 无关的键盘验收。未更改用户输入法设置。
4. 最终 `inputToOwnedNotepad`：**OK (1 test)，31.218 秒**。Sunshine 绝对鼠标点击文本区，
   Ctrl+A/Backspace 仅清空自己的测试内容，F5/回车、一次 `typeText` 中文粘贴、Ctrl+S；
   经 SSH 仅读自己的文件，验证时间戳和“米饭 Windows 中文粘贴验收”。查看截图确认。
   两轮输入总共写了两次剪贴板，**每次写入前都通知 Claude，send-text 后均发送 Enter**。
   没有读取/恢复旧剪贴板，最后测试文本保留。未操作其他窗口/标签。
5. 接收和输入测试成功后 `delegate.awaitStopped` 通过，再轮询 helper 确认 active_stream=false；
   每轮失败后也另做只读核查。最终 cleanup **OK (1 test)，0.112 秒**，删除 App 测试
   工作区/主机、加密导入 SSH key、独立串流身份、临时 PIN；再次确认临时文件不存在。

6. 最终审阅补强额外显式来源的完整修复计划后，helper 升到 v3。再跑 controller `helper`
   模式（不配对/串流/剪贴板），App 从已安装 v2 原子升级到 v3，逐字节/BOM、平台和
   新范围检查验证通过：**OK (1 test)，11.874 秒**。随后再次 cleanup **OK (1 test)，
   0.067 秒**，清理导入密钥及配置。

| 项目 | 只收画面 | 最终输入 |
| --- | --- | --- |
| 编码/请求 | H.264，1280×720@30，4000 Kbps | 同左 |
| video/audio/control encrypted | true / true / true | true / true / true |
| received/rendered frames | 14 / 13 | 203 / 202 |
| 累计 received/rendered FPS | 12.92 / 11.93 | 15.52 / 15.44 |
| decode mean/max ms | 28.61 / 209.14 | 9.03 / 59.03 |
| dropped / RTT | 1 / 16 ms | 2 / 22 ms |
| decoder | c2.goldfish.h264.decoder | 同左 |

首次默认 HEVC 在模拟器解码阶段失败，正确映射 DECODER_UNSUPPORTED，无 VNC/RDP 回退。
最终输入使用显式 H.264、模拟器解码例外及 **与正式默认相同的 4 秒建流期限**；只收画面
那轮曾给软件解码测试实例 10 秒。两轮都使用 App 原有 ImageReader 预检及 Surface 交接。
测试 Activity 是 debug 专用、不导出的 SurfaceView；截图的竖屏 Surface 拉伸横屏画面，
不作为正式屏幕页布局验收。goldfish 虽自报 hardwareAccelerated=true，仍是模拟器后端；
不能据这些短测声明达到 30 fps 或手机性能通过。音频仅验证加密统计，未做播放验收。

### 最终只读状态和产物

`app/build/test-output/p7b2/final-state.json`：helper_version=3；Sunshine running=true、active_stream=false、
加密 2/2，公开证书指纹保持 `8edb025280591442b733c675de6dafb1b011b0f69abddd1f18ed178b14bf512d`；
防火墙模型正常、unsafe_rules=[]、建议计划=null，来源仍 LAN 192.168.31.0/24 与
100.64.0.0/10。自己的 clip/记事本启动一次性任务列表为空。没有 firewall apply。
已通知 Claude 串流结束、用户可正常切换窗口/使用其他客户端。

产物保存在 build 目录，不提交桌面截图或凭据：`pair.txt`、`p7b2-pair.txt`、`screen.txt`、
`p7b2-screen.png/.txt`、`input.txt`、`p7b2-input.png/.txt`、`helper.txt`、`p7b2-helper.txt`、`cleanup.txt`、`final-state.json`。
Windows 自有测试文档/标签保留供用户核验，测试配对客户端保留在 Sunshine；App 本轮独立
私有身份已清理，不删除其他配对客户端。

## 已知限制

- 仅 Windows 11 单屏、100% DPI、SSH 用户与控制台相同用户的模拟器功能验收范围。
  多屏/缩放、锁屏/UAC、真实手机 LAN/切网/性能留给 P7e。
- 开放 firewall check 需要能读取 NetSecurity；缺权限失败关闭，不降级连接。
  只审计绑定 sunshine.exe 的规则，不冒充机器全部防火墙/Any-program 规则审计。
- Sunshine 同时只支持一个串流。加密修改沿用 helper 活动/未知串流保护，不自动执行。
- 调试实例的 `RemoteStreamEngine.configureSessionForTest` 仅 DEBUG 可设置，供 androidTest
  为 emulator 的软件解码器显式开例外；release 默认策略未放宽。模拟器帧率不代表手机性能。
