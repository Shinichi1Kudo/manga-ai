# CHG-20260510-026 首页我的系列列表加载提速

## 背景

首页“我的系列”一页只显示 9 个系列，但列表加载体感需要数秒。日志显示列表请求读出了系列和角色表中的大字段，同时首页 HTML 渲染前还额外请求了 100 条系列数据，只用于查找处理中系列。

## 问题

1. 首页首屏渲染前先请求 `/v1/series/list?page=1&pageSize=100`，增加了一次后端等待。
2. 页面同步加载 SockJS/STOMP CDN 脚本，列表请求要等脚本加载后才开始。
3. 后端 `/v1/series/list` 使用实体默认 `SELECT *`，会读取 `outline`、`character_intro`、`global_style_prompt` 等大字段。
4. 角色数量统计查询也读取了 `role` 表完整字段，只需要 `series_id`。

## 改动

1. 首页 HTML 不再预查 100 条系列，直接渲染页面并由列表接口加载当前页。
2. 处理中系列 ID 改为从当前页列表结果同步。
3. SockJS/STOMP 改为仅当前页存在处理中系列时动态加载，不阻塞首屏列表请求。
4. 后端系列列表改为专用聚合 SQL，一次返回卡片字段和角色数量。
5. `background` 只返回首页卡片需要的摘要长度，避免传输完整背景文本。
6. 新增启动期幂等索引检查：
   - `idx_series_user_deleted_created`：优化首页按用户、删除状态、创建时间分页排序。
   - `idx_role_series_deleted`：优化角色数量聚合。

## 影响范围

- `frontend/apps/series/views.py`
- `frontend/templates/series/series_list.html`
- `backend/src/main/java/com/manga/ai/series/service/impl/SeriesServiceImpl.java`
- `backend/src/main/java/com/manga/ai/series/mapper/SeriesMapper.java`
- `backend/src/main/resources/mapper/SeriesMapper.xml`
- `backend/src/main/java/com/manga/ai/common/config/SchemaMigrationConfig.java`
- `backend/src/main/resources/schema.sql`

## 验证

- `python3 manage.py check`
- 首页脚本语法检查通过。
- `mvn -DskipTests compile`
- 后端日志确认首页列表 SQL 已改为轻量聚合查询。
- 接口返回体从约 64KB 降到约 8KB；后端列表热请求约 0.2-0.6 秒，前端代理热请求约 0.3-0.6 秒。
- 前后端已重启。
