# 规格：R9 非主人回合的外传审批 + 头像预设与恢复

前置阅读：
- `AGENT_ISOLATION_SPEC.md`（R1–R7）；
- `AGENT_SELF_PROFILE_SPEC.md`（R8、P2）；
- 以及两份对应的 NOTES。

主人在 2026-10-12 确认了下面两项。

## 一、R9：主人私聊中的非主人回合，可能外传内容的工具一律审批

**背景**：R8 只管住了 `send_message`。`AGENT_SELF_PROFILE_NOTES.md` 列出了这一轮里其他可以把主人私聊内容带出去的途径，主人决定这些途径也都要审批。

**规则 R9**：当 `access.ownerThreadNonOwnerTurn == true` 时：

1. **白名单之外的所有工具都要审批。** 白名单只包含只在本机读取、不把参数发往外部的工具：
   - `my_environment`、`list_contacts`
   - `recent_chats`、`conversation_search`、`read_partner_chat`（这三个已经受 R3 约束）
   - `get_time_info`、`get_screen_time`、`calendar_query`
   - `use_skill`
   - `ask_user`（问的是主人本人）
   - `workspace_read_file`，但仅限本机工作空间。远程工作空间的读取也要审批。
   - 请核实 `miffan_help`：如果确实不把主人输入的主题发出设备，可以列入白名单，并在 notes 里写明依据。
2. **其余工具全部强制 `needsApproval = true`**，并且覆盖它们原有的免审批逻辑。包括但不限于：
   - 工作空间写文件、shell、终端、发布；
   - 网页抓取、`search_web`、`scrape_web`；
   - 所有 MCP 工具；
   - 电脑操作，也就是 `dynamicTools` 动态提供的那些工具；
   - 剪贴板、日历写入、朗读、执行 JavaScript；
   - 扩展管理；
   - `send_message`（R8 已经覆盖，保持不变）。
3. **模型侧的内置工具**（`BuiltInTools.Search`、`UrlContext` 等，由服务商执行，App 无法逐次审批）在这一轮里**关闭**。
4. **新增工具默认要审批**：实现成“白名单之外一律审批”，而不是“黑名单才审批”。这样以后新增的工具默认受保护。白名单写成一个常量，加注释说明加入的条件。
5. **在一处集中实现**：在 `ChatService` 组装完工具（包括 `dynamicTools`）之后统一包装，不要在每个工具里分别判断。
6. **审批卡要能说明原因**：界面需要知道某个待审批的工具调用是因为 R9 才要求审批的。
   - 请在工具调用上留一个界面可以读到的标记，例如工具调用 metadata 里的一个字段，或者已有审批记录里的 `via` 或原因。具体用哪种方式由你选，选最贴合现有数据结构的那个，并在 notes 里写明字段名和取值。
   - 界面由我来做。
7. 伙伴视角分段、群聊、主人发起的回合，行为保持不变。

**测试**：
- 白名单内的工具不需要审批。
- 白名单外的工具，包括 MCP 和 dynamic 工具，都需要审批，并且带有 R9 标记。
- 内置工具在这一轮被关闭。
- 其他回合的行为不变。

## 二、P2 补充：头像预设与恢复

主人反馈：伙伴现在只能把头像换成 emoji，换不回之前的头像，也换不回两个内置吉祥物（Miffan 碗和鲸鱼娘）。

`update_my_profile` 新增参数 `avatar_preset`，和 `avatar_emoji` 互斥，两个都给时返回错误：

| 取值 | 效果 |
|---|---|
| `miffan` | `Avatar.Miffan`。可以同时给可选参数 `miffan_kind`（`rice`/`sprout`/`dumpling`/`stargazer`）和 `miffan_palette`（`classic`/`matcha`/`sakura`/`moonlight`/`sea_salt`/`ink_jade`），取值就是这两个枚举的 `@SerialName`。没给的项：当前头像已经是 Miffan 时沿用当前值，否则用默认值。`motionProfile` 保留当前值。 |
| `whale_girl` | `Avatar.WhaleGirl`，`motionProfile` 保留当前值。 |
| `previous` | 从修订历史里找到最近一个**和当前头像不同**的头像，恢复它。可以是任何类型，包括图片。 |

细节要求：
- **`previous` 的计算**：
  - 写成一个可以复用的公开函数，例如 `suspend fun previousAvatar(assistantId: Uuid): Avatar?`，放在 data 层。审批卡会用它来显示“将恢复成什么样”，所以函数签名要方便界面调用。
  - 找不到时，执行阶段返回 `success:false`，并说明“没有更早的头像”。
  - 只读取调用者自己的修订记录（R5）。
- **只给 `miffan_kind` 或 `miffan_palette`、没给 `avatar_preset`**：报错。
- **工具描述**：要讲清楚三种预设各自的效果。
- **`my_environment`**：`avatar` 字段对 Miffan 头像补充 `kind` 和 `palette`，方便伙伴知道自己当前的样子。其他类型保持现状。

**测试**：
- 每种预设都能正确写入。
- 和 `avatar_emoji` 同时给时报错。
- 不合法的 kind 或 palette 会被拒绝。
- `previous` 覆盖三种情况：恢复图片头像、恢复 emoji 头像、没有历史时报错。
- `previous` 读不到其他伙伴的修订。
- 修订记录和撤销的往返仍然成立。

## 不做

- 界面改动由我来做，包括审批卡的 R9 原因说明和预设头像预览。
- 数据库版本变更。

## 验收

- `./gradlew :app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug` 全部通过。
- **模拟器暂时不要用**：emulator-5560 可能正被另一个会话占用，内存也很紧张。不做真实模型实测，只靠 JVM 测试覆盖，在 notes 里写明。
- 交付：
  - 分成 R9 和头像两个提交，提交到 `feature/agent-relay-avatar`；
  - 写 `docs/agent-collab/AGENT_RELAY_AVATAR_NOTES.md`；
  - 日志和临时文件放在 worktree 内的 `build/` 下。
- 有事实性问题直接在输入框里问。
