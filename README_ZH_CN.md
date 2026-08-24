<div align="center">
  <img src="docs/assets/branding/miffan-icon.svg" alt="Miffan 应用图标" width="120" />
  <h1>Miffan</h1>
  <p>把模型、助手、工具与本地工作区装进手机的原生 Android AI 客户端。</p>

  <p>
    <a href="https://github.com/Ayuilos/Miffan/releases"><img alt="GitHub Release" src="https://img.shields.io/github/v/release/Ayuilos/Miffan?display_name=tag&sort=semver" /></a>
    <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" />
    <a href="LICENSE"><img alt="许可证：AGPL-3.0" src="https://img.shields.io/badge/License-AGPL--3.0-blue" /></a>
  </p>

  <p><a href="README.md">English</a> · 简体中文 · <a href="README_ZH_TW.md">繁體中文</a></p>
</div>

Miffan 是为 Android 打造的开源 AI 工作空间。你可以连接自己正在使用的模型服务，为不同助手配置独立的提示词、记忆、工具和性格，并在一个原生 APP 中管理对话与文件。

你可以通过 API Key 连接 OpenAI 兼容、Gemini 或 Claude 服务，也可以使用符合条件的 ChatGPT 订阅登录 Codex。Miffan 本身不内置模型，也不替代模型服务账号；具体可用能力和费用取决于你配置的服务。

## 为什么选择 Miffan

- **不同模型，一个入口。** 官方 API、兼容网关、自部署端点和 Codex 订阅可以共存，不必把工作流绑定在单一供应商上。
- **助手不只是提示词。** 每个助手都能拥有独立的模型参数、记忆、工具、MCP、Skills、视觉形象与对话历史。
- **手机不只是聊天窗口。** Miffan 可以搜索网页、处理文件、运行本地 Linux 工作区、使用设备能力，还能通过浏览器访问同一套对话。
- **有意义的角色系统。** 动态 Miffan 角色会响应时间、输入、生成与错误状态，并可按助手分别定制。

## 界面预览

<table>
  <tr>
    <td align="center"><img src="docs/img/miffan-empty-chat.png" alt="空白会话中的 Miffan 动态角色" width="280" /></td>
    <td align="center"><img src="docs/img/miffan-character-settings.png" alt="Miffan 角色外观与动作定制" width="280" /></td>
    <td align="center"><img src="docs/img/miffan-tool-call.png" alt="包含本地工具调用的聊天回复" width="280" /></td>
  </tr>
  <tr>
    <td align="center">动态角色</td>
    <td align="center">角色定制</td>
    <td align="center">工具调用</td>
  </tr>
</table>

### 划词翻译流程

在任意 Android APP 中选中文字，从文字操作菜单选择 **Miffan-翻译**，即可在不离开当前页面的情况下，通过小型悬浮窗口查看翻译结果。

<table>
  <tr>
    <td align="center"><img src="docs/img/miffan-selected-text-action.png" alt="从 Android 文字操作菜单选择 Miffan-翻译" width="300" /></td>
    <td align="center"><img src="docs/img/miffan-selected-text-translation.png" alt="Miffan 悬浮窗口中的翻译结果" width="300" /></td>
  </tr>
  <tr>
    <td align="center">1. 选中文字并选择 Miffan-翻译</td>
    <td align="center">2. 查看或复制翻译结果</td>
  </tr>
</table>

## 功能

### 模型与供应商

- 支持 OpenAI Chat Completions / Responses 兼容服务、Google Gemini / Vertex AI，以及 Anthropic Claude 兼容服务
- 使用符合条件的 ChatGPT 订阅，通过浏览器登录 OpenAI Codex
- 内置常见官方服务与网关预设，也可自定义供应商、Base URL、模型、请求路径、Headers 与 Body 参数
- 支持模型发现，并可配置模态、推理、工具调用、上下文窗口与生成参数
- 支持带认证的 HTTP/SOCKS5 代理、自定义 User-Agent、连接测试和可选的余额查询
- 根据模型能力提供聊天、推理、工具调用、图像生成与多模态输入

### 对话体验

- 流式输出、消息编辑与重新生成、回复分支、收藏、文件夹和本地全文搜索
- 对话级系统提示词、历史压缩、自动标题、追问建议、Token 用量与生成统计
- 支持图片和文档附件；必要时可在本地提取 PDF、DOCX、PPTX 与 EPUB 文本
- 富 Markdown 与 HTML 渲染，支持代码高亮、LaTeX、表格、Mermaid、图片与 Diff
- 对话可导出为 Markdown 或图片，也可从 Android 分享内容并交给指定助手处理

### 助手与 Miffan 角色

- 每个助手可独立配置模型、提示词、采样参数、上下文限制、自定义请求与聊天背景
- 支持记忆、引用近期对话、预设消息、快捷消息、正则转换、模式注入与世界书
- 支持导入 JSON 或 PNG 格式的酒馆角色卡
- 四种 Miffan 角色、六套精选配色、三种动作风格，并可选择跟随 APP 主题配色
- 针对待机、思考、成功、错误、输入、提交、点击与昼夜场景的语义动画，并支持减少动态效果

### 工具与扩展

- 支持基于 SSE 或 Streamable HTTP 的 MCP，包括 OAuth 流程和按助手选择服务器
- 可从文件、GitHub 仓库和 Skill.sh 目录安装并管理 Skills，并对安装目标进行约束
- 可接入 Bing、Tavily、Exa、SearXNG、Brave、Perplexity、Firecrawl、Jina、Grok 等搜索服务，也支持自定义 JavaScript 搜索适配器
- 可选本地工具包括时间、剪贴板、JavaScript、文字转语音、向用户提问、屏幕使用时间与日历事件
- 隔离的本地 Linux 工作区，包含文件管理、编辑器、终端、工作目录上下文与 AI 文件/命令工具

### 语音、翻译与浏览器访问

- 可配置 OpenAI Realtime、DashScope、火山引擎、MiMo 与阶跃星辰语音识别
- 支持 Android 系统语音，以及 OpenAI、OpenRouter、Gemini、MiniMax、Qwen、Groq、xAI、MiMo、ElevenLabs、Fish Audio、阶跃星辰等 TTS 服务
- 内置 AI 翻译，并支持通过 Android“处理文字”在小型悬浮窗口中翻译选中文本
- 可选本地 Web 服务器，支持本机或局域网浏览器访问、密码认证、仅本机监听与 mDNS 发现

### 数据与迁移

- 对话与设置保存在 APP 本地数据库中
- 支持选择内容的本地备份与恢复、备份提醒、WebDAV 和 S3 兼容存储
- 可从 Chatbox 导入供应商与完整对话、从 Cherry Studio 导入供应商，也可导入兼容的 RikkaHub 备份
- 使用独立的应用 ID、签名身份、发布渠道与深链协议，可与 RikkaHub 同时安装

完整的模型协议、搜索、语音、工具与迁移支持情况见[功能兼容矩阵](docs/FEATURE_MATRIX.md)。

## 下载与首次配置

Miffan 目前通过 [GitHub Releases](https://github.com/Ayuilos/Miffan/releases) 发布签名 APK。正式版本面向运行 Android 8.0 或更高版本的 `arm64-v8a` 设备。

1. 安装最新的 Miffan APK。
2. 打开 **设置 → 供应商** 配置模型服务，或使用支持的订阅登录 OpenAI Codex。
3. 添加或发现模型，再将其设为全局模型或某个助手的专属模型。
4. 仅在需要时启用搜索、MCP、Skills、本地工具或工作区。

Miffan 的应用 ID 为 `me.ayuilos.miffan.app`，深链协议为 `miffan://`。RikkaHub 的已有数据不会自动共享；如需迁移，请先在旧 APP 中导出备份，再导入 Miffan。

## 安全与隐私说明

Miffan 是客户端：提示词、附件和工具数据会发送到你选择的模型、搜索、语音、MCP、同步或其他服务端点。请分别了解所配置服务的隐私政策和计费方式。详细数据流说明见 [PRIVACY.md](PRIVACY.md)。

Skills、MCP 服务器、本地工具与工作区可能在授权范围内访问外部服务或设备数据。请只安装可信 Skills，检查工具请求，并只为助手开启必要能力。

在开启局域网访问、执行工作区命令或存放敏感凭据前，请阅读 [SECURITY.md](SECURITY.md)。

## 从源码构建

项目使用 Kotlin、Jetpack Compose、Material 3、Gradle 与 Java 17。

```bash
git clone https://github.com/Ayuilos/Miffan.git
cd Miffan
./gradlew assembleDebug
```

常用校验命令：

```bash
./gradlew test
./gradlew lint
```

`app/google-services.json` 是可选配置。没有经过授权的配置时，Firebase 使用情况分析与崩溃上报会保持关闭。生产签名与发布流程见 [docs/releasing.md](docs/releasing.md)。

### 仓库模块

| 模块 | 职责 |
| --- | --- |
| `app` | Compose UI、数据、助手、对话、工具与应用服务 |
| `ai` | 供应商抽象以及 OpenAI、Gemini、Claude 协议实现 |
| `search` | 网页搜索与页面内容服务集成 |
| `speech` | 语音识别、合成与播放 |
| `document` | PDF、DOCX、PPTX 与 EPUB 文本提取 |
| `workspace` | 隔离的本地文件系统与 Shell 环境 |
| `web` / `web-ui` | 内置服务器与浏览器客户端 |
| `highlight`、`material3`、`common` | 渲染与共享基础设施 |

## 项目历史与归属

Miffan 最初源自 [RikkaHub](https://github.com/rikkahub/rikkahub) 的社区分支，并会继续选择性吸收上游改进。现在它作为独立应用维护，拥有自己的产品定位、角色系统、包名、签名证书、发布版本线与功能开发方向。

项目会依照许可证保留上游版权与归属信息。Miffan 不是 RikkaHub 的官方版本。

## 参与贡献

欢迎提交 Issue 与 Pull Request。对于较大的改动，建议先创建 Issue 讨论产品方向和实现范围。报告问题时请附上 Miffan 版本、Android 版本、供应商类型和复现步骤，并移除 API Key 与私人对话内容。

## 许可证

Miffan 使用 [GNU Affero General Public License v3.0](LICENSE) 发布。
