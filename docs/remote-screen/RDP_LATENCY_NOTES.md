# RDP 解码延迟测量与低延迟配置

以下为第一轮记录；第二轮实现和最终对照见文末，后者更新事件循环、ACK 和 sink 行为。

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


## 第二轮：真机反馈后的实现

基线来自规格末尾的 OPPO 数据：`c2.qti.avc.decoder.low_latency`，标准 low-latency 与 qti-picture-order 被接受，pending=0、timeout=0；decode 40、YUV 17、sink 41、surface→ACK 244、ACK queue 3 ms。攒帧问题已消除，因此继续保留同步解码语义，不做多帧在途。以下新指标需要 Claude 接入浮层；Logcat 和 RdpStats 已提供。

### 1. 拆分帧耗时与事件驱动

- `frameDataMs` / Mean / Max：第一个 SurfaceCommand 到 EndFrame 回调入口，表示 FreeRDP 解析到帧末的时间。它包含此前 SurfaceCommand 处理，并非纯网络收包时间；不把这个指标称为端到端延迟。
- `surfaceWorkMs`：该帧各 SurfaceCommand 原回调耗时之和，包括解码、转换及相关 GDI 工作；`loopWaitMs`：同一帧首个 SurfaceCommand 后，在事件循环 Sleep / WaitForMultipleObjects 中累计的真实等待。两者均按帧采样，有 last / mean / max。
- `composeMs`：EndFrame 原 GDI 回调耗时，减去其中同步 sink 的耗时。这样能区分“已收齐 EndFrame 之后的合成”与此前命令处理。
- `transportReadCalls` / `transportReadBytes` 为累计成功 BIO/JNI 读取次数/字节，`frameReadCalls` 为首个 SurfaceCommand 后到 EndFrame 的成功底层读取次数。0 不表示没处理 PDU，可能帧数据已经在 TLS/FreeRDP 缓冲中。

源码依据：[FreeRDP 3.32.1 transport_check_fds](https://github.com/FreeRDP/FreeRDP/blob/3.32.1/libfreerdp/core/transport.c) 每次处理一个 PDU，随后设置 rereadEvent 要求马上再处理；原 loop 不理会此事件，每次无条件 Sleep(10)。数据在 TLS 缓冲中时也会按 PDU 累积睡眠。新 loop 使用 `freerdp_get_event_handles` / `WaitForMultipleObjects`，包括 reread、channel 输出队列和 stream 的 WinPR event。接收线程、输入入队、剪贴板、暂停和关闭立即 SetEvent；闲置时有 50 ms 上限供 FreeRDP 定时维护及统计，数据/命令不必等待该上限。

防丢唤醒：先 ResetEvent，再检查 Kotlin 的缓冲区/关闭状态及 wakeGeneration，再等待；生产者在此期间要么留下可见状态，要么在 reset 之后 SetEvent。WinPR handle 的注册、signal 和注销使用同一 Kotlin 锁；销毁之前注销，避免 reader/input 线程触碰已释放的 handle。没有重入解码器或同时处理下一帧。

### 2. 成功解码后先 ACK，再复制

[MS-RDPEGFX 2.2.2.13](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-rdpegfx/0241e258-77ef-4a58-b426-5039ed6296ce) 要求回应 EndFrame，含义是逻辑帧成功解码；并不要求界面复制或 GPU 绘制完成。[FreeRDP gdi_EndFrame](https://github.com/FreeRDP/FreeRDP/blob/3.32.1/libfreerdp/gdi/gfx.c) 先 UpdateSurfaces 合成到 primary buffer，随后 [rdpgfx_recv_end_frame_pdu](https://github.com/FreeRDP/FreeRDP/blob/3.32.1/channels/rdpgfx/client/rdpgfx_main.c) 才产生 ACK。

在原 EndFrame 的 GDI 合成期间，EndPaint 只留下 dirty region，暂不分配 IntArray / 调 Kotlin sink。原 GDI EndFrame 成功返回、原 FrameAcknowledge/QoE ACK 入队后，新增 completion hook 排空**仅已有 channel write 消息**，由原 SendChannelData 真正发送 ACK，然后在同一 worker 复制 dirty region 并完成 sink。没有调用包含 channelEvents 的 `freerdp_channels_process_pending_messages`，没有在 frame callback 内处理输入 PDU。没有创建第二套 ACK、改 frameId 或提前确认未完成的解码。

支持多输出 surface 合成，保留累计 primary dirty region 到 flush 后再清除。ACK 被服务端协商为 suspend/禁用时仍交付 sink；paused/错误/取消沿用原关闭和丢弃规则。`ackBeforeSinkFrames` 仅在原 transport send 成功且当前帧仍有 deferred sink 时递增，是时序验证计数。副作用是 sink 抛错可能发生在 ACK 已成功发送之后；此时会话关闭，帧缓冲的解码/合成已经完成。

`0004-ack-before-sink.patch` 为 FreeRDP 增加仅排空 write queue 的 helper，以及 frame completion hook；builder 也恢复并重打 `libfreerdp/core/client.c`。两 ABI 已重编。

### 3. 可选 JNI 直写 Bitmap，待 Claude 接 VM

新增 `RdpBitmapFrameSink : RemoteScreenFrameSink`，只有 RDP 使用该接口，VNC 的 onPixels 路径不变。原 sink 没有实现接口时自动保留 IntArray。已实现 JNI `AndroidBitmap_getInfo/lockPixels/unlockPixels` 路径：[Android NDK Bitmap 文档](https://developer.android.com/ndk/reference/group/bitmap)。只复制 dirty rectangles，尊重实际 stride，BGRA primary buffer 转成 RGBA_8888，并为这些矩形设置不透明 alpha；没有每帧 NewIntArray / Kotlin setPixels。

先测的通用转换版本出现回退：同一次五阶段运行中，IntArray sink 为 32.72 ms，直写 Bitmap 为 88.24 ms（目录 `20261010-122022-307678`）。[FreeRDP generic color copy](https://github.com/FreeRDP/FreeRDP/blob/3.32.1/libfreerdp/codec/color.c) 对 BGRA→RGBA 逐像素转换，随后又做了一遍 alpha 写入。现在 BGRA/BGRX primary 使用单遍颜色交换与 opaque alpha：arm64 NEON 每次 16 像素，标量处理尾部与其他架构；其他源格式保留通用 fallback。host 检查覆盖 NEON 与强制标量、非对齐地址、16 像素边界前后、不同 stride、源数据及目标矩形外 guard，不能仅因去掉 IntArray 就认定更快。最终收益见下表。

非空 Bitmap lease 的 release 恰好一次；非法尺寸、不可变、已回收或错误格式在 Kotlin 中释放后 fallback。NDK lock 失败且无 JNI exception 也 fallback；异常与 unlock/copy 失败关闭会话并释放 lease，不发布半写帧。完成 native unlock、release 后仍走原 onFrameComplete，避免提前失效 frameVersion。

Claude 的界面接入项（本分支未改 UI）：

1. VM 的 sink 实现 `RdpBitmapFrameSink`，`onSize` 继续创建 mutable `Bitmap.Config.ARGB_8888`，尺寸为客户端实际像素尺寸。
2. `acquireBitmap(width,height)` 返回 VM 持有的同尺寸 Bitmap，也可返回 null fallback；非空返回必须持有所有权，直到 `releaseBitmap(bitmap)`。可用 ReentrantLock / 等价 lease，保护 resize、替换和 recycle；不能把同步块结束就释放的锁当作整个 native lease。
3. `releaseBitmap` 仅释放所有权，不触发 frameVersion。保留原 `onFrameComplete` 的 frameVersion++、bytes 更新。异步绘制/读快照必须与写 lease 协调，避免读半帧或回收在用 Bitmap。原 onPixels 保留用于 VNC 与 fallback。
4. 浮层可接 `frameDataMeanMs`、`surfaceWorkMeanMs`、`loopWaitMeanMs`、`composeMeanMs` 和下面的 codec API 耗时；directBitmapFrames 可验证新路径确实启用。默认 RdpOptions.directBitmap=true，实际是否可用取决于 sink 接口实现。

App instrumentation 用独立 Bitmap sink 实现验证该接口与画面，不代表 VM/GPU 已接入生产路径。

### 4. 接收计数独立发布

RdpSession 的实际 reader 累计 `received` 本来会递增；旧 RdpStats 只在 worker 的 publishStats 达到帧窗口时才带出它，握手、无 EndPaint 或 decoder 阻塞期间统计会滞后。模拟器的活跃基线没有复现“持续 0”，不能据此断言已定位真机截图的全部原因。

现在 reader 每约 500 ms 直接发布累计 bytesReceived；worker 每次发布也刷新 counters，不等待 FPS 窗口。全部 RdpStats 写入改为 MutableStateFlow.update，避免两线程 copy/赋值丢掉 counters 或性能字段。没有每次读取清零计数。新增本地 instrumentation 无需首帧即可观察 1024 bytes、frames=0；原实现此阶段 stats 会是 0。活跃连接的最终对照见下表，真机仍需检查浮层和 rx Logcat 是否一致。

### 5. 40 ms 解码的边界与改进判断

`0003-codec-call-timing.patch` 分别计时 `dequeueInputBuffer`、`queueInputBuffer`、`dequeueOutputBuffer`、`getOutputBuffer`、`releaseOutputBuffer`，对应 inputWait / inputQueue / outputWait / outputAccess / outputRelease 的 last / mean / max。它们是**每次 API 调用**，格式变化/TRY_AGAIN 等也各自采样，不能直接把其窗口均值当成一帧的相同口径求和。

从原计时位置已能排除：真机 decode=40 ms 在拿到 output buffer ID 时已经结束，**不含 getOutputBuffer、读 YUV 像素或 RGB 转换**。慢的不可缓存 YUV 读取会落在 yuvToRgb，而不会直接包含在这 40 ms 内。新 API 细分可继续区分 queue 提交阻塞与 dequeue 输出等待；dequeue 的等待还包括 Codec2/driver 调度、拷贝和硬件执行，不能仅凭此数值称为芯片的纯解码时间。需要用户新一轮 QTI 日志才有此结论，goldfish 的结果不能代表它。

本轮不改 Surface/AImageReader 输出、不猜测 buffer 缓存属性、不增加额外整帧 YUV memcpy。若后续证明 ByteBuffer 输出/CPU 转换是主要瓶颈，方案需先明确：用 Surface→AImageReader/AHardwareBuffer 或 GPU texture 输出，保存 frameId、surface/region 元数据与输出顺序，GPU 合成完整逻辑帧后以 fence 完成作为 ACK 门槛；处理 crop/stride、颜色范围和 AVC444 双路重组，resize/close 释放 image/texture/fence，并提供原 CPU path fallback。单纯 Surface 解码后再 CPU readback 可能新增等待与拷贝，不能假定能消除 decode=40 ms。该方案须另行审阅与真机受控 A/B 后实现。

### 第二轮验证与数据

最终测试使用同一 APK，固定 miffanrdp App SSH/helper 路径、2560×1440、相同 GNOME 持续刷新窗口；每阶段首帧后预热 5 秒，30 行一秒样本。开关顺序：baseline（原 10 ms / ACK 等 sink / IntArray / 原统计发布），event（只移除固定 sleep），ack（再先发送 ACK），bitmap（再启用可选 Bitmap sink），network（再启用独立接收统计）。所有阶段 lowLatency=true，goldfish 仅接受 priority=0。原 off/on 模式保留为只对照低延迟策略。

```sh
python3 rdp/scripts/test-latency.py --measure-seconds 30 \
  --scenarios baseline,event,ack,bitmap,network
```

runner 对 SSH TCP timeout/ConnectException 最多三次 lease 获取尝试，退避 1、2 秒；检查异常栈中的 Socket.connect，且必须未进入 SSH 回调才重试，不重试读超时、host key/认证失败、helper 或 RDP。底层 WorkspaceRepository 原有的一次握手重试仍保留，极端失败时总 TCP 尝试可达六次；本轮不改生产 SSH 逻辑。每个完整运行保留独立输出目录，新增 summary.json 自动按实际 elapsedMs 计算 fps / 接收速率，并给出非零窗口均值中位数。各阶段不是 CPU/GPU 总耗时等价对照，Bitmap 阶段使用上述测试 sink，不包含真实 UI 绘制。

最终完整对照通过：`OK (1 test)`，250.904 秒，目录 `rdp/build/test-output/ssh-latency/20261010-122915-740829/`，含五组原始样本、PNG、Logcat、instrumentation.txt 和 summary.json。五段使用同一 APK；测量期间没有并行构建。Bitmap PNG 已目视确认是正确的 GNOME / 最大化 Alacritty，背景色与通用路径一致，没有红蓝互换或透明区域。最后只补编测试代码：将无首帧计数检查扩为开关对照，并收紧 runner 的 TCP 异常分类，生产代码与本次测量 APK 相同。

下表时间为非零约 2 秒窗口均值的中位数，单位 ms；fps 和接收速率按首尾累计计数差 / 实际 elapsedMs 计算。每段 30 行；窗口可能被相邻样本重复读取，各列中位数不能当作同一帧的可加总时间。

| 指标 | baseline | event | ack | bitmap | network |
|---|---:|---:|---:|---:|---:|
| 实际采样间隔（秒） | 29.17 | 29.99 | 29.45 | 29.28 | 29.46 |
| sink 完成 fps | 2.02 | 5.90 | 5.88 | 8.20 | 10.63 |
| 解码输出 fps | 2.13 | 5.97 | 5.98 | 8.47 | 10.22 |
| decode | 50.13 | 56.57 | 57.32 | 51.25 | 44.63 |
| YUV→RGB | 12.02 | 14.84 | 17.28 | 14.03 | 13.66 |
| sink | 47.45 | 44.04 | 47.14 | 12.57 | 15.62 |
| surface→实际 ACK | 204.96 | 149.23 | 111.90 | 91.39 | 87.04 |
| ACK queue | 3.32 | 2.15 | 2.94 | 2.87 | 1.94 |
| 首个 Surface→EndFrame 入口 | 156.26 | 93.88 | 100.88 | 84.78 | 77.64 |
| SurfaceCommand 工作 | 70.29 | 85.33 | 88.70 | 76.40 | 69.71 |
| 帧内事件循环等待 | 71.72 | 0.08 | 0.07 | 0.07 | 0.04 |
| EndFrame GDI 合成（减去 sink） | 5.68 | 5.23 | 8.23 | 5.75 | 6.52 |
| 接收速率（KiB/s） | 56.34 | 947.98 | 908.60 | 1056.17 | 821.06 |
| bytesReceived 未增长的一秒间隔数 / 29 | 0 | 0 | 0 | 0 | 0 |
| 输出等待超时累计：首→末 | 2→4 | 2→8 | 1→3 | 2→2 | 2→4 |
| Bitmap 直写计数：首→末 | 0→0 | 0→0 | 0→0 | 40→288 | 54→355 |
| ACK 先于 sink 计数：首→末 | 0→0 | 0→0 | 33→210 | 40→288 | 54→355 |

1. **事件循环**：baseline→event 的帧内等待 71.72→0.08 ms，说明原固定 sleep 确实会累积；该运行中 frameReadCalls 全为 0，帧中这些循环没有成功的底层读取，更符合数据已在 TLS / FreeRDP 缓冲中却仍睡眠。surface→ACK 204.96→149.23 ms，sink fps 2.02→5.90。模拟器解释了可观的一段空耗，但不能据此认定真机缺失的约 140 ms 全都属于轮询；新增分段供真机确认。
2. **ACK**：event→ack 的实际确认 149.23→111.90 ms；时序计数在 ack 阶段增长 177 次，验证 ACK 在复制前实际发送。此段 sink fps 5.90→5.88，没有帧率收益；解码、合成及模拟器调度波动仍会影响总吞吐。ACK 排队约 2–3 ms，不是原 140 ms 的主要来源。
3. **Bitmap**：ack→bitmap 的 sink 47.14→12.57 ms，完成 fps 5.88→8.20，248 次直写且未 fallback。通用转换版本的 88.24 ms 回退已通过单遍 NEON 复制消除；两次运行的主机负载不完全相同，最终收益应以本表同 APK 的 IntArray/Bitmap 对照为准。生产 UI 尚未实现新接口，因此目前默认生产 sink 仍使用 IntArray，需要 Claude 按上述接入项连接。
4. **接收统计**：活跃模拟器在旧策略下也一直增长，network 的速率/帧率变化不归因于计数修复。另一个本地无首帧 A/B 中，两边实际 reader 都收到 1024 bytes、frames=0；原策略 stats=0，新策略 stats=1024，直接验证独立发布的作用。真机长期显示 0 的全部原因仍待新浮层/Logcat 对照。
5. **解码细分**如下。goldfish 的输出等待与 queue 提交占主要部分，getOutputBuffer 本身约 1–2 ms；这没有证明高通的缓冲区可缓存性，也不提供 Surface 输出必然更快的证据。先交付新计时，待 QTI 真机数据决定后续路径。

| Codec API 窗口均值中位数（ms） | baseline | event | ack | bitmap | network |
|---|---:|---:|---:|---:|---:|
| dequeueInputBuffer | 0.94 | 3.19 | 2.23 | 2.51 | 1.86 |
| queueInputBuffer | 4.25 | 13.42 | 9.62 | 9.57 | 5.37 |
| dequeueOutputBuffer | 36.43 | 40.01 | 45.40 | 40.45 | 37.95 |
| getOutputBuffer | 0.74 | 2.33 | 2.32 | 1.59 | 1.54 |
| releaseOutputBuffer | 2.02 | 4.80 | 4.65 | 4.33 | 2.65 |

固定顺序、单次模拟器对照不是显著性实验：五段均可出现偶发 100 ms 输出等待计数增长，没有到达 5 秒失败阈值；不同阶段的 decode 均值及压缩流量也有波动。最终 network=10.63 fps、ACK=87.04 ms 仅是此测试 sink / goldfish 的结果，不能当作 OPPO 或实际 GPU 绘制的保证。AVC444、多显示器、QTI、真实 VM 并发绘制与 lease 接入还需真机/界面侧验证。

本地检查：两 ABI 原生补丁及 JNI 构建；7 项 RDP JVM 单测、App compile / 两类 instrumentation APK 构建及 rdp lint 通过；ACK parser、NEON/标量 Bitmap 颜色和边界 host 检查通过；四项本地 instrumentation 通过（关闭、握手取消、首帧前接收计数 A/B、非法尺寸 Bitmap 释放/fallback）。仅连接指定 miffanrdp 测试账号；没有改 UI、miffan.sh、helper 配置或连接 ayuilos。
