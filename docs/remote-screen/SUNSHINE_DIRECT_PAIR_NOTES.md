# Sunshine 直接配对交付说明

实现依据：`SUNSHINE_DIRECT_PAIR_SPEC.md`。分支：`feature/sunshine-direct-pair`。

## 实现

- POSIX helper 19、Windows helper 11 新增 `sunshine pair`。名称与客户端公共证书仅经 stdin JSON 传输；拒绝命令行参数、非法名称、坏 PEM、多证书、私钥、额外文本和 DER 尾随字节，并写入规范 PEM。
- pair/unpair 共用状态编辑事务：先验证已安装/可确认的服务模型和状态文件；运行中停服务并等退出后重读，保留停机刷出的新字段，再原子替换、恢复原运行状态。未运行时只写文件。保留其他客户端、未知 JSON 字段、属主、权限及 ACL；Windows 状态序列化使用 `ConvertTo-Json -Depth 100`。
- pair 按下述全新安装修正规则初始化 `root.uniqueid`，检查 `named_devices` 和每项字符串 `name/cert/uuid`，按证书 DER 去重（包括两条旧重复记录），追加大写 UUID4、`enabled:true`，返回严格的 `added/replaced/restarted/client_sha256`。
- App 提供指定签名的 `pairStreamDirectly`、`RemoteStreamDirectPairing` 和 `RemoteStreamManualPinReason`。沿用 revision、helper、probe 和证书检查；证书缺失或活跃串流时提前返回。helper 调用限时 45 秒，校验本地客户端证书摘要，按指定 detail 映射 PIN 兜底；其他错误抛出且保留 helper detail。
- 写入后最多等待 20 秒，以 SSH 背书的服务端证书 pin 请求 `serverInfo`；仅 `paired=true` 且 DAO revision/旧 pin 条件更新成功时返回 `Paired`。证书不符立即抛错；超时或未配对返回 `NOT_CONFIRMED`。
- 手动 PIN 的 `startStreamPairing` 保留。未改 UI 或 stream 源码，未连接任何真实主机。
- `PROTOCOL.md` 两平台命令表、成功/失败响应及 golden fixtures 已同步；`versions.lock` 保留旧版本行并追加新版本和生成后 SHA-256。

## 验证

- `./gradlew :app:testDebugUnitTest --tests '*RemoteSunshine*' --tests '*RemoteStream*'`：通过，30 项。
- 最终追加 helper 契约/版本锁验收：`./gradlew :app:testDebugUnitTest --tests '*RemoteSunshine*' --tests '*RemoteStream*' --tests '*RemoteHelper*'`：通过，32 项；包括新增 golden inventory、当前 helper 版本与 SHA-256。
- `python3 -I remote-helper/tests/test_sunshine_helper.py`：通过，41 项（全部使用临时目录、模拟 Linux/macOS 服务命令）。覆盖新增、重复替换、未运行不启动、未知字段、停机刷盘重读、状态拒绝且字节不变、名称/证书拒绝、权限/属主/macOS ACL、重启失败等。
- 最后补强 DER 尾随字节和普通文件检查后，单独重跑非法输入、普通文件与权限拒绝共 3 项：通过。
- `python3 -I remote-helper/tests/test_helper_safety.py`：通过。沙箱最初禁止临时 Unix socket；放行后完整离线测试通过。测试支持文件导入已兼容 Python `-I`。
- `python3 -I remote-helper/tests/check_shellcheck.py /private/tmp/shellcheck-v0.10.0/shellcheck`：通过，只有原有 3 条 warning，无新增 warning/error。使用临时目录中的官方 ShellCheck 0.10.0 Darwin arm64 发行包。
- `/bin/sh -n remote-helper/src/miffan.sh`、`git diff --check`：通过。
- `./gradlew :app:assembleRemoteHelper :app:assembleDebug`：通过（补齐本机构建依赖后，47 秒完成）。生成 helper 的 SHA-256 与新锁项一致，Debug APK 已生成。

## 本机环境与未执行项

- JVM 测试用 mock SSH 校验两个平台 stdin/命令行、严格成功形状、客户端摘要不符、detail 映射、未知失败与 timeout；抽出的预检覆盖 `ActiveStream`、显式中断、`CERTIFICATE_UNAVAILABLE`。未建立覆盖 SSH lease、实际 TLS/JNI、数据库条件更新的完整 repository 集成测试；这些边界仍需集成验证。
- 已补 Windows PS 5.1 等价离线测试（输入/状态拒绝、深层字段、新增/重复替换、服务状态、shutdown 重读、owner/ACL、读写权限和重启失败）。本机没有 `pwsh`/Windows PowerShell，未执行 `remote-helper/tests/windows/Test-Helper.ps1` 或 Windows setup 测试。
- APK 首次构建因 worktree 缺 `.deps/p6a/common-c.tar.gz` 等归档及 `rdp/build/native` 静态库失败。已复制本机主 checkout 的公共依赖归档和两种 ABI 的现成构建产物到当前 worktree 的忽略目录；复制前后不改主 checkout，RDP 源码/补丁与缓存 checkout 一致。stream 归档由原构建脚本校验固定 SHA-256；构建只生成忽略的产物。
- Windows Sunshine 2026.914、CachyOS 2026.1008、Mac 真机配对与回退流程均未执行，由 Claude 和用户验证。本次未运行任何 stage、smoke 或 remote 测试脚本。

## 全新安装审查修正（2026-10-17）

- 修正了首个版本对状态文件必须存在、必须含 root 的错误前提。Sunshine 尚未保存状态或只保存网页登录凭据时，现在可以直接配对。
- pair 首次检查和停服务后的重读共用初始化逻辑：文件缺失时，只有父目录已存在且为普通目录才允许创建；POSIX 使用当前用户所有的 0600 临时文件及 `os.replace`，Windows 使用继承目录 ACL 的同目录临时文件及 `File.Move`。若停机刷出了状态，就保留刷出的凭据、身份和 ACL，并按既有文件方式原子替换。
- 文件顶层缺 root 则新建对象；root 存在却非对象仍拒绝。root.uniqueid 缺失或非字符串时补大写 UUID4，已有字符串原样保留。username/password/salt 及其他顶层字段原样保留，确保 Sunshine `load_state` 能读取新配对。
- unpair 对不存在的文件返回 removed=0/restarted=false，不创建文件、不操作服务，也不会补 root 或 uniqueid。
- Python 和 PowerShell 离线用例新增无文件、仅凭据、缺失/非法 uniqueid、保留已有 uniqueid、首次停机刷盘重读、无效父目录与 missing-state unpair；Python 另断言新文件 UID/0600、覆盖自定义路径，PowerShell 另断言新文件继承目录 ACL。
- 按审查要求，未发布版本号仍为 POSIX 19 / Windows 11，仅更新对应 `versions.lock` SHA-256；旧版本 18/10 锁项不变。

本次修正验证：

- `python3 -I remote-helper/tests/test_sunshine_helper.py`：全量 47 项通过；随后新增的 unpair 不补身份用例和加强停服务前无文件断言的创建用例，专项 2 项也通过（共覆盖当前 48 个用例）。
- `python3 -I remote-helper/tests/test_helper_safety.py`：通过。
- ShellCheck 0.10.0、`/bin/sh -n`、`git diff --check`：通过，无新增 ShellCheck warning/error。
- `./gradlew :app:assembleRemoteHelper :app:assembleDebug :app:testDebugUnitTest --tests '*RemoteSunshine*' --tests '*RemoteStream*' --tests '*RemoteHelper*'`：通过；32 项 JVM 测试通过，两个生成 helper 摘要与锁文件一致。
- Windows PowerShell 离线用例仍未执行（本机无 PowerShell）；没有连接任何真实主机，也未修改 App/UI/stream 源码。
