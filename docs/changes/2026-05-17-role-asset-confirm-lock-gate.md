# CHG-20260517-005 角色资产缺图时禁止确认和锁定系列

## 背景

系列 `百分百兼容 Oracle` 的角色资产页中，有 2 个角色显示“图片生成失败，请检查提示词后重试”，但系列已经处于已锁定状态，用户仍能继续进入后续流程。

## 根因

原逻辑只按角色状态判断：

- `confirmRole` 只要角色没锁定，就能把角色改成“已确认”。
- `lockSeries` 只检查角色状态是否都大于等于 2，没有检查角色是否存在生成成功的可用图片。
- 前端“确认”和“下一步”也只看角色状态，没有把失败图片或缺图纳入门禁。

因此历史上可能出现“角色已确认/已锁定，但没有可用角色图片”的脏状态。

## 改动

- 后端 `RoleAssetMapper` 增加可用激活角色图统计：
  - 单角色是否存在可用激活图片。
  - 系列内缺少可用激活图片的角色数量。
- 后端 `RoleServiceImpl.confirmRole` 增加校验：角色没有生成成功的可用图片时，不允许确认。
- 后端 `SeriesServiceImpl.lockSeries` 增加校验：系列内只要有角色缺少可用图片，就不允许锁定。
- 前端 `series_review.html` 增加角色卡片 `data-has-usable-asset` 标记：
  - 缺图角色禁用“确认”。
  - “下一步”必须同时满足所有角色已确认且都有可用图片。
  - 点击时二次校验并提示用户先重新生成失败图片。
- 前端 `series_review` 视图在渲染时将缺图但已确认/已锁定的角色按待审核展示，避免页面继续误导用户。

## 数据修复

已修复本地数据库中 `百分百兼容 Oracle` 的历史脏状态：

- `林墨：架构师` 缺少可用角色图，已从已锁定退回待审核。
- `陈主任：甲方/监管/信创负责人` 缺少可用角色图，已从已锁定退回待审核。
- 系列 `百分百兼容 Oracle` 已从已锁定退回待审核。

## 涉及文件

- `backend/src/main/java/com/manga/ai/asset/mapper/RoleAssetMapper.java`
- `backend/src/main/java/com/manga/ai/role/service/impl/RoleServiceImpl.java`
- `backend/src/main/java/com/manga/ai/series/service/impl/SeriesServiceImpl.java`
- `backend/src/test/java/com/manga/ai/role/service/impl/RoleServiceImplTest.java`
- `backend/src/test/java/com/manga/ai/series/service/impl/SeriesServiceImplTest.java`
- `frontend/apps/series/views.py`
- `frontend/templates/series/series_review.html`
- `frontend/apps/series/tests.py`

## 验证

- `mvn test -Dtest=RoleServiceImplTest#confirmRoleRejectsRoleWithoutUsableActiveAsset,SeriesServiceImplTest#lockSeriesRejectsConfirmedRolesWithoutUsableActiveAssets`
- `mvn test -Dtest=RoleServiceImplTest,SeriesServiceImplTest`
- `python manage.py test apps.series.tests.SeriesReviewAssetGateTests apps.series.tests.EpisodeDescriptionAssetRenderTests`
- `python manage.py check`

验证结果均通过，前后端服务已重启。
