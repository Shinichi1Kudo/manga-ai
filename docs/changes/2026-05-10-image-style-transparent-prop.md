# CHG-20260510-015 图片生成系列风格与道具透明背景兜底

## 背景

用户反馈：

“添加道具、添加场景、解析剧本和重新解析识别出的道具和场景，在点击生成图片的时候，后台调用火山引擎生成图片有没有结合当前系列的系列风格？没有的话补充上。系列明明是超精细 3D 动漫，生成出来却是真人风格。道具背景后来确认透明背景更合适。”

## 原因

- 场景/道具的部分生成入口会把系列风格写进初始 prompt，但最终调用火山引擎时只直接使用 `customPrompt`，没有在发图层做统一兜底。
- LLM 生成提示词时只是要求“包含风格关键词”，约束不够硬，模型可能把“超精细 3D 动漫”漂移成真人、写实摄影或 live-action。
- 道具提示词里存在 `isolated on white background` 这类白底描述，不适合后续和分镜/场景贴合。

## 改动

- `ImageGenerateServiceImpl` 在最终调用火山图片接口前统一追加系列风格兜底：
  - `strict series visual style: <styleKeywords>`
  - 动漫/3D/卡通风格会追加 `ultra-detailed 3D anime render, stylized 3D animation`
  - 同时追加 `not live action, not photorealistic, not real person`
- 场景图最终提示词继续强制为空场景背景：
  - 禁止人物、角色、人像。
- 道具图最终提示词改为透明背景抠图：
  - `transparent background, isolated clean cutout, alpha channel style`
  - 去掉旧 prompt 中的白底、纯白、浅色背景等残留背景词后再追加透明背景要求。
  - 禁止手、人物、桌面、房间、额外物体和环境背景。
- `SceneServiceImpl` 和 `PropServiceImpl` 的所有场景/道具图片生成入口都补齐 `styleKeywords` 传递：
  - 首次生成
  - 重新生成
  - LLM 提示词生成后点击生成图片
  - 剧本解析/重新解析识别出的场景和道具批量生成
- `ImagePromptGenerateServiceImpl` 收紧 LLM 提示词规则：
  - 必须严格沿用系列风格。
  - 动漫/3D 动漫系列不得生成真人、写实摄影或 live-action。
  - 道具提示词要求透明背景抠图，不要白底、纯色背景、桌面、房间、渐变或纹理背景。

## 涉及文件

- `backend/src/main/java/com/manga/ai/image/service/impl/ImageGenerateServiceImpl.java`
- `backend/src/main/java/com/manga/ai/llm/service/impl/ImagePromptGenerateServiceImpl.java`
- `backend/src/main/java/com/manga/ai/scene/service/impl/SceneServiceImpl.java`
- `backend/src/main/java/com/manga/ai/prop/service/impl/PropServiceImpl.java`

## 行为变化

- 当前系列设置为“超精细 3D 动漫”时，场景和道具图片生成会在最终发给火山引擎的 prompt 中明确带上该系列风格，并追加反真人/反写实约束。
- 道具资产更适合作为可复用素材：默认透明背景、独立单品、居中抠图，不再要求白底。
- 即使 LLM 返回的提示词漏掉风格或误带白底，最终发图层也会兜底修正。

## 验证状态

当前状态：已编译并重启后端。

已执行：

```bash
mvn -DskipTests compile
tmux kill-session -t aiwork-backend
tmux new-session -d -s aiwork-backend ...
lsof -nP -iTCP:8081 -sTCP:LISTEN
```

验证结果：

- Java 编译通过。
- 后端已重启，`8081` 正常监听，新进程 PID 为 `47146`。
