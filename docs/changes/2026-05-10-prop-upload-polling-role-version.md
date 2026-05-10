# CHG-20260510-022 道具上传、轮询收敛与角色默认版本标记

## 背景

剧集详情页有三个体验问题：

- 道具资产只能走 AI 生成，缺少用户自己上传图片的入口。
- 页面资产都稳定后，前端仍持续请求分镜列表和道具列表接口。
- 角色资产只显示“已锁定”，用户看不出当前生效默认角色是哪张图、哪个版本。

## 改动

1. 道具资产支持本地上传。
   - 新增后端接口：`POST /v1/props/upload`、`POST /v1/props/{propId}/upload`。
   - 上传图片会写入 `prop_asset` 新版本，设为当前激活，状态为待审核。
   - 前端新增上传入口，图片不是 1:1 时会弹出裁剪框，用户确认后上传正方形 PNG。

2. 前端轮询改成按需启动。
   - 道具/场景状态轮询只在存在生成中资产时启动，队列清空后自动停止。
   - `/api/v1/props/series/{seriesId}/?episodeId=...` 改为生成中或刚发生资产变化时短轮询，稳定两轮后停止。
   - `/api/v1/shots/episode/{episodeId}/` 改为有视频生成中或资产变化时刷新绑定，稳定后停止。

3. 角色资产增加当前版本提示。
   - 系列角色资产接口返回 `assetId`、`version`、`active`、`defaultClothing`。
   - 前端角色卡片展示“当前默认/当前预览 + 服装名 + 版本号”。
   - 默认角色缩略图角标显示勾选标记。

## 影响范围

- `backend/src/main/java/com/manga/ai/prop/controller/PropController.java`
- `backend/src/main/java/com/manga/ai/prop/service/PropService.java`
- `backend/src/main/java/com/manga/ai/prop/service/impl/PropServiceImpl.java`
- `backend/src/main/java/com/manga/ai/asset/dto/SeriesRoleAssetsVO.java`
- `backend/src/main/java/com/manga/ai/asset/service/impl/AssetServiceImpl.java`
- `frontend/api/backend_client.py`
- `frontend/apps/series/views.py`
- `frontend/apps/series/urls.py`
- `frontend/templates/episode/episode_detail.html`

## 验证

- `python3 manage.py check`
- `mvn -DskipTests compile`
- 前后端已重启。
