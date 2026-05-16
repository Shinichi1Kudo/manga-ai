# CHG-20260517-001 剧集详情分镜列表加载性能优化

## 背景

剧集制作“查看详情”此前已改为并发请求剧集基础信息、分镜、场景、道具和角色资产，但页面仍需要等待 `/api/v1/shots/episode/{episodeId}` 返回后才能完成服务端渲染。该接口会读取分镜主表的所有字段，并继续携带若干生成阶段使用的大字段，容易拖慢首屏文字和分镜卡片显示。

## 改动

- 后端分镜列表入口改用轻量查询：
  - 新增 `ShotMapper.selectEpisodeDetailList(episodeId)`。
  - 只选择详情页首屏和内联编辑需要的字段。
  - 不再在列表响应里携带 `charactersJson`、`propsJson`、`referencePrompt`、`userPrompt` 等生成阶段大字段。
- 分镜列表仍保留角色、道具、场景和视频状态展示能力：
  - 角色、道具、场景缩略图继续通过批量查询关联表和资产表组装。
  - 道具缩略图优先使用 `shot_prop`、`prop`、`prop_asset` 关系数据，不依赖 `propsJson`。
  - 道具资产查询只覆盖当前分镜实际关联到的 `prop_id`，避免详情页入口扫描整部系列道具。
- Django 详情页改为优先从轻量分镜响应里的 `props` 收集本集道具引用，继续保留旧 `propsJson` 兜底。
- 新增剧集详情分镜列表链路复合索引：
  - `shot(episode_id, is_deleted, status, shot_number)`
  - `shot_character(shot_id, role_id)`
  - `shot_prop(shot_id, prop_id)`
  - `scene_asset(scene_id, is_active)`
  - `role_asset(role_id, clothing_id, is_active)`
  - `prop_asset(prop_id, is_active, episode_id, version)`
  - `shot_video_asset(shot_id, is_active, version)`
- 本地/dev 启动兜底迁移同步补齐上述索引。
- Django 并发请求聚合改为按完成顺序收集 future 结果，减少结果收集阶段的串行等待。

## 涉及文件

- `backend/src/main/java/com/manga/ai/shot/mapper/ShotMapper.java`
- `backend/src/main/java/com/manga/ai/shot/service/impl/ShotServiceImpl.java`
- `backend/src/main/java/com/manga/ai/common/config/SchemaMigrationConfig.java`
- `backend/src/main/resources/db/migration/V2026.05.17__Optimize_Episode_Detail_Shot_List.sql`
- `backend/src/main/resources/schema.sql`
- `backend/src/main/resources/mysql_episode_tables.sql`
- `backend/src/test/java/com/manga/ai/shot/service/impl/ShotServiceImplTest.java`
- `frontend/apps/series/views.py`
- `frontend/apps/series/tests.py`

## 行为变化

- 剧集详情页 URL、首屏内容和编辑交互保持不变。
- 分镜列表接口响应更轻，不再返回未用于列表页的大字段。
- 顶部道具区仍能根据分镜关联的 `props` 显示本集相关未锁定道具。
- 详情页入口仍由 Django 服务端渲染，但后端分镜列表查询和资产映射更贴合页面实际需要。

## 验证状态

当前状态：已完成自动化验证。

已执行：

```bash
mvn -Dtest=ShotServiceImplTest test
mvn -DskipTests compile
python3 manage.py check
python3 manage.py test apps.series.tests
```

结果：

- `ShotServiceImplTest`：17 个测试通过。
- `mvn -DskipTests compile`：编译通过。
- `python3 manage.py check`：无系统检查问题；本地 Python 仍有 urllib3/LibreSSL 环境提示。
- `apps.series.tests`：45 个测试通过。

待部署后可用真实数据补充接口耗时对比：

```bash
curl -w 'shots time=%{time_total} size=%{size_download} code=%{http_code}\n' http://127.0.0.1:8081/api/v1/shots/episode/<episodeId>
curl -b 'sessionid=<local-session>' -w 'django_page_auth time=%{time_total} size=%{size_download} code=%{http_code}\n' http://127.0.0.1:8000/<seriesId>/episodes/<episodeId>/
```
