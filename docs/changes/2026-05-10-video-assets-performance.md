# CHG-20260510-003 我的资产库影视资产接口优化

## 背景

“我的资产库 -> 影视资产”对应链路：

- 前端：`/api/series/{seriesId}/video-assets/`
- 后端代理：`frontend/apps/asset/views.py`
- Java 后端：`/api/v1/series/{id}/video-assets`

排查发现有两个问题：

- Java 后端 `SeriesServiceImpl#getSeriesVideoAssets` 在组装影视资产时，会对每个分镜调用一次 `shotVideoAssetMapper.selectActiveByShotId(shotId)`。分镜数量变多后，这会形成 N+1 查询。
- 日志中出现 `RedisCommandTimeoutException: Command timed out after 30 second(s)`。也就是说请求有时不是卡在影视资产查询本身，而是卡在进入接口前的 Redis token 校验。

## 改动

- 在 `ShotVideoAssetMapper` 新增 `selectActiveByShotIds(List<Long> shotIds)`，一次批量查询所有分镜的激活视频资产。
- 在 `SeriesServiceImpl#getSeriesVideoAssets` 中：
  - 批量查询系列下所有剧集的分镜。
  - 按 `episodeId` 在内存中分组分镜。
  - 批量查询所有分镜的激活视频资产。
  - 用 `shotId -> ShotVideoAsset` Map 替代逐分镜查询。
- 保留旧兼容逻辑：如果 `shot_video_asset` 没有激活记录，仍回退读取 `shot.videoUrl` 和 `shot.thumbnailUrl`。
- 前端代理层已经对影视资产响应加入 30 秒短缓存，减少重复切换标签时的请求压力。
- Redis 认证卡顿另见 [Redis 连接保活与认证快速失败](./2026-05-10-redis-keepalive-auth.md)。

## 涉及文件

- `backend/src/main/java/com/manga/ai/series/service/impl/SeriesServiceImpl.java`
- `backend/src/main/java/com/manga/ai/shot/mapper/ShotVideoAssetMapper.java`
- `frontend/apps/asset/views.py`

## 行为变化

- 影视资产接口从“每个分镜额外查一次激活视频资产”改为“所有分镜一次批量查询”。
- 返回结构保持不变，前端渲染逻辑不用调整。
- 对分镜多、剧集多的系列，接口耗时应明显下降。
- 如果卡顿来自 Redis token 校验，影视资产 SQL 优化本身无法完全解决，需要同时启用 Redis 连接保活与快速失败。

## 验证状态

当前状态：已验证。

已执行：

```bash
mvn -DskipTests compile
mvn spring-boot:run -DskipTests
curl -w '\nTOTAL_TIME=%{time_total}\n' -o /tmp/video-assets-79-new.json -s --max-time 10 http://127.0.0.1:8081/api/v1/series/79/video-assets
```

验证结果：

- 后端编译通过。
- 后端重启成功，PID `39894`，`8081` 正常监听。
- `替嫁狂妃：王爷请自重`（seriesId=79）影视资产接口耗时约 `0.59s`。
- 日志确认已从逐分镜查询改为批量查询：`SELECT * FROM shot_video_asset WHERE is_active = 1 AND shot_id IN (...)`。

## 注意事项

- `selectActiveByShotIds` 使用 MyBatis 动态 SQL，已通过编译和接口请求验证。
- 如果传入空 `shotIds`，当前服务层不会调用该 Mapper 方法，避免生成空 `IN ()`。
- 如果数据库里异常存在同一分镜多条激活资产，Map 合并策略保留第一条。
