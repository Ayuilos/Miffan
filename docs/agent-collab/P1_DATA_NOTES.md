# P1 数据层交付说明

工作分支：`feature/chat-org-data`。实现范围遵循 `P1_DATA_SPEC.md`；`ChatOrganization` / `ChatGroup` 的公开契约未改。UI 仅修改 `ImHomeVM` 中读取置顶状态和设置置顶的两处代码。

## 数据行为

- 实现全部分组、置顶纯函数，保留置顶顺序、组顺序和成员追加顺序；取消置顶保留原分组。
- 名称先 trim，再按 Kotlin 字符截断到 24；允许重名。正常数据上的无效操作返回同一个对象。
- 操作同时修复导入数据中的重复置顶和重复成员：置顶保留首次出现；成员归属保留第一个组，显式移动后以目标组为准。即使重复组 id，也不会把一次移动写入多个组。
- `retainChats` 删除失效成员，但保留空组、组顺序与迁移标记。
- DataStore 增加 `chat_organization` JSON key，保存方式与 `ASSISTANT_TAGS` 一致。独立解码函数对缺 key、缺字段、非法 UUID、损坏 JSON 返回 `ChatOrganization()`，不会阻断其他设置的加载。
- `ThreadListPrefs.pinned` / `ThreadListState.setPinned` 已删除。旧键只有迁移内部的读取与清理接口；界面置顶改为更新 Settings。
- `collapsed:<groupId>` 与已读、隐藏状态共用 `im_thread_list` SharedPreferences；展开时删除键，加载时只读取 true 值。折叠状态不进入 Settings 或备份。

## 迁移入口与并发

`RepositoryModule` 通过 `single(createdAtStart = true)` 注册 `ChatOrganizationMigration`。应用 `startKoin` 时启动 AppScope 的 IO 协程，调用 `settingsFlow.awaitLoadedSettings()` 等待真实设置；`Settings.dummy()` 不会进入写入过程。

未迁移时，解析本机 `pinned:` 后缀为 UUID，只合并仍存在的伙伴；保留已有置顶顺序，不重复追加。设置 `legacyPinsMigrated = true` 后，同一次启动调用 `retainChats` 清理失效置顶和分组成员。

写入用 `SettingsStore.updateIfCurrent`，在现有更新互斥锁下校验快照；有并发修改就基于最新设置重算，保留其他设置编辑。迁移自身也有 Mutex；重复调用或另一迁移先完成都不会重复导入。已经迁移的启动仍做 retain 清理。

只有设置保存成功（或真实设置已经迁移且无需变化）后才清除全部旧 `pinned:` 键。保存异常保留旧键，下次启动可重试；启动异常记录日志，协程取消继续向上传递。

## 备份与恢复检查

- 本地 ZIP 与 WebDAV：共用 `WebDavSync.prepareBackupFile` / `restoreFromBackupFile`。
- S3：`S3Sync` 使用同样的 `encodeBackupSettings`、`SettingsJsonMigrator.migrate`、Settings 解码与 `SettingsStore.restoreSettings`。
- 备份前的 `withoutProviderSecrets()` 只 copy providers，不丢失 chatOrganization；共享编码器序列化完整 Settings。
- `SettingsJsonMigrator` 保留该字段，旧备份缺字段由 Settings 构造默认值兼容。
- `restoreSettings` 最终经过同一个 Settings 保存方法，因此新增 key 会写回 DataStore。
- Chatbox、Cherry Studio 导入仅 copy 要导入的提供商/伙伴字段，保留现有 chatOrganization。

没有发现需要修改的备份/恢复路径；扩展 `BackupSettingsTest` 覆盖实际共用编码器（含去除提供商凭据）、JSON 迁移、解码及旧备份默认值。折叠状态没有出现在备份 JSON 中。未进行远端 WebDAV/S3 实际上传测试。

## 验证

定向测试覆盖：

- `ChatOrganizationTest`：每个方法、顺序、无效操作原样返回、名称规则、纯函数输入不变、重复数据不变量与 retain。
- `ChatOrganizationPreferencesTest`：JSON 往返、Settings 序列化、缺字段、损坏 JSON / 非法 UUID 回落。
- `ChatOrganizationMigrationTest`：首次/已迁移/删除伙伴/重复与并发调用、等待真实设置、写入失败、并发用户修改、其他迁移先完成。
- `ThreadListStateTest`：折叠保存与重载、展开删除、无效值、旧键隔离清理、已读与隐藏状态回归。
- `BackupSettingsTest`：共享备份 codec 与迁移往返、旧备份兼容。
- 现有 `LoadedSettingsTest`、`SettingsJsonMigratorTest` 回归。

定向测试命令：

```bash
./gradlew :app:testDebugUnitTest --tests '*ChatOrganization*' --tests '*ThreadListStateTest' --tests '*BackupSettingsTest' --tests '*LoadedSettingsTest' --tests '*SettingsJsonMigratorTest'
```

结果：`BUILD SUCCESSFUL`，7 个测试类、38 项测试，0 failures / 0 errors。其中新增或扩展的类为 32 项，设置加载与 JSON 迁移回归为 6 项。

`./gradlew :app:assembleDebug`：`BUILD SUCCESSFUL`，生成 arm64-v8a、x86_64 和 universal Debug APK。

`git diff --check`：通过。

构建环境使用 `ANDROID_HOME`，没有创建 `local.properties`。首次 assemble 发现 worktree 缺少忽略的原生构建依赖；仅从现有 checkout 复制 `rdp/build/native/{arm64-v8a,x86_64}` 和流媒体构建所需的三份 `.deps/p6a` 源码归档到本 worktree，未复制配置或凭据。这些文件不提交。
