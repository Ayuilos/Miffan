# 蓝色大肥鱼头像素材

## 当前选定状态与透明动画

用户已选 A（默认微笑）、C（摸摸满足）、F（得意完成）、I（惊喜提醒）、
K（扒饭）、L（鼓腮嚼饭）、M（蚊香眼思考），并用 O（歪头打盹）替代 J 的
轻微困倦方向。只有一个乐观、贪吃、偶尔迷糊的性格，不提供多个性格选项。

表情候选和实际提示词在 `output/whale-girl-expressions-v2/`；当前八段视频、
提交和计费记录、去底脚本及 QA 在 `output/whale-girl-motion-v2/`。
APP 使用保留 alpha 的一套素材同时适配浅色和深色页面，不再显示头像底色方块。
生成模型给出普通视频，透明通道由本地视频后处理产生；白发箍、米饭、饭碗、
细发丝和鼻泡必须在浅深背景逐帧检查。桌面图标也复用透明微笑头部，底色由
adaptive icon 提供。旧版纯色素材仅供历史记录，不再作为默认角色表情。

## 历史：首版纯色头像

使用内置 `image_gen` 工具，直接参考原插画生成头部，再分别替换纯色背景。用户选择保留完整纯色头像与图标，因此素材为不透明 PNG；不提供以实心矩形冒充角色轮廓的单色桌面图标。

- 参考图：[蓝色大肥鱼原插画](https://github.com/YunYueSama/codex-deepseek-pet/blob/main/assets/%E4%BD%A0%E8%BF%99%E5%90%83%E7%99%BD%E9%A5%AD%E7%9A%84%E8%93%9D%E8%89%B2%E5%A4%A7%E8%82%A5%E9%B1%BC.png)
- 历史浅色素材：`output/whale-girl-motion-v2/legacy-app-assets/whale_girl_head.png`
- 历史深色素材：`output/whale-girl-motion-v2/legacy-app-assets/whale_girl_head_deep_sea.png`

首版保留蓝色层次发丝、弯曲刘海与呆毛、白色褶边发箍、两侧鲸鳍、侧边蝴蝶结、水光眼睛、腮红及委屈嘴型。以下提示词仅记录当时的生成过程；当前应用与桌面图标已替换为上方的乐观表情和透明素材。

## 头部提取与还原

```text
Use case: background-extraction / identity-preserve.
Input image 1 is the EDIT TARGET, the EXACT character the user wants, not a loose style reference.
Primary request: faithfully extract and finish ONLY THIS EXACT blue-haired whale girl's HEAD as a premium transparent PNG app avatar. Preserve the original illustration's face and rendering as closely as possible, like a careful head cutout and restoration. Do NOT redesign, simplify, stylize into a flat vector mascot or change her identity.
Keep precisely: the same face shape and proportions, enormous glossy watery sapphire eyes with multilayer iris highlights and fine dark eyelashes, the same slightly worried eyebrows, pink blush and tiny trembling pout; long layered indigo/periwinkle blue bangs and beautifully shaded hair strands with pale-blue tips, the tall crescent loop ahoge, white frilled maid headdress with lavender fabric shadows, dark blue drooping whale-fin ears with white frilled undersides, and the small sky-blue bow on the viewer's right temple. Match the reference's exact detailed anime/chibi illustration quality, line weight, colors, lighting, expressive cuteness and slightly angled face pose. Retain teardrop highlights at the lower eyelids as in the original.
Remove ALL text, speech bubbles, pointing hand, rice bowl, sparkles, question marks, whole body, neck, collar, clothes, arms, hands, torso and large tail. Retain only the head plus hair framing it, with natural rounded curled hair ends finishing just below the chin. No neck or bust. No crop through the ahoge or side fins.
Composition: single isolated head centered on a square 1024x1024 canvas, head+hair occupying about 86% of the square, transparent margin all around. Maintain generous room for complete ahoge and both fins. Genuine fully transparent RGBA background, no checkerboard drawn into image, no white matte, no drop shadow, no border or sticker outline. Preserve anti-aliased hair edges.
This is a faithful high-detail head asset of the attached character, not a new mascot design.
```

## 浅蓝背景

```text
Precise background-only edit. Input image is the EDIT TARGET. Preserve the anime girl's head EXACTLY: the same detailed illustration, eyes, face, tears, pout, hair strands, headdress, bow, ahoge, ears, proportions, size, centered placement and all colors. Replace ALL white/gray checkerboard background pixels with a single perfectly uniform flat pale periwinkle blue RGB #E8EEFF. This includes every gap inside the ahoge and hair curls. The entire background must be solid #E8EEFF, completely opaque, no gradients, no texture, no checkerboard, no shadow. Keep the artwork otherwise unchanged. Output a complete square portrait image with ordinary square corners, no rounded-square outline, no frame, no UI mockup, no text. Only the original girl's head on the flat pale-blue background.
```

## 深海背景

```text
Precise background-only edit. Input image is the EDIT TARGET. Preserve the anime girl's head EXACTLY: the same detailed illustration, eyes, face, tears, pout, hair strands, headdress, bow, ahoge, ears, proportions, size, centered placement and all colors. Replace ALL white/gray checkerboard background pixels with a single perfectly uniform flat deep midnight blue RGB #162441. This includes every gap inside the ahoge and hair curls. The entire background must be solid #162441, completely opaque, no gradients, no texture, no checkerboard, no shadow. Keep the artwork otherwise unchanged, especially original BRIGHT blue hair and pale white skin and fabric: do not darken or relight the character for the dark background. Output a complete square portrait image with ordinary square corners, no rounded-square outline, no frame, no UI mockup, no text. Only the original girl's head on the flat deep-blue background.
```
