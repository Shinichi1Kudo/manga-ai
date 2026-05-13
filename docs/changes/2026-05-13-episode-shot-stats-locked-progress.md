# CHG-20260513-001 剧集详情顶部统计按已锁定进度展示

## 背景

剧集详情页顶部原有统计包含“分镜总数、已完成、待生成、完成进度”。用户希望把“已完成”改成“已生成”，增加“已锁定”，并且“完成进度”应表达审核锁定进度，而不是视频生成进度。

## 改动

- 顶部统计卡片从 4 项改为 5 项：分镜总数、已生成、待生成、已锁定、完成进度。
- “已生成”按 `generationStatus == 2` 统计，保留视频生成完成口径。
- “已锁定”按分镜审核状态 `status == 1` 统计。
- “完成进度”改为 `已锁定分镜数 / 分镜总数`。
- 前端新增统一统计刷新函数，锁定、解锁、删除、生成中、手动上传和接口同步后会同步刷新顶部统计。
- 后端分镜生成状态文案将 `generationStatus == 2` 从“已完成”调整为“已生成”，避免和锁定进度混淆。

## 影响范围

- `frontend/templates/episode/episode_detail.html`
- `frontend/apps/series/views.py`
- `backend/src/main/java/com/manga/ai/shot/dto/ShotDetailVO.java`

## 验证

- `python3 manage.py check`
- `node --check /tmp/episode_detail_script_check.js`
- `mvn -DskipTests compile`
- 重启前端 `8000` 与后端 `8081`
