# P1 UI 实现说明

## 已完成

- 新增 `Screen.WorkspaceScreen(id)` 和导航 entry，按工作空间 ID 创建 `RemoteScreenVM`。
- 远程工作空间详情的顶部栏在终端旁显示屏幕入口；本地工作空间不显示。
- 主机卡片的溢出菜单和屏幕页都能打开主机屏幕设置；`WorkspaceVM` 将读取、保存操作委托给现有屏幕仓库。
- 设置支持启用开关、TCP 端口（默认 5900，1–65535）、绝对 Unix 套接字路径、三种认证、macOS 用户名和隐藏密码。已存密码留空保留，无认证由仓库删除密码。密码不写入 Compose saved state。读取/保存失败在对话框内显示，保存成功后屏幕页重新连接。
- 屏幕页显示连接名/主机名、连接流量、输入模式、键盘开关、5/10/20 fps 档位、重连，以及 Connecting / Failed / Closed 状态。未启用或缺少凭据时直接引导至本页设置。
- 黑色画布按比例 Fit；支持 1×–5× 缩放、放大后的平移和边界约束。直接模式支持单击、双击、长按右键、长按拖拽；触控板模式支持相对指针移动与加速、双指右键、双击拖拽和本地指针标记。两种模式均支持双指滚轮及捏合。
- 为区分右键与左键拖拽，直接模式的长按右键在松手时发送；超过移动阈值后改为左键拖拽。开始多指手势、切换模式、离开画布或进入后台时取消待定点按并释放拖拽按钮。
- 同一个 Bitmap 直接绘制，`frameVersion.value` 在绘制作用域读取；不按帧复制 Bitmap。分辨率变化时重建画布包装和输入坐标状态。
- 隐藏文本框接收软键盘输入；仅在 IME 组合结束后提交文字，删除/回车发送对应 keysym。一次性 Ctrl/Alt/Command/Shift 支持组合，应用到下一按键或下一已提交 Unicode 字符后清空。按键栏可横向滚动，包含规定的方向、导航、功能和删除键。
- 页面观察 ON_START / ON_STOP 设置可见性，并在可见期间保持屏幕常亮。计费网络提示在该页面首次进入时显示一次；剪贴板/输入 notices 对应 Snackbar，远端剪贴板复制到手机。
- 新增 38 组 `workspace_screen_` 英文与简体中文资源；其他语言沿用英文回退，仅这些英文资源标注 `tools:ignore="MissingTranslation"`，匹配 spec 的语言边界。

## 没做 / 接口限制

- **Linux 的 Super 修饰键未实现，按键栏的 Super 按钮禁用。** 当前 `RemoteModifier.COMMAND` 在非 macOS 平台映射为 Ctrl，无法表达真正的 Super；UI 没有把这个按钮接成错误的 Ctrl。
- 不修改 `workspace` 模块、数据层、`RemoteScreenVM`、数据库或依赖注入。不包含 P2 远端准备、P3 伙伴控制权或 P4 轻松模式入口。
- 未连接远程主机、模拟器或真机，未宣称端到端联调通过。输入模式、键盘开关、帧率和网络提示状态随旋转恢复；缩放/平移与本地指针在画布重建时回到初始状态。
- 省流档位只调整帧率，不新增 RGB565、JPEG 质量切换或其他核心画面策略。

## 验证

- `./gradlew :app:assembleDebug --offline`：通过（BUILD SUCCESSFUL，1m 17s）。新增屏幕文件无编译警告；日志中的已有警告位于原有代码，包括 RouteActivity 的 Coil opt-in 和 WorkspaceDetailPage 原有的 nullable 调用。
- `./gradlew :app:assembleDebug :app:lintDebug --offline`：通过（BUILD SUCCESSFUL，3m 49s）；新增屏幕 UI 文件无 lint 报告，原有项目警告未改动。初次 lint 的 38 个 MissingTranslation 错误已用上述逐键、限定范围的资源回退标注解决。
- Universal 与 arm64-v8a Debug APK 均通过 `apksigner verify`。
- 英文/中文资源 XML 可解析，38 个键完全匹配，代码引用均存在，格式占位符一致；`git diff --check` 通过。
- 本工作树起初缺少已固定版本的 material-color-utilities 子模块内容和 web-ui 的 node_modules。已从本机主检出复用相同提交的子模块源文件及已安装前端依赖，全程未下载依赖、未修改构建配置。这些构建准备文件未加入提交。
- `locale-tui` 的 Python 环境未安装 `lxml`，离线缓存也缺少完整依赖；本次使用其 `escape_android` / `validate` 校验逻辑和临时 XML 写入器，只处理 spec 要求的两种语言。

## 主代理需要真机确认

1. macOS 与 Linux 上 Fit、Retina 坐标映射、分辨率变化、连续帧重绘及缩放边界；高分辨率帧的内存、画面撕裂和绘制性能。
2. 直接点按/双击/长按拖拽的时间与移动阈值；触控板加速、双指右键、双击拖拽、指针跟随缩放、滚轮方向与水平滚动。确认多指切换、离开页面和后台切换不遗留按下的鼠标按钮。
3. Gboard、系统拼音及其他 IME 的组合文字、候选上屏、连续删除、换行、粘贴和一次性修饰键；确认隐藏文本框不占布局，键盘与按键栏在小屏/横屏下可用。
4. 5/10/20 fps 的真实流量和流畅度，重连后档位恢复；首次计费网络提示、远端剪贴板同步及中文输入失败提示。
5. 无配置/缺密码时的设置入口、密码留空保留、无认证删除密码、用户名回退、保存错误不关闭、保存后重连，以及普通断网/Closed 的重试流程。
6. 进入后台停止请求帧、返回前台恢复，旋转保留同一 ViewModel 会话，页面退出释放会话与常亮状态。

## 对 ViewModel 接口的建议

- 增加 `RemoteModifier.SUPER`，映射到 `RfbKeys.SUPER_L`；保留现有 `COMMAND` 的平台快捷键语义。这样 Linux 才能完成 Super 一次性修饰键。
- 暴露 hostId / hostName / platform 的只读状态，页面可直接显示平台键名与打开设置，避免额外订阅 `WorkspaceVM` 的主机/工作空间列表。
- 由 VM 保留最大帧率，并在连接创建时应用。目前 `setMaxFps` 对未连接会话无效，UI 必须在每次 Connected 时重设；首次连接有短暂的核心默认帧率。
- 输入事件最好统一排队：`typeText` 的非 Latin 文本粘贴是异步操作，紧随其后的 Return、快捷键或另一段输入可能先到达远端；UI 无法通过现有无返回值接口等待粘贴完成。
- `metered` 若改为可观察状态，可在 Wi-Fi/移动网络切换时及时更新提示和默认档位；当前是 VM 创建时的快照。
