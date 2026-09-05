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
