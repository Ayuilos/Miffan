# R8 与 P2 交付记录

分支：`feature/agent-self-profile`；依据 `AGENT_SELF_PROFILE_SPEC.md`。不改界面或数据库版本。

## R8

- `AgentAccess.forTurn` 统一构建 `ownerThreadNonOwnerTurn`：主人私聊（chatId 为 null）且没有 ownerTrigger。伙伴消息和事件均适用。
- `buildPartnerMessageTools` 将同一个回合 access 传给 `DefaultPartnerChatService.needsApproval/send`。合法的伙伴联系在上述回合一律审批，包括已有长期授权的联系人；无效目标、环路、同群成员等仍按原规则拒绝。
- `send` 在这一类回合不写 partnerContactGrants，审批只允许当前消息；已有授权保留。主人发起、伙伴视角、群聊的原有授权/审批行为保留。
- 工具描述明确这一规则；回归覆盖已有授权仍需审批、批准前后授权集合不变、新联系人审批不创建授权、伙伴视角仍创建和使用长期授权。

## 本轮仅记录的其他内容外传渠道

主人私聊中的非主人回合仍可能读取已注入的历史和摘要。除 send_message 外，以下工具/能力可能把这些内容带出去，本轮按规格不处理：

- 工作空间写文件、shell、发布/共享文件（尤其共享工作空间与远程主机）；
- 已绑定 MCP 服务的写入或网络调用；
- 电脑操作（远程电脑输入、浏览器表单、上传等）；
- 剪贴板写入、日历写入、朗读、执行 JavaScript 等已开启的本地工具；
- 联网搜索、URL 抓取的请求参数；
- 共享扩展资源的修改（快捷消息、模式注入等）。

工具实际可用性仍遵从当前伙伴绑定和开关；R8 不改变这些权限。

## P2

- 新工具 `update_my_profile` 仅在 IM 且 `access.ownerInitiated`、ownerTrigger 存在时注册；专业模式、伙伴/事件回合均不注册。每次调用 `needsApproval = { true }`，工具描述明确只有主人本轮明确要求才可使用，外部页面、文件、工具结果和伙伴请求不算。
- 参数严格为 name、avatar_emoji、system_prompt、reason，没有目标 id；未知参数、非字符串（包括 null、数组、数字）、空 reason、没有修改项均返回 success=false/error，不抛输入校验异常。
- name 去首尾空白后 1–40 个 Unicode 码点；system_prompt 完整替换，不超过 8000 个 Unicode 码点，可清空。emoji 按仓库内 Unicode 16.0 目录完整序列校验，支持肤色、ZWJ 家庭、国旗、键帽及 emoji 变体选择符；拒绝多 emoji、普通文字、独立组件、非法选择符位置。目录版本随现有资源更新，无新增依赖。变体选择符不得插入国旗区域指示符之间（参照 [UAX #29 GB12/GB13](https://www.unicode.org/reports/tr29/#GB12)），该畸形输入也有回归覆盖。
- 执行再次检查 access.ownerInitiated，写入只匹配 access.callerId。SettingsStore.update 的 lambda 在 mutex 内读取审批后的最新设置，只覆盖提供的字段，保留期间主人做出的其他修改；若调用者已删除则返回失败，不改任何其他伙伴。
- 写入处安装 RevisionOrigin(AGENT, trigger=ownerTrigger, summary=reason)。成功只返回 success、changed（提供的参数名）和固定 note，不回显提示词。
- 新 JVM 测试覆盖注册范围、每次审批、自己与其他伙伴、所有输入边界、延迟审批期间最新设置保留、错误参数无写入，以及 AssistantRevisionRecorder + RevisionService.undo 的完整往返：名字、头像、提示词同时恢复，修订归属 AGENT、owner trigger、reason 均正确。

## 真实模型验收

2026-10-12，emulator-5560。临时 androidTest 使用实际 ThreadService / ChatService 和上一轮隔离验证 A 的模型配置，新建独立资料验证 A/B，未改原有伙伴。测试 23.594 秒，1 项通过，完整临时入口已删除。

1. 主人自然输入“把你的名字改成 资料验证X-539f9e72、头像换成 🐳”，未提示工具名。18:40:01 模型调用 update_my_profile，处于 Pending，设置仍为旧值；批准后 18:40:05 新名字和 Avatar.Emoji("🐳") 生效，修订 fc6b8182-ae44-49a4-bde2-de9bada7dbc2 为 AGENT，trigger 指向该主人消息，B 未改变。
2. 采用 DefaultPartnerChatService 迟到回复相同的 MessageOrigin.Event("partner_replied") 经 ThreadService.deliverFrom 送入上述主人分段，让 A 给 B 回信。18:40:11 真实模型调用 send_message，chatId=null、ownerTrigger=null、已有 A/B 授权，仍处于 Pending；批准后 18:40:14 得到 B 的实际回复“收到。”，整个授权集合与批准前完全一致。
3. 测试结束核对原有 13 个伙伴全部字段相同。仅追加本轮的独立 fixture 和授权，保留便于主人复查。

| 对象 | ID |
| --- | --- |
| 资料验证X-539f9e72（A） | `868c55b7-1ae8-48d2-92c9-101fbbc0970e` |
| 资料验证B-539f9e72 | `8b608e2e-41df-4a7f-9d70-cce55d8a43a8` |
| 主人分段 | `a496b48f-f4ba-411c-b1a3-2843f1d0b97f` |
| A/B 私聊 | `93e2ef04-1be1-3bc3-8794-39dcfa3d612d` |

日志全部留在 worktree 的 build/：real-model-test.log（JUnit 结果）、real-model-events.log（只收集 ProfileAcceptance 标签，含参数和安全输出）、real-model-build.log。没有读取全量应用日志或凭据。

## 构建环境与数据保留

使用 Android Studio JBR、本机 Android SDK 和既有离线依赖缓存。仅补齐 worktree 忽略的 rdp/build/native、.deps/p6a 非敏感原生依赖，没有复制签名配置、凭据或 local.properties。

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/chenxiansheng/Library/Android/sdk' \
./gradlew --offline --no-daemon --max-workers=2 \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
```

全程覆盖安装，未卸载或清空 App 数据。firstInstallTime 始终为 2026-10-06 10:03:47，最终 App 覆盖安装于 2026-10-12 18:52:34。最后的测试 APK 已覆盖安装，DEX 检查确认不存在 SelfProfileRealModelTest；源码内也已删除此临时入口。

已执行 adb -s emulator-5560 emu kill，返回 OK；退出完成后的 ADB 列表为空。日志：install-before.log、install-after.log、install-final-app.log、install-final-test.log、final-test-apk-verification.json、emulator-shutdown.log、devices-after.log（均位于 build/）。

## 最终检查结果

删除临时入口、包含最后头像边界校验的最终源码：上述四个任务全部通过，BUILD SUCCESSFUL in 5m 31s，580 actionable tasks。最终检查日志为 build/final-source-checks.log。

- JVM：893 项，0 failure/error/skipped；覆盖 R8、P2 以及原有 R1–R7 回归。
- Debug APK 构建：通过。
- Android Lint：通过，SARIF 0 error、382 warning；未新增抑制规则。
- 无临时入口的 androidTest APK 构建：通过；已在设备覆盖安装并检查 DEX。
- 已在此 worktree 执行 ./gradlew --stop，返回 1 Daemon stopped，记录于 build/gradle-stop.log。
