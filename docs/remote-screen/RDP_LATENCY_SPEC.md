# RDP 真机卡顿与高延迟（Codex 规格）

## 现象（2026-10-10，用户真机）

OPPO Find X6 Pro（骁龙 8 Gen 2）经 Tailscale + SSH 连接 CachyOS 上的 GNOME 51 真实会话（gnome-remote-desktop 用户模式，NVIDIA RTX 3060，CUDA，AVC420），桌面 2560×1440、缩放 1.0。用户反馈“比 Mac 的 VNC 还卡，延迟超级高，基本不可用”。模拟器（goldfish 解码器、1920×1080 无头桌面）上没有这个问题。

## 主要怀疑（请先证实或排除）

1. **MediaCodec 同步一进一出**：`libfreerdp/codec/h264_mediacodec.c` 每帧 `queueInputBuffer` 后立刻阻塞在 `dequeueOutputBuffer` 等这一帧的输出（我们的补丁改成 100 ms 轮询、单帧最多 5 秒）。高通等硬件解码器默认有多帧的内部流水线，不喂下一帧就可能不出当前帧，于是每帧都要等到轮询或超时。表现就是帧率很低、延迟很高。模拟器的 goldfish 解码器会立即出帧，所以模拟器上看不出来。
2. **CPU 路径**：2560×1440 的 YUV→BGRA 转换（FreeRDP primitives，确认 arm64 下确实用了 NEON）、GDI 合成、JNI 把脏矩形复制成 Kotlin `IntArray`、`Bitmap.setPixels`、整张位图上传 GPU。每一步都随分辨率增长。
3. **RDPGFX 帧确认**：服务端要等客户端的 FrameAcknowledge 才继续发，客户端任何一步慢都会直接压低帧率、推高延迟。确认我们是在帧真正处理完后立即确认，没有额外延迟（例如 10 ms 轮询的事件循环）。

## 要做的

1. **测量先行**：在 `RdpStats` 和 Logcat（`RemoteScreenPerf`，与 VNC 相同的 tag，约每 2 秒一行）里加入：每帧 H.264 解码耗时（排队到出帧）、MediaCodec 在途帧数、YUV→RGB 转换耗时、sink（含 JNI 复制）耗时、从收到 surface command 到发出 FrameAcknowledge 的时间、实际编码（AVC420/AVC444/其他）、解码器名称、桌面尺寸。不改界面文件；`RemoteScreenStatsOverlay` 已经显示 `RdpStats` 的部分字段，新增字段由 Claude 接到浮层上。
2. **低延迟解码**：在 MediaCodec 配置里启用低延迟：`KEY_LOW_LATENCY=1`（API 30+，并检查 `FEATURE_LowLatency`）、高通的 `vendor.qti-ext-dec-low-latency.enable=1` 和 `vendor.qti-ext-dec-picture-order.enable=1`、`KEY_PRIORITY=0`。参考 Moonlight Android 对各厂商解码器的做法（请查证，不要照抄未证实的键）。不支持的键要安全忽略。
3. **解码流水线**：如果低延迟设置之后仍有出帧滞后，改成不阻塞地喂入/取出，例如允许多帧在途，或在等输出时继续处理下一帧输入。保持与 FreeRDP 解码 API 的语义一致，不能乱序显示。改动放在 `rdp/scripts/patches/` 的补丁里，和现有补丁一样可复现。
4. **CPU 路径**：先用测量数据判断它是不是瓶颈，再决定是否优化（例如直接写入 `Bitmap`/`HardwareBuffer`、减少整帧复制）。优化要有前后数据对比。
5. 事件循环：如果 10 ms 轮询确实拖慢了 FrameAcknowledge 或输入，改成由数据到达、命令入队唤醒。

## 你负责的文件

- `rdp/`（包括 `scripts/patches/`、`src/main/cpp/`、Kotlin 代码与测试）、`docs/remote-screen/RDP_LATENCY_NOTES.md`。
- 不改 `app/src/main/java/me/ayuilos/miffan/ui/`。在 `fix/rdp-decoder-latency` 分支上提交，不要合并、rebase 或推送其他分支。
- 改了补丁要重编原生库：`rdp/scripts/build-native.sh`（源码包在本 worktree 的 `.deps/`）。

## 验证

1. `./gradlew :rdp:testDebugUnitTest :app:compileDebugKotlin` 通过。
2. 模拟器 `emulator-5560`：用 P5a 的 instrumentation 方式连 CachyOS 测试账号 `miffanrdp` 的 GNOME 无头会话。请求 **2560×1440**，在桌面上持续制造画面变化（需要时让 Claude 在远端开一个持续刷新的窗口），记录改动前后的帧率和各段耗时。需要 Claude 启动 GNOME 无头会话、隧道或在远端放内容时，按之前的方式通知 Claude。
3. 真机上的效果只能由用户测：完成后告诉 Claude，由 Claude 打包发给用户。不要连接用户自己的账号（ayuilos@100.64.0.5）。
4. notes：测量方法、改动前后的数据、每个改动的依据（附出处）、仍未解决的问题。
