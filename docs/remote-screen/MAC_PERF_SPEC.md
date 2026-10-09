# 屏幕流畅度：测量与流水线取帧（Codex 规格）

## 背景

用户在同一 Wi‑Fi 局域网内用手机（OPPO Find X6 Pro）连 Mac 的系统屏幕共享，流畅度明显差于连 Linux（wayvnc）。差异来源（按代码推断，尚未实测）：

- Mac 只给 ZRLE 无损，Retina 3600×2338 物理像素；Linux 走 Tight + JPEG。
- ZRLE 在 `ZrleDecoder` 里纯 Kotlin 逐像素解码，之后 `ScreenScaler.copy` 再做一遍 2×2 降采样，再交给 sink（`Bitmap.setPixels`）。
- `RemoteScreenSession.runReader` 是串行的：发请求 → 读完整个更新（读取与解码交错）→ 降采样 → sink → 等帧间隔 → 再发请求。服务端编码、网络传输、客户端解码三者没有重叠。

本次只做两件事：**测量**和**流水线取帧**。半分辨率直接解码、读/解码分线程等优化等测量结果出来再定，不在本次范围。

## 你负责的文件

- `workspace/src/main/java/me/rerere/workspace/screen/` 下的文件（可新增文件）。
- `workspace/src/test/java/me/rerere/workspace/screen/` 下的测试。
- 不要改 `app/` 下的任何文件。界面上的性能浮层由 Claude 做，它只依赖下面定义的 `RemoteScreenSession.stats`。
- 在 `feature/screen-perf` 分支上工作，可以提交到这个分支；不要合并、rebase 或推送到其他分支。

## 1. 测量

在 `RemoteScreenSession` 上新增：

```kotlin
/** Rolling statistics over the last [RemoteScreenStats.WINDOW_MILLIS]; updated about twice a second. */
val stats: StateFlow<RemoteScreenStats>
```

`RemoteScreenStats` 为不可变 data class，至少包含（全部是最近约 2 秒滑动窗口内的值）：

| 字段 | 含义 |
| --- | --- |
| `fps` | 每秒应用到画面的 FramebufferUpdate 数（不含空更新与只有光标的更新，另计 `emptyUpdates`） |
| `bytesPerSecond` | 接收速率 |
| `avgUpdateBytes` / `maxUpdateBytes` | 每次更新的字节数 |
| `avgLatencyMillis` | 从发出请求到收到该更新第一个字节的时间（含服务端编码与排队）。流水线后按请求 FIFO 配对 |
| `avgReadMillis` | 一次更新中阻塞在网络读取上的时间（不含解码） |
| `avgDecodeMillis` | inflate + 解码 + JPEG 回调的时间（沿用 `decodeNanos` 思路，按更新求差） |
| `avgScaleMillis` | `ScreenScaler.copy` 的时间 |
| `avgSinkMillis` | `sink.onPixels` + `onFrameComplete` 的时间 |
| `avgChangedPixels` | 每次更新覆盖的服务端像素面积 |
| `encodings` | 窗口内出现过的编码名（Raw/CopyRect/Tight/TightJPEG/ZRLE…），便于确认 Mac 实际用的编码 |
| `scale` / `pixelFormat` / `outstandingRequests` | 当前降采样倍数、像素格式、在途请求数 |

要求：

- 计时开销要小：只用 `System.nanoTime()` 和计数，不分配大对象，不在每个矩形上打日志。
- 网络读取时间需要能与解码时间区分开。ZRLE/Tight 里 `readFully` 与解码是交错的：把读取包一层计时（例如在 `RfbClient` 里给 `DataInputStream` 下面的流加一个记录阻塞时间的 `FilterInputStream`，只在 `read` 实际调用底层时计时），而不是改动解码循环。
- 每约 2 秒用 `java.util.logging` 或可注入的回调输出一行汇总（workspace 模块是纯 JVM/Android 库，不要直接依赖 `android.util.Log`；可以加一个 `var statsLogger: ((RemoteScreenStats) -> Unit)?`，app 端自己接 Logcat，Claude 来接）。

## 2. 流水线取帧

目标：服务端编码下一帧、网络传输与客户端解码/上传重叠，同时保持 `maxFps` 上限、暂停时停止请求、带宽受控。

做法（可按你的判断调整，但需满足下面的验收条件）：

- 读到 FramebufferUpdate 消息头（type 0）后，如果未暂停、到达帧间隔且在途请求少于 2 个，**先发出下一次增量请求，再解码本次更新**。在途请求最多 2 个。
- 帧间隔仍以 `maxFps` 计，从上一次发请求算起；暂停时不发新请求，已在途的照常读完。恢复时立即补发一次增量请求。
- `DesktopSize` 后：在途的旧尺寸请求照常读完，然后发一次新尺寸的非增量请求；请求坐标始终使用当时的帧缓冲尺寸，不能越界。
- 服务端可能把多个请求合并成一次更新，也可能对增量请求一直不回复（画面不变时）。在途计数不能因此卡死在 2 而永远不再请求：收到一次更新就把在途数减一（下限 0），等价于“每收到一次更新最多补一次请求”。
- 光标伪编码和空更新（0 个矩形）同样算一次回复。
- 剪贴板、Bell 等其他消息不影响计数。
- 写请求与输入事件共用 `output` 的同步，保持现在的线程模型（reader 协程发请求，writer 协程发输入）。

如果你判断 RFB 的 ContinuousUpdates（-313）+ Fence（-312）更合适，可以作为“服务端支持时优先使用”的附加路径，但必须保留上面的请求流水线作为默认，且不能假设 macOS 支持（我们目前无法确认）。

## 3. 真机基准（可选，但希望有）

扩展现有的 `RemoteScreenSpikeTest`（仅在设置了 `MIFFAN_SPIKE_*` 环境变量时运行）：增加一个基准用例，连接 `MIFFAN_SPIKE_VNC_PORT`，以给定 `maxFps` 跑 `MIFFAN_SPIKE_SECONDS`（默认 20）秒，分别在“串行”与“流水线”两种模式下打印 `RemoteScreenStats` 汇总。为此可以给 `RemoteScreenOptions` 加一个 `pipelineDepth: Int = 2`（1 = 现在的串行行为）。用户会在 Mac 上用自己的屏幕共享账号跑这个用例，你不需要也不会拿到密码。

## 验收

1. `./gradlew :workspace:test` 全部通过。
2. 新增单元测试（用假的服务端字节流或管道流，不连真机）覆盖：
   - 流水线下收到头部后、解码前已发出下一次请求；在途请求不超过 2；
   - 暂停时不再发请求，恢复后补发；
   - 服务端合并回复或长时间不回复时不会卡死，也不会无限堆积请求；
   - `DesktopSize` 后请求使用新尺寸；
   - `stats` 的 fps、字节数、编码集合在一个确定的输入序列上符合预期（注入时钟，不依赖真实时间）。
3. `pipelineDepth = 1` 时行为与现在一致（现有测试不改语义即通过）。
4. `./gradlew :app:compileDebugKotlin` 通过（只验证没有破坏 app 的编译，不改 app）。
5. 在 `docs/remote-screen/MAC_PERF_NOTES.md` 写下：做了什么、关键取舍、未覆盖的情况、建议用户如何跑基准。

## 约束

- 不要读取或复制 `local.properties` 以外的任何签名/凭据文件；本工作区的 `local.properties` 只有 `sdk.dir`。
- 需要联网（下载依赖）时沙箱会弹审批，由用户在面板里批准。
