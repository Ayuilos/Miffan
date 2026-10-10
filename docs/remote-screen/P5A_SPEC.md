# P5a：FreeRDP Android 原生库与 RDP 会话（Codex 规格）

背景与决策见 `docs/remote-screen/P5_RDP_PLAN.md`，先读它。本阶段只做非界面部分：原生库、JNI、Kotlin 会话类和验证。不改屏幕页、VM 或任何 Compose 代码。

## 你负责的文件

- 新模块 `rdp/`（Android library，`settings.gradle.kts` 中注册），包名 `me.rerere.rdp`。
- `rdp/scripts/` 下的原生构建脚本，`rdp/src/main/cpp/` 下的 JNI 代码。
- `rdp/src/test`、`rdp/src/androidTest` 下的测试。
- `settings.gradle.kts`、`gradle/libs.versions.toml` 中为新模块必需的改动。
- `docs/remote-screen/P5A_NOTES.md`（你的结论）。
- 不要改 `app/` 与 `workspace/` 的源码。需要复用 `workspace` 模块的类型时，让 `rdp` 依赖 `:workspace`。
- 在 `feature/rdp-client` 分支上提交；不要合并、rebase 或推送其他分支。

## 源码与工具（已下载，不要再联网取这两份源码）

- `.deps/FreeRDP-3.32.1.tar.gz`，sha256 `8803dd26ec9660550252f255cf2d672a785ddd8f544bb475834993e96807c87f`
- `.deps/openssl-3.5.9.tar.gz`，sha256 `603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a`
- NDK `~/Library/Android/sdk/ndk/28.2.13676358`；CMake/Ninja `~/Library/Android/sdk/cmake/3.22.1/bin`（系统 PATH 里没有 cmake）。
- `.deps/` 已被 git 忽略。解压与中间产物放在 `.deps/` 或 `rdp/build/` 下。

## 1. 原生构建

- 脚本 `rdp/scripts/build-native.sh`：校验上面两个 sha256，为 `arm64-v8a` 与 `x86_64` 编译静态 OpenSSL 与静态 FreeRDP（winpr + libfreerdp + 需要的通道），输出到一个明确的目录，供 `rdp` 模块的 CMake 链接成单个 `libmiffanrdp.so`。
- FreeRDP 选项原则：不要客户端程序（SDL/X11/Wayland/Android 自带的 aFreeRDP 界面）、不要服务端、不要 FFmpeg/swscale；开启 MediaCodec H.264（`h264_mediacodec.c`）；通道只要动态虚拟通道、图形管线（rdpgfx）、显示控制（disp）、剪贴板（cliprdr，本阶段可以只编进去不接）。其他通道一律关闭。
- 预编译产物是否入库，由你在 notes 里给出建议和理由（构建耗时、APK 体积、CI 影响）；本阶段先不入库（加入 .gitignore），Gradle 构建在产物缺失时给出明确的报错，指向脚本。
- 记录：每个 ABI 的 `libmiffanrdp.so` 体积（strip 后），以及 app debug APK 的体积变化估算。

## 2. 传输：手机上不监听端口

- 使用 `freerdp_set_io_callbacks`（`include/freerdp/transport_io.h`）让 FreeRDP 的 TCP 读写走 JNI 回调，由 Kotlin 侧的 `InputStream`/`OutputStream` 提供字节。不使用 localhost 端口转发、不开 TCP 监听、不使用 abstract unix socket。
- 若 IO 回调在 TLS/NLA 阶段确实无法使用，再退到 `socketpair` 一端交给 FreeRDP、另一端由 Kotlin 线程搬运；必须在 notes 里说明原因。

## 3. Kotlin API

```kotlin
class RdpSession(
    input: InputStream,
    output: OutputStream,
    transport: Closeable,
    credentials: RdpCredentials,      // username, password, domain?；toString 不得输出密码
    options: RdpOptions,              // 桌面尺寸(默认 1920x1080)、期望的证书 SHA-256（null = 首次连接，接受并通过 state 暴露指纹）
    sink: me.rerere.workspace.screen.RemoteScreenFrameSink,
) : Closeable {
    val state: StateFlow<me.rerere.workspace.screen.RemoteScreenState>
    val certificateSha256: StateFlow<String?>   // 服务器 TLS 证书指纹，供上层固定
    val stats: StateFlow<RdpStats>               // 帧率、字节数、当前编码（AVC444/AVC420/RFX/Planar…）、H.264 是否硬解
    fun start(scope: CoroutineScope)
    fun setPaused(paused: Boolean)               // 页面不可见时停止上传画面（可用 suppress output PDU）
    fun pointer(x: Int, y: Int, buttons: Int)    // 与 RfbClient 相同的按钮掩码；8/16/32/64 为滚轮
    fun key(keysym: Int, down: Boolean)          // X11 keysym（见 RfbKeys），映射到 RDP 扫描码
    fun typeText(text: String)                   // 用 RDP Unicode 键盘事件，支持中文等任意字符
}
```

- 帧：FreeRDP GDI 合成后的画面，每次 EndPaint 只把无效区域转成 ARGB 交给 `sink.onPixels`，然后 `onFrameComplete`；尺寸变化走 `onSize`（scale 恒为 1）。
- 指针形状：转成 `RfbCursor` 交给 `sink.onCursor`（含隐藏指针）。
- 安全：支持 TLS 与 NLA（CredSSP/NTLM）两种安全层，由服务端协商。证书校验回调不得无条件接受：有期望指纹时必须匹配，否则失败；为 null 时接受并暴露指纹。
- 线程：FreeRDP 事件循环在自己的线程；`sink` 回调在该线程上调用，不得阻塞太久。输入调用可来自主线程，不得在主线程上做网络 I/O。
- 关闭：`close()` 幂等，释放原生资源，不泄漏线程。

## 4. 验证

测试服务器由 Claude 在 CachyOS 上启动，并在 Mac 上开好 SSH 隧道。模拟器里用 `10.0.2.2` 访问 Mac 的本机端口：

- KDE：`10.0.2.2:13391` → krdp（`--plasma`，TLS 安全层，用户名/密码见 `.deps/rdp-test.properties`）。
- GNOME：`10.0.2.2:13390` → gnome-remote-desktop（NLA）。两种桌面不能同时运行；默认先开 KDE，GNOME 是否在运行以 `.deps/rdp-test.properties` 中的 `active=` 为准。

要求：

1. `./gradlew :rdp:testDebugUnitTest` 通过（keysym → 扫描码映射、凭据脱敏、证书指纹比较等纯逻辑用 JVM 测试）。
2. 一个 instrumented 测试（`rdp/src/androidTest`），读取 `.deps/rdp-test.properties` 中的参数（通过 instrumentation arguments 传入，不要把密码写进源码或提交），在 `emulator-5560` 上用普通 `Socket` 连 `10.0.2.2:<port>`（隧道已负责 SSH，这里只验证 RDP 栈），断言：连上、收到至少一帧非空画面、协商到的编码、H.264 是否由 MediaCodec 解码；并把一帧保存为 PNG 到测试输出目录供人工查看。
3. 输入：在测试里点击并输入文字（测试桌面上有一个 zenity 输入框），由 Claude 在远端确认结果；在 notes 里写清楚你发送了什么。
4. `./gradlew :app:compileDebugKotlin` 通过。
5. `docs/remote-screen/P5A_NOTES.md`：构建方式与耗时、体积、选项取舍、IO 回调实现、实测的编码/帧率/解码方式、已知问题、P5b 需要的接口建议。

## 约束

- 只用 `emulator-5560`；`emulator-5554` 属于别的会话，不要碰。
- 不读取或复制任何签名配置、SSH 私钥、`local.properties` 以外的凭据文件。测试密码只来自 `.deps/rdp-test.properties`。
- 需要联网（Gradle 依赖等）时沙箱会弹审批，由用户在面板里批准；不要为了绕过审批而改动全局配置。
- 原生构建很慢，可以只先跑 arm64-v8a 验证，再补 x86_64。
