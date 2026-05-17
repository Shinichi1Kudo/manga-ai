# 搜索引擎收录基础配置

## 背景

站点已部署到 `https://www.yzmovie.cn/`，但缺少搜索引擎发现和理解站点需要的基础入口，百度等搜索引擎难以抓取公开页面。

## 改动

- 新增 `robots.txt`，声明公开落地页可抓取，并屏蔽 API、后台、资产、积分、用户数据等私有路径。
- 新增 `sitemap.xml`，收录首页、主体替换、GPT-Image2 三个公开页面，URL 指向正式域名。
- 增加 `SITE_URL` 配置，默认值为 `https://www.yzmovie.cn`，支持通过环境变量覆盖。
- 为公开页面增加 `description`、`keywords`、`robots=index,follow`、`canonical` 和 Open Graph 元信息。
- 登录、注册等非公开页面增加 `robots=noindex,nofollow`，避免被搜索引擎当作入口页。
- 放行 `/subject-replacement/` 和 `/gpt-image2/` 的匿名页面访问，接口仍保持登录态保护。

## 验证

- `python3 manage.py test apps.series.tests.SearchEngineVisibilityTests`
- `python3 manage.py test apps.series.tests`
- `python3 manage.py check`
- 本地请求确认 `/robots.txt`、`/sitemap.xml` 输出正式域名 `https://www.yzmovie.cn`。

## 后续上线操作

- 部署后访问 `https://www.yzmovie.cn/robots.txt` 和 `https://www.yzmovie.cn/sitemap.xml`，确认返回 200。
- 在百度搜索资源平台添加并验证站点，提交 `https://www.yzmovie.cn/sitemap.xml`。
- 在 Google Search Console 和 Bing Webmaster Tools 中提交同一个 sitemap。
