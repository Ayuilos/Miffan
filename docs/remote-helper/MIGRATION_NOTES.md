# Remote helper migration notes

## 实现

- 源码、两组离线 Python 测试、完整 Windows 测试目录用 git mv 移入 remote-helper；
  一次性 tools spikes 与远程屏幕历史文档保持原样。
- 新增 build-logic 的可缓存 AssembleRemoteHelper 及 App convention plugin，使用
  Android variant generated assets API。JVM 测试通过 system properties 接收路径，
  生成文件、goldens 和版本锁均声明为测试输入，构建不需要 Python/pwsh。
- Shell 与 setup 模板直接复制；PowerShell 模板保留 UTF-8 BOM，整行 include 以原始
  字节展开。运行时安装、撤销、解析及协议逻辑均未改变，只更新了两处源码路径注释。
- CuaBridge.cs（250 行）和 MiffanNative.cs（30 行）均抽出，包含自己的 using 与完整类型，
  编辑器可识别独立 C#。前者是独立的管道/MCP bridge；后者是独立的 WTS 会话 interop，
  达到约 30 行且有清晰类型边界。DesktopWorker 中 MiffanDisplay 约 12 行，仅少量 P/Invoke
  声明，按规格保持内联，避免把短嵌套 here-string 再拆开。
- 新增生成内容版本锁、全部共享 fixtures 的 Kotlin 消费者测试、Python 实际 helper
  输出与 golden 比对，以及 Windows 离线 mocked producer 契约用例。修正 Windows 测试
  中原有硬编码版本 8，改为与当前 HelperVersion 比较（运行脚本仍为版本 10）。
- stage.py 先构建，再上传生成 helper 和共享公开 fixtures；Test-Helper.ps1 默认路径改为
  生成目录。没有运行任何远程、stage、smoke、Windows 真机测试。

## 协议盘点与未定义项

PROTOCOL.md 按平台、子命令记录参数、stdin/stdout、退出码、副作用、前置条件和 Kotlin
调用/解析入口。除规格列举的七个 parse 函数，还覆盖了 VNC 启动的内联 decoder、
RemoteScreenRepository.probe 的内联机器 decoder、严格 unpair Result decoder、
WindowsClipboardChannel 的 success 子串判定及 MCP transport。

代码中没有定义的点：Shell 系统命令失败时 probe/env/vnc status 的统一退出码、stderr
与多余参数规则；POSIX clipboard 工具的完整输出及具体失败码；setup 的机器输出与退出码；
Sunshine detail/Windows error_type 的稳定枚举；所安装 cua-driver 的完整 MCP schema。
Windows clip serve 启动失败可以输出失败 JSON 后退出 0，消费者只查 success 子串，
不检查 verified。以上均记录现状，没有在纯重构中改变。

## ShellCheck 与 CI

官方 GitHub API 元数据请求返回 HTTP 403；随后经 pane 的下载审批流程成功下载官方
v0.10.0 Linux x86_64 与 Darwin aarch64 发布归档。Linux 归档本地 SHA-256：
`6c881ab0698e4e6ea235245f22832860544f17ba386442fe7e9d629f8cbedf87`。
CI 使用同一固定 URL 与摘要，在解包前 sha256sum --check --strict。

本地 v0.10.0 `-S warning` 输出 3 条、全部为 SC2155：

| 行 | 诊断 |
|---|---|
| 84 | Declare and assign separately to avoid masking return values. |
| 97 | Declare and assign separately to avoid masking return values. |
| 334 | Declare and assign separately to avoid masking return values. |

`-S error` 无诊断。少于 20 条，因此没有降为 error 门槛；为满足字节不变，CI 仍以
-S warning 运行，按“诊断代码 + 级别”的数量核对基线（`{"SC2155 warning": 3}`），不看行号；出现新的诊断才失败，改动导致行号移动不影响。（Claude 复核时由逐位置比对改为按类型计数。）
这是保留旧 warning 的明确处理，没有修改 helper，也没有全局禁用该规则。
PowerShell 静态检查列在 README 的后续项中。

## 验收实际结果

所有 Gradle 验收均加 `--offline`，使用已有依赖缓存，避免构建期间联网。

| 规格项 | 实际结果 |
|---|---|
| 1. 生成文件 cmp | 三个文件均与 git show 6f4eb1777 导出的基线通过 cmp，PowerShell BOM 保留；Shell/setup 为 LF、无 BOM。 |
| 2. debug APK | assembleDebug 成功；arm64-v8a、x86_64、universal 三个 APK 的全部三个 helper 条目（共 9 项）解包后均逐字节一致。 |
| 3. JVM 与 lint | 最终 test assembleDebug：BUILD SUCCESSFUL in 21s，512 tasks；全模块 XML 汇总 1668 tests、0 failures、0 errors、8 skipped（workspace 原有跳过项），App 1127 tests 全过。完整 test lint assembleDebug：BUILD SUCCESSFUL in 6m 16s，677 tasks；App lint 464 warnings、6 hints、无 error，本次涉及 Kotlin 文件没有 lint 诊断。最终测试源码的 lint 复验也 BUILD SUCCESSFUL in 32s，507 tasks，报告内容保持不变。 |
| 4. configuration cache | 连续两次 assembleRemoteHelper --configuration-cache 成功；第二次 Reusing configuration cache，任务 UP-TO-DATE，660ms。 |
| 5. Python | 不带 UPDATE_FIXTURES 的两个测试文件均通过；safety 输出四组 PASS，Sunshine 30 tests，82.183s，OK。43 个共享样例中 sh 28 个均由 stub 环境运行 helper 实际产出，ps1 15 个手工整理并接入 Windows mocked producer 比对。 |
| 6. 反向测试 | 给 Shell 末尾加一个 LF、不升版本：RemoteHelperVersionTest 失败且 XML 包含要求的中文提示；把 sunshine-running 的 installed 改为 false：RemoteHelperContractTest 失败。两次 Gradle 均按预期 exit 1，finally 恢复源码/fixture 后两个测试 exit 0（9s）。最终全量测试与 APK 构建又通过，cmp 再次确认已恢复。 |
| 7. 移动历史 | git log --follow 可看到实施提交 48f8785b5 后继续追溯到 e802e7800、cd77c382b、21470c204、b9b65727a、c97a26b8b 等旧路径提交；Shell/setup rename 为 100%，PowerShell 模板 rename 为 79%。 |
| 8. 路径清理 | 活跃源码、JVM 测试、脚本和 CI 已无旧源码路径；全仓文本搜索仅有未改的 docs/remote-screen 历史文档与本分支原有 MIGRATION_SPEC 自身。规格自身也包含旧路径，因此字面“仅有历史文档”需排除此不可改的迁移说明。运行时资源读取和 androidTest 保持原样。 |

生成文件 SHA-256：

| 文件 | SHA-256 |
|---|---|
| miffan.sh（18） | ba495b52623fc19772122ca5d1fac1f600b25c9d16ca99ab8c1583790c67c55f |
| miffan.ps1（10） | 2263f7a47a09939a445c259ed3fd80c510f7db925dea1c330fdd3fab289a39fa |
| windows-setup.ps1 | 5469eedc4e69774cb67cc38fee209d6a748c4765836457aa91d4d850a909a179 |

另外：sh -n、所有迁移 Python 脚本 py_compile、ShellCheck 基线门禁及 git diff --check 通过。
local.properties 的键仅 sdk.dir；未修改 .deps 指向的内容。没有 push、没有连接远程主机。
Windows PS 5.1 用例按要求未在本机执行，后续 Windows 运行结果不在此验收声明中。
