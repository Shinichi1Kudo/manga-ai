# CHG-20260514-007 未登录联系我们二维码裂图修复

## 背景

首页允许未登录用户访问后，“联系我们”弹窗仍会请求公共二维码接口。但该接口没有被 Django 登录中间件放行，匿名请求会被 302 到登录页，前端拿不到二维码 URL，最终表现为图片裂开。

## 改动

- 登录中间件放行 `/api/v1/common/contact-image/`，让未登录首页也能获取联系我们二维码。
- 联系我们弹窗的二维码区域增加加载、成功、失败三态：
  - 默认显示“二维码加载中...”。
  - 只有接口返回 URL 且图片可被浏览器加载时，才显示二维码和“大图预览”入口。
  - 接口异常或 OSS 图片加载失败时显示“二维码加载失败”和重试按钮，不再出现裂图。
- 新增 Django 回归测试，覆盖匿名访问公共二维码接口不会跳转登录页。

## 影响范围

- 登录中间件：`frontend/manga_ai/middleware.py`
- 基础模板：`frontend/templates/base.html`
- 回归测试：`frontend/apps/series/tests.py`
- 不涉及数据库结构和后端业务逻辑。

## 验证

- `python3 manage.py test apps.series.tests.AnonymousPublicEndpointTests.test_contact_image_endpoint_is_public` 通过。
- `python3 manage.py check` 通过。
- `mvn -DskipTests compile` 通过。
- `git diff --check -- frontend/manga_ai/middleware.py frontend/templates/base.html frontend/apps/series/tests.py docs/changes/README.md docs/changes/2026-05-14-contact-qr-anonymous-fix.md` 通过。
- 匿名访问 `/api/v1/common/contact-image/` 返回 `200 OK` 和二维码 URL，不再 302 到登录页。
- 前端 `8000`、后端 `8081` 已重启并确认监听。
