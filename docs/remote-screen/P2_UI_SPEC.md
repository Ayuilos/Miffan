# P2 UI：自动连接、检测环境与失败引导

背景见 `docs/REMOTE_SCREEN_PLAN.md` 与 `docs/remote-screen/P1_UI_SPEC.md`。P1 的屏幕页与主机屏幕设置对话框已存在，本任务在其上扩展。数据层与远端脚本已完成，接口见下文。

## 边界

可以修改：

- `ui/pages/extensions/workspace/screen/RemoteHostScreenSettingsDialog.kt`
- `ui/pages/extensions/workspace/screen/RemoteScreenPage.kt`
- 同目录下按需新建的文件（例如环境面板组件）
- `ui/pages/extensions/workspace/WorkspaceVM.kt`：新增委托方法
- `app/src/main/res/values/strings.xml`、`values-zh/strings.xml`：新字符串，前缀 `workspace_screen_`

不要修改：`workspace` 模块、`data/` 下的文件、`RemoteScreenVM.kt`、`assets/remote/miffan.sh`、数据库与依赖注入。接口不够用时写进 `docs/remote-screen/P2_UI_NOTES.md`，不要自己改。

## 已有接口（`data/repository/RemoteScreenRepository.kt`）

- `RemoteScreenEndpoint.Helper`：新的连接方式。打开屏幕时远端脚本会启动只对该账户可见的 VNC 服务并返回端点。存储值 `helper`。
- `probeHost(hostId): RemoteMachineProbe`：在远端安装/更新脚本并探测。字段：
  - `os`（`macos` / `linux`）、`arch`
  - `session.present`、`session.type`（`wayland` / `x11` / `quartz` / `none`）、`session.desktop`（例如 `niri`、`GNOME`、`KDE`、`macos`）
  - `cua.path`、`cua.version`、`cua.min`（当前 `0.34.0`）、`cua.ok`
  - `vnc.server`（`wayvnc` / `x11vnc` / `macos-screen-sharing` / `none`）、`vnc.running`、`vnc.endpoint`
  - `clipboard`（`wl-copy` / `xclip` / `xsel` / `pbcopy`，可能为 null）
- `installCuaDriverOnHost(hostId, upgradePath): RemoteCommandOutcome`：`upgradePath` 为 null 时安装（执行 `/bin/bash -c "$(curl -fsSL https://cua.ai/driver/install.sh)"`），否则执行 `<upgradePath> update --apply`。返回 `success` 与最后 30 行输出。可能运行数分钟。
- `RemoteScreenUnavailableException.problem: RemoteScreenProblem` 与 `detail`：
  - `NOT_FOUND`、`NOT_ENABLED`、`BAD_ENDPOINT`、`PASSWORD_MISSING`
  - `NO_WORKSPACE`：主机下没有远程工作空间，无法检测
  - `NO_GRAPHICAL_SESSION`：远端账户没有登录的桌面会话
  - `NO_VNC_SERVER`：没有可用的 VNC 服务端，`detail` 为会话类型（`wayland` / `x11`）
  - `VNC_START_FAILED`：启动失败，`detail` 为日志末尾

## 主机屏幕设置对话框

### 连接方式

- 选项顺序：**自动（推荐）**、TCP 端口、Unix 套接字。选“自动”时不显示端口/路径输入。
- “自动”的说明：Miffan 会在远端安装一个小脚本（`~/.miffan/bin/miffan`），用于找到桌面会话并启动只有本账户能连接的屏幕服务；连接全程经过 SSH。
- 主机尚未启用屏幕时（`enabled == false`），默认选中“自动”并打开启用开关的建议状态由你决定，但默认连接方式必须是“自动”。已启用的主机保持其已保存的方式。

### 检测环境

- 对话框内增加“检测环境”区块与按钮。点击后调用 `probeHost`，期间显示进度，失败显示错误（`NO_WORKSPACE` 时提示先为此主机创建一个远程工作空间）。
- 结果按行展示，每行一个状态图标（正常 / 需要处理 / 不支持）：
  - 系统：macOS 或 Linux，加架构。
  - 桌面会话：类型与桌面名；`present == false` 时提示“没有已登录的桌面会话，请在这台电脑上登录桌面”。
  - 屏幕服务：
    - `macos-screen-sharing` 且未运行：提示在“系统设置 → 通用 → 共享”中打开“屏幕共享”，认证选 macOS 账户。
    - `none`：按会话类型给出安装提示。Wayland：`wayvnc`（Arch：`sudo pacman -S wayvnc`；Debian/Ubuntu：`sudo apt install wayvnc`）。X11：`x11vnc`（同上替换包名）。桌面名是 GNOME 或 KDE 时，说明这类桌面的支持仍在验证中。命令可一键复制。
    - 其他：显示服务名与是否在运行（未运行属于正常，打开屏幕时会自动启动）。
  - 剪贴板：有工具时正常；Linux 为 null 时提示安装 `wl-clipboard`（Wayland）或 `xclip`（X11），否则无法输入中文等非拉丁文字。
  - cua-driver（伙伴操作电脑需要）：
    - 未安装：按钮“安装”。
    - 已安装但 `ok == false`：显示版本与最低版本，按钮“升级”。
    - `ok == true`：显示版本。
- 安装/升级前必须弹出确认对话框，写明将在远端执行的完整命令、会从 cua.ai / GitHub 下载并安装软件、macOS 安装后需要在系统设置中授予辅助功能与屏幕录制权限。用户确认后调用 `installCuaDriverOnHost`，期间禁止关闭对话框（可显示“正在安装，可能需要几分钟”），结束后显示成功或失败及输出末尾，并自动重新检测。

## 屏幕页的失败引导

`Failed` 且错误为 `RemoteScreenUnavailableException` 时，按 `problem` 显示：

| problem | 说明 | 操作 |
| --- | --- | --- |
| `NOT_ENABLED`、`BAD_ENDPOINT`、`PASSWORD_MISSING` | 屏幕未启用或配置不完整 | “屏幕设置” |
| `NO_GRAPHICAL_SESSION` | 远端没有已登录的桌面会话，请在电脑上登录桌面后重试 | “重试” |
| `NO_VNC_SERVER` | 按 `detail` 的会话类型给出与设置对话框相同的安装提示（可复制命令） | “重试”、“屏幕设置” |
| `VNC_START_FAILED` | 屏幕服务启动失败，显示 `detail` 中的日志末尾（可折叠） | “重试” |
| 其他 | 现有行为 | 现有行为 |

其他异常保持 P1 的行为。

## 验收

1. `./gradlew :app:assembleDebug --offline` 通过，无新增编译警告；`:app:lintDebug --offline` 无新增错误。
2. 只在你自己的分支 `feature/remote-screen-ui` 上提交（先基于 `feature/remote-screen` 的最新提交变基或重建该分支），不要合并、不要推送。
3. 完成后写 `docs/remote-screen/P2_UI_NOTES.md`：做了什么、没做什么、需要真机确认的行为、对接口的建议。
4. 不需要连接远程机器；联调由主代理完成。
