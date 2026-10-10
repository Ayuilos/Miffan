# 调研：用 Sunshine + Moonlight 统一替代现有远程屏幕实现？（Codex 调研任务）

## 背景

Miffan 让用户在手机上查看并操作自己的电脑桌面，伙伴（AI，经远端 cua-driver）也操作同一块真实桌面。现状按平台拼了三条路：

| 平台 | 现在的做法 | 问题 |
| --- | --- | --- |
| macOS | 系统屏幕共享（VNC，ZRLE 无损） | Retina 视频类画面每帧 5–7 MB，约 2 fps；要有损编码只能另装第三方采集程序 |
| Linux wlroots/niri、X11 | 远端脚本启动 wayvnc / x11vnc（Tight/JPEG） | 可用，但仍是 VNC |
| GNOME | gnome-remote-desktop（RDP，NVENC，AVC420） | 真机已很流畅（用户评 95 分） |
| KDE | krdp（RDP）。KPipeWire 6.7.5 只有 libx264/openh264/VA-API，NVIDIA 上只能 CPU 软编 | 用户评 70 分 |

用户的判断：缺一个在所有环境下“一招鲜”的方案。最早讨论时提过 Sunshine + Moonlight，当时放弃的原因是“集成方式不优雅，需要从 Moonlight 源码里拆模块”。请先核实这个前提是否成立（例如 moonlight-common-c 是不是本来就是一个独立的库）。

先读这些了解现状与约束：`docs/REMOTE_SCREEN_PLAN.md`、`docs/remote-screen/P5_RDP_PLAN.md`、`P5A_NOTES.md`、`P5B_NOTES.md`、`RDP_LATENCY_NOTES.md`、`MAC_PERF_NOTES.md`。

## 已知事实

- Miffan 是 Android（Kotlin/Compose）App，许可证 **AGPLv3**。已有原生模块 `rdp/`（FreeRDP 3.32.1 静态链接 + JNI，MediaCodec 硬解）。
- 用户的机器：CachyOS（NVIDIA RTX 3060，NVENC 可用；GNOME / KDE Plasma / niri 三种会话；**Sunshine 2026.1008 已安装且作为用户服务在运行**）；Mac（macOS 26.5，Apple Silicon）。手机：OPPO Find X6 Pro（骁龙 8 Gen 2，`c2.qti.avc.decoder`）。
- 现在所有连接都只经 SSH 隧道（JSch direct-tcpip / streamlocal），手机上不监听端口，远端服务尽量只监听本机；用户与 AI 共用一块真实桌面，有控制权仲裁；轻松模式需要“一步接入”的引导。

## 要回答的问题（逐条给出结论和出处）

1. **覆盖面**：Sunshine（及 Apollo 等活跃分支）在 macOS、Linux（GNOME Wayland、KDE Wayland、wlroots/niri、X11）、各厂商 GPU（NVIDIA NVENC、AMD/Intel VA-API、Apple VideoToolbox）上的采集方式、硬件编码支持和成熟度。能否抓取**已登录的真实桌面**（不是虚拟显示器或游戏），多显示器、HiDPI/缩放、锁屏时的行为。macOS 支持到什么程度。
2. **集成方式**：moonlight-common-c 的边界、依赖（ENet、OpenSSL、Reed-Solomon 等）、API（连接、解码回调、输入）。Android 上如何像 moonlight-android 那样接 MediaCodec（最好解码直出 Surface，零拷贝）。与我们的 `rdp/` 模块、`RemoteScreenFrameSink`、`RemoteScreenScaffold` 屏幕页如何拼接。估算工作量。
3. **许可证**：moonlight-common-c、moonlight-android、Sunshine 及依赖与 AGPLv3 是否兼容，分发时有什么义务。
4. **传输与安全（关键约束）**：GameStream 协议用哪些 TCP/UDP 端口；视频、音频、控制流走 UDP（RTP/ENet），**无法经 SSH direct-tcpip 隧道**。逐项评估可行替代：只在 Tailscale/WireGuard 内网用、Sunshine 只绑定 Tailscale 或本机地址、UDP over SSH 的可行性、协议自带加密覆盖哪些流。说明这会怎样改变我们“只经 SSH”的安全模型，以及在没有 Tailscale 的用户那里怎么办。
5. **配对与自动化**：PIN 配对、证书，是否能由我们的远端脚本经 SSH 无人值守完成（Sunshine 的 Web API、配置文件、凭据存储），首次在 Mac 上需要哪些系统权限（屏幕录制、辅助功能、输入）。
6. **输入与交互**：鼠标（绝对/相对）、触控板模式、滚轮、键盘（扫描码 vs Unicode，中文/IME 文本输入）、剪贴板（上游是否支持，Apollo 是否支持）、远端光标显示、触摸。能否保留我们现有的手势模型。
7. **与 AI 共用桌面**：Sunshine 采集与 cua-driver 并行时有无冲突；输入注入方式（Linux uinput、macOS）对伙伴操作和控制权仲裁的影响；Sunshine 是否会独占或改动显示配置。
8. **性能**：典型延迟、码率、分辨率/帧率自适应；H.265 优先、回退 H.264（用户之前的偏好）、AV1；移动网络下的表现。
9. **运维与风险**：上游活跃度与发布节奏、协议兼容性（GameStream 本是 NVIDIA 私有协议的逆向实现）、Sunshine 安装/更新方式（Arch/AUR/CachyOS 仓库、Homebrew、macOS 签名）、与系统已装的 Sunshine 共存（用户已在用）。
10. **替代还是并存**：是否应全面替代 VNC/RDP，还是作为“高性能模式”与现有实现并存，或只在部分平台使用。对照现有实现列出得失。

## 交付

- 写 `docs/remote-screen/research/SUNSHINE_MOONLIGHT.md`（中文），结构：结论与建议（先写）、逐题回答（附出处链接）、风险清单、如果采纳的分阶段计划（含一个最小可行验证 spike：例如手机经 Tailscale 连用户 CachyOS 上已运行的 Sunshine，测延迟与交互）、需要用户决定的问题。
- 只做调研：不改代码，不安装任何东西，不连接用户的任何机器。可以下载/阅读公开源码与文档。
- 在 `research/sunshine-moonlight` 分支上提交这份文档；不要合并、rebase 或推送其他分支。
- 区分“已核实（有出处）”和“推断”；拿不准的写清楚。
