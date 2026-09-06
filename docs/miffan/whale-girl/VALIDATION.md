# 透明动画验证记录

验证日期：2026-09-05。分支：`codex/whale-girl-theme`；基于 master `4fd12969b02671d9b6153e26ba4901c6ef1891b3`。

- 应用 Debug APK 与 Android 测试 APK 构建通过。
- 51 项相关 JVM 测试通过（Miffan、WhaleGirl、AssistantAvatar、AssistantGenerationPhase）。
- Pixel 8 / API 35 / arm64 模拟器上 17 项设备测试通过：8 套透明资源、浅深背景透底、循环动画、单次动画重播与末帧停留、历史消息和减弱动态静止、后台暂停恢复、摸摸全过程暂停恢复、真实聊天组件转场、单性格设置及图标切换。
- 实际界面截图位于 `build/whale-girl-transparent/`，测试和构建日志位于其 `reports/` 子目录。
- 本机浏览器已检查微笑、扒饭和打盹在浅色、深色、棋盘格上的播放；Android 页面截图确认头像没有背景色块。
- arm64 Debug APK 的 v2 签名验证通过。安装包：`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`。
- 八段视频实际生成成本合计 $3.20；APP 本地播放不请求生成接口。

本次验证针对角色与相关聊天、设置、图标功能；未运行全项目 lint 或所有模块测试，未在实体手机上验证。

## 2026-09-06：引导页与启动图标

- 引导页新增持久主题开关，浅/深配色与角色即时更新；关闭恢复默认外观。
- 修复系统启动页固定饭碗图标，应用加载视图使用同一启动角色选择。
- 新增 7 项 JVM 测试全部通过；API 35 模拟器共 7 项设备回归通过（引导 2、启动 2、原图标 3）。验证实时背景/卡片颜色、授权无副作用、设置持久与组合重建、系统启动样式资源与 Activity 重建后的启动选择。
- Debug APK 构建与 v2 签名验证通过。截图和日志位于 `build/whale-onboarding/`。
- Android 系统在 Activity 创建前显示启动页；升级后第一次冷启动可能早于旧设置同步，应用同步后系统持久主题用于后续冷启动。未在实体手机上验证。

## 2026-09-06：外部入口图标

- 桌面三种图标同步作用于 SEND 分享、PROCESS_TEXT、相机快捷链接及两种 OAuth 回调入口。
- Debug 与设备测试 APK 构建成功，API 35 模拟器上 6 项设备回归全部通过。
- 三种图标逐一验证五类外部 Intent：恰好一个解析目标、正确 alias/target、资源 ID 及 ResolveInfo.loadIcon 实际像素与所选图标一致。
- 验证原显式 Activity 保持可用；升级时 external aliases 默认错配可修复，且不改变原 launcher overrides；反复选择和修复保持幂等。
- 启动页样式和重建回归同时通过。日志位于 `build/whale-external/reports/`。
- 第三方自行缓存图标或只读取固定 ApplicationInfo 图标的列表不受入口 alias 控制；本次验证的是标准 Intent 解析结果。

## 2026-09-06：30 fps 动作重建

- 循环由 48 帧 / 4 秒提升为 120 帧 / 4 秒，短反应由 36 帧 / 1.5 秒提升为 45 帧 / 1.5 秒，统一 30 fps。
- 从原生 24 fps 视频顺序解码，每段 124 个真实帧；沿用旧播放窗口并选取不同的原始帧，所有输出索引严格递增，无重复选帧。不是重新付费生成或简单复制旧图集补帧。
- 逐帧抽样确认所有相邻帧存在主体像素变化，透明角落与完整 alpha 保留。图集为 320px、10 列，最大尺寸 3200×3840，单循环解码占 46.875 MiB，纳入 64 MiB 缓存。
- 播放器移除第二个采样时钟，逐 vsync 推进，只在帧索引变化时更新绘制。保留暂停、重播、结束回调及静态模式。
- 8 项时间轴 JVM 测试和 14 项 API 35 设备回归全部通过，覆盖素材元数据/相邻帧变化、循环/单次动作、暂停恢复、透明、摸摸完整播放和聊天状态；APK 构建与签名验证通过。
- 图集重建记录、新旧对比页与静态 QA 位于 `output/whale-girl-motion-v3/`；设备日志位于 `build/whale-30fps/reports/`。
- 本次没有视频生成费用。未在实体手机上测量帧耗时。

## 2026-09-06：白色头箍闪动

- 逐帧定位到透明提取误删：白色头箍横带贴近蓝发，颜色又接近视频背景；头部移动时跨过旧高度阈值，时而被认成呆毛孔洞。原始视频中的横带始终存在。
- 给封闭呆毛孔洞增加宽高比和紧凑度约束，重建 idle/eating。其他六套图集逐字节不变，帧数、时长和运行时播放逻辑不变。
- `verify-headband-matte.py` 对735个已选源帧的旧/新掩膜逐像素比较：只恢复65处既定白箍区域（idle46、eating19，循环混合前），保留141个真实呆毛孔洞，新增删除区域为0。实际RGBA验证5个坏帧的白箍内部alpha由0恢复255，RGB与原始像素一致；正常对照帧RGBA完全相同。
- 新增 Android BitmapFactory 解码像素回归，覆盖此前交替完整/透明的头箍帧。102个内部像素全部通过不透明和白色检查；旧图集在宿主解码时72个采样失败、30个正常对照通过。
- Debug/设备测试APK构建、arm64 APK签名验证通过；API35模拟器15项播放及视觉回归全部通过。日志在 `build/whale-headband/reports/`。
- 修复后的八套图集共19,797,704 bytes。可逐帧切换深浅背景的对比页为 `output/whale-girl-motion-v3/headband-preview.html`，修复前素材完整保存在 `pre-headband-fix/`。
- 本次修正已证实的头箍透明误删；未重新生成视频或产生费用，未改原视频自带细节变化及循环过渡方式。未在实体手机验证。

## 2026-09-06：文本选中翻译入口

- 三个 PROCESS_TEXT intent-filter 增加各自 icon 与本地化 label。旧 APK 专项测试确认 ResolveInfo.icon 为0，新版直接提供所选图标资源；已有 Activity alias 图标和切换逻辑保持不变。
- 六种语言的 process_text_translate_label 原来全部是简体中文“Miffan-翻译”；通过 locale-tui 的单条更新接口修正为各语言“使用 Miffan 翻译”。未配置自动翻译凭据，使用人工翻译，逐文件确认只改目标条目且 XML 有效。
- Debug/测试APK构建及arm64 APK签名验证通过。API35模拟器5项图标回归通过，新增全局ACTION_PROCESS_TEXT text/plain查询（flags0和GET_RESOLVED_FILTER）、三款图标唯一目标、直接icon/labelRes、ActivityInfo回退与实际loadIcon像素验证。
- 真实Chrome本地页面长按选中文本后，翻译入口位于展开菜单；本机Chrome该菜单只显示文字，因此没有复现用户手机上的“旧图标仍显示”。不能将直接字段缺失认定为用户Chrome现象的已证实根因。本次为入口元数据兼容加固，实体手机视觉结果仍需确认。
- 对照 [Chromium菜单实现](https://chromium.googlesource.com/chromium/src/+/2e551bd1478216bfe653eb8740863b8ed02f6d0c/content/public/android/java/src/org/chromium/content/browser/selection/SelectActionMenuHelper.java) 和 [Android intent-filter属性](https://developer.android.com/guide/topics/manifest/intent-filter-element)；标准Chrome使用ResolveInfo.loadIcon，旧ActivityInfo回退理论上也应正确，因此未宣称所有浏览器均有相同缺陷。
- 构建、旧版失败与新版通过日志和Chrome截图位于 `build/process-text/`。没有修改翻译请求或会话内容。

## 2026-09-06：老用户主题发现与一键体验

- 新增一次性升级介绍卡，复用微笑、吃饭与转晕动画；支持手动预览、默认不勾选的桌面图标切换和关闭操作。新安装走已有引导页，外部分享/跳转不消耗介绍机会。
- 设置入口提供最长30天的“新主题”提示。体验前保存配色及当前助手内置头像，允许恢复；自定义头像、其他助手及后续手动头像选择受到保护。
- 9项 JVM 测试通过，覆盖升级迁移、保存时间、提示过期、重复体验、恢复、序列化及自定义头像保护。
- API35模拟器17项设备测试通过：介绍卡4项、真实设置存储与状态恢复4项、已有角色视觉7项、引导页2项。状态恢复使用 StateRestorationTester，小屏使用受限 LocalConfiguration；未在实体手机进行旋转验证。
- 介绍卡深浅色及小屏滚动截图已人工检查。测试使用减少动态效果设置冻结预览，截图不是动画流畅度验证；此次未修改动画素材。
- Debug及设备测试APK构建通过，最终arm64 APK签名验证通过。启动计数改为原子更新，避免并发覆盖介绍已读状态。
- 构建、测试日志及JVM报告保存在 `build/whale-discovery/reports/`，截图位于 `build/whale-discovery/`。未调用图片或视频生成服务。

## 2026-09-06：独立助手与统一桌面图标

- 根据后续产品决定，体验不再修改当前助手：确认后原子创建/复用专属助手并切换到新对话。使用独立保存的助手ID识别，保留改名、换头像和配置编辑；删除后下次确认可重新创建。关闭介绍不会创建助手。
- 提供完整默认助手配置与乐观、爱吃白饭、轻微嘴硬的统一性格提示词，默认使用全局模型。基础设置的重置入口在改名/换头像后仍可见；确认后恢复全部配置并保留ID、聊天记录和已保存记忆。配色恢复不再修改或删除任何助手。
- 引导页开启开关也创建/复用专属助手，关闭只恢复配色。原有授权连接流程保持不变。
- 设置仅提供饭碗及一款大肥鱼桌面图标。旧深海alias保留兼容声明，自动迁移到统一选择，分享/跳转/文本入口同步迁移；旧深海选择不再强制深色启动外观。
- 17项JVM测试通过（助手发现10、启动外观3、引导页4）；26项API35模拟器测试通过（介绍卡4、真实存储与恢复4、角色视觉7、专属助手重置1、图标6、启动外观2、引导页2）。包含取消重置、完整重置、原助手不变及创建后导航回调验证；未在实体手机验证。
- 最终Debug与测试APK构建、arm64 APK签名验证、差异空白检查通过。新版介绍卡截图人工检查通过。日志及报告位于 `build/whale-assistant/reports/`，截图位于 `build/whale-assistant/`。未生成新媒体，未产生生成费用。
