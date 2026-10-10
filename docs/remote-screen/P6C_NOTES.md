# P6c 数据层与远端脚本

日期：2026-10-10。分支：`codex/p6c-data`。依据 [P6C_SPEC.md](P6C_SPEC.md)。未修改 UI、`RemoteScreenVM.kt` 或字符串资源；未 SSH 到远端、未执行远端配置修改。

## 实现与接口

- 按规格增加 `STREAM`、`RemoteSurfaceTarget` / `RemoteVideoSize`、`streamStats`、主机配置字段、fallback、证书变化异常、状态／配对／显式强制加密方法。按 Claude 补充契约提供独立 `setStreamEnabled(hostId, enabled): Boolean`；`updateConfig` 不增加 streamEnabled 参数，编辑 VNC/RDP 设置不会覆盖独立开关。切换开关成功时关闭该主机现有会话；不存在的主机返回 false。app 以 `implementation(project(":stream"))` 依赖串流模块。
- `open()` 在 Linux 且开关开启时优先 Sunshine。安装／运行／强制加密／配对预检失败保留具体 fallback，再执行原有 VNC/RDP 选择逻辑；证书变化始终抛 `RemoteStreamCertificateChangedException`，不会回退或覆盖 pin。
- 配对 PIN 用 `SecureRandom` 生成，5 分钟期限；身份为每台安装一份，设备名 `Miffan (<Build.MODEL>)`。由 `:stream` 验证签名与 challenge 后得到 DER SHA-256，并以 SSH `connectionRevision` + 原 pin 条件更新 Room，防止覆盖并发信任决定。`await()` 或 `close()` 释放租约，取消会关闭配对通道；不 cancel、不 unpair。
- 身份使用独立 Android Keystore AES-GCM key、AAD 和 `AtomicFile` 存于 `noBackupFilesDir/remote-stream/identity.bin`，原子写入且恢复旧版本 AtomicFile 的备份。损坏或无法解密时失败，不静默生成替代身份。身份内容不进入日志／Room。
- TCP（HTTP/HTTPS/RTSP）全部经 `openLoopbackStream` 到主机 `127.0.0.1`；UDP 使用数字候选。`:stream` 强制加密，无关闭入口。SSH 通道新增可选连接期限，默认保持旧值；STREAM 使用 3 秒，取消现有 workspace operation 时能断开正在建立的通道。
- `open()` 用临时 `ImageReader(PRIVATE)` Surface 消费解码输出，确认首帧与 Streaming 后交接，释放临时 Surface 并设为 null。`start(scope)` 将已预检的 STREAM 绑定到调用方生命周期，仍只允许调用一次；VNC/RDP 保持延迟启动。关闭／取消会清理独立会话 scope，返回连接前的 Room 更新失败也释放租约。
- Surface 为 null 或 paused 时继续接收，暂停渲染；恢复调用库的 `setSurface` 重建 codec 并请求 IDR。UI 拥有传入 Surface，数据层不释放它。指针按协商视频尺寸定位，RFB 鼠标／滚轮只在边沿发事件；X11→VK 覆盖 RdpKeys 与当前屏幕键盘，并支持右修饰键 E0、F1–F24 和数字键盘。
- 串流开始后 `state` 发出 `Connected`，名称使用主机名（空白时使用应用名），宽高与 `videoSize` 同取已协商的 `StreamStats` 尺寸，scale 为 1，供 VM 建立指针坐标系。
- `typeText` / `sendClipboard` 使用有界串行队列经 SSH `miffan clip` 写入；只有写入成功才发 Ctrl+V，临时释放并恢复物理修饰键。中文不走 Sunshine UTF-8 输入。STREAM 的 `clipboard` 流为空，旧 `stats` 映射渲染 fps／编码，`rdpStats` 为 null。

## 路由、权限与期限

- 顺序：当前网络成功缓存 → 同子网 LAN → `100.64.0.0/10` VPN → SSH 地址解析出的数字 IP；规范化、去重后最多 **2** 个地址。缓存按 hostId、connectionRevision、网络键隔离；revision 改变后旧键不能命中，下次访问清除该主机旧条目。删除主机也清除缓存。
- 网络键来自 `ConnectivityManager` 的 Wi-Fi／以太网前缀与网关、蜂窝／VPN 类型及接口；不读取 SSID/BSSID、不需位置权限。切网期间成功的路由不会误写到此前的网络键。同网段且同网关的不同 Wi-Fi 可能共享键，缓存只是候选优先级，仍须真实握手成功。
- 已从本机 `platforms/android-37.0/android.jar` 用 `javap -constants android.Manifest\$permission` 核实 `ACCESS_LOCAL_NETWORK = android.permission.ACCESS_LOCAL_NETWORK`；SDK metadata 为 Android 17、API 37.0、revision 2。当前 targetSdk=37，manifest 已有该权限，新增的是获取网络信息所需的 `ACCESS_NETWORK_STATE`。
- [Android 官方局域网权限文档](https://developer.android.com/privacy-and-security/local-network-permission) 确认 target 37 在 Android 17 上须运行时授权。`RemoteStreamPermissions.localNetwork` 在系统 API ≥37 时返回该名称，旧系统返回 null。没有权限时过滤 LAN（包括缓存／SSH 数字 LAN 地址），仍尝试 Tailscale／非 LAN；全部未成功且确实跳过 LAN 时报 `LOCAL_NETWORK_PERMISSION`。权限申请由 UI 完成。
- helper 安装／版本刷新、已有 SSH 租约建立及原有平台探测是共同前置步骤；从 `sunshine probe` 开始 Sunshine 尝试预算 **20 秒**，包含 serverinfo、apps、DNS（最多 3 秒）、两个候选与 `StreamHost.retryAfterMillis` 的退避。每个 native 建流使用库默认 4 秒，HEVC 优先、H.264 回退，固定 1920×1080@60／15 Mbps。
- 两候选复用同一个 StreamHost；默认 Sunshine pending 的 11 秒窗口由库管理，并在同主机后续 open 中保留剩余冷却时间。失败候选关闭后在后台最多等待 **5 秒** 确认停止，确认之前不开始下一候选。取消清理可使用户观察到约 25 秒，不能把 20 秒理解为包括所有 SSH 前置步骤的绝对墙钟上限。无可达路由的 detail 列出已开始尝试的地址；主机拒绝／解码不支持／加密错误直接保留对应原因，不浪费另一路由。

## helper v10

- `sunshine probe` 只查包／user unit／配置／公开证书／接口／UDP socket，不运行 Sunshine 二进制，不输出密码、PIN、私钥。
- 版本依次来自 `pacman -Q sunshine`、`dpkg-query -W -f=${Version} sunshine`、`rpm -q --qf %{VERSION} sunshine`、`flatpak info dev.lizardbyte.app.Sunshine` 的 Version 行；均不可用返回 null。禁止调用 `sunshine --version`：协调方实测它会加载配置并轮转运行实例的日志。
- 活动串流依据 `ss -H -uanp` 中 Sunshine 占用 video/control/audio UDP 端口。默认 47998–48000，配置自定义 base port 时按 +9/+10/+11 计算；进程信息不可见但端口被占用时保守视为活动。源码依据 `.deps/p6a/sunshine/src/stream.cpp` 的 `start_broadcast` / `end_broadcast`：首个会话引用建立 socket，最后引用释放 socket；pending 会话也计为活动。运行中无法检查 socket 时保守为 busy。
- user unit 从系统查询，配置位置参考运行 PID 的 cmdline 或停用 unit 的 ExecStart，不求值命令行。显式 `enforce-encryption` 要求可确认的普通配置文件、无活动流且无加密命令行 override；备份带时间戳，原子替换两项，保留其他配置。替换前检查配置是否并发改变；替换前与重启前再检查 socket。新串流阻止重启时返回失败说明并保留备份。无法确认的 wrapper／Flatpak unit 不猜测要修改的配置。
- 不修改防火墙、不安装软件。socket 检查与 restart 之间仍有外部客户端并发进入的极小窗口，Sunshine 没有提供原子“无会话时重启”接口。

## 验证

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :workspace:testDebugUnitTest
bash -n app/src/main/assets/remote/miffan.sh
python3 tools/remote-screen/test_sunshine_helper.py
adb -s emulator-5560 shell cmd package compile -m speed -f me.ayuilos.miffan.app.debug
adb -s emulator-5560 shell am instrument -w -e disableAnalytics true \
  -e class me.ayuilos.miffan.data.db.migrations.Migration_32_33_Test,me.ayuilos.miffan.data.db.migrations.Migration_31_32_Test \
  me.ayuilos.miffan.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

- Gradle 构建通过；app JVM **679 项全部通过**，其中新增 11 项覆盖键盘／修饰键、鼠标边沿、路由／权限／缓存键、helper JSON、fallback、证书异常。workspace JVM 179 项、0 失败，6 项原有环境条件跳过。
- `bash -n` 通过；本机没有 shellcheck。离线 helper **6 项通过**，覆盖只读 probe、四类包版本 fallback、拒绝运行 Sunshine 二进制、活动流／重启前竞态、备份与保留其他设置。
- Room 使用自动迁移 32→33，导出 `33.json`；新增迁移测试检查旧 VNC/RDP 设置、默认 stream disabled / unpinned、配对 CAS、stale SSH 编辑、独立开关只修改自身字段以及 VNC/RDP 编辑保留开关。在 `emulator-5560`（Android 15、arm64、16 KiB 页面）上，32→33 与 31→32 两组共 **5 项全部通过**。第一次 instrumentation 因冷启动／资源压力发生启动 ANR，预编译目标 Debug 包后重跑正常；没有数据库断言失败。
- 协调方以用户账号在 CachyOS 运行只读 probe，exit=0：安装／运行 true，lan/wan 均 2，active_stream=false，候选 `192.168.31.61`、`100.64.0.5`；公开证书指纹与已有 P6b 记录一致。首轮版本读取触发日志轮转的问题已移除，新的包查询分支离线验证通过，按协调方要求未要求再次远端运行。

## 验收边界

真机 UI、首次配对／证书更换交互、LAN 权限弹窗、ImageReader 预检后 TextureView 交接、切网与长时间性能由 Claude／用户端到端验收。本任务没有完成这些真机验证。Sunshine 非默认端口仍受 `:stream` 固定发现端口限制；无法确定配置的 launcher 只能报告加密修改失败。旧兼容接口 `bytesReceived` 为 0（库未公开媒体累计字节）；实际码率与网络／解码统计使用 `streamStats`。
