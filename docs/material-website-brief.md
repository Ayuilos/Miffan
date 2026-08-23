# Material 3 项目站点实现要求

请新建一个单页、响应式的静态站点，视觉风格遵循 Google Material Design 3。页面保持简洁，不添加功能介绍、截图、导航栏或其他内容，只实现以下两项。

## 1. 图标

- 使用当前仓库中的 `docs/icon.png` 作为项目图标，将其复制到新站点的静态资源目录；不要重画、变形、裁剪或添加文字。
- 将图标作为页面的主视觉元素，水平和垂直方向居中。桌面端建议显示为 `192–240px`，移动端建议显示为 `144–192px`，完整保留正方形比例。
- 使用 Material 3 的大圆角、柔和阴影和充足留白。页面色彩可从图标提取：浅蓝用于背景或 surface，陶土棕用于 primary，暖黄用于少量强调。同时支持浅色和深色模式，确保对比度和可读性。
- 同一张图标还要配置为站点 favicon、`apple-touch-icon` 和 Open Graph 预览图，让浏览器、搜索引擎及 OpenRouter 等外部服务能够从公开域名读取项目图标。
- 图片必须包含有意义的替代文本，例如 `alt="应用图标"`。

## 2. 访问 GitHub 的下载链接

- 在图标下方放置一个 Material 3 Filled Button，按钮文案为“前往 GitHub 下载”，可使用 Material Symbols 的 `download` 图形作为前缀。
- 按钮必须链接到：`https://github.com/Ayuilos/Miffan/releases/latest`。
- 点击后在新标签页打开，并配置 `target="_blank"` 和 `rel="noopener noreferrer"`。
- 按钮需要具备 Material 3 的状态反馈，包括 hover、focus、pressed 和键盘焦点；在移动端保证至少 `48px` 的可点击高度。
- 验收时确认链接不会指向站点内部或直接绑定某个版本号，应始终进入 GitHub 上的最新 Release 页面。
