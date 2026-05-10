# CHG-20260510-031 场景资产支持本地上传与按比例裁剪

## 背景

剧集详情页的道具资产已经支持本地上传图片并裁剪，但场景资产仍只能通过“添加场景/重新生成场景”调用图片生成服务。用户需要像道具资产一样，直接上传本地场景图，并且图片比例要与添加场景里的比例选择一致。

## 改动

- 场景资产区新增“上传场景图片”按钮。
- 单个场景卡片新增上传图标，可为已有场景上传新版本。
- 上传场景弹窗复用添加场景的图片比例选项：`16:9`、`9:16`、`4:3`、`3:4`、`3:2`、`2:3`、`21:9`、`1:1`。
- 前端上传前会按所选比例裁剪图片；已符合比例的图片也会统一转成裁剪后的 PNG 再上传。
- 后端新增场景上传接口，上传后直接保存为新的 `scene_asset` 版本并设为待审核当前版本，不调用火山引擎。
- 后端同时校验文件类型、大小和图片比例，避免绕过前端上传错误比例图片。
- 上传成功后会更新场景卡片、清理历史版本缓存，并刷新分镜内联场景资产绑定。

## 影响范围

- `backend/src/main/java/com/manga/ai/scene/controller/SceneController.java`
- `backend/src/main/java/com/manga/ai/scene/service/SceneService.java`
- `backend/src/main/java/com/manga/ai/scene/service/impl/SceneServiceImpl.java`
- `frontend/apps/series/urls.py`
- `frontend/apps/series/views.py`
- `frontend/templates/episode/episode_detail.html`

## 验证

- `mvn -DskipTests compile`
- `python3 manage.py check`
- `node --check /tmp/episode_detail_script_check.js`

验证时间：2026-05-10 23:15:04 CST
