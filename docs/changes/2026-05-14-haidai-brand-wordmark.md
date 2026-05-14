# CHG-20260514-015 海带品牌字标视觉优化

## 背景

网站名称已经统一为“海带 AI 智能短剧制作系统”，但页面里的“海带”仍然和普通标题文字混在一起，辨识度不够，缺少品牌字标感。

## 改动

- 将“海带”拆成独立 `brand-name` 字标。
- 使用更厚重的中文衬线字体、青蓝绿金渐变和细光线，增强品牌识别度。
- 将“AI 智能短剧制作系统”作为 `brand-subtitle` 展示，保持清晰克制。
- 顶部导航、首页主标题、登录页、注册页统一使用新字标结构。
- 增加移动端适配，窄屏下导航仅展示“海带”，首页标题允许上下排列。

## 影响范围

- `frontend/templates/base.html`
- `frontend/templates/series/series_list.html`
- `frontend/templates/auth/login.html`
- `frontend/templates/auth/register.html`
- `frontend/apps/series/tests.py`

## 验证

- `python3 manage.py check`
- `python3 manage.py test apps.series.tests.SiteBrandingTests apps.series.tests.SubjectReplacementCreditButtonTests apps.auth.tests.LoginRedirectTests`
- `git diff --check`
- 已重启本地前后端服务。
