# Sunshine + Moonlight 远程真实桌面调研

调研日期：2026-10-10。范围：公开文档、公开源码和本仓库现状；未安装、编译或运行任何远程软件，未连接用户机器，未执行下面提出的 spike。

## 结论与建议

**建议接入 Sunshine 作为可选“高性能模式”，先验证 CachyOS + RTX 3060，保留 VNC/RDP；现在不全面替代。** 它有希望统一视频与键鼠客户端，并解决 KDE/NVIDIA 的软编码及 Mac VNC 的视频带宽问题，但无法同时原样保留“所有连接只经 SSH”、跨平台完整剪贴板和无需主机授权的一步接入。

1. **原先放弃的集成前提不成立。** `moonlight-common-c` 就是独立 C 协议库，官方明确推荐用于自建客户端，Android/Qt/iOS 都复用它。需要另写或移植的是配对/HTTP 管理、JNI、MediaCodec、生命周期和输入适配，不是从整套 Moonlight UI 中拆协议。[核心库说明][C-readme]、[Android 原生构建][A-build]
2. **Linux 覆盖面已明显扩大。** 对应 `2026.1008` 的源码有 `wlr`、KMS、X11、KWin、Portal/PipeWire 路径；NVIDIA NVENC、AMD/Intel VA-API 可以编码同一个已登录桌面。不存在“GNOME/KDE Wayland 必须另造虚拟桌面”的限制，但 GPU 驱动、权限、包的编译选项和显示器状态仍需验证。[Linux 后端选择][S-linux]、[故障排查][S-trouble]
3. **Mac 是值得验证的候选，不是已证明的统一答案。** Apple Silicon 有 VideoToolbox H.264/HEVC；当前采集仍是 AVFoundation `AVCaptureScreenInput`，并非 ScreenCaptureKit。macOS 26.5 上的采集、Retina 坐标、锁屏恢复和持续性能，本次均未实测。[Mac 采集][S-mac-video]、[编码器][S-video]
4. **最大的决策是传输。** Sunshine 当前流媒体用 UDP，JSch `direct-tcpip` 只提供 TCP 字节流。推荐“SSH 管理与 AI 通道 + Tailscale/WireGuard 视频与用户输入通道”。这需要批准安全模型变更；若“只能经 SSH”不可变，则不应采用 Sunshine 作为主屏幕方案。[流端口][S-stream-h]、[SSH 标准 §7.2][N-ssh]
5. **先用上游 Sunshine，不以 Apollo 为基础。** Apollo 的主要增益集中在 Windows 虚拟显示器、客户端权限和 Artemis 扩展；其 Linux/macOS 剪贴板函数仍是占位，不能解决本项目的跨平台剪贴板。项目还明确预告将与原版 Sunshine/Moonlight 分化。[Apollo 说明][P-readme]、[Linux 剪贴板][P-linux]、[Mac 剪贴板][P-mac]
6. **客户端应直接解码到 Surface。** 当前 `RemoteScreenFrameSink` 是 CPU 像素接口，不能硬套在视频流上。应保留同一 `RemoteScreenScaffold` 和控制权模型，为画面增加 Surface 渲染实现；已有 `rdp/` 继续负责 FreeRDP，不与 GameStream 共用解码状态。[MediaCodec 实现][A-decoder]、[本地帧接口](../../../workspace/src/main/java/me/rerere/workspace/screen/RemoteScreenSession.kt)

推荐优先级：**KDE/NVIDIA → niri/X11 → Mac → GNOME 的可选增强**。GNOME 已有用户满意的 RDP，替换它的收益最小。允许尚无 VPN 的用户继续使用 SSH 下的现有协议，轻松模式不能因此失去基本接入能力。

## 证据口径与版本

- **已核实**：文档或指定源码快照存在该实现；不表示已经在用户设备上通过验收。
- **推断/建议**：由实现推导的集成或产品判断；性能预算和工作量都属于估算。
- **待验证**：必须在对应桌面、GPU、手机或网络上测试才能确认。
- Brief 中“Mac 约 2 fps、GNOME 95 分、KDE 70 分、Sunshine 已安装运行”是任务提供的现场信息，不是本次重新测得的数据。

源码下载到临时目录阅读，固定版本如下（归档中的 Git commit 元数据）：

| 项目 | 阅读基线 | 用途 |
| --- | --- | --- |
| Sunshine | `v2026.1008.43744`，`d6453a3ab2fbe7e1649d77105a42bd32182461c7` | 主要结论基线；此标签在发布页标为 **pre-release** |
| Sunshine master | `3411311acf9a9e6f64314be55b9e802dab7aa6ac` | 交叉检查；不把 master 当成用户已安装版本 |
| moonlight-common-c | `f900dd4767759c7b9d0e93bcea666b55c69ea62f` | 公共 API、依赖、加密与网络 |
| moonlight-android | `b48494cb96bff23d8886c4775cc4f39a1075495d` | JNI、HTTP/配对、MediaCodec |
| Apollo | `adc5c5a0bd80831ce495434bb16aee2cd4175fb8` | 分支差异和剪贴板占位实现 |
| libvirtualhid | `7cd296cabc1503f78f110cc8cc9aedd7fc3cddc3` | 独立上游后端及许可证；不是已核实的用户包所链接版本 |
| nanors | `f3dc50c67ccfdd6c20177e57658a011a7d26e2bd` | Reed-Solomon/FEC 依赖许可 |

用户的“2026.1008”没有完整包版本、构建选项和下游补丁；**不能仅凭前缀认定全部特性已在该包启用**。调研时发布页的稳定版本是 `v2026.914.233613`；Android 最新发行页指向 `v12.2`。集成应固定相互兼容的具体提交及子模块，不能混用随时变化的 `latest`。[Sunshine 发布][S-releases]、[1008 标签][S-release1008]、[Android 发布][A-release]

已阅读背景文件：[总计划](../../REMOTE_SCREEN_PLAN.md)、[P5 计划](../P5_RDP_PLAN.md)、[P5a](../P5A_NOTES.md)、[P5b](../P5B_NOTES.md)、[RDP 延迟](../RDP_LATENCY_NOTES.md)、[Mac 性能](../MAC_PERF_NOTES.md)。本仓库 RDP 已有事件驱动、先 ACK 后 sink、可选 JNI 写 Bitmap 等后续优化，不能用早期低 fps 记录来代表当前实现；Brief 的真机评价与 notes 的模拟器指标也不能直接横向比较。

## 1. 覆盖面：采集、GPU、真实桌面

### 平台矩阵

下表“成熟度”是基于实现与官方说明的判断，不是本次设备测试评分。

| 环境 | 已核实的采集路径 | 编码与权限 | 判断 / 未确定部分 |
| --- | --- | --- | --- |
| GNOME Wayland | XDG RemoteDesktop Portal → PipeWire；也可尝试 KMS | NVENC / VA-API / Vulkan Video，具体取决于 GPU/包；Portal 首次授权，KMS 需 `CAP_SYS_ADMIN` | 已有正式路径；Portal 恢复 token 可能因桌面切换失效；用户 GNOME 51 未实测 Sunshine |
| KDE Wayland | 原生 `kwin` screencast → PipeWire，或 Portal、KMS | 与上行相同；`kwin` 可绕开 Portal 手动授权流程 | 最值得验证，可避开 krdp 的 KPipeWire 编码器局限；不能把“同用 PipeWire”理解成“只能用 KPipeWire 的 libx264” |
| wlroots | `wlr-screencopy-unstable-v1`，提供 RAM / DMA-BUF 路径；KMS 备选 | NVENC / VA-API；协议版本、DMA-BUF modifier 和驱动影响直通 GPU | 现成实现；Flatpak 安全上下文与协议开放范围要检查 |
| niri | niri 文档确认支持旧 `wlr-screencopy` 和新 `ext-image-copy-capture`，也提供 Portal/PipeWire；Sunshine 的 `wlr` 走前者 | NVENC / VA-API；KMS 备选 | 当前 niri main 的 screencopy 已是 v3，并发送 DMA-BUF/buffer_done；旧版本可能不同。niri 不是 wlroots，仍需核对安装版本和 GPU buffer 路径 |
| X11 | XCB/X11；NVIDIA 可用 NvFBC；也有 KMS | NVENC / VA-API；NvFBC 受驱动与硬件限制 | 最传统路径；官方称 X11 采集较慢、CPU 成本较高；NvFBC 不支持原生 Wayland 或用 XWayland 捕捉整个 Wayland 桌面 |
| macOS / Apple Silicon | AVFoundation `AVCaptureScreenInput`，按 `CGDirectDisplayID` 选显示器 | VideoToolbox H.264 / HEVC；TCC 录屏和输入授权 | 有真实实现，非仅 README 宣称；macOS 26.5 的稳定性与性能仍需独立验证 |

出处：[Linux 后端选择][S-linux]、[Wayland 实现][S-wayland]、[wlr 实现][S-wlr]、[KWin 实现][S-kwin]、[Portal 实现][S-portal]、[官方推荐组合][S-trouble]、[niri 官方 screencasting 文档][N-niri]、[niri 当前协议源码][N-niri-code]、[Mac 采集][S-mac-video]。**自动选择不等于按桌面选择最佳后端**：源码会依次探测可用路径；KMS 可能先于 KWin/Portal 被选中，应记录实际 capture，而不是只看桌面名。

### GPU 矩阵

| 主机 GPU | 可用硬编 | 编码范围与限制 |
| --- | --- | --- |
| NVIDIA / Linux | NVENC；较新环境还有 Vulkan Video 路径 | Sunshine 注册 H.264/HEVC/AV1，最终按 GPU 能力探测；**RTX 3060 支持 H.264/HEVC 编码，不支持 AV1 编码**，AV1 解码能力不等于编码能力 |
| AMD / Linux | VA-API；具备相应驱动能力时 Vulkan Video | H.264 为基本路径；HEVC/AV1 与 GPU 代际、Mesa 编译时开启的 codecs、驱动相关，不能对“AMD”整体承诺 |
| Intel / Linux | VA-API；具备相应驱动能力时 Vulkan Video | 同上；不要把 Windows 的 Quick Sync 配置直接当成 Linux 专用后端 |
| Apple Silicon / macOS | FFmpeg VideoToolbox 硬件编码器 | H.264/HEVC 是本项目首选；源码注册 `av1_videotoolbox` 不代表 Apple Silicon 芯片具有 AV1 编码器，须以运行时探测为准 |

出处：[Sunshine 编码器定义及探测][S-video]、[VA-API 实现][S-vaapi]、[Vulkan 编码][S-vulkan]、[NVIDIA 官方支持矩阵][N-nvidia]。Linux NVIDIA 的 GPU 内存路径还受 CUDA 编译支持影响；官方预编译 Arch 包与本地 AUR 构建的能力可能不同。[安装文档][S-install]

### 已登录桌面、多屏、HiDPI 与锁屏

**已核实：可以直接串流已登录的真实桌面，不要求启动游戏或虚拟显示器。** Linux/macOS 自带 `Desktop` 应用项，没有要启动的游戏命令；它采集所选显示器，输入落在同一图形会话。应选择这个项，检查用户是否给它添加了 prep 命令。[Linux 默认应用][S-app-linux]、[Mac 默认应用][S-app-mac]

- **多显示器**：支持枚举并通过 `output_name` 选择输出；这不等于 RDP 的多显示器虚拟通道或同时拼接所有屏幕。第一版按一个活动输出设计；切屏可能需要重新建立会话，Portal 还可能要求重选授权显示器。[配置源码][S-config]、[Portal token 恢复][S-trouble]
- **HiDPI / 缩放**：Mac 采集使用 `CGDisplayModeGetPixelWidth/Height` 获取像素尺寸，编码尺寸可以低于物理采集尺寸。请求 1920×1080 的视频不应默认改变用户桌面分辨率；绝对鼠标还需要把视频内容区映射到所选输出的真实坐标，考虑黑边、输出偏移、旋转、Retina 点/像素和分数缩放。需测试手机缩放后点击，不可直接复用 VNC 的 `scale=2` 假设。[Mac 采集][S-mac-video]、[Mac display][S-mac-display]、[公共输入映射][S-hid-input]
- **锁屏 / 显示器关闭 / 会话注销**：没有找到覆盖全部平台的上游保证。Portal、KWin、KMS、X11、Mac 可能分别显示锁屏、黑屏、停帧或断流；KMS 访问物理扫描输出，不能把 compositor 的隐私/锁屏规则自动等同于 KMS 的规则。显示器休眠、DP 断开、注销也可能使 encoder/capture 重新探测失败。**不能承诺无人登录可用、能绕过锁屏、或能自动恢复权限**。[KMS 源码][S-kms]、[启动时编码探测][S-nvhttp]。这些是明确的验收项目；检测到锁屏时伙伴通道仍遵循现有停止操作规则。

Mac 补充：对应标签的 getting_started 已说明 macOS 14+ 可用 Audio Tap 原生系统音频，不再必然安装 BlackHole；与此同时 configuration 仍留有“只能麦克风”的旧文字，getting_started 也留有过时的 gamepad 提示。这里以实际代码和新权限说明为准，不依赖陈旧片段。Miffan 最初可只做画面，不把音频驱动或手柄 broker 纳入引导。[Mac audio 源码][S-mac-audio]、[安装与授权][S-install]

## 2. 集成：库边界、MediaCodec 和现有页面

### `moonlight-common-c` 做什么

**已核实：独立 C11 库，有 CMake，可静态或动态链接。** 它提供 RTSP 建流、UDP 视频/音频、ENet 控制、分包重组、FEC、流加解密、连接状态和输入事件。它不包含 Android UI、MediaCodec、Compose，也不替应用完成主机发现、HTTP 配对、应用列表、证书存储或 `/launch`、`/resume`。[README][C-readme]、[构建依赖][C-build]、[连接 API][C-api]、[Android HTTP 层][A-http]

依赖：

- **ENet 必须使用它固定的 `cgutman/enet` 子模块**。官方说明有 IPv6/重传等不兼容变更，不能换系统 libenet。
- **nanors** 提供 Reed-Solomon FEC，并包含构建所用的 `deps/obl` 运算源码；不是必须再安装的网络服务。
- **OpenSSL `libcrypto`**，或构建时选择 MbedTLS。common-c 本身不需要完整 FFmpeg；HTTPS 管理由客户端自己的 HTTP/TLS 层负责。
- 若做声音：Android 上游 JNI 另链接 **libopus**，由客户端解码 Opus 并播放。没有音频播放功能时可以不引入其完整播放层，但必须妥善处理协商及收到的音频，不能只漏实现回调后假定没有音频流。

出处：[子模块][C-submodules]、[CMake][C-build]、[Android.mk][A-build]。已有 RDP 使用的 OpenSSL 3.5.9 可作为共享构建依赖候选，但两 JNI SO 的静态符号应隐藏，核对版本/ABI/构建选项，不能让一套库意外解析到另一套全局符号。

关键接口：

| 用途 | API / 数据 | 注意 |
| --- | --- | --- |
| 连接 | `LiInitializeServerInformation`、`LiInitializeStreamConfiguration`、`LiStartConnection` | 传入 `/serverinfo` 与 launch 返回的 session URL、视频格式和输入密钥；不等于只给 host/IP 就能配对 |
| 状态、取消 | `CONNECTION_LISTENER_CALLBACKS`、`LiInterruptConnection`、`LiStopConnection` | start/stop 非线程安全，取消后必须等待退出再重连 |
| 视频 | `DECODER_RENDERER_CALLBACKS.setup/start/stop/cleanup/submitDecodeUnit`；或 pull renderer | H.264/HEVC 的 Annex B 数据交给解码器；失败返回 `DR_NEED_IDR`，缓冲只在规定生命周期内有效 |
| 输入 | `LiSendMouseMoveEvent`、`LiSendMousePositionEvent`、mouse button、scroll、keyboard、UTF-8、touch | 具体语义见第 6 节；不是 VNC keysym 或 FreeRDP 扫描码 API |
| 统计 | `LiGetEstimatedRttInfo`、pending video/audio、连接质量回调、decode unit 时间字段 | ENet RTT、解码耗时与端到端时延必须分别显示 |

出处：[完整头文件][C-api]。**还存在进程级单会话限制**：当前连接状态在 C 全局变量中，API 没有独立 connection handle。Miffan 应串行化 native start/stop，并在切换主机时关闭旧流；多页面同时观看不同主机不应视作现成支持。[Connection.c][C-connection]、[API 线程说明][C-api]

### Android：视频直出 Surface

上游 Android 的参考路径已经成立：JNI 接收 decode unit → Java renderer 整理参数集/帧并提交 `MediaCodec` input buffer → `configure(format, Surface, …)` → 独立输出线程 `releaseOutputBuffer` 渲染到 Surface；另有低延迟 codec 选择、厂商兼容与丢弃过期输出的策略。[JNI callbacks][A-callbacks]、[MediaCodec renderer][A-decoder]、[codec 策略][A-helper]、[Android API][N-codec]

这里的“零拷贝”应准确描述为：**避免 CPU 读取解码后的 YUV、YUV→RGB、IntArray/Bitmap 整帧复制和再上传 GPU**。网络码流整理、JNI 到 input buffer 的复制仍可能存在；上游 JNI 确实有 byte-array 桥接，不能称端到端无复制。

建议新增独立 Android library（名称暂定 `moonlight/`），固定 common-c/ENet/nanors，接少量 JNI 和经过裁剪的 HTTP/配对及 MediaCodec 实现。可借鉴/移植 Moonlight 的成熟 codec 兼容策略，尤其是 QTI 的低延迟参数，而非只写 `MediaCodec.createDecoderByType()`。保持 arm64-v8a、x86_64、现有 NDK 与 16 KiB 页对齐要求；不要直接打包 Moonlight APK 或一整个 Activity。

### 接到 Miffan 的具体边界（建议，未改代码）

| 现有部件 | 所需变化 |
| --- | --- |
| [`rdp/`](../../../rdp/) | 保留 FreeRDP/GDI/Bitmap 路径。复用构建经验、日志口径和凭据原则，不共用 FreeRDP decoder 或 RDP surface/ACK 状态 |
| [`RemoteDesktopSession`](../../../app/src/main/java/me/ayuilos/miffan/data/repository/RemoteDesktopSession.kt) | 增加 GameStream adapter 和 capability 信息（文本、剪贴板、内嵌光标、相对输入、可调整码率）；维持 state/start/close/输入语义 |
| [`RemoteScreenFrameSink`](../../../workspace/src/main/java/me/rerere/workspace/screen/RemoteScreenSession.kt) | 保留 CPU sink；另外引入 Android Surface 输出目标，不能让 GameStream 每帧回调 `onPixels` |
| [`RemoteScreenVM`](../../../app/src/main/java/me/ayuilos/miffan/ui/pages/extensions/workspace/screen/RemoteScreenVM.kt) | 将 Bitmap-only 展示状态扩为画面目标、视频尺寸、内容区变换与统计；伙伴截图继续走 cua-driver |
| [`RemoteScreenScaffold`](../../../app/src/main/java/me/ayuilos/miffan/ui/pages/extensions/workspace/screen/RemoteScreenPage.kt) / [`Canvas`](../../../app/src/main/java/me/ayuilos/miffan/ui/pages/extensions/workspace/screen/RemoteScreenCanvas.kt) | 复用唯一页面、工具栏、聊天条、手势及仲裁；内容层按协议使用 Bitmap 或 `AndroidView` 中的 SurfaceView/TextureView。平移/缩放、裁剪、Compose 覆盖层须单独验证 |
| [`RemoteScreenRepository`](../../../app/src/main/java/me/ayuilos/miffan/data/repository/RemoteScreenRepository.kt) | 增加独立媒体地址与连接方式，不把 GameStream 假装为 `openLoopbackStream`。SSH 租约用于 probe、身份背书、配对管理与伙伴，不是媒体流生命周期的唯一依据 |
| 凭据存储与 host 配置 | 分离 SSH 主机 key、Sunshine 服务端 cert pin、客户端 key/cert、媒体地址/端口、配对 ID 与可用能力；备份与模型上下文排除私钥/管理密码 |

当前 `typeText=false` 后会转 clipboard 的逻辑要更新：GameStream 未提供剪贴板时应给出支持范围或显式走现有 SSH clipboard bridge，不能无条件调用一个空 `sendClipboard`。当前 5/10/20 fps 菜单与 pause 语义也不能机械沿用：停止本地绘制不等于停止服务端发视频；离页应关闭流，回来恢复/重连，调清晰度初期通过重建流完成。

**工作量估算（单名熟悉项目的 Android/NDK 工程师，非承诺）：** 现成 Moonlight 外部 spike 1–2 人日；App 内 Linux MVP 约 8–12 人日（native/HTTP/证书 3–4、Surface/codec 3–5、输入/生命周期 2–3）；上线所需安全接入、配置迁移、中文/剪贴板、Mac 与桌面矩阵验收再约 12–20 人日。整体约 **4–7 周**，音频、HDR、AV1、多主机并发、虚拟显示器不在首版。APK 增量本次没有构建测量，不能给出可靠 MB 数字。

## 3. 许可证与分发

**结论：主路径可以与 Miffan 的 AGPLv3 组合；不是因 GPL 而必须放弃。** GPLv3 第 13 条明确允许与 AGPLv3 作品组合，GPL 部分仍遵循 GPL，AGPL 的网络交互要求适用于组合后的作品；不能简单把第三方代码的许可证标签全部改成 AGPL。[common-c 的 GPLv3 全文 §13][L-common]；Miffan [LICENSE](../../../LICENSE) 的第 13 条也有对应许可。

| 组件 | 已核实许可 / 判断 | 分发动作 |
| --- | --- | --- |
| moonlight-common-c | GPLv3 | 随 Miffan 组合分发可行；提供对应源码、修改和构建所需资料，保留版权、许可 |
| moonlight-android 中移植的 JNI/Java | GPLv3 | 同上；移植 codec helper 也产生源码及归属义务，不能只注明 common-c |
| Sunshine / Apollo | GPLv3 | 协议通信本身不等于链接；用户自行安装不等于 Miffan 分发。若捆绑或改版分发，要独立履行相应 GPL 义务 |
| ENet（指定 fork） | MIT | 兼容；保留版权与全文 |
| nanors（含所用 OBL 文件） | 仓库 MIT | 兼容；固定实际子模块后再核对包含文件的 notices，保留许可 |
| OpenSSL 3.x / 现有 3.5.9 | Apache-2.0 | 与 GPLv3/AGPLv3 主路径兼容；保留许可和适用 NOTICE。不要因为 CMake 接受 OpenSSL 1.0.2 就复制旧 OpenSSL 的不同许可版本 |
| MbedTLS（可选替代） | 常用发行版 Apache-2.0；需固定版本再核实 | 没有必要为此替换现有 OpenSSL；若采用，审核具体版本及依赖 |
| libopus（若加入） | BSD 风格许可 | 兼容；保留 Opus 版权/许可，并核对实际包含的优化源码 |
| libvirtualhid（Sunshine 主机依赖） | 公共跨平台库及普通 Linux/Mac 后端 MIT；Windows driver、Mac/Windows broker 等 **LB-SAL 1.0** | 不可把整个项目一概叫 MIT。首版键鼠不要求 Mac 手柄 broker；不要把专有 broker 打包进 AGPL 客户端 |
| Android MediaCodec / 系统 Surface | 平台 API，客户端不分发系统 codec | 不需要把 Android 固件解码器当成自己分发的第三方二进制；硬件能力仍需探测 |

出处：[common-c 许可][L-common]、[Android 许可][L-android]、[Sunshine 许可][L-sunshine]、[Apollo 许可声明][P-readme]、[ENet MIT][L-enet]、[nanors MIT][L-nanors]、[OpenSSL 许可][L-openssl]、[MbedTLS 许可][L-mbedtls]、[Opus 许可][L-opus]、[libvirtualhid 许可地图][L-hid]。

实际交付应包括：APK 内 notices/许可入口；准确匹配二进制的源码与补丁下载；固定子模块、构建脚本/选项和工具链版本；标识修改；按 GPL/AGPL 第 6 条选择合适的源码提供方式，适用 User Product 条件时提供 Installation Information。AGPL 第 13 条在其条件成立时要求向远程交互用户显著提供对应源码的获取机会；不是“任何未修改 Sunshine 在网上运行就自动改用 AGPL”。H.264/HEVC 的专利许可与开源著作权许可是两回事，本次没有评估商业分发的各地区专利安排。

**未做完整 Sunshine 全依赖 SBOM。** 服务端还涉及 FFmpeg、Boost 等及可选组件，不能据此报告宣称所有二进制包都已经审计通过。推荐复用用户已有、由其包管理器维护的 Sunshine，避免 Miffan 自行重打服务端和 broker。

## 4. 传输与安全：决定能否采纳的关键

### 端口与流

默认基准端口 `P=47989`；Sunshine 的主要监听如下：

| 默认端口 | 协议 | 用途 | 可直接用现有 SSH 字节流吗 |
| --- | --- | --- | --- |
| 47989（P） | TCP / HTTP | `/serverinfo`、发现及配对流程部分 | 可以转发 TCP，但客户端 HTTP 层也要提供接入方式 |
| 47984（P−5） | TCP / HTTPS | 配对后认证、应用列表、launch/resume/cancel | 同上；保留 TLS 和 cert pin |
| 47990（P+1） | TCP / HTTPS | Sunshine 管理 Web UI/API | 可以经 SSH 管理；不是视频端口 |
| 48010（P+21） | TCP | RTSP 建流/协商 | TCP 可以转发，但不把 UDP 媒体变成 TCP |
| 47998（P+9） | UDP | 视频/RTP、FEC、ping | **不能**经 `direct-tcpip` / streamlocal 直接转发 |
| 47999（P+10） | UDP / ENet | 现代控制流、键鼠输入、流反馈 | **不能** |
| 48000（P+11） | UDP | 音频/RTP | **不能** |
| 48002（P+13） | UDP | 旧端口表中的 Mic，Sunshine 文档标为 unused | 首版无需为它盲目开防火墙 |
| 5353 | UDP / mDNS | 本地发现 | 可用手工地址/SSH probe 避免依赖跨 VPN 广播 |

出处：[HTTP 偏移][S-nvhttp-h]、[Web 偏移][S-web-h]、[RTSP 偏移][S-rtsp-h]、[流偏移][S-stream-h]、[实际绑定][S-stream]。Moonlight 的历史 GameStream 指南还列出 UDP 48010 等兼容端口；**不能把 NVIDIA/GFE 的老清单全部当成当前 Sunshine 必需监听**。本表以对应 Sunshine 源码为准，采用时应检查实际 listeners 和协商返回的端口。[历史指南][N-moonlight-setup]

core 自己创建 TCP/UDP socket，没有与 FreeRDP `freerdp_set_io_callbacks` 等价的公开全传输 IO 注入 API。即使把 HTTP 和 RTSP 经过 SSH，UDP 的目标地址/端口和 ping/NAT 回程仍需解决，不能靠一条 JSch channel 完成。[socket 实现][C-sockets]、[公共 API][C-api]

### 方案逐项评估

| 方案 | 可行性 | 对本项目的影响 / 结论 |
| --- | --- | --- |
| 两端使用外部 Tailscale | **推荐 MVP**；应用用 tailnet IP 连接原生 TCP/UDP | 不开公网 GameStream 映射；需要 Tailscale 账号/设备身份/ACL，手机 VPN 与可达性。SSH 指纹仍管管理和 AI，媒体另用 cert pin。优先直连，DERP/peer relay 要区分 |
| 自建 WireGuard | 技术可行 | 保留 UDP 语义及外层加密，无 Tailscale 控制面；用户维护 peer keys、路由、端点及 NAT，可不是面向所有用户的一步接入 |
| Sunshine 绑定 Tailscale IP | **当前版本支持** `bind_address=100.x.y.z` | 限制可达接口；地址必须已经存在，服务启动顺序及重连需处理；该设置作用于多类监听，不能误以为 Web UI 仍在 127.0.0.1 |
| Sunshine 绑定 127.0.0.1 | 不能由 VPN 手机直接访问 | 仅 VPN 并不会把手机变成远端 loopback；必须另做 UDP proxy、路由或 helper，因此不作为高性能模式的现成方案 |
| 保留 all-interface，主机防火墙只放行 tailnet / WG 和 loopback | 可行，但需验证规则与 IPv6 | 能保留 `127.0.0.1:47990` 的 SSH 管理入口；代价是安全依赖规则及其持久性，不能偷偷修改用户规则或宣称服务本身只监听内网 |
| UDP over SSH（自定义 datagram framing/helper） | **理论可行、无现成 drop-in** | 两端需 relay，保留包边界、多个流和反向回程；core 需 socket 接口改造或本地 UDP sockets。SSH 的 TCP 丢包重传会导致队头阻塞，视频/input 共用连接还可能影响 AI 通道，不推荐生产首版 |
| OpenSSH TUN / `ssh -w` | 可承载 IP/UDP，但不是 JSch direct-tcpip | 需要 TUN、服务端 `PermitTunnel`、权限、路由；Android 若用 VpnService 会与 Tailscale 等 VPN 槽位竞争，仍有 TCP 隧道的延迟风险 |
| 公网端口映射 + 协议加密 | 协议上可运行 | 扩大服务暴露、配对与管理攻击面，且 CGNAT/防火墙仍可能不可达；不符合当前产品默认安全方向，不作为无 VPN 的自动后备 |
| 没有 VPN、只允许 SSH | **继续 VNC/RDP** | 这是可交付的默认后备，不能把一个理论 UDP-over-SSH 原型作为所有用户必须经过的步骤 |

出处：[绑定与网络代码][S-network]、[绑定配置][S-config]、[UDP sockets][C-sockets]、[SSH direct-tcpip 标准][N-ssh]、[OpenSSH TUN 说明][N-ssh-man]、[Tailscale 连接类型][N-tailscale]。

两个容易遗漏的限制：

1. 若绑定 tailnet 地址，当前 `ssh.openLoopbackStream(47990)` 就不能访问管理服务。要么明确选择“all-interface + 防火墙 + Web UI `origin_web_ui_allowed=pc`”，要么设计 SSH 侧对已验证的 tailnet 本机地址调用管理 API，并相应限制 Web UI 访问；**不要把 `bind_address` 写成支持同时传 127.0.0.1 和 tailnet 的列表**。API 的来源限制与 TLS pin 也要一起验收。[Web listener][S-web]、[网络代码][S-network]
2. Moonlight 在手机上会 `bind()` 临时 UDP 端口接收服务器返回数据。可以保持“不建立 TCP 转发监听/公网映射”，**不能逐字保留“手机上不监听任何端口”**。如果原要求禁止任何本地 bound UDP socket，原生 Moonlight 路径也不满足。[客户端 UDP 绑定][C-sockets]

外部 VPN 客户端不应在 Miffan 内秘密安装或代管。以后若集成 VpnService，要单独处理系统授权及与用户已有 VPN 的冲突，而非把它当成透明网络库。

### 协议自带加密覆盖什么

**不是“GameStream 全部明文”，也不是“配对后所有包当然都加密”。** 当前 Sunshine/common-c 已有扩展：

| 流 / 阶段 | 核实结果 |
| --- | --- |
| 配对与身份 | 客户端和服务端证书、PIN 派生的挑战/签名流程；初始 HTTP 请求和部分发现信息不在 HTTPS 内。PIN 不是后续每次登录的长期密码 |
| 配对后的 HTTP 管理/launch | HTTPS；客户端证书身份及固定服务端证书，launch 的 `rikey/rikeyid` 为流密钥材料 |
| RTSP | Sunshine 扩展支持 `rtspenc://` + AES-GCM；客户端须追加 `LiGetLaunchUrlQueryParameters()` 并保留返回的 session URL，不能降成普通 RTSP 字符串 |
| 控制/输入 | 当前新式控制流 AES-GCM，common-c 始终开启远端输入加密；老主机的控制协议能力不能直接类推 |
| 视频 | 可协商 AES-GCM；受 host 的 LAN/WAN encryption policy 与 client flags 影响 |
| 音频 | 可协商 AES-CBC；不能把它说成与视频相同的带 GCM 完整性认证；包头/流量形态也不能视为隐藏 |

出处：[配对实现][A-pair]、[HTTPS launch][A-http]、[RTSP 与策略强制][S-rtsp]、[common-c 协商][C-sdp]、[RTSP 加密][C-rtsp]、[视频解密][C-video]、[音频解密][C-audio]、[控制加密][C-control]。

建议客户端设 `encryptionFlags=ENCFLG_ALL`，主机经用户同意后同时设置 `lan_encryption_mode=2`、`wan_encryption_mode=2`，拒绝不满足加密要求的连接。默认 LAN=0、WAN=1 不能当成强制全加密；`100.64/10` 与 RFC1918 分类、客户端和服务端 LAN 判断可能不同，不要只调一个 policy。也不能先以“视频加密会降速”为由关闭它。VPN 加密是另一层，既限制可达范围，也覆盖协议未隐藏的发现/元数据；它不替代服务端证书验证。[默认策略与强制校验][S-config]、[server launch 拒绝逻辑][S-nvhttp]、[stream 配置字段][C-api]

### 安全模型实际变化

当前信任锚是 SSH 主机 key，所有画面/操作都经过一个受控字节流。新增模式后变为：

```text
手机 ── SSH（固定主机 key） ── 远端 probe / 配对管理 / cua-driver
  └── Tailscale 或 WireGuard（TCP + UDP） ── Sunshine ── 同一真实桌面
       Sunshine 客户端证书 + 服务端 cert pin + 强制流加密
```

需要新增 VPN 身份、ACL/防火墙、Sunshine 的配对撤销与证书生命周期，媒体通道不再由 SSH channel 的关闭自动结束。建议把 Sunshine cert DER SHA-256 通过已验证 SSH 取得并与媒体握手比对，沿用现有 RDP 的首次背书原则；地址或 SSH revision 变化时使旧媒体目标失效。VPN 可达只证明路径，不证明这是用户选择的那台电脑。

## 5. 配对、自动化与 Mac 首次权限

**结论：网络配对可在有管理授权时经 SSH 自动完成；TCC/Portal 首次授权和已有实例的管理员凭据不能凭空省掉。**

典型流程：客户端生成长期 key/cert 和 unique ID，产生 4 位 PIN，发起 GameStream `/pair`；用户在 Sunshine 管理界面输入 PIN；双方完成 challenge/签名，Sunshine 持久保存被授权客户端证书，客户端固定 host cert；此后用证书重新连接。[Android PairingManager][A-pair]、[Sunshine 配对状态][S-nvhttp]

对应 `v2026.1008.43744` 已有这些管理 API：[实际 routes 与实现][S-web]

| 接口 | 作用 / 自动化注意 |
| --- | --- |
| `GET /api/pin` | 返回待处理配对的 id、name、address |
| `POST /api/pin` | **此版本需 `pairing_id`、`pin`、`name`**；只发旧示例的 PIN 不足；请求等待握手成功或超时，必须与客户端 `/pair` 并行推进 |
| `DELETE /api/pin` | 取消指定 pending request |
| `/api/clients/list`、`/api/clients/unpair`、`/api/clients/update` | 查询、撤销、启用/禁用配对；别调用 unpair-all 清除用户其他客户端 |
| `GET/POST /api/config` | 读取/写入配置；有些改变需要重启，属于用户实例的全局改变 |
| `POST /api/password` | 管理密码创建/变更流程；已有凭据时要求认证/当前信息，不应以自动配对名义重置 |

Web API 用管理员 Basic auth **over HTTPS**；源码存在 CSRF 检查。此标签对不带 Origin/Referer 的非浏览器脚本放行 CSRF token 检查，但认证仍需通过；带浏览器来源时应使用允许来源或 `/api/csrf-token`，不能笼统声称“POST 无需 CSRF”，也不要修改白名单来绕过认证。

建议的 SSH 自动配对只在应用明确获得这台主机的配对授权后做：

1. SSH probe 发现服务与完整版本、配置路径和服务端 cert；不重建证书。
2. 手机持有 client private key，发起配对；SSH helper 查询 pending requests，严格匹配本次请求，不批准所有待配对设备。
3. 用用户提供的 Sunshine 管理授权提交对应 PIN，等待双方 cryptographic handshake 完成，核对 host cert，持久化成功状态。
4. 失败/取消清理本次 pending request；注销电脑只撤销 Miffan 自己的 client。

**已有 Sunshine 的管理员密码通常不能从存储的密码摘要恢复。** SSH 能以同一账号读取文件，不等于有可直接调用 Web API 的明文密码。用户不提供管理凭据时可保留“手动输入一次 PIN”的路径；不要覆盖既有密码或正在运行的 state 文件来伪造无人值守。也可研究源码级本地管理接口，但本次未核实有无需 admin auth 的稳定 IPC API。

配置通常在 Linux/macOS `~/.config/sunshine/`；配置中的 `pkey`、`cert`、`file_state`、`credentials_file` 可以覆盖默认路径。默认有 `credentials/cakey.pem`、`credentials/cacert.pem`、`sunshine_state.json`；state 保存授权 clients，credentials 默认也使用该 state 文件。运行中的服务会缓存、更新授权状态，直接编辑 JSON 有竞态和丢失其他配对的风险。[默认路径与配置覆盖][S-config-cpp]、[load/save state][S-nvhttp]

主机私钥不得复制到手机；手机 client key 与管理密码使用 Keystore 加密、noBackup 存储，日志不记录 PIN、key、认证 header 和 launch 密钥。每台手机建议有可独立撤销的客户端身份，不与系统已装 Moonlight 共用私钥。

Mac 首次权限：[getting_started][S-install]、[权限代码][S-mac-misc]

- **屏幕录制**：系统 TCC 授权，必要时 Quit & Reopen；授权给 Sunshine 的身份，不会因为 cua-driver 已获授权而自动继承。
- **键鼠注入**：CoreGraphics post-event 权限，对应辅助功能设置；源码调用 `CGPreflightPostEventAccess` / `CGRequestPostEventAccess`。没有证据支持把额外“输入监控”权限说成所有版本都必须，按系统实际提示处理。
- **本地网络**：macOS 15+ 在 Bonjour 使用时会请求；**系统音频录制**在系统 Audio Tap 使用时请求；自定义麦克风 sink 另需麦克风权限。通知是可选项。
- 键鼠经 libvirtualhid 的 CoreGraphics 后端，**不需要为了桌面键鼠安装或购买 Mac 手柄 broker**。TCC 提示、首次授权、登录图形会话不能由 SSH 脚本无提示授予；因此轻松模式是“引导并尽量自动化”，不是保证零人工步骤。

## 6. 输入与交互

**结论：现有点按/触控板/滚轮/缩放模型可以保留，但键盘、光标及中文 fallback 需专门适配。**

| 需求 | 已核实支持 | Miffan 对接方式与限制 |
| --- | --- | --- |
| 绝对鼠标 | `LiSendMousePositionEvent(x,y,referenceWidth,referenceHeight)` | 映射内容区后发送，按钮独立；不能把 RFB mask 原样当 button 编号 |
| 相对鼠标 / 触控板 | `LiSendMouseMoveEvent`；另有维护虚拟光标的 `LiSendMouseMoveAsMousePositionEvent` | 现有触控板语义可继续；避免 absolute 与虚拟相对坐标状态并发调用 |
| 长按右键、拖动 | button down/up + move | 保留；切换控制者、断连、页面离开时释放按键/鼠标按钮，避免 stuck key |
| 滚轮 | 垂直、水平、高精度 scroll API | 双指滚动可映射；单位和正负方向要验证，不能照抄 VNC 的 wheel-bit 脉冲 |
| 双指缩放 | 客户端画面变换 | 不必发到主机；Surface 与点击坐标使用同一变换 |
| 键盘硬键/快捷键 | `LiSendKeyboardEvent/2` | **Win32 VK / US 布局语义**，含 extended modifier；不是 RDP set-1 scancode，也不是 Unicode keysym；Command/Super、AltGr、左右修饰键逐项测试 |
| 手机输入法提交文本 | `LiSendUtf8TextEvent` | 能发送已 commit 的 UTF-8 文本；不承诺把 Android IME 的组合态/候选窗远程同步 |
| 原生触摸 / 笔 | Sunshine 扩展 `LiSendTouchEvent` / `LiSendPenEvent`，host feature flags | 按 host capability 使用；Linux 后端可虚拟 touchscreen，Mac 不可凭 API 名称保证实现；首版优先鼠标仿真 |

出处：[输入公共 API][C-api]、[Sunshine input 转发][S-input]、[libvirtualhid 平台支持][H-platform]。

### 中文与剪贴板是功能缺口，不是小修饰

`LiSendUtf8TextEvent` 确实存在，不能说 Moonlight “只能扫描码”。Sunshine 当前会转到 libvirtualhid `keyboard->type_text()`；独立上游源码显示：

- **Mac**：转换为 UTF-16，调用 CoreGraphics Unicode 键盘事件。对普通文本控件有实现依据；安全输入、终端、特定 Electron 应用及 IME 开启状态仍需测试。[Mac backend][H-mac]
- **Linux**：每个码点模拟 `Ctrl+Shift+U`、十六进制字符、Enter。这依赖目标应用/输入法支持 Unicode 输入法快捷序列；GTK 常见路径不能推广为 Qt、终端或所有 niri 应用均有效。队列成功只证明事件发送，不证明文本实际写入；应核对用户包实际 libvirtualhid 提交。[Linux backend][H-linux]、[Sunshine Unicode 入口][S-hid-input]

**上游 Sunshine/common-c 没有标准双向剪贴板 API/通道**（已检查此版本 public header 和 Sunshine 源码）。Apollo README 宣称 clipboard sync，但其 Linux/macOS `get_clipboard()` 返回空字符串、`set_clipboard()` 返回 false；对应 Windows 与 Artemis 扩展不能作为这两个平台的验收证据。[Apollo Linux][P-linux]、[Apollo Mac][P-mac]、[Apollo README][P-readme]

建议先保留并抽象仓库已有 SSH 侧 clipboard bridge：当前 [`miffan.sh`](../../../app/src/main/assets/remote/miffan.sh) 的 `clip` 使用 pbcopy / wl-copy / xclip / xsel 设置剪贴板；若将来增加 pbpaste 等回读，必须另验收会话和权限。按显式“粘贴”动作设置文本并发送 Ctrl+V / Cmd+V，确认写入成功、限制大小、经过控制权检查。**这是额外桥接，不是 Sunshine 原生能力**；双向自动同步另做隐私/生命周期设计，不能为改善中文而持续读取用户剪贴板。若当前桥接不可用，应明确禁用相关能力，不能默默把中文编码成普通按键。

### 远端光标

视频路径一般把 host 光标合成进画面：Portal 请求 `CURSOR_MODE_EMBEDDED`，KWin 请求 embedded cursor，wlr/KMS 有相应合成处理。core 的 renderer API 不提供 VNC/RDP 那种 cursor shape 回调；不能默认把现有本地箭头再画一遍。[Portal][S-portal]、[KWin][S-kwin]、[KMS][S-kms]、[common-c API][C-api]

应把“光标在视频中”作为显式能力；需要降低用户感知输入延迟时可加本地辅助指示，但必须处理两个光标、远端/伙伴主动移动、拖动和光标隐藏。用户和伙伴是否始终看到同一个实际光标，是验收项，尤其不能用最后一次手机点击位置替代伙伴的光标位置。

## 7. 与 cua-driver 共用真实桌面

**推断：采集可以并行，输入不会被 Sunshine 自动仲裁。** Sunshine 采集显示器，cua-driver 截图/读树使用自己的 API，没有代码依据表明普通 `Desktop` 流会独占桌面或阻止另一个客户端截图。但二者会共享 GPU、内存带宽、图形会话与当前焦点，必须测持续视频同时截图/操作的干扰。[采集后端][S-linux]、[Mac display][S-mac-display]

- Linux 当前键鼠由 libvirtualhid → `/dev/uinput` 虚拟设备，X11 可在 uinput 不可用时使用 XTest fallback；**Portal 获得采集权限不等于键鼠权限已经具备**。uinput 是独立部署权限，可直接作用于真实座席。[Linux input 入口][S-linux-input]、[后端权限][H-platform]
- Mac 普通键鼠是 CoreGraphics 会话事件；与 cua-driver 的辅助功能/前台动作可能作用于同一焦点。cua-driver 后台窗口操作依然可能不可见，Sunshine 不改变现有前台演示授权规则。[Mac input][S-mac-input]、[Mac backend][H-mac]
- “用户触摸即接管、伙伴每一步执行前检查”继续放在 Miffan。发送用户输入前先完成原子接管，清理上一控制者的排队输入；**已经在远端执行的 cua-driver 调用可能无法立即撤回**，要保留现有 in-flight 行为说明。
- 其他 Moonlight 客户端、本地键盘鼠标或其他 SSH agent 不受 Miffan 的应用内状态锁约束。必要时提示已有流占用，不能自动踢掉用户会话；Sunshine 配对授权不等于加入 Miffan 控制权系统。
- 普通 Desktop 不必改分辨率；但用户的 `global_prep_cmd`、应用 prep/undo 脚本、Low Res Desktop、Apollo headless/虚拟显示器可能改变输出、焦点或音频。应检查实际应用项与全局 prep，并禁止 Miffan 为适配手机自动关闭物理屏幕或切换主显示器。[应用与 prep 配置][S-app-linux]、[配置源码][S-config]

以现有权限和显示器正常工作为前提，可以共用；“绝不冲突、绝不改屏幕”只能通过禁用会话相关改屏设置和真机验收获得，不能由软件名称保证。

## 8. 性能、编码策略与移动网络

**结论：架构上适合低延迟连续视频，但本次没有这套设备的性能实测，不能给出保证 fps 或毫秒数。** 有损视频编码 + 硬解 Surface 预计能显著减小 Mac ZRLE 动态画面的带宽，并避免现有 RDP 的 CPU 像素转换/复制；静止桌面、细小彩色文字、中文交互却未必优于现有协议。[低延迟 encoder 配置][S-video]、[Android renderer][A-decoder]

### 编码与自适应

- 首选 **HEVC SDR 8-bit 4:2:0**，在 host 编码、手机解码尺寸/性能均支持时协商；回退 H.264。common-c 用 `supportedVideoFormats` 能力集合，不是任意排序列表；首版不要广告 AV1，让 HEVC/H.264 路径先稳定。实际 HEVC configure 或持续解码失败时可重建 H.264 流，但不可顺便降低加密要求。[core 格式定义][C-api]、[Android codec 选择][A-helper]
- RTX 3060 无 AV1 encode，不值得为本次 spike 强行启用；换到具有 AV1 编码器的 GPU 后再做端到端 capability 验证。手机或 Mac 能“解码 AV1”不能证明主机能编码它。[NVIDIA 矩阵][N-nvidia]
- 请求分辨率/fps/bitrate 在建流时协商。Sunshine 有 host `max_bitrate` 上限和静态画面 `minimum_fps_target`，还有 FEC 与连接质量反馈；**不等于像 WebRTC 那样已有通用无缝动态分辨率/码率自适应**。本次 API 没有公开的随意运行中更改 stream config 接口，首版降档用重新建流，UI 说明短暂停顿。[stream config][C-api]、[编码参数][S-video]、[配置][S-config]
- client `bitrate` 注释说明包含 FEC，默认约 20% FEC 时视频 encoder 净码率会更低；音频及 VPN/IP 开销另算。静态画面可能仍发送重复帧，不能把 10 Mbps 上限等同于稳定消耗 10 Mbps，也不能保证空闲接近 VNC 的零流量。[core 字段][C-api]、[帧调度][S-video]
- 初版关闭 HDR/4:4:4，先测文本质量。视频 4:2:0 会损伤彩色小字/边缘；服务端支持某种 4:4:4 编码不代表 Android 硬解及 Surface 显示链能接受，也不是 RDP AVC444 的直接替代。

### 测试用预算，不是“典型实测”

建议起点：Wi-Fi 1080p60、HEVC 10–15 Mbps；对照 1440p60、15–25 Mbps；移动网络先 720p30、3–6 Mbps，然后按实际质量上调。范围是本项目的调参建议，非上游保证或某设备测量。按持续 5 / 10 / 20 Mbps 计算，仅数据率折算约 **2.25 / 4.5 / 9 GB 每小时**；FEC、音频、外层开销和 encoder 变化会改变实耗。

60 fps 每帧约 16.7 ms，30 fps 约 33.3 ms。以“等待捕获、编码、单程网络、解码队列、显示刷新”拆预算，低 RTT 网络期望达到几十毫秒量级是合理目标，但不存在可对所有用户承诺的 10 ms 或 50 ms。`LiGetEstimatedRttInfo` 是控制 ENet RTT，MediaCodec 输出时刻也不等于屏幕实际亮起；端到端须使用输入→光学变化的独立测量。

移动网络关注：上行带宽、抖动、丢包、UDP 阻断、CGNAT、VPN MTU、Wi-Fi ↔ 蜂窝切换、温控和耗电。Tailscale 直连优于 relay；DERP 可保持连接但吞吐/延迟可能较差，并且外层转发也可能引入队头阻塞。不能因“连上 tailnet”就宣称已获得低延迟。[Tailscale 官方连接说明][N-tailscale]

packet size 初期用 core 文档建议的保守 1024 字节，验证 VPN 内外均无分片；core AUTO 的远程判断基于地址分类，不能直接代表实际网络路径。丢包恢复/FEC 提高可用性但不是拥塞控制的替代；码率超过可用带宽会造成堆积，应设置保守档位和明确重连降档。[core packet size][C-api]、[FEC/视频收包][C-video]

## 9. 运维、上游活跃度与共存

### 上游与分支

Sunshine 采用日期式版本，有稳定/预发布轨道；近期发布包含 Portal、KWin、编码器、接口绑定、Mac 输入和权限改进，项目仍活跃。此次阅读的 `2026.1008.43744` 是预发布，不能把它叫稳定版。Android 有成熟独立客户端和 `v12.2` 发布，core 被多个客户端共享，但这不构成稳定 ABI 或兼容永不变化的承诺。[Sunshine release][S-releases]、[Android release][A-release]、[核心库][C-readme]

GameStream 最初是 NVIDIA 的协议，Sunshine/Moonlight 是独立实现且加入自己的扩展；core 仍保留针对不同服务器版本的处理。配对、RTSP 加密、video format 和 host feature flags 都应做版本/capability 检测，不能依靠 NVIDIA 服务继续维护协议。升级组合需要回归测试，尤其不能混拼老 JNI 和新 core。

Apollo 活跃发布页有 `v0.4.6` 与 `v0.4.7-alpha.1`，但发行重点是 Windows；Linux/Mac 的部分函数只是继承的框架。Foundation Sunshine 是另一分支，其 README 明确专注 Windows 游戏串流，虚拟显示器、HDR 和驱动扩展不解决此处 Linux/Mac 的统一问题。没有发现足够依据推荐某个 fork 已经覆盖本项目所有缺口。[Apollo releases][P-releases]、[Apollo 差异][P-readme]、[Foundation README][P-foundation]

实际安全事件：Sunshine `GHSA-fp6g-27w5-489j` 影响 `v0.19.0` 至 `v2026.906.222525`，在 `v2026.914.233613` 修复；是 Linux 启动环境可加载不可信 GUI 模块并继承 capabilities 的本地提权风险，不是仅凭网络就能触发。说明 KMS 的高权限必须纳入版本维护，也不能推荐旧 fork 而不检查修复。[上游公告][S-advisory]

### 安装与更新方式（本次没有执行）

- **Arch/CachyOS**：上游提供 PKGBUILD 归档和 LizardByte pacman-repo 的预编译包；也有 AUR 路径，官方提示第三方包风险。AUR 页面本次被反爬拒绝，未可靠核实当天 `sunshine`/`sunshine-bin` 的版本，也未核实 CachyOS 仓库完整 PKGBUILD/当前版本；不能说用户一定来自某个仓库。以后只读 probe 应查实际 package owner/version/build options，而不是自动重装。[上游 Arch 指南][S-install]、[官方 pacman-repo][S-pacman]
- **Linux 包能力**：AppImage 不支持 KMS；Flatpak/降权限包的采集、uinput 与 GPU 高优先级路径有不同限制。NVIDIA CUDA 支持取决于编译时，运行后再装 CUDA 不会把缺失支持加回二进制。检查 logs 的实际 capture/encoder。[安装][S-install]、[权限与 GPU 排查][S-trouble]
- **Mac**：当前上游列出 arm64/x86_64 DMG 与 Homebrew `LizardByte/homebrew` tap；不能继续使用“Sunshine 只支持 Homebrew、没有 DMG”的旧结论。CI 有 Developer ID 签名和 notarization/stapling 流程，也有 unsigned artifact 分支；不能因存在签名脚本就断言任意 fork 或每个 nightly 已签名。推荐核实官方具体下载资产的签名，保持 bundle identity，更新后确认 TCC。[Mac 安装][S-install]、[签名/公证 CI][S-mac-ci]
- 更新沿用原安装渠道和用户服务管理方式；不并行混装 distro、AUR、Flatpak/Homebrew 和手工二进制。滚动发行版升级后需检查 GPU/FFmpeg/动态库兼容性。

### 用户已在用 Sunshine：接入现有实例

**建议复用，不另起第二个默认端口实例，不夺取 ownership。** 默认监听会冲突；即使改端口，第二实例仍可能共享采集、uinput、显示设置和 GPU，带来难以解释的行为。[实际监听][S-stream]、[配置路径][S-config-cpp]

只读探测完整版本、user unit、监听地址、当前应用/活动流、capture/encoder 和 host certificate。新增一个可撤销的 Miffan client，优先使用用户认可的普通 Desktop 项；有现有 stream 时不要 `/cancel` 或杀服务。调整 `bind_address`、加密、全局 prep、`output_name`、音频或版本都可能影响现有 Moonlight 客户端，应显示具体变化后由用户决定。

若必须创建 Miffan 专用应用项，只新增自己的条目且保留整个原列表，检查 global prep 仍会执行；不要覆盖 `apps.json`。不要修改、导出主机私钥或复制其他客户端 cert/key。断开 Miffan 应只结束自己的流，不自动停止服务或清除所有配对。

## 10. 全面替代还是并存

| 对比点 | 现有 VNC/RDP | Sunshine 高性能模式 |
| --- | --- | --- |
| 动态画面 | Mac 系统 VNC 无损带宽高；KDE/NVIDIA krdp 当前软编；GNOME RDP 已很好 | 统一 H.264/HEVC 硬编 + Surface 硬解，预计改善前两者，需实测 |
| 传输 | 可直接复用验证过的 SSH direct-tcpip/streamlocal | 原生 UDP，需要 VPN/另一传输方案；安全边界改变 |
| 服务准备 | Mac 系统屏幕共享；Linux 按桌面适配服务 | 一个 Sunshine 家族，但采集后端/系统权限依然按平台分化；Mac 新增主机软件和 TCC |
| 真实桌面与伙伴 | 已有模型、权限与仲裁 | 可复用同一桌面；上游不会替 Miffan 仲裁 |
| 中文与剪贴板 | 现有 RDP cliprdr 与 SSH bridge 有真实工作基础 | UTF-8 事件不保证 Linux 应用正确输入；无通用双向剪贴板 |
| 文本清晰与空闲流量 | VNC 无损/增量，RDP 为桌面图形设计 | 视频 4:2:0 与静态重复帧有代价；视频码率/fps 要独立调档 |
| 多屏/缩放/光标 | 已有 Bitmap、cursor shape、坐标体系 | 通常单输出视频与内嵌光标；Surface 坐标和页面变换需新增 |
| 运维 | 三类服务的维护负担 | 可减少视频协议数量，新增 native 客户端、VPN、配对和 codec 兼容维护；共存期负担增加 |
| 无 VPN 用户 | SSH 路径可继续工作 | 高性能模式暂不可用，保留原协议即可接入 |

**建议按主机能力与用户选择并存，而不是仅按平台强制切换：**

- GNOME：RDP 保持默认，Sunshine 做可选项。
- KDE + NVIDIA：通过 spike 后可推荐 Sunshine；无 VPN/无法配对时仍 RDP。
- niri/X11：有兼容 Sunshine + VPN 时可推荐高性能模式；wayvnc/x11vnc 保留。
- Mac：保留系统 VNC 的基础接入/授权救援通道；Sunshine 在 macOS 26.5 验证通过后成为可选增强。不会因 Linux 成功就自动把它设为 Mac 默认。

“一招鲜”目前能做到的是同一个高性能视频客户端与大部分输入逻辑，**做不到完全一致的安装、权限、网络、锁屏和剪贴板行为**。全面替代应等跨平台矩阵达标、无 VPN 的产品路径明确且用户接受新的安全模型后再决定。

## 风险清单

| 优先级 | 风险 | 处理 / 采纳门槛 |
| --- | --- | --- |
| P0 | 原生 UDP 违反全部经 SSH 的既有约束 | 先决定是否允许 VPN 媒体通道；不允许则停止主路径方案 |
| P0 | 默认加密、接口绑定或 IPv6 暴露与预期不符 | 两种 encryption policy 强制；pin 验证；按实际监听/ACL/防火墙验收，不只看配置字符串 |
| P0 | 接管用户现有 Sunshine、重启/踢流/覆盖配对 | 只探测和新增自有 client；全局改变逐项审批；保留用户实例 ownership |
| P1 | Linux 中文和剪贴板退化 | 逐应用测试；保留显式 SSH paste bridge，能力不支持时不假装成功 |
| P1 | Mac 26.5/TCC/Retina/锁屏不稳定 | Mac 独立验证，VNC 后备；不承诺无人登录或自动绕过权限 |
| P1 | Surface 重建、旋转、缩放造成卡死/错误点击 | codec 与 Surface 生命周期分离；同一内容区变换；native 取消、按键释放、恢复关键帧验收 |
| P1 | core 单会话/全局状态 | App 全局串行流所有权，切主机先关闭；多主机并发另立项目 |
| P1 | KMS/uinput 高权限及过旧构建 | 记录能力与安全修复版本；优先满足性能的较低权限后端；不自动追加系统 capabilities |
| P1 | VPN relay/MTU/移动网络导致延迟和高流量 | 区分 direct/relay；保守 packet size；低码率档位、流量统计和网络切换测试 |
| P2 | H.265/AV1/4:4:4 声明与手机实际能力不匹配 | codec probe + 运行时配置回退；首版 SDR420、HEVC/H.264；不伪报硬解 |
| P2 | 上游/fork 协议、API 或依赖漂移 | pin commits/submodules，固定兼容组合；跟踪上游安全公告及升级回归 |
| P2 | 误分发 LB-SAL broker 或遗漏 GPL 对应源码 | 客户端不打包主机 broker；完整 notices、源码与构建资料；发布前审核实际依赖清单 |

## 如果采纳：分阶段计划

以下是后续工作建议，**均未在本次调研中执行**。任何安装、连接用户机器、修改其服务/配对/网络或输入操作都应在后续任务得到授权。

### 阶段 0：最小可行验证 spike（1–2 人日）

目标：先用现成 Moonlight Android，经 Tailscale 连接 **用户已有 CachyOS Sunshine** 的普通 Desktop，验证视频与交互；先证明链路价值，再投入 Miffan JNI。若手机没有 Moonlight/Tailscale，则由用户/Claude 在后续授权下准备，不在本次安装。

执行设计：

1. 记录完整 Sunshine/package、驱动、桌面会话、capture/encoder、显示器及缩放，记录 OPPO Android/codec；确认现有其他流没有被接管。只用已安装服务，先不更新或重装。
2. 单独授权本次配对，使用自己的 client identity；确认 SSH 背书/手动确认的 host cert；核对既有安全配置，再决定是否允许具体修改。禁止试验性开公网端口或 unpair-all。
3. 优先 **KDE Wayland + NVENC**，随后 niri、GNOME、X11（若可进入）；切会话由用户执行并记录。对每个会话明确区分 Portal/KWin/wlr/KMS，不只写“Sunshine 可用”。
4. 1080p60/HEVC 10–15 Mbps 预热 30 秒、测量 3×60 秒；对照 H.264、1440p60。内容分别为静止文字、持续滚动代码、视频、伙伴前台演示。RDP/VNC 对照使用同一主机、输出、手机、网络、内容和接近的显示尺寸；记录时间与配置。
5. 统计实际编码器/解码器、收帧/渲染 fps、丢帧/网络丢包、码率/总流量、host encode、client decode、ENet RTT，并记录 Tailscale direct/DERP/peer-relay。Moonlight 浮层的 latency 不标成端到端。
6. 输入→画面采用用户可执行的高帧率摄像/时间标记测试，至少 30 次，给 p50/p95。没有该测量工具时只报告可取得的分段数据和主观评价，不编造端到端数字。
7. 点按边角/多屏偏移/拖动/右键/双指滚动/快捷键；中文测试 `中文输入🙂`、换行、长文本，覆盖 GTK、Qt、终端、浏览器；注明 native UTF-8 与 SSH 粘贴分别的结果。验证伙伴移动光标、用户接管、交还、输入中断和 stuck key。
8. 10 分钟以上持续运行观察温控/耗电；断网/网络切换/重连/显示器休眠/锁屏恢复各一轮。移动网络先低档测试，并确认流量预算。

**建议通过条件（项目目标，不是已通过）：** 1080p60 动态内容稳定渲染 ≥55 fps、异常/网络丢帧 <1%；direct 网络下实际输入→画面 p95 ≤100 ms 或明显优于相同条件的既有方案；文字可读、CPU 未回退软编、中文有可靠路径；无误点击、按键卡住或破坏现有 Sunshine 客户端；锁屏/断线至少能清楚报状态并恢复。性能数值需结合实测 RTT解释，任何安全或桌面 ownership 失败都优先于 fps。

这一 spike 用官方客户端能回答 host/network/hardware 是否值得；**不能**代表 Miffan 的 Surface/证书/JNI/仲裁已经通过。若只 KDE 收益明显，仍可先局部采用。

### 阶段 1：App 内 Linux MVP（约 8–12 人日）

- 独立 native library、固定依赖与 notices；HTTP/证书/配对、single-session 生命周期、Surface renderer、HEVC/H.264 协商与统计。
- 复用 Scaffold、手势及仲裁；补 capability、内嵌光标、输入映射和可靠中文 paste bridge。
- 仅人工选择已配对 Sunshine + 外部 VPN；不自动安装、更新或全局修改现有实例。
- 验收：错误 cert 拒绝、无法满足加密拒绝、取消 start/旋转/退后台/重连资源释放、切 host 不误发输入；真 OPPO 测实际 decoder 与持续性能。

### 阶段 2：安全自动化与产品引导（约 5–8 人日）

- 扩展只读 probe；经 SSH 背书 cert、授权后定向自动配对；已有 admin 凭据缺失时提供一次 PIN 手动路径。
- 明确媒体地址/端口与 VPN 的要求；实现自有 client 撤销、版本兼容提示、断线降档，保留 SSH-only 后备。
- 全局绑定/加密/显示配置变更提供具体差异与影响，不在打开屏幕页时悄悄进行。

### 阶段 3：Mac 与完整矩阵（约 7–12 人日）

- macOS 26.5 / Apple Silicon / Retina、多屏偏移、Command/中文、TCC 首次授予与撤回、锁屏/休眠/屏幕插拔。
- GNOME/KDE/niri/X11 与 NVIDIA/AMD/Intel 的最小代表硬件矩阵；不要把 NVIDIA 通过当成所有 VA-API 通过。
- 依据结果确定逐平台默认值；音频、AV1、HDR、4:4:4、多主机并发与虚拟显示器另行评估。

## 需要用户决定的问题

本次调研无需等待答案；这些是进入实现前的产品决策，默认建议如下：

1. **是否允许安全模型变成“SSH 管理/伙伴 + VPN 媒体/用户输入”？** 建议允许为可选高性能模式；若坚持全部经 SSH，则保留 VNC/RDP，不投入原生 Sunshine 集成。
2. **能否将外部 Tailscale/WireGuard 作为该模式的先决条件？** 建议可以，无 VPN 用户继续现有协议，不开公网端口作隐式后备。
3. **是否先授权使用已运行的 CachyOS Sunshine 做外部客户端 spike？** 建议先 KDE/NVIDIA，再 niri/GNOME；配对、进入桌面与任何配置修改在下一任务明确范围。
4. **能否接受剪贴板/中文继续通过独立 SSH bridge？** 建议接受；不承诺 Apollo 原生跨平台剪贴板，也不把 IME compose 状态同步纳入 MVP。
5. **是否接受一个进程同时仅一条 GameStream？** 建议首版接受；已有多个屏幕页要有清楚的流所有权与切换行为。
6. **Mac 是否可新增 Sunshine 及其系统权限？** 建议 Linux 验证后再决定；未通过前保留系统屏幕共享，首版不要求音频/手柄 driver。
7. **是否允许更改用户 Sunshine 的全局加密/绑定配置？** 建议任何具体变化单独确认，并展示对既有 Moonlight 的影响；拒绝变化时明确能力不可用，不能静默降安全要求。

## 出处索引

上文链接尽量固定源码提交/标签；滚动文档/发行页以调研日状态为准。独立库 license/backend 快照不能代替未来实际打包依赖的逐文件检查。

[C-readme]: https://github.com/moonlight-stream/moonlight-common-c/blob/f900dd4767759c7b9d0e93bcea666b55c69ea62f/README.md
[C-build]: https://github.com/moonlight-stream/moonlight-common-c/blob/f900dd4767759c7b9d0e93bcea666b55c69ea62f/CMakeLists.txt
[C-submodules]: https://github.com/moonlight-stream/moonlight-common-c/blob/f900dd4767759c7b9d0e93bcea666b55c69ea62f/.gitmodules
[C-api]: https://github.com/moonlight-stream/moonlight-common-c/blob/f900dd4767759c7b9d0e93bcea666b55c69ea62f/src/Limelight.h
[C-connection]: https://github.com/moonlight-stream/moonlight-common-c/blob/f900dd4767759c7b9d0e93bcea666b55c69ea62f/src/Connection.c
[C-sockets]: https://github.com/moonlight-stream/moonlight-common-c/blob/f900dd4767759c7b9d0e93bcea666b55c69ea62f/src/PlatformSockets.c
[C-sdp]: https://github.com/moonlight-stream/moonlight-common-c/blob/f900dd4767759c7b9d0e93bcea666b55c69ea62f/src/SdpGenerator.c
[C-rtsp]: https://github.com/moonlight-stream/moonlight-common-c/blob/f900dd4767759c7b9d0e93bcea666b55c69ea62f/src/RtspConnection.c
[C-video]: https://github.com/moonlight-stream/moonlight-common-c/blob/f900dd4767759c7b9d0e93bcea666b55c69ea62f/src/VideoStream.c
[C-audio]: https://github.com/moonlight-stream/moonlight-common-c/blob/f900dd4767759c7b9d0e93bcea666b55c69ea62f/src/AudioStream.c
[C-control]: https://github.com/moonlight-stream/moonlight-common-c/blob/f900dd4767759c7b9d0e93bcea666b55c69ea62f/src/ControlStream.c
[A-build]: https://github.com/moonlight-stream/moonlight-android/blob/b48494cb96bff23d8886c4775cc4f39a1075495d/app/src/main/jni/moonlight-core/Android.mk
[A-callbacks]: https://github.com/moonlight-stream/moonlight-android/blob/b48494cb96bff23d8886c4775cc4f39a1075495d/app/src/main/jni/moonlight-core/callbacks.c
[A-decoder]: https://github.com/moonlight-stream/moonlight-android/blob/b48494cb96bff23d8886c4775cc4f39a1075495d/app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java
[A-helper]: https://github.com/moonlight-stream/moonlight-android/blob/b48494cb96bff23d8886c4775cc4f39a1075495d/app/src/main/java/com/limelight/binding/video/MediaCodecHelper.java
[A-http]: https://github.com/moonlight-stream/moonlight-android/blob/b48494cb96bff23d8886c4775cc4f39a1075495d/app/src/main/java/com/limelight/nvstream/http/NvHTTP.java
[A-pair]: https://github.com/moonlight-stream/moonlight-android/blob/b48494cb96bff23d8886c4775cc4f39a1075495d/app/src/main/java/com/limelight/nvstream/http/PairingManager.java
[A-release]: https://github.com/moonlight-stream/moonlight-android/releases/tag/v12.2
[S-releases]: https://github.com/LizardByte/Sunshine/releases
[S-release1008]: https://github.com/LizardByte/Sunshine/releases/tag/v2026.1008.43744
[S-config]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/docs/configuration.md
[S-install]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/docs/getting_started.md
[S-trouble]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/docs/troubleshooting.md
[S-linux]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/platform/linux/misc.cpp
[S-wayland]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/platform/linux/wayland.cpp
[S-wlr]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/platform/linux/wlgrab.cpp
[S-kms]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/platform/linux/kmsgrab.cpp
[S-kwin]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/platform/linux/kwingrab.cpp
[S-portal]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/platform/linux/portalgrab.cpp
[S-vaapi]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/platform/linux/vaapi.cpp
[S-vulkan]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/platform/linux/vulkan_encode.cpp
[S-video]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/video.cpp
[S-mac-video]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/platform/macos/av_video.m
[S-mac-display]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/platform/macos/display.mm
[S-mac-audio]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/platform/macos/av_audio.mm
[S-mac-misc]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/platform/macos/misc.mm
[S-mac-input]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/platform/macos/input.cpp
[S-linux-input]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/platform/linux/input/virtualhid.cpp
[S-hid-input]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/platform/virtualhid_input.cpp
[S-input]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/input.cpp
[S-stream-h]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/stream.h
[S-stream]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/stream.cpp
[S-rtsp-h]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/rtsp.h
[S-rtsp]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/rtsp.cpp
[S-nvhttp-h]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/nvhttp.h
[S-nvhttp]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/nvhttp.cpp
[S-web-h]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/confighttp.h
[S-web]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/confighttp.cpp
[S-network]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/network.cpp
[S-config-cpp]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src/config.cpp
[S-app-linux]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src_assets/linux/assets/apps.json
[S-app-mac]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/src_assets/macos/assets/apps.json
[S-mac-ci]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/.github/workflows/ci-macos.yml
[S-pacman]: https://github.com/LizardByte/pacman-repo
[S-advisory]: https://github.com/LizardByte/Sunshine/security/advisories/GHSA-fp6g-27w5-489j
[P-readme]: https://github.com/ClassicOldSong/Apollo/blob/adc5c5a0bd80831ce495434bb16aee2cd4175fb8/README.md
[P-linux]: https://github.com/ClassicOldSong/Apollo/blob/adc5c5a0bd80831ce495434bb16aee2cd4175fb8/src/platform/linux/misc.cpp
[P-mac]: https://github.com/ClassicOldSong/Apollo/blob/adc5c5a0bd80831ce495434bb16aee2cd4175fb8/src/platform/macos/misc.mm
[P-releases]: https://github.com/ClassicOldSong/Apollo/releases
[P-foundation]: https://github.com/AlkaidLab/foundation-sunshine/blob/master/README.md
[H-platform]: https://github.com/LizardByte/libvirtualhid/blob/7cd296cabc1503f78f110cc8cc9aedd7fc3cddc3/docs/platform-support.md
[H-linux]: https://github.com/LizardByte/libvirtualhid/blob/7cd296cabc1503f78f110cc8cc9aedd7fc3cddc3/src/platform/linux/uhid_backend.cpp
[H-mac]: https://github.com/LizardByte/libvirtualhid/blob/7cd296cabc1503f78f110cc8cc9aedd7fc3cddc3/src/platform/macos/macos_backend.cpp
[L-common]: https://github.com/moonlight-stream/moonlight-common-c/blob/f900dd4767759c7b9d0e93bcea666b55c69ea62f/LICENSE.txt
[L-android]: https://github.com/moonlight-stream/moonlight-android/blob/b48494cb96bff23d8886c4775cc4f39a1075495d/LICENSE.txt
[L-sunshine]: https://github.com/LizardByte/Sunshine/blob/v2026.1008.43744/LICENSE
[L-enet]: https://github.com/cgutman/enet/blob/master/LICENSE
[L-nanors]: https://github.com/sleepybishop/nanors/blob/f3dc50c67ccfdd6c20177e57658a011a7d26e2bd/LICENSE
[L-openssl]: https://github.com/openssl/openssl/blob/openssl-3.5.9/LICENSE.txt
[L-mbedtls]: https://github.com/Mbed-TLS/mbedtls/blob/development/LICENSE
[L-opus]: https://opus-codec.org/license/
[L-hid]: https://github.com/LizardByte/libvirtualhid/blob/7cd296cabc1503f78f110cc8cc9aedd7fc3cddc3/LICENSES/license-map.md
[N-niri]: https://github.com/niri-wm/niri/wiki/Screencasting
[N-niri-code]: https://github.com/niri-wm/niri/blob/main/src/protocols/screencopy.rs
[N-nvidia]: https://developer.nvidia.com/video-encode-decode-support-matrix
[N-ssh]: https://www.rfc-editor.org/rfc/rfc4254#section-7.2
[N-ssh-man]: https://man.openbsd.org/ssh#SSH-BASED_VIRTUAL_PRIVATE_NETWORKS
[N-tailscale]: https://tailscale.com/docs/reference/connection-types
[N-codec]: https://developer.android.com/reference/android/media/MediaCodec
[N-moonlight-setup]: https://github.com/moonlight-stream/moonlight-docs/wiki/Setup-Guide
