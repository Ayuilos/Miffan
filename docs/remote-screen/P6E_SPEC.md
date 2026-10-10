# P6e：屏幕页改进的数据层（画面档位、本次不用高性能、地址与流量、声音、CI）

背景：P6c 已把 Sunshine 高性能模式接进 App（分支 `claude/p6c-ui`，见 `P6C_SPEC.md`、`P6C_NOTES.md`）。用户审阅屏幕页后决定一起做：统一的画面档位、移动网络省流、连接状态可见、菜单里“本次用普通连接”、全屏横屏、声音。**本任务（Codex）只做数据层、`:stream` 库与 CI；UI、`RemoteScreenVM.kt`、`app/src/main/java/me/ayuilos/miffan/ui/` 与字符串由 Claude 负责。** 契约有疑问先在 pane 里问 Claude。

## 接口契约

`app/.../data/repository/`：

```kotlin
/** 用户选择的画面档位；各协议自行映射，RDP 由服务器决定、忽略它。 */
enum class RemoteScreenQuality { SAVER, BALANCED, BEST }

/** 打开屏幕时对高性能模式的要求。 */
data class RemoteStreamRequest(
    val quality: RemoteScreenQuality = RemoteScreenQuality.BALANCED,
    /** 用户在菜单里选了“这次用普通连接”：不尝试 Sunshine，也不产生 streamFallback。 */
    val skip: Boolean = false,
)

// RemoteScreenRepository.open 增加参数（默认值保持现有行为）：
suspend fun open(workspaceId, sink, jpeg, options = RemoteScreenOptions(), stream: RemoteStreamRequest = RemoteStreamRequest()): RemoteScreenConnection

class RemoteScreenConnection {
    …
    /** 本次是否请求了高性能模式（主机开关打开且未 skip）。UI 据此决定菜单里显示哪个切换项。 */
    val streamRequested: Boolean
    /** 正在使用的 UDP 数字地址（STREAM 时非空）。 */
    val streamAddress: String?
}

/** STREAM 会话的声音；其他协议为 null。默认关闭。 */
interface RemoteAudioControl {
    val enabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)
}
interface RemoteDesktopSession { …; val audio: RemoteAudioControl? get() = null }
```

### 高性能模式档位（`quality` → `StreamConfig`）

| 档位 | 分辨率 | fps | 码率 |
| --- | --- | --- | --- |
| SAVER | 1280×720 | 30 | 4 000 kbps |
| BALANCED | 1920×1080 | 60 | 10 000 kbps |
| BEST | 2560×1440 | 60 | 20 000 kbps |

若能从 helper 得知主机显示器分辨率，BEST 不超过主机分辨率（按比例取不大于主机的那一档尺寸）；拿不到就按表。换档由 UI 通过重新 `open` 实现，数据层不需要运行中切换。

### 流量

- `:stream` 的 `StreamSession` 公开累计接收字节（视频 + 音频，至少统计应用层收到的 UDP 载荷；在 NOTES 写明口径）。`StreamDesktopSession.bytesReceived` 返回它（当前恒为 0，屏幕页顶部显示的流量因此不对）。

### 声音（`:stream` + 适配层）

- 解码 Sunshine 的 Opus（立体声，按 RTSP 协商的配置）：优先 `MediaCodec` 的 `audio/opus` 解码器，输出到低延迟 `AudioTrack`（`USAGE_MEDIA`，`PERFORMANCE_MODE_LOW_LATENCY`，缓冲尽量小，积压超过约 100 ms 时丢弃旧数据，不让声音越来越滞后）。
- 默认关闭：关闭时仍接收、解密并丢弃（与现在一样），不创建 AudioTrack。`setEnabled(true)` 时申请音频焦点（`AUDIOFOCUS_GAIN`），失去焦点时静音并把 `enabled` 置为 false；关闭、会话结束时释放焦点与 AudioTrack。
- 不录制、不发送手机麦克风。

### CI（现在主分支上就是坏的）

`:stream` 已在 `settings.gradle.kts` 中，但 `.github/workflows/ci.yml`、`daily-build.yml`、`release.yml` 只获取并构建了 RDP 的原生依赖，没有获取 `.deps/p6a` 的 common-c / ENet / nanors 源码包，Gradle 的 `:stream` CMake 准备步骤会失败。请参照 RDP 的步骤，在三个工作流里加入 `stream/scripts/fetch-sources.sh`（下载 + SHA-256 校验）以及需要的缓存；`:stream` 依赖 RDP 构建出的 OpenSSL 静态库，注意顺序。无法联网验证时，在 NOTES 写明并给出本地模拟验证方式。

## 验收

1. JVM 单元测试：档位映射（含主机分辨率封顶）、`skip` 时不调用 Sunshine 也不产生 fallback、`streamRequested` / `streamAddress` 取值。
2. `./gradlew :stream:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` 通过。
3. 模拟器（只用 `emulator-5560`）上用 `:stream` 的验收脚本实测：声音开启后 AudioTrack 正常播放（记录解码器名称与积压丢弃次数），关闭后不再输出；`bytesReceived` 随串流增长。需要转发或主机日志时在 pane 里找 Claude；不要 ssh，不要修改主机配置。测试时不要向主机输入文字或快捷键。
4. `docs/remote-screen/P6E_NOTES.md`：实现说明、口径、CI 改动与验证方式、遗留问题。

## 约束

- worktree 分支提交，不提交到 `feature/im-4.0`。
- 不修改 UI、`RemoteScreenVM.kt`、字符串资源。
- 不读取或复制签名配置、`local.properties`（worktree 里只有 `sdk.dir`）、任何私钥。
