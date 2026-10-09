# P5a 实现与验收记录

日期：2026-10-09。分支：`feature/rdp-client`。新增 `rdp` Android library；复用 `workspace` 的 frame sink、state、rect 和 cursor 类型。没有修改 app、VM、屏幕页或 Compose，也没有把 RDP 接入 app 依赖图。

## 构建与产物

```sh
# 默认 SDK/NDK 路径见脚本；需要本地已有的两个 .deps 源码包。
rdp/scripts/build-native.sh                 # 两个 ABI
rdp/scripts/build-native.sh arm64-v8a       # 单独构建
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew :rdp:testDebugUnitTest :rdp:assembleDebug :rdp:assembleDebugAndroidTest :app:compileDebugKotlin
python3 rdp/scripts/test-emulator.py         # 按 properties 中 active 选择桌面
# 可覆盖 zenity 坐标/文字：--desktop gnome --x 960 --y 540 --text 'Miffan P5A 中文输入'
```

脚本先校验规格中的两个 SHA-256，解压本地 FreeRDP 3.32.1 / OpenSSL 3.5.9；不下载源码。NDK 28.2.13676358、CMake/Ninja 3.22.1、Android API 26、8 个编译任务。静态库和头文件安装到 `rdp/build/native/<ABI>`，Gradle CMake 链接为单个 `libmiffanrdp.so`，C++ runtime 静态链接，ELF LOAD 段对齐 16 KiB。缺少任一所需 archive 时，CMake 报错明确指向原生脚本；已用缺失 ABI 的配置实测此报错。

用 `RDP_NATIVE_OUT="$PWD/rdp/build/native-benchmark"` 指定全新目录测量冷构建（OpenSSL + FreeRDP + 安装；已解压源码）：arm64-v8a **67 秒**，x86_64 **77 秒**，合计约 **144 秒**。补丁后的增量构建每个 ABI 约 **2 秒**。本次最后的 Gradle 四项检查约 4 秒，包含缓存命中；不是 clean app 全量编译基准。

| ABI | strip 后 SO | MiB | ZIP deflate 估算 |
| --- | ---: | ---: | ---: |
| arm64-v8a | 9,419,512 bytes | 8.98 | 约 3.80 MiB |
| x86_64 | 9,895,216 bytes | 9.44 | 约 3.60 MiB |

app 目前 `useLegacyPackaging=true`，因此接入后的通用 debug APK 增量估算约 **7.4 MiB**，ABI split 约增加 3.8 / 3.6 MiB，另有少量 Kotlin/JNI 元数据与许可证。若改为不压缩 SO 打包，则两 ABI 增量约 **18.4 MiB**，再加 ZIP 对齐开销。以上是 SO 压缩测量估算，没有修改 app 打包配置或声称测得集成后的 APK。

建议：**不提交二进制**。源码构建耗时可接受，CI 增加原生脚本步骤，并按源码包哈希、补丁、NDK、ABI、构建选项缓存静态库；有需要时发布带校验和的 CI artifact。常规 Gradle 构建不隐式下载或自动执行长原生构建。`rdp/build/`、`.cxx/`、`.deps/` 都被忽略。许可证及依赖归属已放入 AAR assets 的 `rdp-licenses/`。

## 原生选项与兼容处理

- 静态 OpenSSL、WinPR、FreeRDP core/client-common；关闭所有客户端 UI/程序、服务端、FFmpeg、swscale、OpenH264、音频、设备重定向、打印机、智能卡等。
- 仅编译 **drdynvc、rdpgfx、disp、cliprdr** 的 client 通道；cache 已逐项核查。cliprdr 可打开，但 P5a 不提供剪贴板 API。
- MediaCodec H.264 开启；AVC420、AVC444/AVC444v2 能力开启，允许 RFX/Planar 等图形编码。禁用异步 update/channel，并启用 `SynchronousDynamicChannels`，确保 JNI 和 sink 都在 RDP worker 上。
- 静态 addin provider 在进程内初始化一次。FreeRDP 3.32.1 在 PreConnect 后会重建通道列表，因此 **通道加载必须放在 `LoadChannels`**，PreConnect 只订阅 channel 事件。ChannelConnected 中初始化 GDI graphics pipeline。这修复了 KDE 已认证却一直收不到 RDPGFX CapsAdvertise 的问题。
- 显式加载四个通道；不用 CLI 的通用 addin loader，避免 NetworkAutoDetect 的默认行为试图加载未编译的 rdpdr。NetworkAutoDetect / Heartbeat 保留，供服务端的 MCS message channel 使用。
- 对 Android 无 HOME 的路径查询作链接期 wrapper，使用应用临时目录的 `miffan-rdp` 子目录；不修改进程 HOME 或全局配置。WinPR 的静态 JNI VM 初始化也由 bridge 完成。
- `scripts/patches/0001-mediacodec-cancellation.patch` 是对已校验本地源码的可重复补丁：原版 dequeue 无限等待改为 100 ms 轮询，检查会话取消，单次解码总等待上限 5 秒；失败返回 codec error。不是软件解码 fallback。

## IO、线程、画面和安全

使用 `freerdp_set_io_callbacks`，保留 FreeRDP 的 BIO/TLS/CredSSP 实现，只替换 TCP `ConnectLayer` 为读写 JNI byte stream 的 transport layer。TLS 与 NLA 都已经在此路径上完成握手，**没有 socketpair fallback、TCP 监听、端口转发或 abstract Unix socket**。instrumented 测试里的 Socket 仅用于连已存在的测试隧道。

Kotlin reader 把阻塞 InputStream 搬到有界 1 MiB ring buffer。native 读空时返回 EAGAIN，EOF 返回 0；握手 Wait 回调通过条件变量等数据，每次最多 50 ms。所有 OutputStream 写入/flush 在 RDP worker 上。输入方法只写入有界命令队列，不在调用者线程做网络 IO；队列溢出关闭会话并报告错误。事件循环约每 10 ms 调用 `freerdp_check_event_handles`，该函数同时处理 core 和 channel 事件；无 fd 的 stream 使用 WinPR event 和轮询，不依赖只处理 socket 的循环。

GDI 输出 BGRA32，EndPaint 只复制裁剪后的 dirty rectangles 为不透明 ARGB `IntArray`，然后回调 frame complete；resize 发 onSize，scale=1。pointer mask 转为 ARGB，包含隐藏和系统默认箭头。鼠标按钮/滚轮掩码与 VNC 相同，按键用 set-1 扫描码及 E0 扩展，typeText 发送 UTF-16 Unicode down/up（包括代理对）。未知 keysym 不猜测扫描码，文本应走 typeText。

pause 发送 SuppressOutput PDU，并在本地停止画面 sink 上传；输入/通道仍可处理。close 幂等并关闭所拥有的 transport，唤醒 buffer 等待，native 在 worker 上断连并释放 FreeRDP、GDI、MediaCodec、event。scope 取消也会关闭连接。**上层传入的 Closeable 必须能中断对应 InputStream/OutputStream 的阻塞 IO**；sink 回调必须快速返回。不能靠此库强制终止任意不响应 close 的第三方流或阻塞 sink。

支持 AUTO（同时提供 NLA/TLS）、TLS、NLA。默认 AUTO 由服务端协商；测试 KDE 指定 TLS，因为此 krdp 命令行账号未配置 NLA SAM，不能把 NLA 登录失败自动降级为 TLS。GNOME 使用 NLA。禁用传统 RDP security；TLS 证书始终经外部校验回调，计算真实 DER 证书 SHA-256。null pin 首次接受并暴露 fingerprint，已给 pin 必须恒时比较匹配，错误 pin 返回 SecurityException；不会无条件跳过证书检查，也不静默更新 pin。

## 实测与限制

仅使用 **emulator-5560**（arm64 Android 15 / 16 KiB），没有操作 emulator-5554。参数只从忽略的 `.deps/rdp-test.properties` 传入 instrumentation；脚本不回显密码，并对 runner 输出脱敏。PNG 和报告位于 `rdp/build/test-output/<desktop>/rdp-test/`，不提交。

| 测试 | KDE / krdp | GNOME 51 / gnome-remote-desktop |
| --- | --- | --- |
| 连接 | 22:26:45，TLS | 22:46:01，NLA |
| 画面 | 非空 1920×1080，PNG 人工查看通过 | 非空 1920×1080，PNG 人工查看通过 |
| 实际编码 | AVC420 | AVC420；服务端接受 CAPVERSION_107，确认客户端 AVC444=true、AVC420=true |
| 解码器 | `c2.goldfish.h264.decoder` / MediaCodec | 相同 |
| 帧统计 | 静态桌面 1 帧，末窗口 0 fps | 最终累计 96 帧，末短窗口约 13.67 fps |
| 输入及 Return | Claude 确认 zenity 输出 `Miffan P5A` | Claude 确认 22:46:14.299 zenity 退出，输出 `Miffan P5A`，Return 延迟约 0.95 秒 |
| 证书 / 关闭 | 正确 pin 重连、错误 pin 拒绝、重复关闭通过 | 同上；握手取消、启动前关闭也通过；最终 native 释放约 145 ms |

GNOME 最终 3 项测试用时 30.4 秒。22:46:26 主会话关闭后，两次短连接分别是正确 pin 的 NLA 重连（Connected 后立即关闭）和错误 pin 的拒绝（在证书阶段结束）；Claude 观察到后一条连接的 `nla_server_recv_stream` 错误，是本地拒绝证书导致握手中断的预期结果。22:41 的前一轮也已确认 Return 成功，zenity 于 22:41:55.177 输出相同文本。

两边发送的原始文本都是 **`Miffan P5A 中文输入`**，先点击 `(960,540)`、Ctrl+A，typeText 后另发 Return down/up。两边拉丁字符、扫描码、点击、Return 均经远端日志确认。中文四个字事件已到服务端，但 krdp 报 `Failed to convert keysym into keycode`；GNOME 报 `No keycode found`，对应 U+4E2D / U+6587 / U+8F93 / U+5165。这是当前服务端键盘布局转 keycode 的限制；**KDE 和 GNOME 的非布局字符需在 P5b 通过 cliprdr 粘贴，沿用 VNC 现有做法**，不修改客户端 Unicode 映射来掩盖它。

**AVC444 未验证**。GNOME 服务端日志确认收到客户端 AVC444 能力，但这个测试账号仅成功初始化 CUDA，没有成功初始化 Vulkan；Claude 核查该版本的 AVC444 dual-view 着色器 + NVENC 路径，因此本环境发送 AVC420。两种 AVC444 codec ID 的 GDI 路径已接入、构建成功，但不能据此声称实测通过。

Android 返回该解码器 `isHardwareAccelerated=true`，这是厂商/系统的 codec 分类，模拟器 goldfish 不能证明实体手机 GPU 硬解；API <29 或查不到 codec 时统计为 null。解码器名来自实际创建的 AMediaCodec，而不是仅因 WITH_MEDIACODEC=ON 就填 true。分类定义参见 [Android MediaCodecInfo 文档](https://developer.android.google.cn/reference/android/media/MediaCodecInfo#isHardwareAccelerated())。实体机功耗、持续视频帧率、AVC444、Windows、动态 resize、各鼠标滚轮与 cursor shape 尚需 P5b 扩展实机覆盖；此处短窗口 fps 不是视频性能基准。

GNOME 首轮 22:35:37 已连上，22:36:05 在测试侧排入 Return，但远端未确认；那次还出现 10 秒线程退出检查失败。该次同时做冷构建、模拟器响应很慢；更直接的测试问题是 PNG 压缩持有 sink 锁，能阻塞 RDP 事件循环。已修正为锁内只复制像素、锁外压缩并 recycle Bitmap，避免测试 IO 饿死输入/关闭。后续正常负载复测通过。首次失败保留为诊断记录，不算通过，也不推断未到达的 Return 是服务端错误。存图只是当时已收到的帧，可能早于 zenity 退出后的更新；最终测试在 Return 后等 4 秒，远端日志作为输入验收依据。

## 验收清单

- [x] arm64-v8a / x86_64 静态 OpenSSL、WinPR、FreeRDP 构建；单 SO 链接、strip 体积与 16 KiB 对齐检查。
- [x] 四个 client 通道；关闭其他通道、服务端、UI、FFmpeg/swscale；源码哈希校验，二进制忽略，缺产物报错验证。
- [x] IO callbacks 经过 TLS 和 NLA 实测；手机侧无监听端口及 socketpair。
- [x] Kotlin API、dirty rectangle ARGB、size scale=1、指针转换、输入队列、pause、幂等 close 和 scope 取消实现。
- [x] JVM 单元测试 4 项通过：扫描码、脱敏、SHA-256 校验、参数边界。
- [x] KDE instrumented 测试 1 项通过；GNOME 最终测试覆盖 3 项，包含握手取消、启动前关闭和完整连通/输入/证书/关闭流程。
- [x] 两边非空 PNG 人工查看；编码与实际 MediaCodec 名称断言；输入远端确认，中文限制如实记录。
- [x] `:app:compileDebugKotlin` 通过；没有 app/workspace 源码改动；凭据、构建产物不入库。
- [x] 构建耗时、体积、选项、IO、实测数据、已知限制和后续接口建议已记录。
- [ ] AVC444 真正解码与实体机硬解/持续性能：测试环境未提供所需服务端能力，不宣称验收通过。

## P5b 接口建议

1. 给屏幕 VM 引入共同会话接口（state/stats、pointer/key/typeText、pause/close），RDP/VNC adapter 共用页面和 sink；不要让 Compose 依赖 JNI 或协议细节。
2. transport factory 每次重连创建新的 SSH channel + 新 session；由上层决定重试退避、页面生命周期与控制权，原生不偷偷重连。关闭 transport 必须中断双方 IO。
3. 按主机/端点存储 TOFU fingerprint，首次上层展示并固定，变更需要明确确认；credentials/security policy 与主机配置绑定，不记录密码，不把 TLS fallback 当认证恢复。
4. 接 cliprdr 的 Unicode 文本读写和粘贴确认；中文及不在布局里的文本用 clipboard + Ctrl+V。处理远端剪贴板异步请求、取消、长度上限及双方控制权。
5. 接 disp 的动态尺寸 API，并加 resize/光标/滚轮/恢复更新测试；统计继续区分实际 codec、decoder name 与系统硬解分类。将来 HEVC decoder MIME 扩展独立于 RDP 当前 AVC 能力。
6. 把目前 10 ms 轮询进一步换成 stream 数据/命令和 WinPR channel wait 对象共同唤醒，降低后台唤醒；任何图像转换、PNG、UI 上传都不得在 sink 回调内做长 IO。
