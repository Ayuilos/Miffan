# 关闭应用与无法确认状态

基线：`78dfc440`。未执行 Git 写操作、提交或设备测试。

## 改动

- `ComputerTools.kt`：将 `kill_app` 纳入动作能力白名单，仍从驱动公布的 schema 创建工具，缺少该能力的旧驱动不会暴露它。沿用其他动作的审批、Automatic 来源及用户接管检查。仅在 `createReadGuideTool` 的 Operating 提示词追加规格中的一句话，computer_start 提示词不变。
- schema 通过本机 **cua-driver 0.34.0** 的只读 `cua-driver describe kill_app` 确认：必填整数 `pid`，另有可选 `session`。驱动描述明确这是强制结束进程，建议先尝试应用自身的关闭方式；本次保留该原始描述，未实际调用 kill_app。`ComputerActionTitle.kt` 使用新标题，优先显示 pid，也兼容应用名称/标识详情。
- `convert()` 对错误结果的 `launch_handoff_timeout`，或其他 `_timeout` 且 effect 不是 `failed` 的结果，添加 `status=unconfirmed`；其他错误仍为 `error`。保留原文及 structured 输出，以便模型进一步检查。
- `ComputerToolUI.kt` 增加 UNCONFIRMED，ERROR 在组合结果中仍优先。专业模式及轻松模式使用“无法确认”文案和 onSurfaceVariant；工具步骤标题/图标同步使用中性色。仅修改状态到文案/颜色的映射，未调整布局或新增组件。
- `threadComputerEvidence` 已按“状态为空”统计确认成功的动作，新状态自然不计入成功数，也不会判成 ERROR；新增测试覆盖这一点，无须改动聚合逻辑。
- 六个语言文件各追加 `computer_use_close_app`、`computer_use_unconfirmed` 两条字符串，英文撇号使用 `\'`，已有文案不变。

## 验证

- `./gradlew :app:testDebugUnitTest :app:assembleDebug --offline`：通过（42s），**606 项**测试，0 failures / errors / skipped。
- 新增 `ComputerCloseAppTest` **6 项**全部通过：convert 超时/明确失败/缺失字段/非错误结果、错误优先级、非状态文本、证据聚合、PID/名称标题、能力过滤、审批策略及提示词。
- 六语言 XML 解析、键唯一性和 `git diff --check` 通过。
- `./gradlew :app:lintDebug --offline`：通过。
