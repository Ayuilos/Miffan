# P6 Sunshine 高性能模式计划

## 目标

在现有 VNC / RDP 之外增加一种“高性能模式”：电脑端用 Sunshine 采集和硬件编码，手机端在 App 内用 moonlight-common-c 接收 H.265 / H.264 视频并硬件解码到 Surface。屏幕页、控制权、伙伴操作（cua-driver）都不变，用户和伙伴看到的仍是同一块真实桌面。

依据：`research/SUNSHINE_MOONLIGHT.md`（调研）与用户实测——官方 Moonlight 在 niri、KDE 上延迟明显好于现有方案，Mac 上的体验也很好；GNOME 上 Sunshine 曾因缺少托盘支持而退出，装上 AppIndicator 扩展后正常。

## 已确定的决策（2026-10-10）

- **安全模型**：SSH 负责管理、配对、伙伴操作和证书核验；画面与用户输入走 Sunshine 自己的 UDP 通道。用户已接受。
- **能否使用高性能模式，看 UDP 能否到达，不看有没有 VPN**。局域网、Tailscale / WireGuard、有公网 IP 的云服务器都可以；UDP 到不了时才退回 VNC / RDP。
- **TCP 一律走 SSH**：配对、HTTPS 管理（47984 / 47989）、RTSP 建流（48010）都经已验证的 SSH 会话转发，这几个端口不需要对外开放。只有媒体 UDP（47998–48000）直连。
- **强制加密**：客户端 `encryptionFlags=ENCFLG_ALL`，主机 `lan_encryption_mode=2`、`wan_encryption_mode=2`，不满足就拒绝连接（上游 `ENCFLG_ALL` 不会拒绝降级，由 common-c 补丁 `requireEncryptedStreams` 负责）。会话密钥由手机生成、经 SSH + HTTPS 的 launch 交给主机，公网上看不到。视频和控制 / 输入是 AES-GCM，音频是无认证标签的 AES-CBC，这是协议现状。修改主机配置前要向用户展示具体变化并取得同意。
- **与 VNC / RDP 并存**，不替换。每台主机可选高性能模式；默认值在 P6 测试矩阵完成后按平台决定。
- **编码**：优先 HEVC SDR 8-bit 4:2:0，不支持时回退 H.264；首版不做 AV1、HDR、4:4:4。
- **中文与剪贴板**继续走现有 SSH 粘贴桥（`miffan.sh clip` + Ctrl/Cmd+V），不依赖 Sunshine 的 UTF-8 键入。
- **同一时间只有一条串流**（moonlight-common-c 有全局状态），切换主机先关闭上一条。

## 传输设计

```text
手机 ── SSH（固定主机 key）
  │     ├── 管理 / 探测 / 配对 / 证书核验（miffan.sh）
  │     ├── 伙伴操作（cua-driver）
  │     └── direct-tcpip → 127.0.0.1:47984 / 47989 / 48010（HTTPS、配对、RTSP）
  └── UDP 直连 → <选中的地址>:47998–48000（视频、控制与输入 AES-GCM；音频 AES-CBC）
```

TCP 走隧道时，客户端看到的主机地址是本地转发地址，主机看到的客户端地址是 127.0.0.1；UDP 则是手机与主机的真实地址。Sunshine 用 launch 时生成的 ping payload 和 connect data 匹配 UDP 会话，不比较 IP，因此“地址分离”可行：P6a 已在 Tailscale 上实测通过（`P6A_NOTES.md` 第 6 节）。

## 路径选择

1. 候选地址按顺序：主机在同一局域网的地址 → VPN 内网地址（Tailscale `100.64/10` 等）→ SSH 连接用的地址。
2. 依次尝试建流；在限定时间内收不到视频（common-c 的 `ML_ERROR_NO_VIDEO_TRAFFIC` 等）就换下一个。
3. 全部失败时退回该主机的 VNC / RDP，并在页面说明原因（例如“云服务器的安全组没有放行 UDP 47998–48000”）。
4. 按“主机 + 当前网络”缓存上次成功的地址，下次直接先试它。
5. UDP 全不通时 common-c 在控制流建立阶段约 8–10 s 失败；候选尝试应设更短的总期限并用 `LiInterruptConnection` 取消，换下一个候选前等上一条完全停止。
6. 局域网候选需要 Android 的本地网络权限（新版 Android 开始限制访问局域网地址），未授权时跳过局域网候选。

## 云服务器

- 云厂商的安全组 Miffan 改不了，只能引导用户放行 UDP 47998–48000；TCP 端口不用放行。主机自身防火墙（ufw / firewalld）的修改同样需要用户同意。
- 多数云服务器没有 GPU，Sunshine 只能软件编码，体验会比 NVENC 差，但应仍好于 VNC。页面要如实显示编码器。
- 无显示器的服务器需要虚拟显示器，首版不做，作为开放问题。

## 安全

- 每台手机生成自己的 Sunshine 客户端证书，私钥存 Android Keystore，不与已安装的 Moonlight 共用，可单独撤销。
- 主机证书的 SHA-256 经 SSH 读取并固定；握手时比对，不一致就拒绝，沿用 RDP 的“信任新证书”流程。
- 不调用 unpair-all，不覆盖 `apps.json`，不重启或踢掉用户已有的串流；注销电脑时只撤销 Miffan 自己的客户端。
- 日志不记录 PIN、私钥、管理密码和 launch 密钥。

## 客户端架构

- **新模块 `stream`**：moonlight-common-c（GPLv3，与 Miffan 的 AGPLv3 兼容）静态编译 + JNI，参照 `rdp/` 的构建脚本与预编译方式。HTTP / 配对层用 Kotlin 实现，TCP 连接经 SSH 转发。
- **解码直接到 Surface**：MediaCodec 低延迟配置（`KEY_LOW_LATENCY` 与 QTI 厂商键，与 RDP 相同的检测方式），不经过 Bitmap。屏幕页的 `RemoteScreenScaffold` 增加 Surface 渲染实现，缩放、平移和点击坐标使用同一变换。
- **输入**：绝对 / 相对鼠标、按键、滚轮映射到 `LiSend*`；键盘使用 Win32 VK 语义。光标由视频内嵌，不再叠画本地箭头。切换控制者、断开、离开页面时释放所有按下的键和按钮。
- **统计浮层**：沿用长按连接行打开的浮层，显示编码、分辨率、收帧 / 渲染 fps、解码耗时、RTT、码率、丢包和所用地址。

## 阶段

| 阶段 | 内容 | 交付 |
| --- | --- | --- |
| P6a 验证（已完成 2026-10-10） | ① 地址分离：TCP 经 SSH、UDP 直连，Sunshine 能否认出同一会话，common-c 需要怎样的补丁。② 强制加密下视频、音频、输入都加密，降级会被拒绝。先读源码，再在用户授权后对 CachyOS 上的 Sunshine 实测 | 结论文档 + 补丁草案；不可行时回到“TCP 也直连”的方案并重新评估安全 |
| P6b 原生库 | `stream` 模块：common-c 构建、JNI、HTTP / 配对、Surface 解码、输入；模拟器经 SSH 连测试机 | 模拟器上能看、能点、能打字 |
| P6c 接入 App | 主机配置增加高性能模式；路径选择与回退；屏幕页 Surface 渲染；统计浮层；中文与剪贴板桥 | 真机连 KDE / niri / GNOME，延迟与官方 Moonlight 相当 |
| P6d 自动配对与引导 | 经 SSH 探测 Sunshine、核对证书、定向批准 Miffan 的配对请求；没有管理凭据时提供一次手动 PIN；加密配置变更的确认界面；云服务器放行端口的引导 | 轻松模式下一步接入 |
| P6e Mac | macOS 上的 Sunshine：TCC 授权引导、Retina 坐标、Command 键、锁屏恢复 | Mac 高性能模式 |

分工：非 UI 部分（原生库、JNI、配对、helper、验证）交给 Codex，在独立 worktree 中完成；屏幕页、设置、引导等 UI 由 Claude 负责。

## 开放问题

- 地址分离若不可行：TCP 也直连会让 HTTPS / RTSP 端口暴露在可达网络上，需要重新评估。
- 没有显示器的云服务器是否支持（虚拟显示器）。
- 已有 Sunshine 的管理密码无法从主机上恢复，自动配对需要用户提供一次，或走手动 PIN。
- 音频：首版是否带声音。
