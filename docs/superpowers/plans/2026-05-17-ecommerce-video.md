# Ecommerce Video Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first ecommerce video MVP: grouped home entry, ecommerce series, models, products, lightweight scripts, and direct model-image + product-image video generation.

**Architecture:** Add an isolated backend `commerce` module with its own tables and APIs, while reusing existing OSS upload, Seedance video generation, credit accounting, and user context patterns. Add Django BFF routes and server-rendered ecommerce templates that keep the product flow separate from short-drama series. Keep ecommerce model assets as a single base image and do not expose or create clothing-version assets.

**Tech Stack:** Spring Boot 3.2, MyBatis Plus, Flyway SQL migrations, Django server-rendered templates, Tailwind-style utility classes, existing `BackendClient`, existing Seedance video service.

---

## File Structure

- Create backend migration: `backend/src/main/resources/db/migration/V2026.05.17.3__Create_Commerce_Video_Tables.sql`
- Create backend package: `backend/src/main/java/com/manga/ai/commerce/`
- Create backend entities: `CommerceSeries`, `CommerceModel`, `CommerceProduct`, `CommerceProject`, `CommerceShot`
- Create backend DTOs for create/update/list/project requests and VOs.
- Create backend mappers extending `BaseMapper`.
- Create backend service and controller: `CommerceService`, `CommerceServiceImpl`, `CommerceController`
- Create backend tests: `backend/src/test/java/com/manga/ai/commerce/service/impl/CommerceServiceImplTest.java`
- Modify frontend URLs and views: `frontend/apps/series/urls.py`, `frontend/apps/series/views.py`
- Create frontend templates: `frontend/templates/commerce/series_list.html`, `series_create.html`, `series_detail.html`, `model_detail.html`, `project_detail.html`
- Modify home template: `frontend/templates/series/series_list.html`
- Modify frontend tests: `frontend/apps/series/tests.py`
- Add docs change note: `docs/changes/2026-05-17-ecommerce-video-mvp.md`, update `docs/changes/README.md`

## Task 1: Backend Commerce Schema And Domain Skeleton

**Files:**
- Create: `backend/src/main/resources/db/migration/V2026.05.17.3__Create_Commerce_Video_Tables.sql`
- Create: `backend/src/main/java/com/manga/ai/commerce/entity/CommerceSeries.java`
- Create: `backend/src/main/java/com/manga/ai/commerce/entity/CommerceModel.java`
- Create: `backend/src/main/java/com/manga/ai/commerce/entity/CommerceProduct.java`
- Create: `backend/src/main/java/com/manga/ai/commerce/entity/CommerceProject.java`
- Create: `backend/src/main/java/com/manga/ai/commerce/entity/CommerceShot.java`
- Create: `backend/src/main/java/com/manga/ai/commerce/mapper/*.java`
- Test: `backend/src/test/java/com/manga/ai/commerce/service/impl/CommerceServiceImplTest.java`

- [ ] **Step 1: Write failing schema/domain tests**

Add test methods that assert `CommerceServiceImpl` can create a default series and rejects invalid ratios once the service exists:

```java
@Test
void createSeriesDefaultsToClothingRealisticAndThreeFour() {
    UserContextHolder.setUserId(9L);
    CommerceSeriesCreateRequest request = new CommerceSeriesCreateRequest();
    request.setName("小雅女装种草号");

    CommerceSeriesVO result = service.createSeries(request);

    assertThat(result.getCategory()).isEqualTo("clothing");
    assertThat(result.getVisualStyle()).isEqualTo("realistic");
    assertThat(result.getDefaultAspectRatio()).isEqualTo("3:4");
}

@Test
void createSeriesRejectsUnsupportedRatio() {
    UserContextHolder.setUserId(9L);
    CommerceSeriesCreateRequest request = new CommerceSeriesCreateRequest();
    request.setName("小雅女装种草号");
    request.setDefaultAspectRatio("2:1");

    assertThatThrownBy(() -> service.createSeries(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不支持的画幅比例");
}
```

- [ ] **Step 2: Run tests to verify missing classes fail**

Run: `mvn -Dtest=CommerceServiceImplTest test`

Expected: compilation fails because commerce service classes do not exist.

- [ ] **Step 3: Add migration and entity/mapper skeleton**

Create the five commerce tables with `user_id`, ownership indexes, status fields, timestamps, and logical delete where needed. Entities use `@TableName`, `@TableId`, Lombok `@Data`, and camelCase fields matching SQL columns.

- [ ] **Step 4: Run compile**

Run: `mvn -q -DskipTests compile`

Expected: compile passes after skeleton exists.

## Task 2: Backend Commerce Series, Model, And Product APIs

**Files:**
- Create/Modify: `backend/src/main/java/com/manga/ai/commerce/dto/*.java`
- Create/Modify: `backend/src/main/java/com/manga/ai/commerce/service/CommerceService.java`
- Create/Modify: `backend/src/main/java/com/manga/ai/commerce/service/impl/CommerceServiceImpl.java`
- Create/Modify: `backend/src/main/java/com/manga/ai/commerce/controller/CommerceController.java`
- Test: `backend/src/test/java/com/manga/ai/commerce/service/impl/CommerceServiceImplTest.java`

- [ ] **Step 1: Write failing service tests**

Add tests for:

```java
@Test
void createModelRequiresNameAndKeepsBaseImageOnly() {
    UserContextHolder.setUserId(9L);
    CommerceModelCreateRequest request = new CommerceModelCreateRequest();
    request.setName("小雅");
    request.setSourceType("uploaded");
    request.setBaseImageUrl("https://oss.example.com/model.png");
    request.setBodyProfile("身高168，M码");

    CommerceModelVO result = service.createModel(100L, request);

    assertThat(result.getName()).isEqualTo("小雅");
    assertThat(result.getBaseImageUrl()).isEqualTo("https://oss.example.com/model.png");
    assertThat(result.getStatus()).isEqualTo("pending_review");
}

@Test
void createProductRequiresImageAndBelongsToModel() {
    UserContextHolder.setUserId(9L);
    CommerceProductCreateRequest request = new CommerceProductCreateRequest();
    request.setName("冰丝防晒衬衫");
    request.setImageUrl("https://oss.example.com/product.png");
    request.setProductType("上衣");
    request.setSellingPoints("凉感, 显瘦, 通勤");

    CommerceProductVO result = service.createProduct(200L, request);

    assertThat(result.getName()).isEqualTo("冰丝防晒衬衫");
    assertThat(result.getImageUrl()).isEqualTo("https://oss.example.com/product.png");
}
```

- [ ] **Step 2: Run tests to verify red**

Run: `mvn -Dtest=CommerceServiceImplTest test`

Expected: fail on missing service methods or validation.

- [ ] **Step 3: Implement service methods**

Implement list/get/create methods for series, models, products, plus `confirmModel(modelId)`. Enforce current-user ownership through `UserContextHolder`.

- [ ] **Step 4: Implement controller endpoints**

Expose:

```text
GET    /v1/commerce/series
POST   /v1/commerce/series
GET    /v1/commerce/series/{seriesId}
GET    /v1/commerce/series/{seriesId}/models
POST   /v1/commerce/series/{seriesId}/models
POST   /v1/commerce/models/{modelId}/confirm
GET    /v1/commerce/models/{modelId}/products
POST   /v1/commerce/models/{modelId}/products
```

- [ ] **Step 5: Run backend tests**

Run: `mvn -Dtest=CommerceServiceImplTest test`

Expected: new commerce tests pass.

## Task 3: Backend Lightweight Project, Script, And Direct Video Generation

**Files:**
- Modify: `backend/src/main/java/com/manga/ai/commerce/service/impl/CommerceServiceImpl.java`
- Modify: `backend/src/main/java/com/manga/ai/commerce/controller/CommerceController.java`
- Modify: `backend/src/main/java/com/manga/ai/commerce/dto/*.java`
- Test: `backend/src/test/java/com/manga/ai/commerce/service/impl/CommerceServiceImplTest.java`

- [ ] **Step 1: Write failing tests for project creation**

Add tests:

```java
@Test
void createTryOnProjectBuildsFiveShotsAndUsesModelAndProductImages() {
    UserContextHolder.setUserId(9L);
    CommerceProjectCreateRequest request = new CommerceProjectCreateRequest();
    request.setContentType("try_on");
    request.setAspectRatio("3:4");

    CommerceProjectVO result = service.createProject(300L, request);

    assertThat(result.getContentType()).isEqualTo("try_on");
    assertThat(result.getAspectRatio()).isEqualTo("3:4");
    assertThat(result.getShots()).hasSize(5);
    assertThat(result.getShots().get(0).getVisualDescription()).contains("模特").contains("商品");
}

@Test
void createProjectRejectsUnlockedModel() {
    UserContextHolder.setUserId(9L);
    CommerceProjectCreateRequest request = new CommerceProjectCreateRequest();
    request.setContentType("talking");

    assertThatThrownBy(() -> service.createProject(300L, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("请先审核并锁定模特");
}
```

- [ ] **Step 2: Run tests to verify red**

Run: `mvn -Dtest=CommerceServiceImplTest test`

Expected: fail because project methods are absent.

- [ ] **Step 3: Implement project and shot draft generation**

Create projects from a product. Generate five deterministic shots for `talking` and five for `try_on`. Store model image URL and product image URL on the project, not as clothing assets.

- [ ] **Step 4: Implement direct video generation method**

Add `generateProjectVideo(projectId)` that iterates shots and submits each shot with `SeedanceRequest` contents containing two image references: model base image and product image. Set project/shot statuses and friendly failure messages. Reuse `UserVideoGenerationLimiter` and credits if feasible; if not feasible in first pass, keep the endpoint disabled behind validation and return a clear `BusinessException` until credit constants are defined.

- [ ] **Step 5: Expose endpoints**

Expose:

```text
POST /v1/commerce/products/{productId}/projects
GET  /v1/commerce/projects/{projectId}
PUT  /v1/commerce/projects/{projectId}/shots/{shotId}
POST /v1/commerce/projects/{projectId}/generate-video
```

- [ ] **Step 6: Run backend tests**

Run: `mvn -Dtest=CommerceServiceImplTest test`

Expected: project creation and validation tests pass.

## Task 4: Django BFF Routes And Frontend Pages

**Files:**
- Modify: `frontend/apps/series/urls.py`
- Modify: `frontend/apps/series/views.py`
- Create: `frontend/templates/commerce/series_list.html`
- Create: `frontend/templates/commerce/series_create.html`
- Create: `frontend/templates/commerce/series_detail.html`
- Create: `frontend/templates/commerce/model_detail.html`
- Create: `frontend/templates/commerce/project_detail.html`
- Test: `frontend/apps/series/tests.py`

- [ ] **Step 1: Write failing Django tests**

Add tests asserting:

```python
def test_commerce_create_forwards_default_ratio_and_fixed_style(self):
    session = self.client.session
    session['token'] = 'token-1'
    session.save()
    backend_client = Mock()
    backend_client.post.return_value = {'id': 10}

    with patch('apps.series.views.get_client', return_value=backend_client):
        response = self.client.post('/commerce/series/create/', {'name': '小雅女装种草号'})

    self.assertEqual(response.status_code, 302)
    backend_client.post.assert_called_once_with('/v1/commerce/series', {
        'name': '小雅女装种草号',
        'category': 'clothing',
        'visualStyle': 'realistic',
        'defaultAspectRatio': '3:4',
        'enabledContentTypes': ['talking', 'try_on'],
    })
```

Also add template assertions that the ecommerce model page does not contain `生成新服装` or `服装 Tab`.

- [ ] **Step 2: Run tests to verify red**

Run: `python3 manage.py test apps.series.tests.CommerceFlowTests`

Expected: fail due to missing routes/templates.

- [ ] **Step 3: Implement views and routes**

Add `/commerce/` routes for list, create series, series detail, model detail, product creation, project creation, project detail, shot update, and video generation forwarding.

- [ ] **Step 4: Implement templates**

Build server-rendered forms and cards. Keep first screen usable, with ratio helper text showing `3:4` default and `9:16` for Douyin/Kuaishou/Video Account full-screen vertical video.

- [ ] **Step 5: Run Django tests**

Run: `python3 manage.py test apps.series.tests.CommerceFlowTests`

Expected: commerce tests pass.

## Task 5: Home Grouping And Navigation

**Files:**
- Modify: `frontend/templates/series/series_list.html`
- Modify: `frontend/apps/series/views.py`
- Modify: `frontend/apps/series/tests.py`

- [ ] **Step 1: Write failing template/API tests**

Add tests asserting the home template contains `短剧系列`, `电商系列`, `创建电商系列`, and fetches `/commerce/` for ecommerce list navigation. Add a BFF test for `/commerce/api/series/` forwarding to `/v1/commerce/series`.

- [ ] **Step 2: Run tests to verify red**

Run: `python3 manage.py test apps.series.tests.CommerceHomeGroupingTests`

Expected: fail until template and API are updated.

- [ ] **Step 3: Update home UI**

Split “我的系列” area into short-drama and ecommerce sections. Keep existing short-drama API intact. Wire ecommerce cards to `/commerce/`.

- [ ] **Step 4: Run tests**

Run: `python3 manage.py test apps.series.tests.CommerceHomeGroupingTests`

Expected: pass.

## Task 6: Documentation, Regression Tests, And Verification

**Files:**
- Create: `docs/changes/2026-05-17-ecommerce-video-mvp.md`
- Modify: `docs/changes/README.md`
- Verify frontend and backend tests.

- [ ] **Step 1: Add change documentation**

Document behavior, scope, affected files, and verification commands.

- [ ] **Step 2: Run focused verification**

Run:

```bash
cd frontend && python3 manage.py test apps.series.tests.CommerceFlowTests apps.series.tests.CommerceHomeGroupingTests
cd frontend && python3 manage.py check
cd backend && mvn -Dtest=CommerceServiceImplTest test
cd backend && mvn -q -DskipTests compile
```

- [ ] **Step 3: Run broader regression**

Run:

```bash
cd frontend && python3 manage.py test apps.series.tests --keepdb
```

- [ ] **Step 4: Review git diff**

Run: `git diff --check && git status --short`

Expected: no whitespace errors; only intended files changed.

- [ ] **Step 5: Commit**

Commit after verification:

```bash
git add backend frontend docs
git commit -m "feat: add ecommerce video workflow"
```
