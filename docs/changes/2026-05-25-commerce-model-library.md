# CHG-20260525-001 电商模特库与锁定模特沿用

## 背景

电商系列里的模特以前只能在当前系列内创建。用户在多个系列里复用同一位已锁定模特时，需要重新创建、重新生成和重新锁定，既慢也浪费积分。

## 改动

- 电商系列详情右侧在“场景库”下方新增“模特库”入口。
- 新增模特库工作区，展示当前用户所有系列里已锁定的模特。
- 模特库卡片展示模特图、名称、来源系列、性别/类型/人种/身材、比例、创建时间和提示词摘要。
- 属于当前系列的模特显示“已在当前系列”，其他系列的锁定模特可点击“沿用到当前系列”。
- 沿用逻辑为复制一条新的 `commerce_model` 到当前系列，状态直接为 `locked`，不重新生成图片、不扣积分。
- 后端新增：
  - `GET /v1/commerce/series/{seriesId}/model-library`
  - `POST /v1/commerce/series/{seriesId}/models/reuse/{sourceModelId}`
- `CommerceModelVO` 新增 `seriesName` 和 `inTargetSeries`，用于前端展示来源系列和当前系列状态。

## 影响范围

- `README.md`
- `docs/changes/README.md`
- `frontend/apps/series/urls.py`
- `frontend/apps/series/views.py`
- `frontend/templates/commerce/series_detail.html`
- `frontend/templates/commerce/model_library.html`
- `frontend/apps/series/tests.py`
- `backend/src/main/java/com/manga/ai/commerce/controller/CommerceController.java`
- `backend/src/main/java/com/manga/ai/commerce/dto/CommerceModelVO.java`
- `backend/src/main/java/com/manga/ai/commerce/service/CommerceService.java`
- `backend/src/main/java/com/manga/ai/commerce/service/impl/CommerceServiceImpl.java`
- `backend/src/test/java/com/manga/ai/commerce/controller/CommerceControllerTest.java`
- `backend/src/test/java/com/manga/ai/commerce/service/impl/CommerceServiceImplTest.java`

## 验证

- `python manage.py test apps.series.tests.CommerceFlowTests --keepdb`
- `mvn -Dtest=CommerceServiceImplTest,CommerceControllerTest test`
- `git diff --check`

## 注意

- 模特沿用是复制，不是移动。原系列里的模特不会被修改。
- 只有 `locked` 且有基础图的模特可以沿用。
- 当前系列已有的模特在模特库里只展示“已在当前系列”，避免同一条模特记录重复沿用自身。
