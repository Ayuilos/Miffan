---
name: publish-release
description: Publish a GitHub release for this fork, with a bilingual changelog that separates fork-owned changes from changes introduced by upstream merges. Use when the user asks to prepare or publish a release update.
---

# Publish Release

为当前 fork 准备并发布 GitHub Release。整个流程分为“生成并确认更新日志”和“正式发布”两个阶段；用户确认前，不得创建 tag、Release 或上传 APK。

## 确定发布范围

1. 确认 `origin` 指向个人 fork，`upstream` 指向原项目，并记录当前发布目标 commit。默认目标为 `HEAD`，不要静默切换到其他 commit。
2. 在生成日志前更新 `origin`、`upstream` 和 tags；若无法访问远端，应明确说明当前判断仅基于本地 remote-tracking refs。
3. 优先从当前 fork 的 GitHub Releases 中选择目标 commit 祖先链上最近一次已发布 Release 的 tag 作为起点。不要直接选择仓库中排序最新的 tag，因为合并上游后，上游项目的 tag 也可能出现在本地提交图中。
4. 若无法唯一确定上一次 fork Release，先请用户确认起始 tag。

## 按来源分类提交

运行随技能提供的脚本：

```bash
bash .agents/skills/publish-release/scripts/classify_release_commits.sh <上次发布tag> <目标commit> <上游ref>
```

通常上游 ref 为 `upstream/HEAD` 或 `upstream/master`。脚本按提交拓扑分类，而不是按作者或提交语言猜测：

- **Fork commits**：发布范围内未由上游同步 merge 引入的非 merge 提交，包括个人直接提交及普通功能分支中的提交。
- **Upstream commits**：上游同步 merge 的上游父分支相对于其第一父分支新引入、且确实落在本次发布范围内的非 merge 提交。
- **Upstream sync merges**：识别出的上游同步点，用于复核实际合入效果和 merge 冲突处理。
- **Other merges**：普通本地合并；不要把 merge 标题本身写成更新项，应总结其中实际改动。

对每个上游同步点，额外检查 `git diff <merge>^1 <merge>`，确保只存在于 merge 冲突处理中的用户可见改动没有遗漏。普通本地 merge 如有冲突处理，也同样检查其第一父分支 diff。

若上游同步采用了 squash、rebase 或 cherry-pick，原始拓扑已经丢失，脚本无法可靠判定来源。此时不要根据作者、日期或相似标题强行分类；应先请用户提供同步 commit 或明确范围，再继续生成日志。

## 编写更新日志

结合分类结果检查相关提交和 diff，按用户实际能感知的功能、修复和体验变化进行总结：

- 自有改动与上游改动必须放在不同小节，任何一条都不能混合两个来源。
- 相似的 BUG 修复和 UI 调整可在各自来源小节内合并，但不得为了压缩数量而跨来源合并。
- 每种语言最多 10 条实质更新，两个语言版本逐条对应。
- 每条以一个短标题开头，用「：」接说明，例如「出门在外也能用电脑：不在家时伙伴可以经中转连回电脑。」英文用 `: `。App 内的更新页、更新通知和「新变化」卡片会把冒号前的部分（中文不超过 28 字）当作这一条的标题显示；有子项的条目以「：」结尾。
- 不写 commit hash、PR 编号、构建流程、重构细节或不必要的技术名词。
- 纯发布准备、版本号更新和无用户影响的内部改动通常不进入日志。
- 某个来源没有面向用户的改动时仍保留该小节，并明确写“本次无面向用户的改动”，不要省略来源分类。

使用以下格式；项目名分别取自 `origin` 和 `upstream` 的仓库名，本项目通常为 Miffan 与 RikkaHub：

```markdown
更新内容：

### Miffan 自有改动

- xxx

### 同步自 RikkaHub

- xxx

Updates:

### Miffan changes

- xxx

### Synced from RikkaHub

- xxx
```

展示草案时，同时给出一段不属于 Release 正文的来源核对摘要：起始 tag、目标 commit、识别到的上游同步点，以及两类原始提交数量；再列出 README 打算改动的段落和新增的历史行（见“更新 README”）以及文档站计划更新的 Help Skill 版本与涉及 topic（见“更新文档站”）。随后请求用户确认日志、README 改动和版本号。只有用户明确确认可以发布后，才进入下一阶段；如日志发生实质修改，需要再次确认。

## 更新 README

每次发版都要同步更新 `README.md`、`README_ZH_CN.md`、`README_ZH_TW.md`，与版本号和更新日志放在同一个发布准备提交里，随发布 PR 一起合入 master；不要等 Release 发布后再补。

- 次版本和主版本：在 “Project history / 项目历史” 表末尾加一行，写这一版最主要的一两项变化。
- 新功能或已有功能的说法改变（入口、名称、支持的服务等）：更新对应段落，不要只改历史表。
- 补丁版本没有新的用户可感知能力时可以不改，但要在来源核对摘要里说明“README 无需更新”。
- 三种语言逐句对应；繁体中文沿用文件里已有的用词（如“夥伴”“閘道”“訊息”）。
- 写给普通读者：不写 commit、PR、类名或构建细节。

## 更新文档站（`miffan-site` / Miffan Help Skill）

每次正式版本（stable release）发布后，必须同步更新同级仓库 `Ayuilos/miffan-site`（本地路径 `/Users/chenxiansheng/miffan-site`，流程详见 `miffan-site/docs/miffan-help/README.md`），让应用内帮助与 `https://miffan.ayuilos.me/docs` 保持与最新正式版一致：

1. 从上一版目录复制出新的完整不可变版本目录 `public/skills/miffan-help/<新skillVersion>/`（严禁原地修改已部署的旧版本目录）。
2. 对照本次发布提交的源码与 `values-zh` / `values` 资源更新 `zh-CN` 与 `en` 正文（保持两语言标题层级和行内 `` `topic-id` `` 引用一致，英文保留 `# 标题` + 空行 + `> 摘要`），并同步 `index.md`、`remote-workspaces.md` 等处依据的 App 版本号与发布提交短 SHA。
3. 更新 `public/skills/miffan-help/manifest.json`（`skillVersion`、`appVersionRange` 设为 `=<新App版本>`、各 topic `path`，以及必要的描述与不重复关键词）、在 `public/_headers` 末尾追加 `/skills/miffan-help/<新skillVersion>/*` 不可变缓存规则，并更新 `tests/help-skill.test.mjs` 与 `docs/miffan-help/README.md`。
4. 在 `miffan-site` 运行 `npm run help:hash && npm test && npm run build && npm run help:verify && npm run deploy:check`，确认全部通过后提交并推送至 `origin/main` 触发 Cloudflare Workers Builds；部署完成后核对线上 `https://miffan.ayuilos.me/skills/miffan-help/manifest.json` 与 `/docs`。

## 发布

1. 版本号同时作为 Release 标题和 tag；tag 不加 `v`。
2. 确认目标 commit 已存在于 `origin`，且相同 tag 或 Release 尚不存在。不要覆盖、移动或删除已有 tag/Release。
3. 从 `app/release/` 中只选择 `arm64-v8a` APK。若没有唯一候选，停止并请用户指出正确文件。
4. 在临时目录创建带版本号的上传副本，文件名使用 `<项目名>-<版本号>-arm64-v8a.apk`，并在同一目录用 `shasum -a 256 <文件名> > <文件名>.sha256` 生成校验文件；不要改动或上传其他架构的 APK。
5. 使用 `gh release create` 在目标 commit 上创建 Release，标题为版本号，正文必须是用户最后确认的双语更新日志，并在同一条命令里同时上传 APK 和 `.sha256` 校验文件。本仓库的 Release 发布后不可修改：之后无法补传文件，删除后同一版本号也不能再用；下载站同步流程缺少校验文件会失败。
6. 发布后用 `gh release view` 核对 tag、目标 commit、Release 正文、唯一的 arm64 APK 及其 `.sha256`，并确认 “Publish APK to R2” 工作流成功；同步完成 `miffan-site` 文档站更新并确认线上 `manifest.json` 生效后，向用户返回 Release 链接、资源文件名与文档站状态。

任何发布前检查失败时停止，不得用强制覆盖、删 tag 或替换已有 Release 的方式继续。
