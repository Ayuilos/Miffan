<div align="center">
  <img src="docs/assets/branding/miffan-icon.svg" alt="Miffan 应用图标" width="120" />
  <h1>Miffan</h1>
  <p>像和朋友聊天一样用 AI 的 Android 应用：伙伴之间能分工协作，还能替你操作电脑。</p>

  <p>
    <a href="https://github.com/Ayuilos/Miffan/releases"><img alt="GitHub release" src="https://img.shields.io/github/v/release/Ayuilos/Miffan?display_name=tag&sort=semver" /></a>
    <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" />
    <a href="LICENSE"><img alt="License: AGPL-3.0" src="https://img.shields.io/badge/License-AGPL--3.0-blue" /></a>
  </p>

  <p><a href="README.md">English</a> · 简体中文 · <a href="README_ZH_TW.md">繁體中文</a></p>
</div>

<table>
  <tr>
    <td align="center" valign="top"><img src="docs/img/v4/zh/01-chats-light.webp" alt="伙伴消息列表，带未读数和正在输入状态" width="260" /></td>
    <td align="center" valign="top"><img src="docs/img/v4/zh/03-parallel-light.webp" alt="同时问三件事，每条回复都引用对应的问题" width="260" /></td>
    <td align="center" valign="top"><img src="docs/img/v4/zh/05-self-config-light.webp" alt="伙伴按你的要求调整偏好，并提供撤销" width="260" /></td>
  </tr>
</table>

用你已经在用的 AI 服务就行：OpenAI 兼容、Gemini 或 Claude 服务的 API Key，通过 Codex 登录的 ChatGPT 订阅、OpenRouter 账号，或者跑在你自己电脑上的 [Magpie](https://usemagpie.ai) 网关。Miffan 不内置模型，聊天记录和设置都保存在你的手机上。

## 像发消息一样聊天

每个 AI 伙伴就是一个持续的聊天。不用新建对话，也没有非调不可的参数。

- **一个伙伴，一段对话。** 和伙伴的聊天记录跨越多天也是一条时间线。Miffan 会在背后把不同话题分开，长聊天依然又快又切题。新话题从哪里开始会有分隔线标出，点一下就能改回接着上个话题聊。
- **同时问好几件事。** 不相关的问题会并行回答，每条回复都引用它回答的那条消息。
- **想快就快，想细就细。** 在伙伴资料页选它想多久再回，从秒回到深思熟虑。遇到难题，就在 + 菜单里选「这次认真想想」。
- **需要时再开能力。** 伙伴需要日历、剪贴板或使用时长时，会在聊天里请你打开，你同意后它接着做。
- **说一句话就能调整伙伴。** 比如“回答短一点”，伙伴就会调整自己的偏好。经你同意，伙伴还能改自己的名字、头像和设定。每次改动都关联到引起它的那条消息，保留在历史里，随时可以撤销。
- **看看 AI 记住了你什么。** 每条记忆都能看到来源，随时可以删除。
- **想要图就说。** 选好一个画图模型，所有伙伴都能帮你画图；说一句“把它改成……”，就在上一张图上接着改。
- **重要的话收起来。** 长按一条消息就能收藏。“发现”页汇集收藏的消息和最近的画作，不找伙伴也能直接画图、翻译。

## 伙伴之间协作

伙伴可以互相发消息，你也能把几个伙伴拉进同一个聊天。

- **群聊。** 主持的伙伴负责分工，被 @ 的伙伴同时回复，只有 @ 到你时才通知你。
- **随时 @ 伙伴。** 在聊天里输入 @，就能叫上其他伙伴。
- **按你的习惯整理。** 置顶伙伴、分组整理，头像上的状态灯一眼就能看出谁在工作（绿灯）、谁在等你批准（黄灯闪烁）。
- **彼此隔离。** 伙伴只能回顾自己参与过的聊天，两个伙伴第一次联系也要先经你同意。

## 让伙伴操作你的电脑

通过 SSH（包括 Tailscale）连接一台 Mac、Windows 或 Linux 电脑，伙伴就能在上面打开应用、输入文字、点击按钮，你在手机上看着它操作。没有屏幕的服务器或 NAS 也可以连，伙伴会通过命令来操作。

- **同一块屏幕。** 在手机上查看并操作电脑桌面，竖屏或全屏横屏都行，一碰屏幕就能立刻接管。
- **高性能模式。** 电脑上装有 Sunshine 时，画面由显卡编码，延迟更低、更流畅，还能听到电脑的声音。设置和配对都由 Miffan 替你完成。
- **由你做主。** 每台电脑都可以选择让伙伴先问你、自动操作，或者完全不碰。每次批准都记在权限记录里，删掉聊天也会保留。
- **定时任务。** 请伙伴“每天早上帮我做一件事”，电脑自己的定时器会按时运行，手机关机也不影响（Mac 和 Linux）。任务可以是一条命令，也可以调用电脑上装好的 AI 工具，比如 Claude Code、Codex CLI 或 Gemini CLI。打开 Miffan 时结果会同步回聊天，「我的电脑」里能看到每次运行的输出。
- **看看电脑好不好。** 状态页用一句话说明电脑是否就绪，发现问题就地修好，并提示更新操作小助手。
- **连接很简单。** 轻松模式会自动找到同一 Wi-Fi 和 Tailscale 上的电脑。用账号密码登录一次，Miffan 就会改用自己的密钥；Windows 电脑提供一键配置脚本。

<table>
  <tr>
    <td align="center" valign="top"><img src="docs/img/v4/zh/11-computer-screen-light.webp" alt="伙伴正在便签里写字，你在手机上看着" width="260" /></td>
    <td align="center" valign="top"><img src="docs/img/v4/zh/12-computer-approval-light.webp" alt="伙伴输入文字前先征求你的同意" width="260" /></td>
    <td align="center" valign="top"><img src="docs/img/v4/zh/13-computer-setup-light.webp" alt="引导流程中实时显示电脑桌面" width="260" /></td>
  </tr>
  <tr>
    <td align="center">看着它操作</td>
    <td align="center">每一步先问你</td>
    <td align="center">几步就能连上</td>
  </tr>
</table>

支持的桌面：macOS、Windows，以及 Linux 上的 GNOME、KDE（通过系统自带的远程桌面）、基于 wlroots 的 Wayland（如 niri、Sway、Hyprland）和 X11。

## 点一下就能批准

伙伴要做重要的事，比如执行命令、安排定时任务、添加日程或调用 MCP 工具时，会先发一张看得懂的卡片：一句话说明它想做什么，列出关键信息，原始调用点一下才展开。

- 几个普通请求可以一次全部允许；某类操作也可以设为始终允许，之后随时在权限记录里收回。
- 添加日程前可以先调整时间。
- 伙伴向你提问时，点选一个选项或者自己写回答都行。
- 打开任意一条已完成的改动，能看到它具体改了什么，并单独撤销或重做这一项。

## 需要时切换到专业模式

随时可以切换到专业模式，它和轻松模式共用同一份数据。

- 混用官方 API、兼容网关、自建服务和 Codex 订阅。
- 为每个助手单独配置提示词、模型参数、记忆、MCP 服务器、Skills 和联网搜索。
- 在手机上运行本地 Linux 工作空间，或让助手通过 SSH 在远程服务器上处理文件、使用终端。
- 语音输入与朗读、在任意应用中划词翻译，还能在同一网络里用浏览器聊天。

完整的服务商、搜索、语音和工具支持情况见[功能矩阵](docs/FEATURE_MATRIX.md)。

## 有表情的角色

<p align="center">
  <img src="docs/assets/branding/miffan-whale-girl.png" alt="蓝色大肥鱼从微笑的 Miffan 饭碗里探出身来" width="420" />
</p>

每个伙伴都有自己的样子：四种造型、六种配色的 Miffan 饭碗，蓝色大肥鱼，一张照片或一个表情，在“换个样子”页边看实时预览边挑，Miffan 还能选它的动作性格。它们的表情会跟着对话变化：等待时吃饭，AI 思考时转圈圈眼，回复到达时露出笑容，夜里还会打瞌睡。

## 开始使用

1. 从 [GitHub Releases](https://github.com/Ayuilos/Miffan/releases) 下载最新 APK。正式版适用于 Android 8.0 及以上的 `arm64-v8a` 设备。之后的更新在应用内就能下载安装。
2. 首次打开时，Miffan 会在聊天里带你连上 AI 服务（OpenRouter 一键登录或 API Key），选好模型和第一个伙伴；也可以从备份恢复。
3. 开始聊天。

[使用指南](https://miffan.ayuilos.me/docs)介绍了每项功能。遇到问题时，可以添加「反馈助手」伙伴：它会帮你排查，并把问题整理成反馈，由你提交。

数据可以备份到 WebDAV 或 S3 兼容存储，也可以从 RikkaHub、Chatbox 和 Cherry Studio 导入。提示词、附件和工具数据只会发给你配置的服务，详见 [PRIVACY.md](PRIVACY.md) 和 [SECURITY.md](SECURITY.md)。

## 项目历史

Miffan 最初是开源 Android LLM 客户端 [RikkaHub](https://github.com/rikkahub/rikkahub) 的一个 fork，后来逐渐发展成独立的应用。

| 时间 | 版本 | 变化 |
| --- | --- | --- |
| 2026 年 8 月 | 2.4.10-miffan.1 | 基于 RikkaHub 2.4.10 分出，更名为 Miffan，并使用独立的应用 ID，可与 RikkaHub 同时安装。新增通过 Codex 登录 ChatGPT 订阅。 |
| 2026 年 8 月 | 2.4.10-miffan.5 | 加入会动的 Miffan 饭碗角色。 |
| 2026 年 8 月 | 3.0.0 | 改用 Miffan 自己的版本号，不再跟随 RikkaHub 的版本。 |
| 2026 年 9 月 | 3.1 – 3.3 | 首次设置引导、凭据加密、持久保存的回复分支，以及蓝色大肥鱼。 |
| 2026 年 9 月 | 3.4 | 远程 SSH 工作空间。 |
| 2026 年 10 月 | 4.0 | 轻松模式，以及让伙伴操作你的电脑。 |
| 2026 年 10 月 | 4.1 | 远程屏幕的高性能模式，并支持 GNOME 和 KDE 桌面。 |
| 2026 年 10 月 | 4.2 | 伙伴私聊和群聊，支持 Windows 电脑和没有屏幕的服务器。 |
| 2026 年 10 月 | 4.3 | 画图、重新设计的电脑连接流程，以及看得懂的审批卡片。 |
| 2026 年 10 月 | 4.4 | 由电脑自己按时运行的定时任务，手机关机也不影响。 |
| 2026 年 10 月 | 4.5 | 轻松模式改版：伙伴、“我”和“发现”页，“换个样子”页，消息收藏，以及 Magpie 网关。 |
| 2026 年 10 月 | 4.6 | 和 Miffan 聊着完成的首次设置、可调的伙伴思考时长，以及电脑状态页。 |

Miffan 仍会选择性吸收 RikkaHub 的改进，每一项都经过审查，并在发布说明中单独列出（见[上游同步策略](docs/upstream-sync.md)）。项目依照许可证保留上游的版权与署名信息。Miffan 不是 RikkaHub 的官方版本。

## 构建与参与贡献

需要 JDK 17、带 NDK 28.2.13676358 和 CMake 3.22.1 的 Android SDK、Node.js 与 pnpm，以及 Python 3。

```bash
git clone --recurse-submodules https://github.com/Ayuilos/Miffan.git
cd Miffan
(cd web-ui && pnpm install)
rdp/scripts/fetch-sources.sh && rdp/scripts/build-native.sh   # FreeRDP 和 OpenSSL，只需一次
stream/scripts/fetch-sources.sh                                # Moonlight 源码，只需一次
./gradlew assembleDebug
```

应用使用 Kotlin 和 Jetpack Compose 编写。提交 PR 前请先阅读[贡献指南](CONTRIBUTING.md)和[Issue 指南](docs/ISSUE_GUIDELINES.md)。发布与签名流程见 [docs/releasing.md](docs/releasing.md)。

Miffan 以 [GNU Affero General Public License v3.0](LICENSE) 授权发布。
