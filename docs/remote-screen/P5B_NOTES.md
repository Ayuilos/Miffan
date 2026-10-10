# P5b：App 数据层、远端启动与 CI

## 界面接口

- `RemoteScreenConnection.session` 为 `RemoteDesktopSession`，`protocol` 是 VNC/RDP；start、pause、pointer、key、tapKey、typeText、sendClipboard、close 统一。SSH lease 的释放行为保持不变。
- `VncDesktopSession.delegate` 暴露原 VNC 会话；`stats`、`statsLogger`、`clipboard`、`state` 直接委托原对象，maxFps 原样传递。RDP 的 `setMaxFps` 忽略，`rdpStats` 暴露实际编码、安全协议、解码器、帧率和字节数；兼容 `stats` 仅映射帧率与编码，RDP 不调用 VNC 的 `statsLogger`。
- 配置增加 `RemoteScreenProtocol.AUTO/VNC/RDP`、脚本报告的 `rdpUsername`、`rdpCertificateSha256`。`updateConfig` 的新参数均可选，原 VNC 调用方式仍有效；RDP 用户名由脚本固定为 `miffan-<uid>`，密码由手机自动生成。VNC 的 auth/password 控件不用于 RDP。
- 首次 `open` 返回的会话尚未 start。helper v8 经固定主机密钥的 SSH 报告自有证书的 DER SHA-256；没有已有 pin 时直接作为 `RdpOptions.certificateSha256` 严格校验，进入 Connected 且实际指纹相同后自动固定，无需首次手动确认。已有 pin 始终优先，不匹配仍报证书变化且不自动更新。旧 helper/手动端点没有报告指纹时保留 TOFU：只暴露 `certificateSha256: StateFlow<String?>`，不自动固定，由界面处理。用户明确确认证书或替换变化后的证书仍调用 `pinRdpCertificate(hostId, sha256)`，该接口保留关闭现有连接、随后重连的语义。只接受完整 SHA-256 十六进制值（允许大小写及冒号）。
- 已固定指纹变化时，`Closed.error` 是 `RemoteRdpCertificateChangedException`，含 expected/actual，并对应 `RDP_CERTIFICATE_CHANGED`。界面可以展示比较结果并要求用户明确确认；不得捕获后自动调用 pin。
- `typeText` 返回 false 时没有发送任何前缀，也没有令会话失败。RDP 仅直接发送可打印 ASCII、Tab、CR/LF；超过 1024 个 UTF-16 单元或队列容量不足也返回 false。中文、其他非布局字符、长文本请 `sendClipboard(text)` 后发送 Ctrl+V。
- RDP 的 `setRemoteClipboard` 转到活动会话的 cliprdr。`sendClipboard` 异步设置 Unicode 文本，native 在 FormatList ACK 前保持输入队列等待，避免紧跟的粘贴快捷键先于剪贴板声明。远端复制的 Unicode 文本通过 `clipboard` 发出，界面负责写到 Android 系统剪贴板。
- 剪贴板上限为 4 MiB（RDP 的 UTF-16LE 连终止 NUL），拒绝包含 NUL 的文本。待发送文本合并为最近一次，native 接收队列限制 32 条且合计 4 MiB；close/取消清理待发送值并断开通道。暂不支持文件、HTML、图片剪贴板及动态分辨率 disp。helper 在可用时报告 xrandr 会话尺寸，无头固定 1920×1080，未报告尺寸时仓库使用 1920×1080。
- 脚本错误映射：`RDP_ALREADY_CONFIGURED` 提示已有配置不会覆盖；`RDP_KEYRING_LOCKED` 提示解锁；`RDP_CREDENTIAL_SETUP_UNAVAILABLE` 提示缺少安全凭据工具；`NO_RDP_SERVER`、`RDP_START_FAILED` 提示安装或启动失败。原始凭据相关 stderr/log 不拼进异常。

## 迁移与凭据

Room 31→32 使用 AutoMigration，附版本 32 schema 与 `Migration_31_32_Test`（迁移及旧快照更新不能撤销 pin 两项实测通过）。旧主机默认 protocol=auto、rdpUsername 为空、证书 pin 为 null，VNC 端点/认证/用户名以及 SSH 固定值保留。SSH 配置、屏幕配置和探测更新使用局部 SQL，避免覆盖并发确认的证书 pin；端口不进数据库。

RDP 密码为 SecureRandom 32 字节的 64 位十六进制字符串，独立 AES-GCM/Android Keystore 命名空间 `remote-rdp-credentials`，位于 noBackupFilesDir。与 VNC 凭据同 hostId 但不共用文件或密钥；删除主机时两者均清理。协议切换不会误删 RDP 密码。密码不在 Room、配置 DTO、备份、日志或模型上下文中。

## helper v8

`miffan rdp start` 从 stdin 读取手机密码，输出一行 JSON `{server,port,username,desktop,mode,error,log,width,height,certificate_sha256}`；日志只输出固定诊断，不输出原始 credential 工具结果。`probe` 增加 RDP server/version/running；AUTO Helper 下 GNOME/KDE/Plasma 使用 RDP，其他桌面与手动 VNC 端点保持 VNC。

证书及私钥长期保存在 `~/.miffan/rdp/`（目录 0700，文件 0600），有效期十年；已有证书不因重连而重建，缺一半的文件对报错。启动有目录锁。成功启动记录 helper ownership；相同密码/端口的活动 GNOME 服务复用，避免再次连接重启服务。密码比对摘要只保存在用户私有 ownership metadata 中，不返回 App。

GNOME 使用 user/headless 模式，按 gnome-shell --headless 判断；随机 20000–59999 端口，禁用端口协商和 view-only，GRD 认证固定 NLA。仅重启 helper 已认领的对应用户 unit。现有 GRD 进程、非空 TLS 设置或已配置用户名会返回 already_configured；已认领配置的证书/密钥路径或端口被外部改变也拒绝覆盖。用户模式先查询 Secret Service 的 Locked 属性，锁定时不会绕过钥匙串。

`grdctl rdp set-credentials` 使用零位置参数，用户名及密码均经 stdin。上游为两个 prompt 分别创建 buffered GIO reader，普通 pipe 可能把第二行预读后丢掉，因此借助 util-linux `script --echo never` 的 canonical PTY，禁止 echo，transcript 明确指向 `/dev/null`，所有输出丢弃；15 秒 timeout。不设置密码环境变量，不把密码拼入任何外部命令 argv。

KDE 使用独立 `XDG_CONFIG_HOME=~/.miffan/rdp/krdp-config` 的 krdpserverrc，General/Users 指定脚本用户名，SystemUserEnabled=false；QtKeychain `ReadPasswordJob("KRDP")` 使用 KWallet folder=KRDP、entry=username、Password 类型。`kwallet-query -w username -f KRDP wallet` 只通过 stdin 接密码，预先确认已有 wallet 已解锁，不创建无密码钱包。缺少 qdbus6、kwallet-query/kwallet6-query、timeout 或可用钱包时明确报错。

krdp 只通过命名 transient unit `miffan-rdp-kde.service` 启动，参数 `--address 127.0.0.1 --plasma`；无 -u/-p。拒绝覆盖已有 krdp 进程/用户配置，仅停止自己 unit 的旧实例。krdp 6.7.5 的 `RdpConnection::initialize` 明确设置 NlaSecurity=false；配置用户和 PAM 都没有改变这一点，因此 KDE 连接明确选 TLS（包含证书校验），GNOME 明确选 NLA，不做失败后降级。

依据：[krdp 用户配置入口](https://github.com/KDE/krdp/blob/v6.7.5/server/main.cpp)、[krdp TLS/NLA 设置](https://github.com/KDE/krdp/blob/v6.7.5/src/RdpConnection.cpp#L333)、[QtKeychain KWallet 映射](https://github.com/frankosterfeld/qtkeychain/blob/0.16.0/qtkeychain/keychain_unix.cpp)、[kwallet-query stdin 写入](https://github.com/KDE/kwallet/blob/master/src/runtime/kwallet-query/src/querydriver.cpp)、[grdctl stdin prompt](https://github.com/GNOME/gnome-remote-desktop/blob/master/src/grd-ctl.c)、[script echo/transcript 选项](https://github.com/util-linux/util-linux/blob/master/term-utils/script.1.adoc)。

## 构建与 CI

ci、daily-build、release 均在 Gradle 前安装 NDK 28.2.13676358/CMake 3.22.1，缓存 `rdp/build/native`。key 包含两个源码完整 SHA-256、NDK 版本、runner OS、native builder、source downloader 和全部补丁内容；无宽松 restore-key。cache miss 执行 fetch-sources.sh 的固定上游 URL 下载及校验，再 build-native.sh 编译两个 ABI。cache hit 不下载源码；本地 build-native.sh 继续只使用已有 .deps 包，不隐式联网。ANDROID_HOME/ANDROID_SDK_ROOT 都可定位 SDK。

静态检查：三个 YAML 经 Ruby Psych 解析；核对 builder/downloader/cache 三处源码 hash 一致、native build 排在 Gradle 前；bash -n 与 sh -n 通过。本机未安装 actionlint/shellcheck，未宣称运行这两个工具。GitHub runner 的冷缓存下载/编译及热缓存复用尚待实际 CI 验证。CI 另外运行隔离 helper 安全夹具。

## 验收记录

- 规定的 :rdp:testDebugUnitTest（7 项）、:app:testDebugUnitTest（662 项）、:app:compileDebugKotlin 已通过；:rdp:lintDebug 无问题；会话适配器、文本边界、脚本输出解析测试已新增。
- app debug + androidTest、两个 ABI 的 JNI 桥接编译通过。
- emulator-5560 上 Room 31→32 迁移测试通过，保留旧 VNC 配置并默认不固定证书。
- SSH 密钥由 debug App 内生成，私钥未导出，公钥由 Claude 安装；经 App 仓库层直连 Tailscale SSH，再 direct-tcpip 到远端 loopback。已有 GNOME 配置的拒绝覆盖测试通过（约 23:38），没有改其凭据。
- `python3 rdp/scripts/test-helper.py` 的隔离 fixture 检查通过：密码只在 stdin、argv 不含密码、证书保留、权限、幂等启动、已有配置拒绝、短密码拒绝、钱包缺失/锁定拒绝与 KDE 安全启动。它不替代真实 GRD/KWallet 验收。
- GNOME 全项通过：23:45:49 脚本启动 headless GRD，随机端口 20448，NLA，关闭端口协商，VNC disabled；1920×1080 非空画面，实际 AVC420、c2.goldfish.h264.decoder（模拟器报告 hardware=true），收到约 401 kB。23:45:56.297 客户端发 Return，23:45:57.190 远端 zenity 输出 `Miffan P5B 中文输入` 并退出。客户端断言 Ctrl+A/Ctrl+C 的 cliprdr 回传也是完整的同一字符串。
- GNOME 正确 pin 重连通过；错误 pin 以 RemoteRdpCertificateChangedException 拒绝，数据库错误 pin 未被自动改回。重新连接复用同一密码/证书/服务，没有再重启 GRD。
- 安全审计：instrumentation 经 SSH 执行 `ps -eo args`，在内存中与手机凭据比较，断言不含生成的 RDP 密码，不打印原始进程列表或密码。Claude 另检查 `ps -eo user,args`：GRD 只有 `/usr/lib/gnome-remote-desktop-daemon --headless`，无 grdctl 密码参数；目录 0700，cert/key/owner/digest/credentials.ini 0600。代码仅 stdin/钥匙串传密，不使用 krdp -p。
- KDE 安全路径已实现，夹具验证安全启动及钱包缺失/锁定的 keyring_locked 输出。真实 Plasma 会话下的端到端验证待 P5c（用户最终决定）。本次测试账号没有钱包，也没有持久 Secret Service default collection；不使用临时 session collection 代替产品路径，不创建无密码钱包。Claude 的无头 PAM 初始化尝试被其权限系统拦截，未创建钱包，未绕过拦截。
- GNOME 无头会话保持运行，helper 的 GRD 服务已由 Claude 在 KDE 准备阶段停止；测试参数仍 active=gnome，P5a 备份保留。
- 旧的 P5a test-emulator.py 也改为经 stdin 将测试密码写入 debug App 私有 cache（0600），不再传 instrumentation 密码参数，结束清理；文本遇到 typeText=false 时走 cliprdr 粘贴。
- 本阶段未修改 ui/ 源文件，未复制或读取 Mac 私钥，未重新下载本地 FreeRDP/OpenSSL 包，未提交 .deps/ 测试参数。

## 按规格清单自检

1. JVM 测试、app Kotlin 编译、适配器/文本/解析测试及 Room 迁移：通过；两个 Room instrumentation 用匹配 APK 在 emulator-5560 通过。
2. GNOME App 仓库层 SSH direct-tcpip、脚本传密和启动、画面、ASCII、中文粘贴、Return：通过，远端日志与客户端剪贴板断言相互印证。
3. KDE：安全配置路径与夹具通过；按用户最新决定，真实 Plasma 会话下的端到端验证待 P5c。
4. RDP 密码 argv 安全审计：GNOME 实测通过，KDE 夹具验证 stdin-only，产品代码没有 -u/-p 密码路径。
5. notes：已记录界面接口、迁移、证书固定、脚本行为、安全检查、CI、未覆盖事项。CI 的 hosted 冷/热缓存执行、真实 KDE 和动态尺寸仍未实测；AVC444 延续 P5a 的未验证状态。

## 审查后追加：经 SSH 认证的首次证书固定

按审查后的新要求，helper v8 在成功启动/复用自有 GNOME 或 KDE 服务时，解码 `~/.miffan/rdp/cert.pem` 为 DER 后返回小写、无冒号的 64 位 `certificate_sha256`，与 native `RdpSession.certificateSha256` 格式相同。不对已有外部配置或失败启动做证书背书；DER 解码失败返回 `rdp_start_failed`，不返回空输入的摘要。临时 DER 位于私有 start.lock 中并及时清理；已有 PEM 不重建。

仓库选择已有 pin，否则选择 SSH helper 指纹。新指纹送入 native 严格比对，只有 Connected 且实际指纹一致才调用独立 `pinFirstRdpCertificate`，不调用用户确认接口、不关闭成功连接。条件 SQL 要求 pin 仍为 null 且 SSH connectionRevision 未变化，防止并发连接、用户确认或主机配置编辑覆盖已建立的信任。未收到指纹、认证失败、证书不匹配、断开或取消均不自动固定。helper 报告了格式错误的指纹时拒绝连接，不能降级成 TOFU。

本次补充测试覆盖 DER/PEM 摘要区别、GNOME/KDE 首次与复用返回同一指纹、非自有配置无指纹、损坏证书拒绝、旧 helper 缺失字段、非法指纹拒绝、已有 pin 优先、Connected 前不写入、失败/取消不写入，以及数据库并发确认和 SSH revision 的保护。仓库端到端 instrumentation 已更新为断言首次自动固定，再验证正确 pin 重连及错误 pin 不被覆盖；本次不重启或改动远端 GNOME 服务，完整界面流程由 Claude 接续验收。

追加验证通过：`./gradlew test` 全模块 JVM 测试（App 667 项，零失败），App Kotlin/debug APK/androidTest APK 构建，`sh -n` 与 helper 隔离夹具，以及 emulator-5560 上匹配 APK 的 Room 三项测试（含首次 pin 条件写入）。未修改 ui/ 或文案文件；自动固定接口与证书变化后的用户确认接口分离。

## 界面联调后追加：活动屏幕立即重连

Claude 在 GNOME 真机服务 + emulator-5560 的界面联调确认首次画面、`Miffan UI` + Return、NLA/AVC420/硬件解码统计成功。随后发现已连接时菜单 Reconnect 先 close 再 open 会报 JSch `session is down`，失败页 Retry 则成功。

关闭租约没有销毁父 SSH，池也会剔除已断开的会话。问题在 `RemoteChannelStream.close` 同步调用 JSch `channel.disconnect()`：它不是纯本地关闭，会发送 SSH CLOSE 包。Android 主线程禁止网络时，JSch 会在发送失败后静默吞掉异常；编码已推进的密码状态令随后复用的 SSH 失效。本地真实 JSch/Apache SSHD 夹具在 socket 输出层模拟这项网络禁令，旧关闭实现后下一条命令失败，新实现连续立即重开成功。依据：[JSch Channel.close/disconnect](https://github.com/mwiede/jsch/blob/jsch-2.28.7/src/main/java/com/jcraft/jsch/Channel.java)、[Session._write 的 encode/put 顺序](https://github.com/mwiede/jsch/blob/jsch-2.28.7/src/main/java/com/jcraft/jsch/Session.java)。远端 sshd 检查未发现协议错误记录，不能单凭服务端日志排除此客户端故障。

修复只改传输层：raw channel 和 PTY 的 close 立即标记对象关闭，真正发送 CLOSE、关闭 input/output/errors 在独立 SupervisorJob + Dispatchers.IO 中完成，不受已取消的屏幕 scope 影响。没有关闭父 SSH、没有修改租约/池策略、没有自动重试请求、没有在 UI 添加延迟。RDP/VNC 都使用这一条原始 SSH 通道清理路径。

VNC 对照使用本地完整 RFB 3.8/None 握手，经实际 SSH direct-tcpip 连续三次 Connected→主线程 close→立即重新打开与执行命令，通过。临时恢复旧同步通道关闭时，此 VNC 夹具同样通过，未复现 RDP 的稳定故障；VNC 的 reader/writer 协程取消可能先从 IO 发起 shutdown，不能据此断言 VNC 在所有调度下都有或都没有同样问题。共用修复保证调用主线程不再发送 CLOSE/EOF，保留 VNC 行为。

Android instrumentation 使用原 P5b 手机内测试公钥和临时 host/workspace，并断言各轮复用同一个 SSH 对象，经 SSH cat channel 完成五次主线程 StrictMode detectNetwork + penaltyDeathOnNetwork 下 close→立即 execute/open，全部通过；只测试共用 SSH 关闭/池复用，不代替完整 RDP 界面验收。未运行 helper rdp start、未改远端桌面、钱包、密码或 TLS 文件。测试后清理临时数据库对象，界面主机和原证书 pin 保留。

追加回归构建：`./gradlew test :app:assembleDebug :app:assembleDebugAndroidTest` 通过；App 667 项、workspace 179 项（SSH 传输测试 15 项，含两项新用例）零失败。emulator-5560 已安装本次 debug APK；完整 RDP 菜单 Reconnect 由 Claude 接续复测。

## KDE Secret Service 修复（helper v9，2026-10-10）

根因依据本次真机复现记录：混装 GNOME/KDE 时，PAM 启动的 gnome-keyring-daemon 先获得 `org.freedesktop.secrets`，默认 login 集合已解锁；ksecretd 的 kdewallet 虽也已解锁，却不是该总线名的提供者。KWallet 6.30 的 kwalletd6 是 Secret Service 之上的兼容外壳，在当前提供者中找不到 kdewallet，所以旧 helper 的 `networkWallet/isOpen` 判断及 kwallet-query 写入失败。`keyring_locked` 在这里实际指向了错误的钱包路径。

helper 升至 v9，KDE 改用 `gdbus` 对当前 `org.freedesktop.secrets` 调用 `ReadAlias("default")`，再查询返回集合的 `org.freedesktop.Secret.Collection.Locked`。只接受存在的持久集合及明确的布尔 false；默认别名缺失、锁定、D-Bus/属性查询失败或格式不符返回 `keyring_locked`，session 集合也不作后备。不创建集合、不初始化无密码钱包、不主动调用 Unlock。缺少 secret-tool/gdbus 等必要工具返回 `credential_setup_unavailable`。本次没有扩展 probe 输出或修改 GNOME 路径。

写入通过 `printf '%s' "$rdp_secret" | timeout 5 secret-tool store`，属性如下；密码不进入 argv、环境变量、配置或日志。secret-tool 非 TTY 路径读取整个 stdin，不剥离换行，因此这里必须用无末尾换行的 printf。

| 属性 | 值及来源 |
| --- | --- |
| `xdg:schema` | `org.qt.keychain`，QtKeychain 的 schema 名 |
| `user` | `miffan-<uid>`，即 krdp 的 `ReadPasswordJob` key |
| `server` | `KRDP`，即 `ReadPasswordJob("KRDP")` service |
| `type` | `plaintext`，QtKeychain 文本密码的首轮查找类型；不是 `password` 或 `base64` |

已按 [QtKeychain 0.17 libsecret.cpp](https://github.com/frankosterfeld/qtkeychain/blob/0.17.0/qtkeychain/libsecret.cpp#L8) 的 schema、`findPassword`（L185–203）及 `writePassword`（L211–232）逐项核对。schema 使用 `SECRET_SCHEMA_DONT_MATCH_NAME`，所以读取按 user/server/type 匹配、不要求 xdg:schema；写入仍显式保存 QtKeychain 的 schema 元属性，与其正常写入格式一致。libsecret 的 [属性序列化](https://github.com/GNOME/libsecret/blob/0.21.7/libsecret/secret-attributes.c#L19) 负责写入 xdg:schema；[krdp 6.7.5 main.cpp](https://github.com/KDE/krdp/blob/v6.7.5/server/main.cpp#L94) 确认 service 为 KRDP、key 为配置用户名。[QtKeychain 后端选择](https://github.com/frankosterfeld/qtkeychain/blob/0.17.0/qtkeychain/keychain_unix.cpp#L116) 确认 `QTKEYCHAIN_BACKEND=libsecret` 可覆盖 Plasma 对 KWallet 的默认偏好。

`--collection` 传入已检查的具体对象路径，不使用 default 别名作为写入目标。[secret-tool 源码](https://github.com/GNOME/libsecret/blob/0.21.7/tool/secret-tool.c#L228) 确认支持绝对集合路径、stdin 按字节读取及属性原样传递；[libsecret store](https://github.com/GNOME/libsecret/blob/0.21.7/libsecret/secret-methods.c#L965) 仅在目标为 default 别名且缺失时尝试创建默认集合，具体路径可避免这种隐式创建。只读检查与写入并非原子操作：若集合恰在两者之间被锁定，上游 libsecret store 的 `SECRET_ERROR_IS_LOCKED` 分支可能请求 Unlock；secret-tool 没有禁用该分支的 CLI 选项，5 秒 timeout 仅限制等待。夹具验证的是预检时已锁定则绝不执行 store，不宣称已消除此上游竞态。

混装环境中，写入与 krdp 读取均经过当前总线名所有者（例如 gnome-keyring 的 login 集合），不再要求它具有 kdewallet。纯 KDE 环境中，若 ksecretd 持有 `org.freedesktop.secrets` 且 default 指向已解锁的 kdewallet，同一路径直接写入该集合。两种环境均要求用户事先有已解锁的默认集合，不设置或更换默认别名。移除 KWallet 后备，避免重新落入总线所有权与钱包名不一致的问题，或让写入端与读取端选择不同后端；旧 KWallet 条目不迁移、不删除，App 已有密码由 stdin 重新写入。

krdp 保持独立 `XDG_CONFIG_HOME`、`SystemUserEnabled=false`、`--address 127.0.0.1 --plasma`、命名 transient unit，参数仍没有 -u/-p。用户已有配置、非自有进程/同名单元及被外部修改的自有配置均拒绝覆盖。证书、owner/digest 与原端口保留；成功启动另记录 0600 的 `krdp.owner.backend=libsecret`。v8 没有此记录，即使密码摘要和端口相同也先写入 Secret Service 并只重启自己的 unit，随后 v9 相同密码启动继续幂等复用。

本次按规格验收清单自检：

1. `python3 rdp/scripts/test-helper.py`、`sh -n app/src/main/assets/remote/miffan.sh`、`git diff --check` 通过。隔离夹具覆盖 login/kdewallet 默认集合写入、精确属性及无换行 stdin、argv 无密码、缺失/锁定/session 集合、查询失败/异常类型、写入拒绝的 stderr 不外泄、缺少工具、libsecret 环境、v8 活动实例迁移、v9 幂等、配置拒绝、权限和持久 DER 指纹。不访问真实 D-Bus 或凭据存储，不替代实际 QtKeychain/Secret Service 集成验证。
2. `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin` 通过，App 668 项测试，零失败/错误/跳过；新增 KDE 凭据错误用例确认即使响应带端点和指纹，错误仍拒绝连接、保留桌面信息且不泄露原始诊断。
3. 未连接 `ayuilos@100.64.0.5` 或任何用户真实账号。真实 Plasma 登录会话下的读取、RDP 认证及画面由 Claude 和用户继续验证；包含上述上游锁定竞态的边界，未宣称真机验收通过。
4. 未修改 `app/.../ui/`，未合并、rebase 或推送其他分支。源码依据、根因、两类提供者行为与未覆盖事项已记录于本节。
