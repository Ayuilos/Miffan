# 中转界面翻译规格

负责人：herdr agent（Claude Haiku，medium）。审核：主会话。

## 任务

`docs/remote-screen/RELAY_I18N_KEYS.txt` 列出的 108 个字符串键已有英文（`app/src/main/res/values/strings.xml`）和简体中文（`values-zh/strings.xml`）。请补齐以下语言：`values-zh-rTW`（繁体中文，台湾用语）、`values-ja`、`values-ko-rKR`、`values-ru`。

做法：按 `.claude/skills/locale-tui-localization/SKILL.md` 的流程，用 `uv run --directory locale-tui src/main.py apply <json>` 写入（JSON 放在 scratchpad 或 /tmp，不放进仓库）。翻译以简体中文和英文为准，语气与 App 现有文案一致：口语、简短、面向普通用户；不出现 relay、connector、hostId 等术语（「中转」译为各语言里自然的说法，例如日语「中継」、韩语「중계」、俄语「ретранслятор」或「промежуточный сервер」中选一个并全篇统一）。保留所有占位符（`%1$s`、`%1$d`、`%2$d`、`%1$.1f`）。

另外：这 108 个键在 `values/strings.xml` 中带有 `tools:ignore="MissingTranslation"`，补齐翻译后去掉这些键上的该属性（只动这 108 个键）。

## 验收

1. `uv run --directory locale-tui src/main.py missing -m app` 不再报告这些键。
2. `./gradlew :app:testDebugUnitTest --tests '*WorkspaceLocalizationTest*'` 通过。

## 规则

- 工作目录：worktree `.claude/worktrees/relay-i18n`，分支 `feature/relay-i18n`（基于 `feature/relay`）。只改 `app/src/main/res/values*/strings.xml`。
- 在本分支提交（提交信息末尾加 `Co-Authored-By: Claude Haiku 5.5 <noreply@anthropic.com>`），不得合并或推送。
- 构建前若缺 `material3/material-color-utilities` 子模块或 `web-ui/node_modules`：`git -c protocol.file.allow=always -c submodule.material3/material-color-utilities.url=/Users/chenxiansheng/miffan/material3/material-color-utilities submodule update --init material3/material-color-utilities`，以及 `cd web-ui && pnpm install --frozen-lockfile --offline`。
