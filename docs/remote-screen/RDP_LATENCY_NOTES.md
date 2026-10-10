# RDP 解码延迟测量与低延迟配置

日期：2026-10-10，分支 `fix/rdp-decoder-latency`。仅修改 RDP 模块、本文，以及按 Claude 最新要求新增的 App SSH 测量 instrumentation；没有修改 `miffan.sh`、App UI 或连接 `ayuilos`。原始真机反馈是 OPPO Find X6 Pro / SM8550、2560×1440、NLA / AVC420、`c2.qti.avc.decoder`，解码与绘制均为个位数 fps。这是用户观察，尚不是分段计时结果。

## 测量口径

`RdpStats` 新增桌面尺寸、解码输出累计数/帧率、解码排队到输出耗时、当前/窗口峰值在途数、未出帧输入的年龄、输出等待超时次数、YUV→RGB、sink、surface→实际 ACK、ACK 发送队列等待，以及低延迟能力、被 configure 接受的参数和 NEON 状态。时间均使用单调时钟；耗时字段有最近一次、约 2 秒窗口平均/最大值。空窗口均值/最大值为 0，最近一次保留，不能将静止桌面的 0 fps 当作解码性能。

- `decodeMs`：本次 H.264 access unit 首次 queueInputBuffer 前到 dequeueOutputBuffer 返回有效输出；不含等待输入 buffer、输出布局检查和 CPU 色彩转换。`decodedFrames` 是输出 buffer 数，AVC444 双路可能每个 RDP 帧产生多于一次输出。
- `inFlightFrames`：提交但尚未取出的 access unit 数；现有同步 API 正常时峰值为 1。每 100 ms 等待超时计数，并在等待期间发布统计，因此停在首帧时也能看到 `pendingDecodeMs` 增长。
- `yuvToRgbMs`：包围 `yuv420_context_decode` / `yuv444_context_decode`，包含转换 worker 的等待；AVC444 同时包含平面重组。每次转换调用一个样本，不应将 AVC444 样本数量当作桌面帧数。
- `sinkMs`：EndPaint 中 JNI IntArray 分配、复制/不透明 alpha、Kotlin onPixels 和 onFrameComplete。后续 UI 绘制和 GPU 上传不在此同步回调内，不能从该数值推断 GPU 上传时间。
- `surfaceToAckMs`：本帧第一个 SurfaceCommand 进入到 FrameAcknowledge 的 `SendChannelData` 成功返回（经过 TLS/JNI/SSH output.write/flush）。包括解码、转换、GDI 合成、sink、后续命令/EndFrame 到达间隔及 ACK 排队；不是网络 RTT。FreeRDP 内部绕过 `common.FrameAcknowledge`，且 dynamic channel Write 仅入队，因此入队 hook 按 frameId 保存时间，包裹原 SendChannelData，在精确匹配 DRDYNVC DATA / 20-byte FRAMEACKNOWLEDGE 后完成采样。不改变协议数据或原发送结果。没有 surface command 的 ACK 不生成此项样本。`ackQueueMs` 单独测入队到发送成功的等待。诊断关联队列有 256 条上限。
- `neonYuv`：arm64 上同时检查 CPU NEON 能力与实际选中 YUV420 primitive 是否区别于 generic；FreeRDP 该 NEON primitive 对 BGRA32 有专门实现，构建也开启 WITH_SIMD。x86_64 返回 false。

Logcat tag 为 `RemoteScreenPerf`，运行期间约每 2 秒一行，并保留解码器选择、能力查询、configure 尝试的诊断。不记录用户名密码、私钥、剪贴板或桌面文本。

## 低延迟配置及依据

1. API 30+ 枚举 regular AVC 解码器，优先选声明 `FEATURE_LowLatency` 且支持请求尺寸的非 alias decoder。创建失败时仍按系统默认 AVC 解码器创建。实际名称来自 NDK codec，而非根据编译选项猜测。
2. 仅在实际解码器声明该 feature 时设置 `low-latency=1`。设置 `priority=0` 作为实时优先级提示。Android 明确将低延迟模式定义为不保留超出编码标准需要的输入/输出；priority 是资源规划提示，并不保证耗时：[MediaFormat](https://developer.android.com/reference/android/media/MediaFormat#KEY_LOW_LATENCY)、[FEATURE_LowLatency](https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#FEATURE_LowLatency)。
3. 对 `c2.qti.*` / `OMX.qcom.*`，API 31+ 在同名临时 codec 上调用 `getSupportedVendorParameters` 和 `getParameterDescriptor`，确认键存在且为整数才尝试 `vendor.qti-ext-dec-low-latency.enable=1`、`vendor.qti-ext-dec-picture-order.enable=1`。probe 立即 release，查询失败安全跳过 vendor 键；低版本不猜测 OMX vendor 键。所有含 latency / picture-order 的已声明键名也记录到日志，以便核查不同 Codec2/固件的命名。
4. 查询方式遵循 [Qualcomm 的 vendor 扩展说明](https://www.qualcomm.com/developer/blog/2023/07/building-media-rich-android-apps-mediacodec-and-vendor-extensions) 和 [Android API 31 vendor 参数枚举](https://developer.android.com/reference/android/media/MediaCodec#getSupportedVendorParameters())。两个候选键及逐步回退参考 [Moonlight MediaCodecHelper](https://github.com/moonlight-stream/moonlight-android/blob/master/app/src/main/java/com/limelight/binding/video/MediaCodecHelper.java)，其 Qualcomm 前缀同时包括 OMX 和 c2；并不据此声称用户的 OPPO 固件必定支持两键。没有使用第三方 fork 的 output-fence、instant-decode 或其它未验证参数。
5. configure 拒绝可选参数时重建同名 codec，依次撤去 picture-order、vendor latency、standard latency、priority，最终尝试原始 plain 配置。API 26/27 无 codec name 时按 MIME 重建。统计中的 `decoderConfiguration` 仅表明 configure 成功接受的请求，不表示驱动真的减少缓存；真机仍要对照计时验证。

`RdpOptions.lowLatency` 默认 true；仅诊断对照设为 false。false 使用原来的系统默认 decoder / plain 格式，测量探针仍开启。

## API 语义、超时与后续优化

当前 FreeRDP Decompress 同步返回 YUV 指针；GDI 紧接着按当前 SurfaceCommand 的 regionRects 转换并合成，EndFrame 后 ACK 入队。AVC444 还把同一帧的两条码流重组。仅把 dequeueOutputBuffer 改为非阻塞、无图像时返回成功，或者在等待中重入 FreeRDP 收下一帧，都无法保留当前命令/脏矩形/双路帧对应关系。异步多帧流水线须同时保存帧和 surface 生命周期、region 元数据、输出顺序及 ACK 完成语义，不能只修改一个等待 timeout。

本次保留同步一进一出，先交付可验证的低延迟解码器选择与配置。是否仍有攒帧，必须由配置后的高通真机 `decodeMs`、`pendingDecodeMs` 和等待计数确认；goldfish 结果不能排除高通问题。尚未实现多帧在途流水线，不能宣称这部分已经完成。

额外修正：原 5 秒 codec error 会被 GDI 的“忽略更新”路径吞掉，可能继续提交下一帧并错误关联旧输出。到达 5 秒上限时现在通知 Kotlin 令会话失败并关闭，避免持续提交与无期限追赶；取消仍快速返回，不误报超时。

源码确认 ACK 在 EndFrame 后入队，`freerdp_check_event_handles` 先处理 core，再排空 channel 队列。即使同一调用内最终发送，仍可能被 core 中后续帧的解码拖延；新增 `ackQueueMs` 判断这一项。本次未以猜测移除 Sleep(10)；CPU/GPU 路径也暂未优化，后续按分段数据决定，不声称有 CPU 优化的前后收益。

## 可复现构建和测试

`0002-decoder-latency.patch` 在已有取消补丁之后应用。builder 从已校验的本地 FreeRDP 源码包恢复两个被修改文件，逐个使用 `--fuzz=0` 应用全部补丁，避免仅检查一个 marker 而静默漏用新补丁；不下载源码、不提交 native 产物。

```sh
rdp/scripts/build-native.sh
bash rdp/scripts/test-native-perf.sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :rdp:testDebugUnitTest :app:compileDebugKotlin :app:assembleDebug :app:assembleDebugAndroidTest
python3 rdp/scripts/test-latency.py --measure-seconds 30
```

最新测试路径按 Claude 更正使用 App SSH：只允许固定 `miffanrdp@100.64.0.5:22` 和 `/home/miffanrdp`，使用 App 内已授权的 P5b key（不生成或导出私钥），复用已验证的 SSH fingerprint。优先复用已有测试账号 host/密码；若没有对应 host，按 P5b 建临时 test host 并由 App 凭据存储生成密码。两轮均使用同一密码，仅通过 stdin 调用已安装 helper 的 `rdp start`，不安装/升级 helper、不手工更改远端配置。RDP 证书使用固定 pin 或 SSH helper 返回的指纹严格比较。通过 SSH direct-tcpip 打开 loopback stream，无 Mac 隧道。临时 workspace/host 在 finally 清理，已有 host、pin 和凭据保留。

每轮客户端请求 2560×1440，首帧断言实际尺寸和非空图像，预热 2 秒，再记录 30 个每秒 stats 样本；off 在前，on 在后。PNG 在测量后压缩，并断言解码/转换/sink/真实 ACK 钩子都有样本、NLA、AVC420、NEON。该 sink 复制到 IntArray，不能代表 App Bitmap/GPU 绘制性能。每次运行结果保存在独立时间目录 `rdp/build/test-output/ssh-latency/<timestamp>/`（忽略，不提交）：`gnome-off/on.txt`、`gnome-off/on.png`、`perf.txt`、`instrumentation.txt`。发生 App 冷启动 ANR 后可使用 `--skip-install` 重试，但必须确保安装的 APK 与本次构建匹配；runner 检查新 ACK 字段以拒绝旧测量版本。

旧 P5a `test-emulator.py` / 13390 fixture 未用于有效基准。11:2x 那次在误确认之后尝试旧隧道，连接 ECONNREFUSED，没有有效 A/B 数据，明确作废。其本地关闭/握手取消两项通过不能代替远端测量。

## 验证结果

- 两 ABI 原生重编成功，增量构建各约 2 秒；没有重新下载源码。
- `:rdp:testDebugUnitTest` 7 项零失败，`:app:compileDebugKotlin` 通过；App / androidTest APK 构建通过。
- `:rdp:lintDebug` 通过，涵盖 Android API 30/31 的版本保护。
- Native ACK parser 检查通过：一/二/四字节 DVC ID、截断、额外字节、非 ACK、fragmented DATA_FIRST、保留位和错误 GFX header 均覆盖。
- emulator-5560 的本地幂等 close / 握手取消 2 项通过。
- 最终 App SSH A/B 通过（`OK (1 test)`，99.119 秒）。结果目录 `20261010-114254-179350`；两轮 PNG 都取得，on PNG 已目视检查为最大化 Alacritty 持续刷新随机文本。首轮开发版本 ACK 只统计入队，不作为下面最终数据。重测准备阶段另遇到 App 冷启动内存压力 ANR、一次 SSH TCP 超时，均未进入 RDP，不计入 A/B；Claude 确认可达后直接重试成功。
- OPPO 高通真机分段数据、低延迟是否有效、AVC444：待 Claude 打包交给用户验证，未宣称通过。

## 最终模拟器 A/B 数据

环境：emulator-5560，`c2.goldfish.h264.decoder`，Android 标记 hardware；GNOME 51 headless，实际 2560×1440，NLA / AVC420，NEON=true，持续随机文本刷新。off 为 plain；on 的 `FEATURE_LowLatency=false`，仅配置 `priority=0`，不会尝试 QTI 键。此处“on”是默认低延迟策略开关，不能描述为 goldfish 已启用标准低延迟模式。

| 指标 | off | on |
|---|---:|---:|
| 采样点数 | 30 | 30 |
| 首尾样本实际间隔（秒） | 29.235 | 29.214 |
| sink 完成帧数增量 / fps | 60 / 2.05 | 31 / 1.06 |
| 解码输出数增量 / fps | 60 / 2.05 | 30 / 1.03 |
| decode 窗口均值中位数（ms） | 51.48 | 50.60 |
| YUV 窗口均值中位数（ms） | 13.51 | 14.78 |
| sink 窗口均值中位数（ms） | 40.24 | 39.94 |
| surface→实际 ACK 窗口均值中位数（ms） | 193.10 | 203.47 |
| ACK 排队→发送窗口均值中位数（ms） | 2.43 | 1.50 |
| ACK 队列窗口最大值中的最大值（ms） | 30.11 | 30.53 |
| 在途峰值 | 1 | 1 |
| 输出等待超时累计数：首样本→末样本 | 2→3 | 1→1 |

fps 用 `(末样本计数−首样本计数) / (末 elapsedMs−首 elapsedMs)`，不假设每次 delay 恰好 1 秒。耗时表取 30 行中非零约 2 秒窗口均值的中位数；窗口会被相邻样本重复读取，不能解释为每帧整体算术平均。预热仅 2 秒，首个已发布窗口仍可能包含首帧开销；off 的 decode 最大值 716.10 ms 属于该首窗口。因此上述结果是一次固定顺序诊断对照，不能据此证明 priority 改善或恶化，更不能外推到 QTI。前一轮开发版本的帧率方向也不同，模拟器负载影响显著。

本轮 ACK 入队到真正发送的典型等待是约 1–2 ms，并非持续额外 10 ms；暂不以此修改事件循环。YUV 与 JNI/sink 都有可见成本，但没有覆盖真实 Bitmap/GPU，也没有受控优化前后实验，暂不做 CPU 路径改写。整体帧率仍很低，表中各段耗时无法独自解释全部帧间隔；surface→ACK 还包含命令到达间隔，不能把它全归因于解码或当作端到端延迟。goldfish 不存在持续输出等待超时，本轮没有提供低延迟配置后仍攒帧的 QTI 证据。

交给 Claude 的真机验证重点：日志中的实际 `c2.qti.*` 名称、`vendorLatencyParameters`、FEATURE_LowLatency、configure 接受的键；持续变化画面下 decode / pendingDecode / outputTimeouts 与 sink / surfaceToAck / ackQueue。若低延迟配置后仍出现高通输入等待输出且缺少下一帧输入，则进入保存 SurfaceCommand 元数据与 ACK 生命周期的流水线改造；本提交未实现这一条件步骤。
