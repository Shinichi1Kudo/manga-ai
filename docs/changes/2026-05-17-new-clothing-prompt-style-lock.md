# CHG-20260517-012 生成新服装描述必填并锁定角色风格

## 背景

角色审核页的“生成新服装”弹窗里，服装描述原本是可选，图片风格也允许重新选择。这样容易出现两类问题：用户没有给出明确服装意图，生成结果不可控；新服装风格与角色创建时的图片风格不一致，影响角色资产统一性。

## 改动

- 将“服装描述（可选）”改为“服装描述”必填。
- 提交前新增前端校验，未填写服装描述时提示“请填写服装描述”。
- 角色卡增加创建角色时使用的图片风格数据。
- 打开“生成新服装”弹窗时，图片风格自动沿用该角色创建时的图片风格。
- 禁用新服装弹窗里的图片风格选择和风格预览按钮，避免用户误改。
- 当角色风格缺失时，兜底使用系列风格。

## 影响范围

- 系列详情/角色审核页 `series_review.html`。
- 不改变后端生成接口和积分逻辑，仍按既有 `styleKeywords` 字段提交。

## 验证

- `python manage.py test apps.series.tests.SeriesReviewAssetGateTests.test_new_clothing_uses_role_style_and_requires_clothing_prompt`
- `python manage.py test apps.series.tests.RoleNameGuidanceTests apps.series.tests.SeriesReviewAssetGateTests`
- `python manage.py check`
