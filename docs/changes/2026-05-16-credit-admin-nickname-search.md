# CHG-20260516-004 积分管理后台最近流水支持昵称模糊搜索

## 背景

积分管理后台的“最近积分流水”只能按时间分页查看。用户想查看某个用户的积分流水时，需要在近 3 天全量流水里人工翻找，效率比较低。

## 改动

- 后端 `CreditAdminController` 的看板接口新增可选 `nickname` 查询参数。
- `CreditAdminService` 和 `CreditAdminServiceImpl` 支持把昵称关键词传入最近流水构建流程。
- 最近流水在近 3 天过滤后、分页前，按用户昵称做 `contains` 模糊匹配。
- 总用户数、总余额、今日消耗、小时趋势、用户余额排行等总览数据保持全局口径，不受昵称搜索影响。
- Django 代理接口透传并 URL 编码 `nickname` 参数，避免中文昵称转发异常。
- 积分管理后台页面在“最近积分流水”标题右侧新增昵称搜索框，支持点击搜索、回车搜索和清空恢复全量流水。
- 分页函数复用当前搜索关键词，搜索状态下翻页不会丢失过滤条件。

## 影响范围

- 工藤新一专属积分管理后台。
- `/api/admin/credits/dashboard/` 前端代理接口。
- `/api/v1/admin/credits/dashboard` 后端看板接口。
- 只影响最近积分流水列表，不改变积分扣费、返还、统计口径和普通用户页面。

## 验证

- `mvn test -Dtest=CreditAdminServiceImplTest,CreditAdminControllerTest`
- `python manage.py test apps.series.tests.CreditAdminDashboardTests`
- `curl -I --max-time 8 http://127.0.0.1:8000/`
- `curl -sS --max-time 8 'http://127.0.0.1:8000/api/admin/credits/dashboard/?hours=24&recordPage=1&recordPageSize=20&nickname=%E5%B7%A5%E8%97%A4'`

## 重启

- 已重启后端 Spring Boot，监听端口 `8081`。
- 已重启前端 Django，监听地址 `127.0.0.1:8000`。
