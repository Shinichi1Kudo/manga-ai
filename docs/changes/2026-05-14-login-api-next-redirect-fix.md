# CHG-20260514-008 登录后跳到用户 JSON 修复

## 背景

未登录首页会触发 `/api/user/info/` 这类 AJAX 请求。此前登录中间件会把任何未登录访问都记录为 `next`，导致 `/api/user/info/` 被写入 session。用户随后登录成功时，页面会跳转到这个 API 地址，浏览器直接展示用户信息 JSON。

## 改动

- 未登录访问 `/api/` 路径时，不再写入 `next`，改为返回 `401` JSON：`{"code": 401, "message": "请先登录"}`。
- 登录成功后增加兜底过滤，`next` 只能跳页面地址；如果是 `/api/` 或不安全地址，统一回到首页 `/`。
- 保留公共接口 `/api/v1/common/contact-image/` 的匿名访问能力。
- 增加回归测试：
  - 匿名私有 API 请求不会污染登录后的 `next`。
  - session 中已有 `/api/...` 残留时，登录后也会回首页。

## 影响范围

- 登录中间件：`frontend/manga_ai/middleware.py`
- 登录视图：`frontend/apps/auth/views.py`
- 回归测试：`frontend/apps/series/tests.py`、`frontend/apps/auth/tests.py`
- 不涉及数据库结构和后端业务逻辑。

## 验证

- `python3 manage.py test apps.series.tests.AnonymousPublicEndpointTests apps.auth.tests.LoginRedirectTests` 通过。
- `python3 manage.py check` 通过。
- `mvn -DskipTests compile` 通过。
- `git diff --check -- frontend/manga_ai/middleware.py frontend/apps/auth/views.py frontend/apps/series/tests.py frontend/apps/auth/tests.py docs/changes/README.md docs/changes/2026-05-14-login-api-next-redirect-fix.md` 通过。
- 匿名访问 `/api/user/info/` 返回 `401` JSON，不再 302 到登录页，也不再污染 `next`。
- 前端 `8000`、后端 `8081` 已重启并确认监听。
