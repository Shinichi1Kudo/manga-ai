# CHG-20260510-020 分镜视频生成状态跨标签同步

## 背景

用户在系列 `替嫁狂妃：王爷请自重` 第 2 集 `回门打脸，霸气护妻` 的分镜 7 点击生成后，当前页面显示 `生成中`，但新开标签页没有同样显示生成中；同时生成中状态下按钮仍可能被再次点击。

## 根因

- 重新生成已有视频时，分镜可能保留旧 `videoUrl` 或旧缩略图。模板原先优先判断旧缩略图，再判断 `generationStatus == 1`，导致新标签页虽然拿到了后端生成中状态，也可能先展示旧预览区域。
- 当前页点击生成后主要依赖前端乐观 UI，生成按钮只锁住当前点击按钮，没有统一锁住同一分镜卡片内的生成/重新生成操作。
- 后端生成接口缺少“正在生成中”的幂等保护，极短时间内重复提交可能重复扣积分或重复启动任务。
- 剧集进度接口只返回 `generationStatus`，前端轮询时无法拿到生成开始时间来恢复跨标签计时。

## 改动

- 分镜模板优先渲染 `generationStatus == 1` 的生成中占位，再显示旧缩略图或旧视频。
- 分镜卡片增加稳定的 `shot-thumbnail`、`shot-actions`、`generation-status` 选择器，便于前端按后端状态统一更新 UI。
- 点击生成/重新生成后，同一分镜卡片的生成相关按钮会统一禁用，并临时收起预览、下载、历史等操作，只保留生成中计时。
- 页面轮询和资产刷新接口都会调用统一的 `syncShotGenerationStatus`，新开标签页或旧标签页都按后端 `generationStatus` 校准显示。
- 前端请求响应解析改为先读文本再解析 JSON，后端异常返回 HTML 时不再直接提示 `Unexpected token '<'`。
- 后端 `generateVideo` 和 `generateVideoWithReferences` 增加生成中重复提交保护；并用带条件的状态更新防止并发窗口重复提交，失败时返还本次扣除积分。
- 剧集进度接口的每个分镜进度增加 `generationStartTime`，前端可用它恢复跨标签生成计时。

## 影响范围

- 后端：
  - `backend/src/main/java/com/manga/ai/shot/service/impl/ShotServiceImpl.java`
  - `backend/src/main/java/com/manga/ai/episode/dto/EpisodeProgressVO.java`
  - `backend/src/main/java/com/manga/ai/episode/service/impl/EpisodeServiceImpl.java`
- 前端：
  - `frontend/templates/episode/episode_detail.html`

## 验证

- `mvn -DskipTests compile` 通过。
- `python3 manage.py check` 通过。
- 已重启后端 `8081` 和前端 `8000`。
- 接口确认分镜 `707` 当前生成任务已完成：`generationStatus=2`，`videoUrl` 已存在。
- 进度接口已返回分镜 `707` 的 `generationStartTime` 字段。

