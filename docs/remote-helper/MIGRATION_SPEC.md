# 远程 helper 仓库内模块化规格

目标：把 `app/src/main/assets/remote/` 下的远程 helper（`miffan.sh`、`miffan.ps1`、
`windows-setup.ps1`）整理为仓库内独立目录 `remote-helper/`，有自己的源码结构、文档、测试和 CI，
但仍随 App 一起构建、一起发版。**不拆仓库，不改 helper 行为，不改安装协议。**

工作目录：worktree `.claude/worktrees/remote-helper`，分支 `refactor/remote-helper`（基于 `feature/im-4.0`）。
在本分支上提交即可；不要动 `feature/im-4.0`，不要 push。

## 1. 目录结构

```
remote-helper/
  README.md            # 是什么、如何构建、如何跑测试、版本规则
  PROTOCOL.md          # App ↔ helper 协议（见 §4）
  versions.lock        # 版本 → sha256（见 §5）
  src/
    miffan.sh          # 原样移动（git mv），保持 LF、无 BOM
    windows-setup.ps1  # 原样移动
    windows/
      miffan.ps1       # 模板：大段 C# 换成 include 标记
      *.cs             # 从 miffan.ps1 抽出的 C#
  fixtures/            # 契约 golden 文件（见 §6）
  tests/
    test_helper_safety.py      # ← rdp/scripts/test-helper.py
    test_sunshine_helper.py    # ← tools/remote-screen/test_sunshine_helper.py
    windows/                   # ← tools/windows-helper-tests/ 全部内容
```

- 用 `git mv` 保留历史。`tools/windows-spike/`、`tools/remote-screen/p7*.py` 是一次性 spike，不动。
- 删除 `app/src/main/assets/remote/`；App 的 assets 改由构建生成（§2）。

## 2. 构建：生成 assets

- 在 `build-logic` 里加一个可缓存、兼容 configuration cache 的 task 类（项目已开启
  `org.gradle.configuration-cache=true`），由 `app` 注册，例如 `:app:assembleRemoteHelper`。
  输出到 `app/build/generated/remoteHelper/`，目录内是 `remote/miffan.sh`、`remote/miffan.ps1`、
  `remote/windows-setup.ps1`。
- 用 `androidComponents.onVariants { it.sources.assets?.addGeneratedSourceDirectory(...) }`
  接入，使 APK 里的路径仍然是 `assets/remote/*`。`RemoteMiffanHelper`、`WindowsSetupScript`、
  `RevokeMiffanKeyCommand` 等运行时代码**不改**。
- `miffan.sh`、`windows-setup.ps1` 原样复制。`miffan.ps1` 由模板展开 include 标记生成。
  标记格式自定，但必须是单独一行、肉眼一看就懂的，例如 `#include CuaBridge.cs`。
  展开时逐字节插入，不做任何换行或编码转换。
- 只抽出明显是独立 C# 源码的大块：目前 `$script:CuaBridgeSource`（约 775–1026 行）必须抽；
  182–213 行的 `Add-Type` 块和 `DesktopWorker` 里的 C# 块，判断后选择抽或不抽，并在报告中写明理由。
  小于约 30 行的保持内联。
- `.cs` 文件需能被编辑器识别为 C#（可以有自己的 `using`，但展开后的结果必须与现状完全一致，
  见下条）。如果做不到"源文件是合法独立 C#"又同时"展开结果逐字节一致"，优先保证逐字节一致。
- **硬性验收：** 在本次迁移中，生成的三个文件必须与基线提交 `6f4eb1777` 中对应 asset
  **逐字节一致**（`cmp`），包括 `miffan.ps1` 的 UTF-8 BOM。因此 helper 版本号不需要升。
- 不新增构建依赖（不能让 Gradle 构建依赖 Python、pwsh）。

## 3. 测试和调用方路径

- 单元测试里直接读 `File("src/main/assets/remote/...")` 的地方（`RemoteSunshineUnpairTest`、
  `WindowsScreenHelperTest`、`WindowsScreenRepositoryTest`、`WindowsSetupScriptTest`）改为读生成目录：
  让 `testDebugUnitTest`/`testReleaseUnitTest` 依赖生成 task，并通过 system property 传入目录，
  不要硬编码 `build/...` 路径。
- androidTest 通过 `context.assets` 读取的不用改。
- Python 测试：`miffan.sh` 无需展开，直接指向 `remote-helper/src/miffan.sh`。
- Windows 测试（`stage.py`、`Test-Helper.ps1` 默认参数）需要展开后的 `miffan.ps1`：
  `stage.py` 先运行 `./gradlew :app:assembleRemoteHelper -q`，再从生成目录上传。
  `Test-Helper.ps1` 的默认 `-HelperPath` 指向生成目录，README 说明先构建。
- 更新代码注释里的旧路径（如 `RemoteScreenRepository.kt` 的 ``assets/remote/miffan.sh``、
  `RemoteScreenSetupGuidance.kt` 提到的 `miffan.sh`）。`docs/remote-screen/` 下的历史规格和笔记**不改**。
- `AGENTS.md` 的 Module Structure 加一行 `remote-helper`（说明它不是 Gradle 模块，是随 App 打包的远程 helper 源码）。

## 4. PROTOCOL.md

按子命令逐一列出（Linux/macOS 的 `miffan.sh` 与 Windows 的 `miffan.ps1` 分开或并列）：
调用方式、参数、stdin、stdout 格式（JSON 字段及含义，或纯文本）、退出码、副作用、
需要的权限/前置条件，以及 App 里哪个 Kotlin 函数调用、哪个函数解析（如 `parseHelperProbe`、
`parseSunshineProbe`、`parseSunshineCommand`、`parseSunshineFirewallCheck`、
`parseSunshineFirewallResult`、`parseWindowsClipboard`、`parseRdpStart`，以及盘点中发现的其他解析点）。全部从现有代码读出，不要臆测；
代码里看不清的写"未定义"并在报告中列出。另写一节"版本与安装"：安装位置、安装命令、
版本比对逻辑（`RemoteMiffanHelper.ensureInstalled`）、`windows-setup.ps1` 的模板渲染。

## 5. 版本锁

问题：`ensureInstalled` 只比版本号，改了 helper 内容却忘了升版本，远端会一直跑旧脚本。

- `remote-helper/versions.lock`：每行 `<文件> <版本> <生成后 sha256>`，`miffan.sh` 和 `miffan.ps1` 各一行
  （`windows-setup.ps1` 无版本号，不入锁）。
- 加一个 JVM 单元测试（放在 app 的 test 里或 build-logic 的检查 task 均可，要能被 `./gradlew test` 跑到）：
  读生成后的文件，取版本号和 sha256，与锁文件比对。不一致时失败信息写明：
  "helper 内容变了：升 `MIFFAN_HELPER_VERSION` / `$script:HelperVersion`，然后更新 versions.lock"。
- README 写明：锁文件里的旧行只能新增不能改，评审时看到某行被改就是忘了升版本。

## 6. 契约 fixtures

目的：helper 的输出格式和 Kotlin 解析器之间有一份共享的样例，两边谁改格式都会挂测试。

- 盘点 Kotlin 解析的每一种 helper 输出，至少覆盖 §4 列出的全部 parse 函数。每种输出至少放一个正常样例，
  有分支的（如 Sunshine 未安装/已运行/端口被占）各放一个。路径如 `remote-helper/fixtures/sh/probe-wayland.json`、
  `remote-helper/fixtures/ps1/probe.json`。
- `miffan.sh` 的 fixtures 由离线 Python 测试**真实产出**：测试运行 helper 后与 fixture 比对；
  支持 `UPDATE_FIXTURES=1` 重新生成。现有 `test_sunshine_helper.py` 的 stub 机制可直接复用。
- `miffan.ps1` 的 fixtures 无法在 Mac/Linux CI 上产出：从现有 Windows 测试或代码手工整理，
  并让 `Test-Helper.ps1` 增加与 fixture 比对的用例（在 Windows 上跑时验证）。
- Kotlin 侧：新增测试读取全部 fixtures，逐个交给对应 parse 函数，断言关键字段。fixtures 目录同样通过 system property 传入。

## 7. CI

在 `.github/workflows/ci.yml`：

- 把 `Verify remote helper safety` 改为运行 `remote-helper/tests/` 下的 Python 测试（两个文件都跑，包括 fixture 比对）。
- 加 shellcheck：在 runner 上用**固定版本 + sha256 校验**下载 shellcheck 二进制（参考文件里已有的 pinned 依赖写法），
  对 `remote-helper/src/miffan.sh` 运行 `shellcheck -S warning`。
  若 warning 级别超过约 20 条，CI 先用 `-S error`，把 warning 清单写进报告，**本任务不修**（保持纯重构）。
  需要联网下载，先在本地确认 URL 和 sha256；联网需要用户在你的 pane 里批准。
- PowerShell 静态检查本次不做（runner 没有 pwsh），在 README 里列为后续项。

## 8. 验收

1. `cmp` 三个生成文件与 `git show 6f4eb1777:app/src/main/assets/remote/<file>` 逐字节一致。
2. `./gradlew assembleDebug` 后解包 debug APK，`assets/remote/*` 同样与基线逐字节一致。
3. `./gradlew test` 全过；`./gradlew lint` 不新增问题。
4. 两次 `./gradlew :app:assembleRemoteHelper --configuration-cache`，第二次命中缓存/up-to-date。
5. `python3 remote-helper/tests/test_helper_safety.py`、`python3 remote-helper/tests/test_sunshine_helper.py` 通过。
6. 故意改 `miffan.sh` 一个字节不升版本 → 版本锁测试失败；故意改一个 fixture 字段 → Kotlin 契约测试失败。验证后还原。
7. `git log --follow remote-helper/src/miffan.sh` 能看到旧历史。
8. 仓库内 `grep -rn 'assets/remote'` 只剩运行时 `assets.open("remote/...")`、androidTest 和 `docs/remote-screen/` 历史文档。

不要连接任何远程主机，不要运行 Windows 真机测试（`stage.py`、`smoke.py` 等）。

## 9. 交付

- 在 `refactor/remote-helper` 上提交，可分几个 commit（移动 / 构建 / 版本锁 / fixtures / CI）。
  commit 信息用英文，句子式，风格参考 `git log`。
- 写 `docs/remote-helper/MIGRATION_NOTES.md`：做了什么、哪些 C# 块抽了/没抽及理由、PROTOCOL 里标"未定义"的点、
  shellcheck 情况、验收 1–8 的实际输出摘要。
- 完成或卡住时通知 Claude：
  `herdr pane send-text w2Y:p1 "remote-helper: <一句话状态>"`，然后 `herdr pane send-keys w2Y:p1 enter`。
  只发 send-text 不发 enter，消息会停在输入框里。
