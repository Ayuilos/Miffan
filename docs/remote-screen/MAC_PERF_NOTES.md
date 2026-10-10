# Mac 屏幕性能：实现与验收记录

## 实现

- 新增不可变 `RemoteScreenStats` 与 `RemoteScreenSession.stats: StateFlow<RemoteScreenStats>`。约每 500 ms 发布最近 2 秒的帧率、接收速率、更新大小、FIFO 请求延迟、读取/解码/缩放/sink 耗时、变化面积、编码集合，以及当前缩放、像素格式和在途请求数。约每 2 秒调用 `statsLogger`，未设置时用 `java.util.logging`；日志回调异常不会断开连接。
- 实际传输流在缓冲层下面计时、计接收字节；缓冲层上面的计数用于精确的单次更新大小，包含 `skipBytes` 的填充字节。解码以矩形 CPU 耗时减去实际读取耗时求和，涵盖 Raw、CopyRect、ZRLE inflate、Tight inflate/JPEG 和光标解码。Tight 自身的 inflate 计时也已补齐。
- 编码名称区分 `Tight` 与 `TightJPEG`，后者只用于统计；`RfbEvent.encodings` 仍保留实际线协议编码 ID。
- 默认 `pipelineDepth = 2`，仅接受 1 或 2。读到 type 0 时，若帧间隔已到且有槽位，先请求再读更新体、解码、缩放及上传。等待消息头时也按帧间隔填满空闲槽位，因此合并回复每释放一个槽位都能补一次请求。无回复时最多保留两个请求，停止继续发送。
- 默认请求间隔为 `1_000_000_000 / maxFps` 纳秒（maxFps 限制在 1..60），从上次请求计。暂停停止新请求但读完已有回复；恢复唤醒 reader，有槽位立即补发一次。恢复是帧间隔的显式例外；满槽时等待回复。
- `pipelineDepth = 1` 保持请求 → 完整读取/解码 → 缩放/sink → 下一请求的串行顺序。首帧及 resize 后的刷新为非增量，其余为增量。
- 更新请求仍全部由 reader 发送，输入事件仍由 writer 发送，写操作沿用 `RfbClient.output` 同步。只把阻塞的一字节消息头读取放入 IO 子协程，等待它时 reader 可以响应控制消息和统计定时器；没有帧队列，没有把读取体和解码拆成并行流水线。定时器睡到下一个请求/统计截止时间，不忙轮询。
- 新增可选 `vncSessionBenchmark`，用相同设置先跑串行再跑流水线，各默认 20 秒。直接 TCP 连接，不读 SSH 私钥或任何凭据文件；密码只从用户提供的环境变量读取。

## 统计口径与关键取舍

- `fps` 只计实际调用 `onPixels` 的更新；空更新和只有光标的更新计入 `emptyUpdates`，仍正常释放一个请求槽位。DesktopSize 会刷新整个输出画面，因此计为画面更新。
- 所有更新（含空/光标更新）参与字节数和耗时平均；无 FIFO 请求可配对的主动更新不参与延迟平均。`avgChangedPixels` 为真实像素矩形面积之和，重叠矩形重复计数，光标及 DesktopSize 元数据不计面积。
- 接收速率包含 Bell、剪贴板等消息及已经读入缓冲区的字节；单次更新字节数仅包含该更新。连接握手不参与统计。启动不足 2 秒时以实际运行时间归一化；流量窗口使用约 500 ms 的累计接收量采样，窗口边界为近似值。
- 延迟使用底层缓冲区读入完成的时间，而非延后消费消息头的时间，避免把前一帧解码/上传排队算作服务端延迟。无法取得 OS 级逐字节到达时间；同一批预读数据共享到达时间。
- `avgReadMillis` 包含读取消息头时的阻塞等待和更新体的底层读取；它可能与请求延迟重叠，不能将各耗时简单相加。解码耗时排除上述读取；sink 耗时只包含 `onPixels` 与 `onFrameComplete`，不含 `onSize`/`onCursor`。
- DesktopSize 后优先读完旧尺寸请求，再发一次新尺寸全量刷新。RFB 没有请求 ID，而且服务端可能已合并旧请求，无法保证一定还有一个单独回复可读：若等待 2 秒仍有旧请求，用一个空闲槽位发送新尺寸全量刷新，保留 FIFO 时间戳，不清零、不突破深度上限。这是针对无确认合并回复的有界退让。每次写请求都读取当时 framebuffer 尺寸；之后恢复增量请求。
- 统计与日志仍由 reader 更新。非常慢的更新体、解码或 sink 会延迟发布和响应控制；不会为每个矩形打日志，也不会复制 framebuffer 来统计。

## 验收自检

2026-10-09 最终执行 `./gradlew :workspace:test :app:compileDebugKotlin`，结果 `BUILD SUCCESSFUL`。workspace 共 177 项测试，171 项通过、6 项环境变量未设置而跳过，0 失败/错误；新增性能测试 12 项全部通过。app 在此前全量 Kotlin 编译成功，最终重复验收显示 UP-TO-DATE。新增测试全部使用内存流/管道和虚拟纳秒时钟；短的真实超时仅作为异步协程同步及失败保护，不用于计算统计预期值。

| 规格条目 | 自检方式 | 结果 |
| --- | --- | --- |
| 1. `./gradlew :workspace:test` | 运行 workspace 全部 JVM 测试 | 通过（177 项，6 跳过） |
| 2a. 头部之后、解码之前已请求；深度 ≤ 2 | 管道先仅写 type 0，收到请求后才送 JPEG 更新体；pacer 深度断言 | 已覆盖 |
| 2b. 暂停/恢复 | 暂停后读完在途回复、无新请求；阻塞等头部时恢复即补发 | 已覆盖 |
| 2c. 合并/静默服务端 | 虚拟时间推进，满槽时反复尝试不再发送；50 次合并回复每次释放并补一个槽位；空/光标更新和 Bell/剪贴板的集成序列 | 已覆盖 |
| 2d. DesktopSize | 旧尺寸增量回复排空后请求新尺寸全量；合并无回复时等待 2 秒后在空闲槽刷新 | 已覆盖 |
| 2e. 确定统计 | 固定窗口精确验证 fps、更新大小、流量、编码、全部平均值和过期；会话集成验证 50 字节接收中只有 40 字节属于三次更新 | 已覆盖 |
| 3. depth=1 行为兼容 | 管道阻塞更新体，确认无下一请求；现有 RfbClient 测试保持原语义 | 已覆盖 |
| 4. `./gradlew :app:compileDebugKotlin` | 只验证编译；不改 app 源码 | 通过 |
| 5. notes | 本文件含实现、取舍、未覆盖情况、基准操作 | 已完成 |

`git diff --check` 通过。改动限定在规格允许的 workspace/screen 主代码、测试和本 notes；`app/` 无改动。未合并、rebase 或推送到其他分支。

额外覆盖：底层 read 只计一次、缓冲命中不增加读取耗时；JPEG 回调 CPU 时间与交错读取时间分离；预读头部保留到达时间；头部 EOF 正确显示 Closed(error)。

## 尚未覆盖

- 未连接真实 Mac，也未读取/获取用户屏幕共享密码；目前没有 Mac/OPPO 的性能数值或流畅度改善结论。
- JVM 基准 sink 为空操作，仍执行 `ScreenScaler.copy`，但不模拟 Android `Bitmap.setPixels` 和 GPU 上传。JVM/ImageIO 的解码耗时不能直接当作手机解码耗时。
- 未加入半分辨率解码、解码专用线程、ContinuousUpdates/Fence、编码偏好调整或 UI 浮层。
- RFB 合并回复只能按 FIFO 近似配对，无法判定具体回复对应几个请求；resize 的 2 秒退让和真实 macOS 行为仍需真机验证。
- reader 卡在更新体或 sink 时不能以 500 ms 固定频率刷新统计；长于窗口的阻塞也会降低接收速率窗口的时间精度。

## 建议用户运行基准

在 Mac 上启用系统屏幕共享。测试直接连接 TCP：在 Mac 本机使用 `127.0.0.1`，或设 `MIFFAN_SPIKE_VNC_HOST` 为目标 Mac 的局域网地址。端口通常为 5900，也可以使用用户自己建立的本地隧道端口。本用例不自动建立 SSH 隧道。

以下是 macOS zsh 示例，密码通过隐藏输入进入环境变量，不写入命令历史或本文件：

```zsh
export MIFFAN_SPIKE_BENCHMARK=1
export MIFFAN_SPIKE_VNC_HOST=127.0.0.1
export MIFFAN_SPIKE_VNC_PORT=5900
export MIFFAN_SPIKE_VNC_USER='你的 macOS 用户名'
read -s 'MIFFAN_SPIKE_VNC_PASSWORD?屏幕共享密码: '
export MIFFAN_SPIKE_VNC_PASSWORD
export MIFFAN_SPIKE_SECONDS=20
export MIFFAN_SPIKE_MAX_FPS=15
./gradlew :workspace:testDebugUnitTest \
  --tests 'me.rerere.workspace.screen.RemoteScreenSpikeTest.vncSessionBenchmark' \
  --rerun-tasks --no-configuration-cache --info
unset MIFFAN_SPIKE_VNC_PASSWORD
```

默认 RGB888，JPEG 开启（服务器仍可选择 ZRLE）。可设 `MIFFAN_SPIKE_LOW_COLOR=1` 对比 RGB565，或 `MIFFAN_SPIKE_NO_JPEG=1` 禁用 JPEG；第二轮前取消对应变量即可恢复默认。只改变一个变量，串行和流水线分别保持相同屏幕尺寸、Wi-Fi、画面内容。测试期间持续滚动同一页面或播放同一动画；静态桌面不回复增量请求是正常情况，测试会在到时关闭流，不会无限等待。

输出每约 2 秒标注 `depth=1/2` 的完整 `RemoteScreenStats`，末尾给出最后一个窗口及连接总接收字节数（总字节含握手）。最后一个窗口不是整段运行的平均。建议忽略首帧启动窗口，比较稳定区间的 fps、bytesPerSecond、延迟、read/decode/scale，并确认 `encodings`。如果终端不显示测试标准输出，可看 `workspace/build/reports/tests/testDebugUnitTest/` 或对应测试 XML 的 `system-out`。
