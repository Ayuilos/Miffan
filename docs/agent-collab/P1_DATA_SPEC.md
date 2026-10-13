# P1 数据规格：消息列表的分组与置顶

背景见 `docs/AGENT_COLLAB_PLAN.md`（第 1 阶段）。界面（`ui/im/` 下的所有文件和 `ImHomeVM`）由 Claude 实现，**不要修改**。本规格只覆盖数据层。

## 契约（已提交，签名不要改）

- `app/src/main/java/me/ayuilos/miffan/data/model/ChatOrganization.kt`：`ChatOrganization` / `ChatGroup`，方法体目前是 `TODO()`。
- `Settings.chatOrganization`（`PreferencesStore.kt`），默认值 `ChatOrganization()`。
- `ThreadListPrefs.collapsedGroups` 与 `ThreadListState.setGroupCollapsed(groupId, collapsed)`（`TODO()`）。

聊天 id：一对一聊天就是伙伴的 `assistantId`。

## 任务

### 1. 实现 `ChatOrganization` 的方法

全部是纯函数，返回新对象，不修改输入。

- `pin`：追加到 `pinned` 末尾，已置顶时原样返回。
- `unpin`：从 `pinned` 移除。分组成员关系不变：取消置顶后，聊天会回到原分组中显示。
- `moveToGroup(chatId, groupId)`：先从所有分组中移除 `chatId`，再追加到目标分组末尾。
  - `groupId == null` 表示移出分组。
  - 目标分组不存在时原样返回。
  - 不影响置顶。
- `createGroup(name, chatId, id)`：
  - 名称先 trim，空白时原样返回；超过 24 个字符就截断。
  - 新组追加到 `groups` 末尾；传入 `chatId` 时，等同于建组后执行 `moveToGroup`。
  - 允许重名。
- `renameGroup`：名称规则同上，空白时原样返回。
- `moveGroup(groupId, offset)`：只处理 `-1` 和 `+1`；越界或分组不存在时原样返回。
- `deleteGroup`：只删除分组本身，成员变为未分组。
- `retainChats(existing)`：从 `pinned` 和各分组的 `chatIds` 中删除不在 `existing` 里的 id，分组本身保留。
- 任何操作之后都要满足：`pinned` 中没有重复；一个 `chatId` 至多出现在一个分组中。

### 2. 持久化

- 在 `PreferencesStore` 中新增 key `chat_organization`，内容为 JSON。读取时解码失败就回落到 `ChatOrganization()`，不能让设置加载失败。
- 保存逻辑和 `ASSISTANT_TAGS` 的写法一致。
- 确认备份与恢复会带上这个字段：本地备份、WebDAV、S3 都走 settings JSON，`SettingsJsonMigrator` 等路径也要检查。缺少这个字段的旧备份恢复后取默认值。
- 如果某条导入或恢复路径会丢掉这个字段，修复它并补测试。

### 3. 迁移 4.0 的本机置顶

- 4.0 的置顶存在 `ThreadListState` 的 SharedPreferences 里（`pinned:<assistantId>`）。
- 实现一次性迁移：
  1. 设置**真正加载完成后**才执行，注意 `Settings.dummy()` 是占位值，不能被保存；
  2. 如果 `legacyPinsMigrated == false`，把本机置顶中能解析成 UUID、并且伙伴仍然存在的 id 逐个 `pin`（顺序不重要）；
  3. 把 `legacyPinsMigrated` 设为 `true` 并保存；
  4. 清除本机的 `pinned:` 键。
- 同一次启动中，在迁移之后用当前伙伴 id 调一次 `retainChats` 做清理。
- 迁移要幂等，并发或重复调用都不能产生重复。入口位置你来选，比如应用启动时，或 `SettingsStore` 首次加载完成后，在交付说明里写清楚。
- 迁移完成后，从 `ThreadListPrefs` / `ThreadListState` 中删除 `pinned` 字段和 `setPinned`；迁移需要的只读访问可以留一个内部方法。删除时，`ImHomeVM` 中对它们的引用会编译失败：把 `ImHomeVM` 里 `pinned = key in prefs.pinned` 改成 `settings.chatOrganization.isPinned(assistant.id)`，`setPinned` 改成更新 `settings.chatOrganization` 的 pin/unpin。**这是允许你改 `ImHomeVM` 的唯一一处**，其他界面代码由 Claude 改。

### 4. 折叠状态

- 实现 `ThreadListState.setGroupCollapsed`：存在同一个 SharedPreferences 里，键前缀为 `collapsed:`，`load()` 时读回 `collapsedGroups`。
- 这个状态只保存在本机，不进备份。

## 测试

- `ChatOrganizationTest`：覆盖每个方法，包括各种原样返回的情况、不变量、`retainChats`。
- 持久化：编码、解码往返；缺字段和损坏 JSON 时回落到默认值。
- 迁移：首次迁移、已经迁移过、伙伴已被删除、重复调用这几种情况。
- 备份恢复路径的测试，如果仓库里已有可以扩展的测试就补上。

## 文件归属

你负责：`ChatOrganization.kt`（只写方法体）、`PreferencesStore.kt`（只改 key、读取和保存）、`ThreadListState.kt`、迁移代码与 DI 注册、备份相关修复、测试，以及上面写明的 `ImHomeVM` 两处改动。

不要改：`ui/` 下除 `ImHomeVM` 上述两处以外的所有文件、`ChatOrganization` 的签名。

## 验收

- `./gradlew :app:testDebugUnitTest --tests '*ChatOrganization*'`，以及你新增的其他测试，全部通过。
- `./gradlew :app:assembleDebug` 通过。
- 在你自己的分支上提交，**不要提交到 `feature/im-4.0`**。完成后写 `docs/agent-collab/P1_DATA_NOTES.md`，说明迁移入口、备份检查的结论和测试列表。
