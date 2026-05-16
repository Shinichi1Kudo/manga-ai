# 角色名称只填人名提示

## 背景

创建系列和系列详情里添加角色时，用户容易把职业、身份或关系一起填进角色名称，例如“主播李强”“程序员周也”。这些内容会影响后续角色识别、资产匹配和分镜资产引用的稳定性。

## 改动

- 创建系列的角色卡片中，角色名称输入框占位文案改为“只输入人名，例如：李强、周也”。
- 在名称输入框下方增加醒目的琥珀色提示，明确角色名称只填人名。
- 系列详情/角色审核页的“添加新角色”弹窗使用同样提示。
- 提示中说明职业、身份、关系等内容应填写到提示词里。

## 影响范围

- `frontend/templates/series/series_init.html`
- `frontend/templates/series/series_review.html`
- `frontend/apps/series/tests.py`

## 验证

- `python manage.py test apps.series.tests.RoleNameGuidanceTests`
- `python manage.py check`
