# locale-tui

Android 语言文件翻译管理 TUI 工具。

## 功能

- 模块选择界面
- 翻译表格显示所有语言
- AI 自动翻译缺失条目
- Dead entry 检测和过滤
- 搜索过滤
- 编辑和删除条目

## 快捷键

| 快捷键 | 功能 |
|--------|------|
| `Enter` | 选择/编辑 |
| `t` | AI翻译缺失条目 |
| `d` | 切换Dead Entry过滤 |
| `m` | 切换Missing过滤 |
| `/` | 聚焦搜索框 |
| `Delete` | 删除条目 |
| `s` | 保存更改 |
| `r` | 刷新数据 |
| `Escape` | 返回/取消 |
| `q` | 退出 |

## 运行

```bash
cd locale-tui
uv run python src/main.py
```

## 测试 AI 连接

```bash
cd locale-tui
uv run python src/main.py test-connection
```

## 配置

编辑 `config.yml` 配置模块列表、语言列表和翻译设置。

在 `.env` 文件中配置 OpenAI API：

```
OPENAI_API_KEY=your_api_key
OPENAI_BASE_URL=https://api.openai.com/v1
```

## 由 AI 代理翻译（无需 API Key）

Claude Code 等代理按 `.claude/skills/locale-tui-localization` 自己完成翻译，只用下列命令检查和写入：

```bash
uv run --directory locale-tui src/main.py missing [-m app] [--json]   # 缺少翻译的 key、英文源仍是中文的 key
uv run --directory locale-tui src/main.py hardcoded [-m app]          # 源码中含中文的字符串字面量
uv run --directory locale-tui src/main.py apply strings.json [--dry-run]
```

