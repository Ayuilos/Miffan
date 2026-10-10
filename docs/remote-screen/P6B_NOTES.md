# P6b：Android `:stream` 原生串流库

日期：2026-10-10；分支：`codex/p6b-stream`。范围为库、脚本和测试，不修改 `app/`。

## 实现

- 包名 `me.rerere.stream`，提供规格中的身份、主机、会话、输入、状态和统计 API。
- 所有 HTTP、HTTPS、RTSP TCP 都由 `StreamTcpConnector.open(remotePort)` 提供；Android 仅建立 AF_UNIX socketpair，并使用两条搬运线程桥接流与 native fd。没有 TCP listener、没有连接本机 loopback。HTTP/HTTPS fd 由 JNI 关闭；RTSP fd 由 common-c 关闭。调用方的 `closeable.close()` 必须能解除阻塞中的读写。
- HTTP/XML 和现代 Sunshine 的 PIN 配对使用 Kotlin；OpenSSL 负责身份生成和 socketpair fd 上的 TLS。TLS 使用客户端证书，最低 TLS 1.2；每次握手按服务端证书 DER SHA-256 比较固定值，然后才写 HTTP 请求。首次配对同时验证服务端密钥签名与 PIN challenge 哈希，最后经固定证书的 HTTPS `pairchallenge` 完成配对；仅成功后更新内存 pin，返回小写 hex，由调用方持久化。
- 未配对、无 pin 的 `serverInfo` 允许使用调用方 HTTP 通道发现服务；已有 pin 的 `serverInfo`、apps、launch/resume 使用 HTTPS。路由白名单不包含 cancel/unpair。主机已有应用运行时使用 resume；每次建流/编码回退均生成独立的新 rikey 和 rikeyid。
- 保留 P6a 的严格 RTSP、视频、音频、控制 V2 加密及 Sunshine 会话标识检查。补丁添加每个 RTSP 请求的 connected-fd 钩子，跳过 UDP 候选地址的 TCP 探测，并使严格加密成为此 fork 的初始化默认值。另修正 socketpair 短写/SIGPIPE，并等待 AsyncTerm 回调退出后才释放 JNI owner，防止旧回调进入下一会话。JNI 无关闭加密的选项。
- 一个进程仅允许一个会话，第二个以 BUSY 失败。关闭不会占用 UI 线程；`awaitStopped()` 在后台等待原生停流、解码器和桥接清理，之后才能开始下一候选。scope 取消也会关闭通道；实际建流期限默认 4000 ms，包括 HTTPS 与首个渲染帧，到期重复调用 `LiInterruptConnection`，直至旧 native 调用返回。Sunshine pending 会话的退避等待不计入此期限，见下文。
- MediaCodec 选择硬件解码器并直接输出 Surface；优先 HEVC。低延迟 feature/KEY_LOW_LATENCY 与 QTI 整数参数分别探测支持后配置。Surface 为 null 时保持网络接收、停止渲染；新 Surface 重建 codec 并请求 IDR。输入缓冲不足请求 IDR。待解码帧自身等待超过 3 秒且没有输出时，先 flush 并请求 IDR；连续三轮仍无输出或连续解码异常才进入编码回退，避免将网络断续误判为 codec 故障。
- 音频仍初始化、接收及解密，回调仅计数，不播放。视频/控制为 AES-GCM，音频为 AES-CBC，不能将“三路加密”解释为音频也具有 GCM 认证。
- 统计包括实际协商编码/尺寸/fps、加密标志、收帧与渲染平均 fps、输入入队至输出可用的解码时间均值/最大值、完整视频帧字节计算的码率、帧号间隙及本地解码丢帧、RTT、codec 名称、硬件及低延迟配置、音频包计数。fps/码率为本次建流首个完整帧起的累计平均，非瞬时窗口；码率不包括 UDP/IP/FEC 额外开销。
- 日志只记录固定错误、验收状态和统计；不输出 PIN、私钥、rikey、launch URL 查询参数或原生 RTSP 日志。
- `scroll(vertical, horizontal)` 原样传递 GameStream/Sunshine 高精度滚轮单位，120 为一格，正垂直值向上、正水平值向右，负值方向相反，遵循 [Windows 滚轮单位](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-mouseinput)；不在库内反转为触控板“自然滚动”。键值为 Windows VK 0–255，modifier 位为 Shift=1、Ctrl=2、Alt=4、Meta=8、Extended=16（E0 scan code）；native 添加协议需要的 0x8000，释放时使用调用方 modifier 值。

## 构建与测试

源码归档及 SHA-256 位于 `stream/scripts/fetch-sources.sh`；离线准备在 `stream/scripts/prepare.py`，解压和补丁仅写入本模块 build 目录，不修改共享依赖原件。Gradle 的 configureCMake 自动依赖源码准备。OpenSSL 静态库仅复用 `rdp/build/native/<abi>`。

```sh
stream/scripts/build-native.sh                    # arm64-v8a + x86_64
./gradlew :stream:testDebugUnitTest :stream:assembleDebugAndroidTest
./gradlew :rdp:assembleDebug :app:assembleDebug
python3 stream/scripts/test-emulator.py pair      # PIN 隐藏输入；身份留在测试包私有 prefs
python3 stream/scripts/test-emulator.py pin       # Mac 新证书 TLS fixture；核对 HTTP 0 字节
python3 stream/scripts/test-emulator.py lifecycle
python3 stream/scripts/test-emulator.py strict
python3 stream/scripts/test-emulator.py stream --codec hevc --software --candidate --timeout 15000
python3 stream/scripts/test-emulator.py stream --codec h264 --timeout 15000
python3 stream/scripts/test-emulator.py stream --codec h264 --fallback --timeout 30000 --width 1280 --height 720 --fps 30 --bitrate 5000
```

脚本只操作 `emulator-5560`；TCP 测试 connector 为 `10.0.2.2` 同名转发端口，UDP 正确候选为 `100.64.0.5`。仅发送鼠标 +5/-5 px 和独立 Shift 按下/抬起。统计保存于 `stream/build/test-output` 及测试包 external files，身份/私钥/PIN 不进入仓库。

已通过：5 项 JVM 测试（URL/路由、XML、HTTP framing、数字地址、错误/输入映射）；两个 ABI 原生构建；rdp/app debug 构建；模拟器 pin fixture（TLS 握手完成后 HTTP 0 字节）；取消握手、close 幂等及进程锁释放；明文 RTSP 拒绝（-110，TCP connector 调用 0 次）。

原生库尺寸（字节；NDK 28.2.13676358、CMake 3.22.1；ELF LOAD 对齐 16 KiB）：

| ABI | Release 未 strip | APK 中 strip 后 |
| --- | ---: | ---: |
| arm64-v8a | 10,416,008 | 6,305,392 |
| x86_64 | 10,449,960 | 6,692,440 |

## 真实主机验收

仅操作 `emulator-5560`，主机证书固定值为 `b1afd9ddaa2c2f90559b3cf77ef9882688aba8130d40453cf246e22e905a1d17`。本模块新生成身份的配对请求于 17:19:18 发出，用户在 Sunshine 网页输入 PIN 后，17:19:56 完成签名/challenge 校验、固定证书 HTTPS 和配对状态验证。PIN 通过 stdin 写入测试包私有 cache 并在使用后删除；身份保存在本任务测试包私有 prefs，没有导出私钥。此前 16:45:39 的请求等待超过 5 分钟后关闭。

两种编码均配置 1920×1080@60、请求 15000 Kbps，在首帧后持续运行至少 30 秒，并在中途替换真实 SurfaceView。以下是累计统计快照，不能解读为达到 60 fps 性能验收：

| 编码 / 测试时间 | 收帧 / 渲染帧 | 收帧 / 渲染 fps | 解码均值 / 最大 ms | 丢帧 | 有效码率 Kbps | RTT ms | 音频包 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| HEVC / 17:52:58 退避修正后 | 266 / 47 | 8.45 / 1.49 | 2239.80 / 5239.60 | 1954 | 1171.41 | 69 | 6269 |
| H.264 / 最终诊断 APK | 94 / 69 | 2.97 / 2.18 | 820.60 / 4339.03 | 1757 | 555.71 | 234 | 4899 |

- 两轮 `videoEncrypted/audioEncrypted/controlEncrypted` 全为 true；空音频回调持续收包。小幅鼠标 +5/-5、独立 Shift、BUSY 拒绝及 Surface 恢复均通过，截图可见主机桌面；没有向主机输入文字或快捷键。
- HEVC 为 `c2.android.hevc.decoder`，仅测试打开软件解码例外；该模拟器无可用 HEVC 硬件 decoder。H.264 为 `c2.goldfish.h264.decoder`，Android 报告 hardwareAccelerated=true。两个 codec 都未公布 LowLatency feature；lowLatency=false。模拟器结果不替代物理手机硬件/低延迟验收。
- 不可达 `192.0.2.1` 候选在约 4.2 秒内返回 UDP_UNREACHABLE（stage 8、-202），包括停止清理；立即创建/启动正确候选，无 BUSY 或旧回调干扰，自动退避后成功。已有应用使用 resume，全程未调用 cancel/unpair，没有 SSH 或改动主机配置。
- 主机协调方确认编码器和控制流无错误、转发正常；同主机同 Tailscale 路径的 P6a Mac probe 可达约 58.8 fps。结合本轮丢帧与慢解码，模拟器用户态 UDP 转发/解码吞吐是限制的候选原因，不将其当作已证明的唯一原因。
- 原生日志仅按固定格式计数 `fecFailureEvents`（不可恢复帧/块事件，非精确包丢失率）和 `idrRequestsSent`（实际发出的 IDR 请求）；不展开原生日志的可变参数。上表 1080p60 复验中 H.264 分别为 123 / 50、HEVC 为 1 / 172；此前 17:46 HEVC 一轮为 0 / 190，解码输入经常不足，不能把全部丢帧归为网络包损失。
- 17:43 的 1280×720@30、5000 Kbps 对照（HEVC 硬件不可用后自动回退 H.264）通过 30 秒、三路加密和 Surface 替换：514 收帧 / 441 渲染，16.45 / 14.05 fps，解码均值 179.68 ms / 最大 735.44 ms，丢帧 504，有效码率 1924.23 Kbps，RTT 59 ms，音频 6198 包，FEC 不可恢复事件 15、IDR 请求 53。低负载有明显改善但仍不等于稳定 30 fps，真机与更长时间验收留给 P6c。

## API 补充与边界

- `StreamConfig.connectTimeoutMillis`（默认 4000）和 `StreamSession.awaitStopped(timeoutMillis)` 为规格所需候选期限与清理等待的补充。
- Sunshine 在控制 UDP 尚未连通时，已有 pending RTSP 密钥不会被新的 resume 替换。主机日志为 17:20:36.419 创建不可达候选会话，17:20:39.448 报 `Failed to verify RTSP message tag`；中间没有客户端连接或 pending 结束日志。这支持 P6A_NOTES 3.3 的单 pending 判断：新 resume 的 RTSP 被旧 launch event 的密钥验 tag，而不是按主机策略拒绝请求；control 未建立时不会调用 launch_session_clear，须等默认 ping_timeout 约 10 秒。
- 库对未建立 control 的失败会话，从 launch/resume **HTTP 返回后**起算 10 秒 + 1 秒余量，对同一个 StreamHost 的下一次 start 自动等待后再打开 TCP/发新请求；HTTP 异常或回复丢失时，保守地从请求异常结束时起算。不能从发送请求前起算，否则慢转发会吃掉余量。以 `host.retryAfterMillis` 和 stats 同名字段暴露等待，不调用 cancel，也不以缺失的服务端结束日志作为完成信号。
- 17:52 的修正后复测：错误 UDP 候选 4085.80 ms 后以 UDP_UNREACHABLE（stage 8、-202）停止；17:52:17.367 立即 start 正确候选时剩余退避 8098 ms。验收 connector 实测首个新 TCP open 距 start 为 8117.30 ms，断言未提前打开连接通过；17:52:28.235 已进入 Streaming，随后加密 HEVC 持续至少 30 秒，Surface 替换恢复，测试 `OK (1 test)`。同轮 5 项 JVM 测试及测试 APK 双 ABI 构建通过。
- 退避等待不计入实际建流期限；因此“立即 start”成立，“立即重新 launch 且总耗时 4 秒成功”在该主机上不成立。等待与当前候选失败前已耗时合计到 HTTP 返回后的 11 秒；默认实际建流总期限为 4000 ms，可通过 `connectTimeoutMillis` 配置。P6c 选择候选应复用同一个 StreamHost，并呈现此等待。若主机 ping_timeout 改为非默认值，固定的 11 秒窗口需相应调整。
- 模拟器正确候选显式设为 15000 ms；默认 4000 ms 保留且错误 UDP 候选仍使用 4000 ms。冷 codec 初始化、转发 RTSP 和首帧曾超过默认期限；这不是默认 4 秒成功的验收。编码回退对照显式使用 30000 ms，初始 HEVC/H.264 回退共享预算。
- HEVC 自动回退仅当调用方同时允许 HEVC/H264；仅允许 HEVC 时将解码失败明确返回。初次建流的回退仍使用同一个总期限；已经 Streaming 后发生持续解码故障，再建流使用新的建流期限。
- `allowSoftwareDecoder` 仅为模块 internal 的模拟器测试开关，生产 API 不暴露；默认要求硬件解码。若验收模拟器只有软件 codec，应明确记为模拟器限制，不能视作物理 Android 硬件解码验收。
- close 是异步的；Closed/Failed 最终状态在 native 清理完成后发布。BUSY 不触碰当前 native 会话。输入释放尽力发送，网络已失联时无法保证主机收到；原生 stopInputStream 会先排空发送队列。
- 未覆盖物理 Android 手机、IPv6 实网、切网、公网链路、QTI 真机厂商键与长时间运行稳定性。
