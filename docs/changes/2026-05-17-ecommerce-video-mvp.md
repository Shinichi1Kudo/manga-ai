# CHG-20260517-015 电商带货 MVP 工作流

## 背景

首页已有“电商带货”待上线入口。第一版电商带货需要围绕真人写实服装模特建立独立流程：一个模特维护多个商品，生成口播种草或试穿展示视频。商品不应沉淀为角色服装版本，避免一个模特下出现大量服装 Tab。

## 改动

- 新增电商带货设计实施计划文档。
- 后端新增 `commerce` 模块和数据表：
  - 电商系列
  - 电商模特
  - 电商商品
  - 带货视频项目
  - 带货分镜
- 电商系列默认品类为服装，默认视觉风格为真人写实，默认画幅为 `3:4`。
- 电商模特支持基础形象图和锁定状态，不提供生成新服装或服装版本管理。
- 商品挂在模特下，商品图作为视频生成参考图。
- 从商品创建口播种草或试穿展示项目时，生成 5 个轻量分镜。
- 电商视频生成直接提交“模特基础图 + 商品图”两张参考图给视频模型。
- 首页“我的系列”拆分展示短剧系列和电商系列，电商入口从待上线变为可进入。
- Django BFF 新增电商系列、模特、商品、项目和生成视频转发路由。
- 新增电商页面：
  - 电商系列列表
  - 创建电商系列
  - 电商系列详情
  - 模特详情
  - 带货项目分镜详情

## 影响范围

- `backend/src/main/java/com/manga/ai/commerce/**`
- `backend/src/main/resources/db/migration/V2026.05.17.3__Create_Commerce_Video_Tables.sql`
- `backend/src/test/java/com/manga/ai/commerce/service/impl/CommerceServiceImplTest.java`
- `frontend/apps/series/views.py`
- `frontend/apps/series/urls.py`
- `frontend/apps/series/tests.py`
- `frontend/templates/commerce/**`
- `frontend/templates/series/series_list.html`
- `docs/superpowers/plans/2026-05-17-ecommerce-video.md`

## 验证

- `python3 manage.py test apps.series.tests.CommerceFlowTests apps.series.tests.CommerceHomeGroupingTests`
- `python3 manage.py check`
- `mvn -Dtest=CommerceServiceImplTest test`
- `mvn -q -DskipTests compile`

## 注意

第一版仍是 MVP 页面闭环。模特 AI 生图和真人图片上传可以继续复用现有上传/图片生成能力深化；当前电商模特页面先接收基础形象图 URL，避免把商品服装写入角色服装资产。
