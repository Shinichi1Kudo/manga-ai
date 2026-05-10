# CHG-20260510-021 分镜拖拽排序与名称保存提速

## 背景

用户反馈拖动分镜修改顺序后，分镜名称更新有点慢。日志显示第 1 集 12 个分镜排序时，后端逐个分镜更新编号，单次排序会连续执行 12 次更新，整体耗时明显。

## 根因

- 前端拖拽结束后等待排序接口返回，才更新默认分镜名显示，用户会感觉名称变化滞后。
- 后端排序接口先逐个 `selectById` 校验每个分镜，再逐个 `update` 编号；网络数据库延迟下，分镜越多越慢。
- 拖拽没改变顺序时也会发起保存请求。
- 清空自定义分镜名时，前端发送 `null`，后端 DTO 无法区分“没传字段”和“传空名称”，默认名恢复不够可靠。

## 改动

- 拖拽开始记录原始顺序，拖拽结束后如果顺序没变，不再请求后端。
- 拖拽顺序变化后，前端立即更新分镜编号和默认名称显示，不等待后端接口。
- 排序保存失败时，前端会恢复到拖拽前顺序并重新更新编号显示。
- 后端新增 `selectOrderFieldsByEpisodeId`，一次查询当前剧集下的分镜最小排序字段用于校验。
- 后端新增 `batchUpdateShotNumbers`，用单条 `CASE WHEN` SQL 批量更新变化过的分镜编号。
- 后端排序只更新编号真正变化的分镜；顺序未变化时直接返回。
- 分镜名称保存增加轻量保存中状态；清空名称时前端发送空字符串，后端统一转成 `NULL`，恢复默认分镜名。
- 分镜名称单字段保存走专用 SQL，只更新 `shot_name` 和 `updated_at`，避免读取并回写整条分镜。

## 影响范围

- `frontend/templates/episode/episode_detail.html`
- `backend/src/main/java/com/manga/ai/shot/mapper/ShotMapper.java`
- `backend/src/main/java/com/manga/ai/shot/service/impl/ShotServiceImpl.java`

## 验证

- `mvn -DskipTests compile` 通过。
- `python3 manage.py check` 通过。
- 已重启后端 `8081` 和前端 `8000`。
- 使用第 1 集 12 个分镜当前顺序原样提交排序，接口快速返回，后端识别为“分镜排序未变化”。
- 后端排序接口 no-op 验证耗时约 `646ms`；旧日志中同样 12 个分镜会逐条更新，整体约数秒。

