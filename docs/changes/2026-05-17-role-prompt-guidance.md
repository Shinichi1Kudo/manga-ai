# 添加人物提示词占位文案优化

## 背景

创建系列和系列详情里添加人物时，提示词输入框原本会引导用户描述“生成三视图图片”。用户实际只需要写角色本身的特点，系统会在需要时自动补充三视图相关生成要求。

## 改动

- 创建系列的角色提示词占位文案改为描述角色特点、外貌、性格。
- 系列详情/角色审核页的“添加新角色”弹窗同步使用同一文案。
- 明确提示用户“不需要写生成三视图、角色设定板等话术，系统会自动处理”。
- 移除用户侧占位文案里的英文 `character design sheet` 等生成话术。

## 影响范围

- `frontend/templates/series/series_init.html`
- `frontend/templates/series/series_review.html`
- `frontend/apps/series/tests.py`

## 验证

- `python manage.py test apps.series.tests.RoleNameGuidanceTests`
- `python manage.py check`
