# P6c：把 Sunshine 高性能模式接入 App（数据层与远端脚本）

背景：`P6_SUNSHINE_PLAN.md`、`P6A_NOTES.md`、`P6B_NOTES.md`。`:stream` 库已合并（`me.rerere.stream`）。

分工：**本任务（Codex）负责数据层、远端脚本、会话适配与测试；不碰 UI**。`RemoteScreenVM.kt` 与 `app/src/main/java/me/ayuilos/miffan/ui/` 下的一切由 Claude 负责。下文“接口契约”是双方的边界，Claude 会按它并行写 UI；如需改动契约，先在 pane 里问 Claude。

## 产品规则（已定）

- 主机设置里新增“高性能模式”开关（默认关）。打开后每次打开屏幕先尝试 Sunshine；不可用时自动退回现有的 VNC / RDP 选择逻辑，并带出原因。
- 证书不匹配**不回退**：抛出专门的异常，由 UI 走“信任新证书”流程（与 RDP 相同）。
- TCP 全部经 SSH（`openLoopbackStream` 到主机 `127.0.0.1`），UDP 直连候选地址；强制加密（库默认，不提供关闭）。
- 不静默修改用户的 Sunshine：开启强制加密必须由用户在 UI 中确认后调用专门的方法。永不 cancel / unpair。
- 中文与剪贴板走现有 SSH 粘贴桥（`miffan.sh clip` + 粘贴快捷键），不用 Sunshine 的 UTF-8 键入。

## 接口契约（Claude 的 UI 依赖这些名字和语义）

`app/src/main/java/me/ayuilos/miffan/data/repository/RemoteDesktopSession.kt`：

```kotlin
enum class RemoteDesktopProtocol { VNC, RDP, STREAM }

data class RemoteVideoSize(val width: Int, val height: Int)

/** Frames go to a Surface instead of the frame sink. */
interface RemoteSurfaceTarget {
    val videoSize: StateFlow<RemoteVideoSize?>   // 协商后的视频尺寸；指针坐标以它为参照
    fun setSurface(surface: Surface?)            // UI 的 TextureView 可用 / 销毁时调用；null 不断流
}

interface RemoteDesktopSession {
    // ……现有成员不变……
    val surface: RemoteSurfaceTarget? get() = null          // 仅 STREAM 非空
    val streamStats: StateFlow<StreamStats>? get() = null   // 仅 STREAM 非空
}
```

`StreamDesktopSession`（新）实现上面的接口，语义：

- `pointer(x, y, buttons)`：x / y 是视频坐标（以 `videoSize` 为参照），buttons 是现有 RFB 掩码（1 左、2 中、4 右、8 / 16 滚轮上 / 下、32 / 64 滚轮左 / 右）。转换为 `mousePosition` + 按键边沿的 `mouseButton`；滚轮位在按下边沿发一格（±120）。
- `key(keysym, down)`：X11 keysym（现有 VNC / RDP 用法）映射为 Windows VK，并跟踪修饰键状态；映射表覆盖现有屏幕键盘与硬件键盘会发出的全部键（参考 `rdp` 模块的 `RdpKeys.kt` 与 VM 中的调用）。
- `typeText(text)`：经 SSH 粘贴桥写入远端剪贴板后发送粘贴快捷键（Linux Ctrl+V；macOS 以后再说）。返回是否已排队。
- `sendClipboard(text)`：经 SSH 粘贴桥写入远端剪贴板。`clipboard` 流为空。
- `setPaused(true)`：停止渲染（相当于 Surface 为 null），不断流；`setPaused(false)` 恢复并请求 IDR。`setMaxFps` 忽略。
- `stats`（兼容旧接口）：映射 fps 与编码名；`rdpStats = null`；`certificateSha256` 发布 Sunshine 主机证书指纹。
- `state` 映射为 `RemoteScreenState`；失败时 `Closed(error)` 的 error 用下面的异常类型。

`RemoteScreenRepository` / `RemoteScreenConnection`：

```kotlin
data class RemoteHostScreenConfig(…, val streamEnabled: Boolean = false, val streamCertificateSha256: String? = null)
suspend fun updateConfig(…, streamEnabled: Boolean)        // 在现有参数基础上增加
suspend fun pinStreamCertificate(hostId: String, sha256: String): Boolean

class RemoteScreenConnection { …; val streamFallback: RemoteStreamFallback? }   // 请求了高性能模式但没用上时非空
data class RemoteStreamFallback(val reason: RemoteStreamFallbackReason, val detail: String? = null)
enum class RemoteStreamFallbackReason {
    SUNSHINE_MISSING, SUNSHINE_NOT_RUNNING, NOT_PAIRED, ENCRYPTION_NOT_ENFORCED,
    UDP_UNREACHABLE, HOST_REJECTED, DECODER_UNSUPPORTED, LOCAL_NETWORK_PERMISSION, OTHER,
}
class RemoteStreamCertificateChangedException(val expectedSha256: String, val actualSha256: String?) :
    RemoteScreenUnavailableException(RemoteScreenProblem.STREAM_CERTIFICATE_CHANGED, …)

// 主机设置页使用（通过该主机上任一远程工作区的 SSH，与 probeHost 一致；expectedRevision 语义相同）
suspend fun streamStatus(hostId: String, expectedRevision: String): RemoteStreamStatus
data class RemoteStreamStatus(
    val installed: Boolean, val version: String?, val running: Boolean,
    val encryptionEnforced: Boolean,      // lan 与 wan 均为 2
    val paired: Boolean,                  // 用本机身份查询 serverinfo 的 PairStatus
    val activeStream: Boolean,            // 主机当前是否有串流（影响能否重启 Sunshine）
    val candidates: List<String>,         // 将尝试的数字地址，按顺序
)
suspend fun startStreamPairing(hostId: String, expectedRevision: String): RemoteStreamPairing
interface RemoteStreamPairing : Closeable {
    val pin: String                       // 4 位，UI 显示给用户
    suspend fun await(): String           // 成功返回并已持久化主机证书指纹；超时 / 取消抛异常
}
suspend fun enforceStreamEncryption(hostId: String, expectedRevision: String): RemoteCommandOutcome
// 备份 sunshine.conf 后设置 lan_encryption_mode = 2、wan_encryption_mode = 2；有活动串流时不重启并返回失败说明
```

另：如 `compileSdk 37` 中存在访问局域网地址所需的运行时权限，提供 `RemoteStreamPermissions.localNetwork: String?`（权限名，或 null 表示不需要），并在没有该权限时跳过局域网候选、在 fallback 中报 `LOCAL_NETWORK_PERMISSION`。权限申请由 UI 负责。请在 NOTES 中写明查证结果（以 SDK 的 `android.jar` / 文档为准）。

## 数据与存储

- Room 迁移 32 → 33：`remote_hosts` 增加 `stream_enabled`（默认 0）、`stream_certificate_sha256`（可空）；导出 schema 33.json；更新迁移测试。
- 客户端身份每台手机一份（不按主机），用 Android Keystore 加密后存 `noBackupFilesDir`，方式参照 `RemoteHostCredentialStore`。设备名 `Miffan (<Build.MODEL>)`。
- 路由缓存：每台主机按“当前网络”记住上次成功的候选地址。网络标识由你从 `ConnectivityManager` 推导（例如 Wi-Fi 网关 + 前缀、蜂窝、是否有 VPN），不需要位置权限；存储方式自定。主机身份（`connectionRevision`）变化时清除。

## 远端脚本（`app/src/main/assets/remote/miffan.sh`，版本升到 10）

- `sunshine probe`：输出 JSON——是否安装、版本、user unit 是否运行、`sunshine.conf` 中 lan / wan 加密模式、主机证书 DER SHA-256（`credentials/cacert.pem`）、是否有活动串流（以 UDP 47998–48000 是否被 Sunshine 占用或其他可靠依据判断，写明依据）、候选地址（各网卡 IPv4：局域网私有地址、`100.64.0.0/10` 的 Tailscale 地址；排除 loopback、docker / bridge 接口）。不输出任何密码、PIN、密钥。
- `sunshine enforce-encryption`：备份（带时间戳）后写入两项设置；仅在无活动串流时 `systemctl --user restart` Sunshine 的 user unit（unit 名从系统中查找，不要写死）；输出结果 JSON。
- 不修改防火墙、不安装软件、不改其他 Sunshine 设置。

## 打开屏幕的流程（`RemoteScreenRepository.open`）

1. `streamEnabled` 且平台为 Linux 时：`sunshine probe` → 未安装 / 未运行 / 加密未强制 / 未配对（serverinfo）→ 记录对应 fallback，走原逻辑。
2. 候选顺序：该网络缓存的地址 → 局域网（手机与主机在同一子网且有权限）→ VPN（`100.64/10`）→ SSH 连接地址（解析为数字 IP；与前面重复则去重）。总尝试次数与总耗时要有上限（建议最多 2 个候选、总计约 20 s，含 `StreamHost.retryAfterMillis` 退避），NOTES 里说明取舍。
3. 全部失败 → fallback `UDP_UNREACHABLE`（detail 写尝试过的地址），走原逻辑。证书不匹配 → 抛 `RemoteStreamCertificateChangedException`，不回退。
4. 成功 → 写路由缓存；首个证书指纹在配对时已固定。
5. 串流参数：1920×1080@60，15 Mbps，HEVC 优先 H.264 回退（本阶段固定，不加设置）。
6. `setRemoteClipboard` 对 STREAM 会话走 SSH 粘贴桥（现有非 RDP 分支即可）。

## 验收

1. JVM 单元测试：keysym→VK 映射与修饰键跟踪、RFB 掩码→鼠标事件、候选排序与去重、fallback 判定、helper JSON 解析、路由缓存键。
2. Room 迁移测试通过；`./gradlew :app:testDebugUnitTest :app:assembleDebug` 通过（`:app` 原有测试不得回归）。
3. `bash -n` 与 `shellcheck`（如本机有）检查 `miffan.sh`；在 CachyOS 上执行 `sunshine probe` 需要 Claude 代劳（不要自己 ssh），把要跑的命令写给 Claude。
4. 端到端真机验收由 Claude 与用户完成（需要 UI），你不需要做。

## 交付

- 代码、迁移、脚本、测试；`docs/remote-screen/P6C_NOTES.md`（实现说明、契约差异、局域网权限查证、候选上限取舍、遗留问题）。
- 在 worktree 分支提交，不要提交到 `feature/im-4.0`。

## 约束

- 不修改 `app/src/main/java/me/ayuilos/miffan/ui/` 与 `RemoteScreenVM.kt`；字符串资源也不要加（UI 文案由 Claude 负责）。
- 不 ssh 到任何机器；不修改 CachyOS 上的配置。
- 只用 `emulator-5560`。
- 不读取或复制签名配置、`local.properties`（worktree 里只有 `sdk.dir`）、任何私钥。
