# P6e 数据层、声音、流量与 CI

## 实现

- 保持 `P6E_SPEC.md` 的接口契约；未改 UI、`RemoteScreenVM.kt` 或字符串资源。
- `RemoteStreamRequest` 默认为 BALANCED、不 skip。三档分别为 1280×720/30/4000、1920×1080/60/10000、2560×1440/60/20000（kbps）。BEST 利用现有 helper probe 的 `rdp.width/height`，按 16:9 等比例缩小到不超过显示器，向下取整到 16×9 的整数倍；缺少或无效几何信息时保持 2560×1440。SAVER/BALANCED 不封顶，RDP 仍由服务器决定。
- `streamRequested = host.streamEnabled && !request.skip`，包括非 Linux 或尝试失败的情况。整个 Sunshine 探测/打开路径都在此条件内；skip 不探测 Sunshine，不生成 fallback。成功才返回 `streamAddress`，使用实际选中的规范化 UDP 数字地址。换档仍通过重新 open。
- `StreamDesktopSession.audio` 代理库的控制器，其他协议 `audio == null`；原有 STREAM 表面与输入适配行为保留。

## 流量口径

`StreamSession.bytesReceived` 为会话内累计的**视频 + 音频应用层 UDP 接收载荷**。计数在 common-c 的两个 `recvUdpSocket` 成功返回之后、校验/解密/FEC 之前发生，因此包含 RTP、加密附加数据、FEC、重复包及收到但最终丢弃的包；不包含 IP/UDP 头、TCP/SSH/RTSP 控制、发送方向和系统层丢失的数据。静音与无 Surface 时仍计数。

原生原子计数约每 100 ms 合并到 Kotlin 的 AtomicLong；原生会话退出时合并尾数。HEVC→H.264 重试不重置累计值；每个 StreamSession 独立，关闭后可读取最终值。`StreamDesktopSession.bytesReceived` 返回此计数。原有 `StreamStats.bitrateKbps` 仍是解码视频压缩帧码率，口径不同。

`0006-media-receive-bytes.patch` 仅作用于 `stream/build/sources`，未修改共享 `.deps/p6a` 源码缓存。

## 声音

- common-c 仍负责 RTSP 协商与音频解密。JNI 传递实际采样率、声道、stream/coupled stream、samples/frame 与 mapping；构造 OpusHead 和 MediaCodec 要求的三个 CSD，支持协商的立体声 multistream mapping。
- 使用系统 `audio/opus` 解码器输出 PCM16 到 `USAGE_MEDIA`、`PERFORMANCE_MODE_LOW_LATENCY` 的 streaming AudioTrack。缓冲取系统最小值与 20 ms 中较大者，所有写入均为非阻塞。
- 默认关闭，不创建 MediaCodec、AudioTrack 或工作线程，也不申请音频焦点。关闭仍接收、解密并丢弃音频。
- 开启申请 `AUDIOFOCUS_GAIN`；焦点被拒绝时保持关闭。失去焦点（包括 transient/duck）立即将 enabled 置 false、清空队列、释放 codec/track 并放弃焦点；不自动恢复。旧播放代次的焦点回调不能关闭新播放代次。
- 输入包队列超过约 100 ms 或 50 包时清除旧数据；解码输出按单调时钟时间戳丢弃超过 100 ms 的 PCM。AudioTrack 的待播帧数/最老 PCM 时间超过约 100 ms 时 pause/flush/play 丢弃旧数据。非阻塞写不完整时丢弃未写尾部，防止不断积压。
- 关闭与会话结束同步释放声音资源；音频解码错误只关闭声音，不破坏视频连接。没有麦克风录制/发送路径。
- `StreamStats` 新增 decoder 名称、track active、written/played frames、backlog drops 和不含主机信息的 audioError，用于验收与排查。

API 依据：[MediaCodec 的 Opus CSD 规定](https://developer.android.com/reference/android/media/MediaCodec)、[AudioTrack 缓冲与播放头](https://developer.android.com/reference/android/media/AudioTrack)、[音频焦点](https://developer.android.com/media/optimize/audio-focus)。Android 15+ 且 target 35+ 需要前台 app 或前台服务才能获得焦点；此处只在屏幕页由用户开启播放，焦点拒绝会体现在 enabled/audioError。

## CI

`ci.yml`、`daily-build.yml`、`release.yml` 均先恢复/构建 RDP 原生库（含 stream 链接所需 OpenSSL），然后缓存三个精确路径的源码包，并**无条件**执行 `stream/scripts/fetch-sources.sh`。缓存 key 包含 OS 和 fetch 脚本哈希；不缓存提取后的源码或带绝对 worktree 路径的 CMake 目录。命中缓存也必须逐包 SHA-256 校验。Gradle 后续通过 prepare.py 重新校验、解包和应用补丁。

没有触发远程 GitHub Actions 或重新从互联网下载三个包；已用本地已校验的源码包模拟下载链路。复现：

```bash
stream/scripts/fetch-sources.sh       # 已有包则离线验证 SHA-256
python3 stream/scripts/test-source-fetch.py
python3 stream/scripts/prepare.py
./gradlew :stream:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug
```

`test-source-fetch.py` 在临时目录复制 fetch 脚本，以本地包模拟 curl，检查冷下载、缓存命中不下载、损坏缓存拒绝、损坏下载不提升 .part 四种情况，不写共享缓存。三个工作流的 YAML 与 RDP→stream fetch 顺序也已本地检查。

## 验证记录

2026-10-10，在 `codex/p6e-data` 验证：

- `./gradlew :stream:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :stream:assembleDebugAndroidTest`：BUILD SUCCESSFUL（最终生产代码构建 34 s）。stream 8 个 JVM 测试、app 683 个 JVM 测试全部通过，无失败、错误或跳过。覆盖档位和比例封顶、无效主机尺寸、skip/主机关闭时零 Sunshine 调用且无 fallback、请求与地址元数据、Opus CSD/mapping 和 100 ms 时间阈值。
- `stream/scripts/fetch-sources.sh` 三个包 SHA-256 OK；`test-source-fetch.py` 四种模拟 PASS；`prepare.py` 全部补丁零 fuzz 成功；两种 ABI 的 stream CMake 原生构建成功。
- emulator-5560：`audioAndTrafficAcceptance` **OK (1 test)**，20.458 s。保留既有配对身份，未重新配对。1280×720/30 fps/4000 kbps，H.264，显式允许模拟器软件视频解码。开启声音的实际 Opus 解码器为 **c2.android.opus.decoder**，AudioTrack active，PCM written=236072 frames、播放头累计 played=172944 frames，audioError=null。
- 关闭后 1.5 s：written/played 原样不变、AudioTrack inactive；bytesReceived 从 **4,407,340 → 5,145,668**，audioPackets 从 **1451 → 1753**，说明静音仍接收。焦点被另一个本地 AudioFocusRequest 抢占后 enabled=false、track inactive、写入不再增长；之后可重新开启。最终 close 后 enabled=false、track inactive，累计流量 **5,441,580 bytes**，backlog drops **87 次**（开启阶段 84，后续重开 3），audioError=null。丢弃计数是事件次数，涵盖清空队列、过期 PCM、AudioTrack flush 与不完整写入，不能直接换算成包数或字节。
- 首轮音频验收已验证开启/关闭与流量，但在焦点回调刚发布 enabled=false 时提前读取 track active，导致一次断言失败。验收改为等待 enabled=false **且** track inactive，再验证写入停止；重跑完整流程通过，未放宽资源释放要求。
- 本地证据位于 `stream/build/test-output/audio-hevc.txt`（文件名沿用脚本 codec 参数；audio 模式实际固定 H.264）及 `p6e-audio-diagnostics.txt`；它们是忽略的构建产物，不提交设备日志。

模拟器只运行新增 `audio` 模式：

```bash
./gradlew :stream:assembleDebugAndroidTest
python3 stream/scripts/test-emulator.py audio --software --width 1280 --height 720 --fps 30 --bitrate 4000 --timeout 10000
adb -s emulator-5560 logcat -d -s StreamAcceptance:I '*:S'
```

此模式不发送按键、鼠标、文字或剪贴板；原有 `stream` 模式包含输入动作，本次未用。需要的 TCP 转发由 Claude pane 负责：模拟器 10.0.2.2 的 47989/47984/48010，经 Mac loopback 到主机同名端口；UDP 使用 100.64.0.5。使用既有测试包 prefs 身份，不读取/复制私钥，不 ssh 或修改主机配置。


## 遗留与验证边界

- 未触发 GitHub Actions，因此真实 runner 的网络下载和 release/nightly 构建还需工作流运行验证；本地已验证脚本、缓存校验、顺序及 debug 原生集成。
- 本次是模拟器的真实 Sunshine 串流与 AudioTrack 播放头验收，不是物理手机听感/端到端音画同步测试。模拟器视频解码存在卡顿（本次输出约 19.6 fps、最大解码耗时约 2 s），积压丢弃确实发生；未据此声称硬件性能或连续无丢音。真实设备与不同厂商 Opus 解码器仍需回归。
- 系统最小 AudioTrack 缓冲可能大于 20 ms，低延迟性能模式由设备决定；不支持所协商立体声格式/Opus 解码时声音关闭，视频继续。当前只实现规范要求的立体声，不提供多声道或原生 libopus fallback。
- UI 的档位菜单、省流策略、声音按钮、横屏与 RemoteScreenVM 接线由 Claude 的任务负责，本提交只提供规范约定的数据层接口。
