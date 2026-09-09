# 大肥鱼原生绘图与动作

## 共享场景状态补齐（2026-09-09）

| 页面语义 | 蓝色大肥鱼动作 |
| --- | --- |
| Idle | 默认微笑；满足夜间或无操作条件时睡眠 |
| 输入框聚焦 | FOCUSED：低头、视线向下关注输入 |
| 正在打字 | TYPING：关注输入，轻微眯眼、视线跟随与头部节奏 |
| 实际发送 | SUBMITTED：一次点头确认，随后恢复当前生成阶段 |
| 等待回复 | EATING：吃饭 |
| 输出正文 | CHEWING：咀嚼 |
| 实际推理 | THINKING：转圈眼 |
| 成功完成 | SUCCESS：短暂得意 |
| 错误 | 静止微笑与错误标识 |
| 有更新 | SURPRISE：惊讶提醒；输入优先 |
| 点击关注 / 摸摸 | PETTING：满足微笑，并朝场景传入方向轻转头 |

新增动作复用原生轮廓与前台时钟，不需要生成图片或下载动作素材。
动作实验室和主题预览已包含聚焦、打字、收到。减弱动态保留静态神态；
发送确认结束回调仍运行，重复发送会重放，错误或成功立即打断确认。
新增表演尚待用户视觉验收，以下八表情记录均为此前版本的验证历史。

本次验证：Kotlin 编译、373 项 JVM 测试、Debug 构建和 ARM64/Universal APK
签名校验通过。API 35 模拟器 17 项播放与视觉测试通过；首轮两项因模拟器
快照锁屏没有可见 Compose 页面，解锁后这两项重跑通过。人工查看了昼夜
十一表情图；截图与构建、首轮及复跑日志在 `build/whale-semantic-validation/`。

> 视觉状态：首版被用户否定；重做的静态正脸随后得到用户认可（“可以 好多了”）。八个原生语义状态已接入该正脸，新增表情尚不代表用户逐项视觉验收。下面首版的测试记录不代表其视觉验收通过。

用户于 2026-09-08 接受 imagegen 幼态双色方向，授权 Android 原生还原。设计参考为 `reference-day.png` / `reference-night.png`；发带、鲸鳍局部强调及眼睛简化可按实际效果调整。成年海报、旧三维方案及发布工作继续暂停。

## 简洁配色与动作细化（当前修订）

后续调节：昼夜蓝色进一步提高饱和度；吃饭改为闭嘴准备、张嘴接饭、收嘴微笑和吞咽，送饭目标向口腔内部调整，碗与头部跟随更明显。咀嚼左右脸颊参数从 11±5 增至 20±11，下颌和点头同步放大。思考加入 ±5.5° 摇晃，漩涡由 0.8 rad/s 加快到 π rad/s（约 3.9 倍）；减弱动态仍静止。设备分镜新增思考，并使用非等分采样避免漏看左右交替的峰值。

本次调节验证：371 项 JVM 测试和 18 项设备测试通过（69.569 秒），Debug 构建及 ARM64 APK 签名检查通过。已人工检查实际昼间表情图、吃饭/咀嚼/思考分镜；截图与日志在 `build/whale-vivid/`，其中 `visual-tests/whale-acting-thinking.png` 展示增强后的摇晃与漩涡相位。

用户进一步认可配色与表演改进方向：保留认可脸型，为头发、鲸鳍、蝴蝶结上色，脸和发带保留浅色；不再要求全图仅两种颜料。`trace_color_regions.py` 在认可轮廓内分区并生成 `WhaleGirlColorRegions.kt`，运行时仍为缓存 Compose Path。昼夜保持各部位的色块分工。

`WhaleGirlActing.kt` 从现有前台时钟采样分段缓动：吃饭 3.6 秒，含准备、送入口、收嘴与撤筷；咀嚼 3.2 秒，含左右脸颊交替、吞咽和停顿；睡眠 5.6 秒，含慢呼吸、点头和鼻端气泡。提醒增加放大的感叹号、张嘴和短促回弹。历史头像和减弱动态仍保持静止语义。

动作实验室新增“动作时间检查”滑杆，可检查 0–6 秒中间姿态。`actingBeatsChangeAndLoopWithoutAPoseJump` 生成四组真实 Android 分镜并检查循环首尾姿态一致。下面记录的旧配色与旧测试截图是各阶段历史证据；本次新增配色尚未获得用户最终视觉验收。

本次最终验证：Debug/设备测试 APK 构建成功，371 项应用 JVM 测试通过；API 36.1 x86_64 独立模拟器 18 项设备测试通过（66.8 秒），ARM64 Debug APK 签名验证通过。首轮分镜发现吃饭循环夹起米饭跳变，已用渐进夹起/入口收缩和边界采样修正，并通过复跑。

最终昼夜八表情图、尺寸矩阵及四组动作分镜位于 `build/whale-color-acting/final/visual-tests/`；分镜文件为 `whale-acting-eating.png`、`whale-acting-chewing.png`、`whale-acting-sleeping.png`、`whale-acting-surprise.png`。构建与设备日志在 `build/whale-color-acting/final-build.log`、`final-device.log`。实际验证使用模拟器，未声称已完成物理手机或用户视觉验收。

## 静态正脸重做（用户已认可）

`WhaleGirlStaticReview.kt` 保留已认可正脸的共享轮廓和静态对照入口。它从已认可参考图的左上正脸提取轮廓，使用填充矢量路径保留发丝、睫毛和发带的粗细变化；虹膜减少内部亮斑，仅保留主要高光。运行时只用 Compose Path，不读取参考位图。

生成脚本为 `tools/whale-line-art/trace_static_face.py`，依赖版本在同目录 `requirements.txt`，使用 Python 3.12。参考位图副本只打入设备测试 APK，用于未经修饰的参考裁切和 Android 捕获像素的并排对照。

静态组件 Debug/设备测试 APK 构建成功，`WhaleGirlStaticReviewTest` 已在 API 36.1 模拟器执行并生成截图。对照图为 `build/whale-static-review/device/reference-vs-native.png`，右侧为真实 Android Canvas 捕获；夜间截图为同目录 `native-static-night.png`。该截图测试只负责生成审阅材料，不自动判定艺术还原度。

## 认可正脸上的状态接入

`WhaleGirlLineArtPortrait` 已改用 `drawApprovedWhaleHead`，聊天、设置和引导的兼容入口继续调用它。默认静止状态直接绘制认可的原始路径；闭眼、得意、提醒、吃饭、咀嚼、思考和睡眠只在五官/脸颊区域变化。头发、发带、鲸鳍和蝴蝶结共用认可轮廓，不再使用首版近似头部。

`WhaleGirlApprovedDrawing.kt` 缓存眼睛、嘴、下颌区域的路径布尔运算结果，动画帧只变换五官和少量动态路径。静态外轮廓提供纸色剪影，头像外透明。昼夜共用几何，Day ink 固定为已认可的 #154BB7。

小头像增加光学校正线重，大尺寸认可轮廓不变。新增设备回归检查认可静态图与生产 Idle 在昼夜/多尺寸下逐像素一致；各状态在不同背景上的四角透明。原有生命周期、减弱动态、同实例回到 Idle、表情矩阵及真实聊天入口测试继续适用。

## 当前版本验证（2026-09-08）

调试页新增“蓝色大肥鱼”标签：可选择全部八种神态、暂停/播放/重放、切换昼夜和减弱动态、选择 28/32/40/80/168/280 dp，并选择单次动作结束后返回微笑。预览使用生产原生绘图组件；所有控制仅作用于该页，离开标签后暂停播放，不修改助手或全局主题。

- Debug 与设备测试 APK 构建成功；371 项应用 JVM 测试全部通过。
- 独立数据盘的 API 36.1 x86_64 模拟器：`WhaleGirlApprovedFaceTest`、`WhaleGirlVisualTest`、`WhaleGirlPlaybackTest` 共 17 项全部通过，最终运行耗时 59.152 秒。
- 人工检查最终昼夜八表情图和 28/32/40/80/168 dp 矩阵，修复咀嚼下颌残留重线；默认脸保持认可轮廓，小头像线条加重。
- Universal 与 arm64 Debug APK 签名验证通过；实际执行验证使用 x86_64 模拟器，未进行物理 arm64 手机验证。

最终截图在 `build/whale-approved-states/final-device/visual-tests/`。`whale-girl-approved-gallery-light.png` 与 `whale-girl-approved-gallery-dark.png` 直接排列八张 168 dp Android 截图，未重绘角色；同目录保留尺寸矩阵和真实页面截图。最终日志为 `build/whale-approved-states/final-build.log`、`final-device.log`。新增表情的设备回归通过不等于用户已逐项认可其美术效果。

## 首版实现（视觉已被否定）

`WhaleGirlLineArt.kt` 使用 Compose Canvas/Path，共用轮廓与昼夜调色板。蓝线白底、白线深蓝底；头像以外透明，内部使用纸色。眼睛只有单色形状与一个留白高光，静态头发等轮廓缓存。

`WhaleGirlAnimatedPortrait` 兼容入口已统一委托到原生绘图，涵盖聊天头像、设置预览及引导预览，不再加载 WebP 图集或旧海报。平台启动图和启动器图标属于独立资源，此次未改。

八种语义使用同一个头部：微笑、摸摸、得意成功、更新提醒、等回复吃饭、输出咀嚼、实际推理转圈眼、睡眠。睡眠使用向下眼睑与气泡，摸摸使用弯眼与变化的笑嘴，成功使用半眯眼与得意笑嘴。错误沿用独立语义提示，颜色也使用角色双色。

眼睑与鼓腮在当前数值基础上过渡；过渡与表情时钟均随前台生命周期暂停。历史头像不启动循环，减弱动态保留静态语义。摸摸在减弱动态下仍使用有限的前台计时完成回调，重复摸摸重启时长。显式暂停不消费反应时长，恢复不追赶后台时间。

## 首版验证记录（历史）

设备测试已改为新入口：`WhaleGirlVisualTest` 包含八表情 × 昼夜 × 28/32/40/80/168 dp 矩阵、同实例状态变化与恢复、静态稳定性和咀嚼脸部变化。`WhaleGirlPlaybackTest` 覆盖生命周期、显式暂停、摸摸结束和重放、减弱动态。

2026-09-08 验证结果：

- `:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:testDebugUnitTest` 构建成功；主应用 Kotlin 编译通过，371 项 JVM 测试全部通过。
- Medium / API 36.1 x86_64 模拟器运行 `WhaleGirlVisualTest`、`WhaleGirlPlaybackTest`：15 项全部通过。使用独立数据盘，未卸载原模拟器中签名不同的应用。
- 已人工查看真实设备的昼夜矩阵、空聊天与等待状态截图。八种表情都可区分，头像外透明，发带/鲸鳍/蝴蝶结可见，单色眼睛只保留一个高光。小尺寸会省略细发丝和发带折线。
- Universal 与 arm64 Debug APK 签名校验通过。设备执行验证使用 x86_64；未声称完成物理 arm64 手机验证。

设备截图位于 `build/whale-native-validation/visual-tests/`，昼夜矩阵分别为 `whale-girl-native-light.png`、`whale-girl-native-dark.png`。测试日志与构建日志保存在 `build/whale-native-validation/`。这些是 Android 测试捕获的像素；矩阵仅对原始截图排列，未用 SVG 代替。

复现构建使用 JDK `/home/ayuilos/.local/share/android-studio/jbr`、SDK `/home/ayuilos/Android/Sdk`。内存受限环境使用 `--max-workers=1 -Dorg.gradle.jvmargs='-Xmx2048m -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process`，构建结束停止 Gradle 后再启动模拟器。新工作区需要先初始化仓库锁定的 `material3/material-color-utilities` 子模块并安装 Web 锁文件依赖。

旧 `build/whale-line-art/` 几何预览由 Kotlin 静态路径生成 SVG，仅用于轮廓检查，不是 Android 截图，不代表动态或小尺寸验收。不得用设计参考或路径预览替代设备证据。

## 移除停用图集

已删除 `app/src/main/assets/whale_motion/` 的八个 WebP 图集和 metadata，以及废弃的素材路径接口与对应测试。默认静态海报仍由启动图/图标引用，继续保留。清理增量打包缓存后 ARM64 Debug APK 从 102.74 MiB 降至 81.97 MiB；三个 ABI 包均检查无旧图集。构建、370 项 JVM 测试及 ARM64 APK 签名检查通过。
