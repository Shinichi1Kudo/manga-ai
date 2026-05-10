# CHG-20260510-029 道具上传后历史版本与回滚修复

## 背景

在 `锦衣藏娇：将军的掌心娇` 系列的第 1 集 `大婚惊变，洞房花烛夜藏刀` 中，道具 `佩剑“破军”` 上传新图后，卡片展示了版本 2，但历史版本弹窗看不到版本 1。

日志确认版本 1 的 `prop_asset.episode_id` 为空，版本 2 绑定了当前剧集 `episode_id=13`。此前历史弹窗复用了当前剧集详情接口，未锁定道具会只返回当前剧集资产，因此把早期未归属剧集的版本 1 过滤掉了。

## 改动

- 后端道具详情接口新增 `includeHistory=true` 查询参数。
- 普通详情和道具列表仍只返回当前剧集可见资产，继续保持“待审核只在本集可见”的隔离规则。
- 历史模式会返回当前剧集资产和早期 `episode_id` 为空的旧资产，确保旧版本能查看、预览和回滚。
- 道具回滚接口新增当前 `episodeId` 透传；当用户回滚到旧的未归属版本时，后端会把该版本补齐到当前剧集，避免回滚成功后详情刷新又被剧集过滤挡掉。
- 当前卡片展示规则调整为：有生成中占位时优先展示生成中；否则尊重后端 `isActive` 版本，保证历史回滚后的当前版本能正确显示。

## 影响范围

- `backend/src/main/java/com/manga/ai/prop/controller/PropController.java`
- `backend/src/main/java/com/manga/ai/prop/service/PropService.java`
- `backend/src/main/java/com/manga/ai/prop/service/impl/PropServiceImpl.java`
- `frontend/apps/series/views.py`
- `frontend/templates/episode/episode_detail.html`

## 验证

- `mvn -DskipTests compile`
- `python3 manage.py check`
- `node --check /tmp/episode_detail_script_check.js`

验证时间：2026-05-10 22:33:25 CST
