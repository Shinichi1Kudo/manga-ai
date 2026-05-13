# CHG-20260514-009 首页主体替换演示素材改为 OSS

## 背景

首页“视频主体替换 - 精准定向修改”展示区之前直接引用 `/static/videos/...` 和 `/static/images/...` 本地静态文件。部署到服务器时如果没有同步这些大文件，替换前视频、参考图和替换后视频会加载失败。

## 改动

- 将三个演示素材上传到 OSS 固定路径：
  - `showcase/subject-replacement/before.mp4`
  - `showcase/subject-replacement/reference-model.png`
  - `showcase/subject-replacement/after.mp4`
- 后端新增公共接口 `/api/v1/common/showcase-assets`，按固定 object key 生成最新签名 URL。
- Django BFF 新增 `/api/v1/common/showcase-assets/` 代理接口，并在未登录中间件中放行。
- 首页展示区不再写死本地 `/static/...`，页面加载后从公共接口拿 OSS URL 再赋给视频和图片。
- 新增回归测试，覆盖未登录状态下也能访问展示素材接口。

## 影响范围

- 后端公共接口：`backend/src/main/java/com/manga/ai/common/controller/CommonController.java`
- 前端代理：`frontend/apps/series/views.py`、`frontend/apps/series/urls.py`
- 登录中间件：`frontend/manga_ai/middleware.py`
- 首页模板：`frontend/templates/series/series_list.html`
- 回归测试：`frontend/apps/series/tests.py`

## 验证

- `python3 manage.py test apps.series.tests.AnonymousPublicEndpointTests` 通过。
- `python3 manage.py check` 通过。
- `mvn test` 通过。
- `git diff --check` 通过。
- 匿名访问 `/api/v1/common/showcase-assets/` 返回 `200 OK` 和三个 OSS 签名 URL。
- 分别请求三个 OSS URL，替换前视频/替换后视频返回 `video/mp4`，参考图返回 `image/png`。
- 前端 `8000`、后端 `8081` 已重启并确认监听。
