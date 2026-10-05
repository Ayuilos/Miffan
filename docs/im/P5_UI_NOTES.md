# P5 UI 实现与验收记录

需求来源：`P5_UI_SPEC.md`。已完整阅读 `DESIGN_BRIEF.md`、`P2_UI_NOTES.md`、仓库 `AGENTS.md`，并使用 `view_image` 查看 01、06、07、08、09、11、12、18、24 设计稿。沿用 P2 的 Compose 组件、角色头像和主题。

## 范围与环境

- Worktree：`/Users/chenxiansheng/miffan-p5-ui`，分支：`feature/im-4.0-p5-ui`。
- 代码改动限于 `ui/im/`、新增路由及 IM 引导分流、新 UI VM 的 DI 注册；资源只新增 75 个 `im_p5_` 键。简体中文和英文之外，繁体中文、日文、韩文、俄文使用 `locale-tui set` 写入自己的翻译，六套键一致。
- 未修改 `data/`、`service/`、数据库、`ThreadTimeline`、`AgentThreadVM` 和专业模式页面。`ThreadTimelineContent.kt` 的修改仅在工具渲染处传递权限、问答回调，不修改时间线模型或加载规则。
- 唯一设备：`RikkaHub_API_35_16K` / `emulator-5556`，Android 15 API 35，1080 × 2400。所有 adb 命令显式带 `-s emulator-5556`，未操作 5554。
- 使用本地临时 OpenAI 兼容 fixture（127.0.0.1:18765），从应用的真实发消息、工具、配置和修订路径生成验收数据；未直接编辑数据库。fixture、辅助脚本、原始 PNG 和日志均未提交。模拟器保留测试伙伴和聊天记录。
- 截图保留既有动态主题、系统栏和 Debug 的“开发模式”标记；未合成内容。45 张真实截图转为 WebP，保存于 [p5-screens](p5-screens/)。截图来自不同验收时刻，改名、增加记忆和头像更换前后的状态会不同。

## 逐项交付

| 稿号 | 完成情况与设备验收 | 截图 |
| --- | --- | --- |
| 01 | 平铺行、分隔线、红色未读数、99+ 分支、置顶标记及既有 VM 排序；typing、图片/文件预览、用户前缀；左滑置顶/取消置顶、不显示；首次灰色提示用本地偏好持久化。设备确认置顶、未读、typing、隐藏后历史保留，以及新消息后重新出现；冷启动不重复显示提示。 | [中文](p5-screens/01-chats-zh.webp)、[英文](p5-screens/01-chats-en.webp)、[首次提示](p5-screens/01-first-hint-zh.webp)、[左滑](p5-screens/01-swipe-en.webp)、[typing](p5-screens/01-typing-en.webp)、[隐藏](p5-screens/01-hidden-en.webp)、[返回](p5-screens/01-returned-en.webp) |
| 08 | 我的伙伴三列网格，点击/长按进入资料卡；四个推荐模板含中英文名字、简介、温和具体的 system prompt。模板用稳定 UUID 判断“已添加”，改名或切换语言后仍识别；添加即保存并进入资料卡。设备实际添加中文写作搭子、通过英文引导添加 Translator，并确认持久保存。 | [中文](p5-screens/08-partners-zh.webp)、[英文](p5-screens/08-partners-en.webp) |
| 09 | 新资料卡：128dp 可编辑头像、名字/性格编辑、发消息、按归属显示记忆数；只有联网搜索和记忆两个真实能力开关。设定历史、现有聊天背景页、弱化换话题、红色删除聊天入口均已接入。设备保存名字与性格、替换 Emoji 头像、切换能力、打开背景页、换话题；删除前出现二次确认，删除后线程为空。 | [中文](p5-screens/09-profile-current-zh.webp)、[英文](p5-screens/09-profile-en.webp)、[英文模板](p5-screens/09-translator-en.webp)、[头像更新](p5-screens/09-avatar-changed-en.webp)、[底部入口](p5-screens/09-profile-bottom-en.webp)、[确认删除](p5-screens/09-delete-confirm-en.webp)、[删除后](p5-screens/09-deleted-empty-en.webp) |
| 06 | 伙伴/记忆共用时间线：时间、五类来源、当前版本、字段或记忆内容摘要；展开切换人话总结/原文差异；伙伴分别逐行比较性格与学到的偏好，记忆逐条比较。来源跳转解析会话所属伙伴；恢复传入当前显示 head，处理成功、冲突、目标缺失。设备覆盖手动、聊天、恢复、初始来源，性格差异、来源回跳，以及伙伴和记忆的旧版本恢复。 | [伙伴历史](p5-screens/06-assistant-history-zh.webp)、[记忆历史](p5-screens/06-memory-history-zh.webp)、[展开与恢复](p5-screens/06-memory-expanded-zh.webp)、[英文历史](p5-screens/06-history-en.webp)、[原文差异](p5-screens/06-diff-en.webp)、[恢复成功](p5-screens/06-restored-en.webp) |
| 07 | 新记忆页替代 IM 的旧记忆入口，按 createdAt 新到旧排列，显示内容、实际来源伙伴、日期；整条来源行可回跳。删除不确认，提供长 Snackbar 撤销；给删除附上唯一修订来源标识，取得本次删除的确切 revisionId 后调用 undo，避免撤销其他并发修改。右上历史、空态、来源缺失反馈已实现。设备实际创建记忆、回跳、删除、立即撤销并确认记录恢复；新记录显示正确的记录者。 | [中文](p5-screens/07-memory-zh.webp)、[英文与新记录](p5-screens/07-memory-list-en.webp)、[空态](p5-screens/07-memory-en.webp)、[删除/撤销](p5-screens/07-delete-undo-zh.webp)、[撤销后](p5-screens/07-undo-restored-zh.webp)、[来源线程](p5-screens/07-source-focus-en.webp) |
| 11 | 用户头像/昵称；AI 了解与服务；外观、备份、通知、语言、关于；高级区全部设置与专业模式入口。AI 对我的了解进入新记忆页。语言行显示当前应用语言，API 33+ 复用系统应用语言设置。中英文页面与记忆入口均已验收。 | [中文](p5-screens/11-me-zh.webp)、[英文](p5-screens/11-me-en.webp) |
| 18 | Pending 工具从折叠步骤中分离，卡片提供标题、说明、允许/这次不用；联网卡明确这是能力开关，允许会开启联网搜索。调用原 VM 的 answerToolApproval；ask_user 复用现有问题组件和 answerToolQuestion。加载失败给重试，加载完成前禁用动作。设备实际拒绝、允许、退出后重进待授权卡，均保留历史；允许后配置开关和聊天修订通知已核实。 | [中文](p5-screens/18-permission-zh.webp)、[英文](p5-screens/18-permission-en.webp)、[拒绝](p5-screens/18-denied-zh.webp)、[允许](p5-screens/18-allowed-zh.webp)、[重进](p5-screens/18-reentered-zh.webp) |
| 24 | 新搜索页从消息 Tab 进入；输入防抖、清除、空态/失败态；按名字过滤伙伴，使用既有全文搜索，分组显示并高亮命中；记录解析来源伙伴，打开带 focusMessageId 的线程。设备 P5 同时命中伙伴和聊天，点击后目标消息有高亮边框；清除后结果消失。 | [中文](p5-screens/24-search-zh.webp)、[英文](p5-screens/24-search-en.webp)、[定位高亮](p5-screens/24-focus-en.webp)、[清除](p5-screens/24-cleared-zh.webp) |
| 12 | IM 专用两步引导：服务商选择前继续禁用；OpenRouter 排首位标一键登录，复用授权/已有密钥恢复；其他服务商进入既有配置。第二步选择默认伙伴或四个模板，完成保存伙伴、模型、服务启用状态并进入 IM 首页。通过 UI 临时移除/加回本地测试模型，走通配置、第二步选 Translator、完成和冷启动保存。OpenRouter 已验证浏览器启动及取消返回。专业模式仍调用原 OnboardingPage。 | [中文连接](p5-screens/12-connect-zh.webp)、[英文连接](p5-screens/12-connect-en.webp)、[中文选择](p5-screens/12-partner-zh.webp)、[英文选择](p5-screens/12-partner-en.webp)、[完成](p5-screens/12-complete-en.webp) |

## 界面侧绕行与偏差

1. **待授权工具的会话生命周期。** 初次设备验收发现：生成任务停止后，既有 ChatService 约 5 秒释放无引用会话；稍后点击授权，原 VM 可能拿到未加载的空会话，导致保存后的聊天归属和内容异常。没有改服务/VM。工具渲染组件仅在待交互卡可见时持有会话引用，重进时用既有 `openThreadSegment(segmentId, assistantId)` 加载准确段，准备好后再转发原 VM 回调。修复后复测延迟拒绝、允许和退出重进通过。其他非 IM 调用方的生命周期问题仍需后续独立处理。
2. **验收旧数据。** 上述绕行前的一次操作改变了测试来源会话归属，所以后期英文记忆图中旧“团子”记录解析为 Default Assistant；界面忠实显示现有来源，没有猜测作者或修补数据库。绕行后新建“喜欢吃辣”记录解析为 P5ddy，回跳正确。
3. **记忆归属。** 资料卡遵循 useGlobalMemory，全局或伙伴私有归属；“我”入口使用当前 settings.assistantId 的同一归属。没有跨伙伴私有归属汇总 API，未扩展数据层。没有来源且是全局旧记录时显示未知记录者，日期缺失显示较早，不编造来源。
4. **搜索边界。** 沿用现有 FTS 返回上限、方括号命中标记和会话 updatedAt 日期；没有单条消息的时间字段可用。无对应伙伴的孤立结果不显示。线程定位沿用已有有界加载逻辑，未改 ThreadTimeline。极旧目标不在已有加载边界内的情况仍由 P2 路径处理。
5. **原文差异。** 普通文本使用逐行 LCS；超过 500 行的尾部按原文显示删/增，避免 UI 做无限大小的二维比较。摘要仍列出实际变化字段。此大文本降级未设备压测。
6. **视觉。** 使用 P2 现有主题与组件，支持内容滚动，长文字省略或换行；没有固定设计稿中的示例人名、记忆、时间或色值。权限理由/聊天内容由 fixture 返回中文，因此英文界面截图仍可包含中文内容。

## 未覆盖项与原因

- 未实际累积 100 条未读；99+ 分支已代码检查。图片/文件预览和“我：”分支已实现，本轮没有重新发送附件做设备覆盖。
- 没有并发注入修订 Conflict、NotFound、备份恢复或缺失来源记录；相关返回分支、刷新反馈及来源保护已实现。没有设备覆盖学到的偏好差异、超长差异、共享记忆多伙伴并发删除。
- ask_user 回调已接入，但默认伙伴只启用 TimeInfo；fixture 强制返回的 ask_user 没有注册工具定义，未进入 Pending 问答，不能据此认定问答提交路径已设备通过。没有为验收擅自给伙伴增加额外工具。终端等专用授权工具的特殊后端契约也未覆盖。
- OpenRouter 仅测试授权入口及取消，没有外部账号凭据，未完成真实登录/回调或已有密钥恢复；联网搜索仅测试能力授权，没有真实远端搜索供应商调用。
- 主要页面看过中英文；繁体、日文、韩文、俄文仅完成资源键/占位符检查，未逐页设备验收。未覆盖 Android 12 及以下、深色主题、横屏、大字体和 TalkBack。
- 指定 16K 模拟器在长时间验收中多次出现输入 ANR；重启应用/模拟器后继续。读取的 ANR 主线程停在 Android MessageQueue/ART ConditionVariable 等待，没有足够证据归因于新增页面，也没有据此宣称设备长期稳定。ANR 和加载中的画面未作为页面交付截图。Gboard 拼音会转换 adb 注入文字，之后切换英文键盘继续；测试伙伴改名为 P5ddy 是实际保存值。

## 构建与检查

各组完成后分别执行 `./gradlew :app:compileDebugKotlin`：01、08/09、06/07、11、18、24、12 七组均通过。material3 子模块按仓库记录检出，web-ui 按冻结 lockfile 安装构建依赖；没有修改子模块引用、依赖清单或锁文件。

最终命令：

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon --max-workers=2
```

- `BUILD SUCCESSFUL`，5 分 34 秒。
- 88 个测试套件、527 个测试：0 failure / 0 error / 0 skipped。
- Lint：0 error、402 warning、6 hint；新增 Kotlin 文件及 im_p5 资源没有告警。旧 `im_partners_hint` 因交互改为进入资料卡而不再使用，保留原资源键，报告包含其 UnusedResources；其余为已有仓库告警。
- 最终 arm64 Debug APK 已重新安装到指定模拟器，复核搜索清除文案与中英文主要页面。
- `git diff --check` 通过，六语言 75 个新键一致。提交范围检查排除了所有禁止路径；不推送，不切换或合并分支。
