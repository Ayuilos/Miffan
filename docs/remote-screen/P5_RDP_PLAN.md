# P5 RDP 客户端计划

## 目标

让 GNOME 与 KDE 桌面（以及将来的 Windows）通过 RDP 接入现有屏幕页。用户和伙伴看到的仍是同一块真实桌面，入口、控制权、输入方式与 VNC 一致；页面只有一套（`RemoteScreenScaffold`）。

## 已确定的决策

- **实现**：使用 FreeRDP 3（Apache 2.0）编译为 Android 原生库，经 JNI 接入。不自己实现 RDP 协议栈（NLA/CredSSP、MCS、动态虚拟通道、图形管线、AVC444 都很大）。
- **传输**：手机上不监听任何端口。FreeRDP 的传输层通过 `freerdp_set_io_callbacks` 接到 Kotlin 的字节流，和 VNC 一样走已验证的 SSH 会话（direct-tcpip 或 streamlocal）。
- **视频解码**：H.264 使用 FreeRDP 自带的 Android MediaCodec 后端（`h264_mediacodec.c`，硬件解码），不打包 FFmpeg。
- **编码协商**：图形管线（RDPGFX）优先 AVC444，其次 AVC420，再退到 RemoteFX / Planar 等无损编码。
- **H.265**：本阶段不做。原因：
  - 微软的 HEVC 只用于 Windows 主机 + GPU 的 Azure Virtual Desktop / Windows 365，不在 FreeRDP、gnome-remote-desktop、krdp 中实现，GNOME/KDE 服务端不会发 H.265。
  - 解码接口按 MIME 类型设计（`video/avc`、`video/hevc`），将来接自有的 Mac 采集程序（VideoToolbox 可硬件编码 HEVC）时，按“先 H.265、不支持再回退 H.264”的顺序协商。
- **画面接口**：RDP 会话实现与 VNC 相同的抽象（帧回调、指针形状、输入、剪贴板），屏幕页和 VM 不区分协议。

## 已验证（2026-10-09，CachyOS 测试账号 `miffanrdp`，无头会话）

- KDE（KWin 6.7 + krdp 6.7.5）：`krdpserver --plasma --address 127.0.0.1` 可只监听本机、不弹门户授权；TLS 安全层登录成功并以 H.264 推流（服务端 libx264 软件编码）。用命令行 `-u/-p` 时 NLA 登录失败（“user not in SAM”），需要改用 krdp 的配置方式。
- GNOME 51（gnome-remote-desktop 51 无头模式）：NLA 登录成功，协商到 AVC444；服务端用 CUDA 硬件编码。RDP 与 VNC 都监听所有网卡。

## 阶段

| 阶段 | 内容 | 交付 |
| --- | --- | --- |
| P5a 原生库与验证 | FreeRDP + OpenSSL 的 Android 构建脚本；`rdp` 模块的 JNI 封装；IO 回调传输；帧、指针形状、键鼠输入；模拟器经隧道连 CachyOS 的 krdp 与 gnome-remote-desktop | 模拟器上能看、能点、能打字；记录 APK 体积、帧率、解码方式 |
| P5b 接入 App | 主机屏幕配置增加 RDP 端点；远端脚本探测 GNOME/KDE 并启动服务（krdp 只监听本机；gnome-remote-desktop 的凭据与证书）；屏幕页与 VM 的协议抽象；剪贴板；断线重连 | 专业模式与轻松模式都能接入 GNOME/KDE 主机 |
| P5c 真实桌面与伙伴 | 已登录的 GNOME/KDE 真实会话（非无头）；cua-driver 在 GNOME/KDE 上的 AT-SPI 操作；轻松模式接入引导 | GNOME/KDE 主机的完整体验 |

## 开放问题

- gnome-remote-desktop 不能只监听本机。可选：远端防火墙规则、随机端口 + 强随机凭据、或仅在需要时启动。P5b 决定。
- APK 体积：FreeRDP + OpenSSL 每个 ABI 预计数 MB，P5a 实测后决定是否接受，或改为按需下载。
- 原生库的构建方式（构建时从源码编译，还是脚本预编译后入库），影响 CI 与发布流程，P5a 给出实测建议。
