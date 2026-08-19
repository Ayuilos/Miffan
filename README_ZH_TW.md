<div align="center">
  <img src="docs/assets/branding/miffan-icon-color.png" alt="Miffan 應用程式圖示" width="120" />
  <h1>Miffan</h1>

由愛好者維護的獨立開源 Android AI 聊天用戶端。

[English](README.md) | 繁體中文 | [简体中文](README_ZH_CN.md)
</div>

Miffan 是基於 [RikkaHub](https://github.com/rikkahub/rikkahub) 的社群 fork，在保留多供應商聊天體驗的同時，使用獨立的應用程式身分、發布版本線、簽署憑證和視覺品牌。

Miffan 是非官方、非商業的開源粉絲專案。Miffan 名稱及圓碗形象是本專案自行設計的識別。本專案與 RikkaHub 維護者、Mercis bv 及其他相關權利方不存在隸屬、授權或背書關係。

## 下載

請從 [GitHub Releases](https://github.com/Ayuilos/rikkahub/releases) 下載。

- 應用程式 ID：`me.ayuilos.miffan.app`
- Deep link 協定：`miffan://`
- Miffan 可以與上游 RikkaHub 同時安裝。
- 兩個應用程式的資料與設定相互獨立；如需移轉，請使用匯出與匯入功能。

## 功能

- Material 3 介面與深色模式
- 支援多種 OpenAI、Gemini 和 Claude 相容供應商
- 支援 OpenAI Codex 訂閱帳號登入
- 支援圖片、文件、PDF、DOCX 等多模態輸入
- 本機工作區與終端工具
- MCP、搜尋、記憶、翻譯和提示詞擴充
- 內建 Web 用戶端
- 訊息分支、Markdown、程式碼醒目提示、公式、表格與 Mermaid

## 建置

```bash
./gradlew assembleDebug
./gradlew test
./gradlew lint
```

`app/google-services.json` 是選用設定。沒有經過授權的設定時，使用情況分析和當機回報會保持關閉。

正式發布流程請參閱 [docs/releasing.md](docs/releasing.md)。

## 授權條款與歸屬

Miffan 繼續使用 [GNU Affero General Public License v3.0](LICENSE)。專案會依照授權條款保留版權聲明與上游歸屬資訊。
