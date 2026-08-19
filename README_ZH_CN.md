<div align="center">
  <img src="docs/assets/branding/miffan-icon-color.png" alt="Miffan 应用图标" width="120" />
  <h1>Miffan</h1>

由爱好者维护的独立开源 Android AI 聊天客户端。

[English](README.md) | [繁體中文](README_ZH_TW.md) | 简体中文
</div>

Miffan 是基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的社区 fork，在保留多供应商聊天体验的同时，使用独立的应用身份、发布版本线、签名证书和视觉品牌。

Miffan 是非官方、非商业的开源粉丝项目。Miffan 名称及圆碗形象是本项目自行设计的标识。本项目与 RikkaHub 维护者、Mercis bv 及其他相关权利方不存在隶属、授权或背书关系。

## 下载

请从 [GitHub Releases](https://github.com/Ayuilos/rikkahub/releases) 下载。

- 应用 ID：`me.ayuilos.miffan.app`
- 深链协议：`miffan://`
- Miffan 可以与上游 RikkaHub 同时安装。
- 两个应用的数据与设置相互独立；如需迁移，请使用导出与导入功能。

## 功能

- Material 3 界面与深色模式
- 支持多种 OpenAI、Gemini 和 Claude 兼容供应商
- 支持 OpenAI Codex 订阅账号登录
- 支持图片、文档、PDF、DOCX 等多模态输入
- 本地工作区与终端工具
- MCP、搜索、记忆、翻译和提示词扩展
- 内置 Web 客户端
- 消息分支、Markdown、代码高亮、公式、表格与 Mermaid

## 构建

```bash
./gradlew assembleDebug
./gradlew test
./gradlew lint
```

`app/google-services.json` 是可选配置。没有经过授权的配置时，使用情况分析和崩溃上报会保持关闭。

生产发布流程见 [docs/releasing.md](docs/releasing.md)。

## 许可证与归属

Miffan 继续使用 [GNU Affero General Public License v3.0](LICENSE)。项目会依照许可证保留版权声明与上游归属信息。
