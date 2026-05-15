# CHG-20260515-004 站点 Logo OSS 化与积分管理后台

## 背景

网站 Logo 原先依赖本地静态资源，部署到服务器后容易出现裂图或 favicon 失效。同时积分流水和余额只能在用户侧查看，缺少给管理员快速排查消耗、余额和近期使用趋势的入口。

## 改动

- 新增后端 `GET /v1/common/site-logo`，返回 `brand/haidai-logo.png` 的 OSS 签名 URL。
- 前端新增 `/site-logo.png` 图片代理，导航、首页 Logo、登录/注册页品牌图和 favicon 均使用该公共入口，避免服务器把 `/api/v1/common/site-logo/` 转发到后端 JSON 接口导致图片裂开。
- 新增工藤新一专属积分管理后台 `/admin/credits/`。
- 后端新增 `GET /v1/admin/credits/dashboard`，聚合用户余额、今日消耗用户明细、近 3 天积分流水、分页信息和趋势数据。
- 前端后台页新增趋势图、用户余额列表、今日消耗详情和最近流水分页。
- 首页用户菜单仅对工藤新一展示后台管理入口。

## 影响范围

- 公共 OSS 资源签名 URL
- 全站品牌 Logo 和 favicon
- 首页、登录页、注册页品牌展示
- 积分管理后台页面与 API 代理
- 管理员用户菜单入口

## 验证

- `mvn test -Dtest=CreditAdminServiceImplTest,CreditAdminControllerTest,OssServiceTest`
- `python3 manage.py test apps.series.tests`
- `git diff --check`
