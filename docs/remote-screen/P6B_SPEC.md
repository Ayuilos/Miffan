# P6b：`stream` 原生库（Sunshine 高性能模式客户端）

背景：`P6_SUNSHINE_PLAN.md`（方案）、`P6A_NOTES.md`（源码核实与实测）、`tools/p6a-probe`（Mac 上已验证可用的命令行工具，含 common-c 补丁）。本阶段只做库和模拟器验收，**不改 App（`app/`）**，接入 App 是 P6c。

## 目标

新 Gradle 模块 `:stream`（包名 `me.rerere.stream`），在 Android 上实现：经调用方提供的 TCP 字节流完成 GameStream 的 HTTP / HTTPS / 配对 / launch / resume / RTSP；UDP 直连调用方选定的数字地址；强制加密；HEVC / H.264 硬件解码直接输出到 `Surface`；键鼠输入。

## 硬性约束

- **手机上不监听任何端口**，也不连本机 loopback 端口。所有 TCP（47989 HTTP、47984 HTTPS、48010 RTSP）都通过调用方提供的回调打开：

  ```kotlin
  fun interface StreamTcpConnector { fun open(remotePort: Int): StreamTcpChannel }
  class StreamTcpChannel(val input: InputStream, val output: OutputStream, val closeable: Closeable)
  ```

  App 里由 SSH direct-tcpip（`NativeSshWorkspaceTransport.openLoopbackStream`）实现；测试里由普通 Socket 实现。common-c 的 RTSP 每个请求都会新建 TCP 连接，所以要在 common-c 加一个连接钩子（新补丁）：native 调回 Kotlin 打开通道，Kotlin 用 `ParcelFileDescriptor.createSocketPair()`（或等价方式）把一端交给 native，另一端与通道双向搬运。HTTP / HTTPS 用同样的通道。
- 保留 `tools/p6a-probe/patches/0001-split-transport-strict-encryption.patch` 的语义：`requireEncryptedStreams` 默认开启且 App 不提供关闭的入口；拒绝降级。可以在其基础上调整为“RTSP 走连接钩子”。
- 主机证书按 DER SHA-256 固定：首次配对得到的指纹由调用方保存，之后每次 TLS 握手后、发送请求前比对，不一致就失败并给出可识别的错误（App 将复用 RDP 的“信任新证书”流程）。
- 永不调用 `/cancel`、`/unpair`；已有应用在运行时用 `/resume`。每次连接生成新的 `rikey` / `rikeyid`。
- 同一进程同时只允许一条串流（common-c 有全局状态）；第二条要报明确错误，不得互相破坏。
- 日志不记录 PIN、私钥、rikey、launch URL 的查询参数。

## 原生部分

- 源码：`.deps/p6a/common-c.tar.gz`（`f900dd4`）、`.deps/p6a/common-c/enet.tar.gz`（`aca8784`）、`.deps/p6a/common-c/nanors.tar.gz`（`b1e3c22`）。写 `stream/scripts/fetch-sources.sh`（URL + SHA-256 校验，参照 `rdp/scripts/fetch-sources.sh`）与 `build-native.sh`；本机已有这些压缩包，构建时不需要联网。
- OpenSSL：复用 `rdp/scripts/build-native.sh` 产出的静态库（`rdp/build/native/<abi>/lib/libssl.a`、`libcrypto.a`），不要再编一份源码。两个 `.so` 各自静态链接即可。
- ABI：`arm64-v8a`、`x86_64`；NDK、CMake 版本与 `rdp` 一致。
- 补丁放 `stream/scripts/patches/`，每个补丁开头写明原因。
- HTTP / 配对：可移植 `tools/p6a-probe` 中已验证的 C 实现（OpenSSL 直连 TLS、DER pin、PIN challenge 校验），去掉 curl / expat 依赖或改用 Kotlin 实现，自行选择，但 TLS 必须经连接钩子提供的 fd。
- 音频：本阶段不播放，提供空的音频回调（仍需正常建立音频流，否则 Sunshine 会断开）。

## Kotlin API（`me.rerere.stream`）

以下是 P6c 要用的契约，可以补充但不要改变语义；有不同意见请在 NOTES 里说明理由。

```kotlin
data class StreamIdentity(val certificatePem: String, val privateKeyPem: String, val uniqueId: String)
object StreamIdentities { fun generate(): StreamIdentity }

class StreamHost(connector: StreamTcpConnector, identity: StreamIdentity, pinnedCertificateSha256: String?) {
    suspend fun serverInfo(): StreamServerInfo            // 版本、是否已配对、当前运行的应用、编码能力
    suspend fun pair(pin: String, deviceName: String): String  // 成功返回主机证书 DER SHA-256（小写 hex）
    suspend fun apps(): List<StreamApp>
}

data class StreamConfig(
    val width: Int, val height: Int, val fps: Int, val bitrateKbps: Int,
    val codecs: Set<StreamCodec>,                       // HEVC 优先，H.264 回退
)

class StreamSession(
    host: StreamHost, app: StreamApp, udpAddress: String /* 数字 IP */, config: StreamConfig, surface: Surface,
) : Closeable {
    val state: StateFlow<StreamState>                    // Connecting / Streaming / Failed(reason, stage, code) / Closed
    val stats: StateFlow<StreamStats>                    // 见下
    fun start(scope: CoroutineScope)
    fun mousePosition(x: Int, y: Int, referenceWidth: Int, referenceHeight: Int)
    fun mouseMove(dx: Int, dy: Int)
    fun mouseButton(button: StreamMouseButton, down: Boolean)
    fun scroll(vertical: Int, horizontal: Int)           // 高精度单位，正负方向在 NOTES 写清
    fun key(windowsVirtualKey: Int, down: Boolean, modifiers: Int)
    fun releaseAllInput()                                // 断开、切换控制者、离开页面时调用
    fun setSurface(surface: Surface?)                    // 旋转、页面重建；为 null 时暂停渲染但不断流
}
```

- `StreamState.Failed` 要能区分：证书不匹配、加密要求不满足、UDP 不通（控制流建立超时 / 无视频流量）、RTSP / TCP 失败、主机拒绝（未配对等）、解码器失败。P6c 的路径选择依赖“UDP 不通”这一类。
- 候选尝试：提供可设定的建流总期限（默认 4 s），到期用 `LiInterruptConnection` 取消，等上一条完全停止后才允许下一次 `start`。
- `StreamStats`：协商的编码 / 分辨率 / fps、各路加密是否开启、收帧 fps、渲染 fps、解码耗时（均值 / 最大）、丢帧、码率、RTT（`LiGetEstimatedRttInfo`）、解码器名称、是否低延迟。

## 解码

- MediaCodec 直接配置到 `Surface`，按 `RdpSession` 现有方式选择硬件解码器并开启低延迟（`KEY_LOW_LATENCY`，QTI 厂商键需先检查支持）。
- HEVC 配置失败或持续解码失败时，重新建流改用 H.264；不得降低加密要求。
- `setSurface` 替换时不断开串流：重建解码器并请求 IDR。

## 验收（只用 `emulator-5560`）

1. `./gradlew :stream:testDebugUnitTest` 与新增的 JVM 单元测试通过（URL / XML 解析、错误映射、输入映射等可测部分）。
2. 原生构建两个 ABI 均成功；记录 `.so` 大小。
3. 模拟器实测（插桩测试或测试脚本，参照 `rdp/scripts/test-emulator.py`）：TCP 连接 Mac 上 `10.0.2.2` 的转发端口（转发由 Claude 建立，到 CachyOS 主机的 `127.0.0.1`），UDP 发往 `100.64.0.5`：
   - 配对：用本模块新生成的身份完成一次 PIN 配对。**需要 PIN 时在 pane 里告诉 Claude，Claude 转给用户在 Sunshine 网页输入**，不要自己尝试其他方式。
   - 串流 30 s：HEVC 1920×1080@60 与 H.264 各一次，解码到真实 `Surface`（例如测试 Activity 的 `SurfaceView`），记录 stats；协商结果必须是三路加密。
   - 证书固定：错误指纹必须在发送任何 HTTP 请求前失败。
   - 候选失败：UDP 发往一个不可达的数字地址（例如 `192.0.2.1`），确认在期限内以“UDP 不通”失败，之后立即用正确地址成功。
   - `setSurface` 切换一次，画面恢复。
   - 输入：只允许 `mouseMove` 小幅移动（例如 +5 / −5 px）和单独按下抬起 Shift。**不要向主机输入任何文字或快捷键**，主机是用户正在使用的真实桌面。
4. 不破坏现有模块：`./gradlew :rdp:assembleDebug :app:assembleDebug` 通过（`:app` 不改，只确认构建）。

## 交付

- 代码、脚本、补丁、测试；`docs/remote-screen/P6B_NOTES.md`：实现说明、实测数据、与 API 契约的差异、遗留问题。
- 在你的 worktree 分支提交，不要提交到 `feature/im-4.0`。

## 约束

- 不修改 `app/`（包括 `app/src/main/java/.../ui/`）。
- 不 ssh 到任何机器，不修改 CachyOS 上的任何配置或服务；需要转发、主机日志或 PIN 时找 Claude。
- 只用 `emulator-5560`（`emulator-5554` 属于别的会话）。
- 不读取或复制签名配置、`local.properties`（worktree 里只有 `sdk.dir`）、任何非本任务生成的私钥。
