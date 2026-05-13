# CHG-20260514-010 主体替换最近任务删除兜底

## 背景

服务器上删除“主体替换5”时提示“系统异常请稍后重试”，但本地同一接口可删除成功。原因是本地经过 Django BFF，`/api/v1/subject-replacements/{id}/delete/` 会被 Django 转发到 Spring 的 `/v1/subject-replacements/{id}`；服务器部署链路可能把 `/api/v1/...` 直接转给 Spring，而 Spring 只注册了 `DELETE /v1/subject-replacements/{id}`，没有 `/delete` 后缀，最终触发 `NoHandlerFoundException`。

同时，删除链路对重复删除、旧列表项或部署端旧后端返回“任务不存在”不够友好，用户会看到失败提示。

## 改动

- 前端删除请求改为标准路径 `DELETE /api/v1/subject-replacements/{id}/`，不再使用 `/delete/` 后缀。
- Django BFF 在任务详情路径上支持 `DELETE /api/v1/subject-replacements/{id}/`，并保留旧 `/delete/` 路径兼容。
- Spring 后端保留标准 `DELETE /v1/subject-replacements/{id}`，同时新增兼容路由 `DELETE /v1/subject-replacements/{id}/delete`。
- 后端主体替换任务删除改为按 `taskId + userId` 直接删除，不再先查询任务详情。
- 删除不存在的任务时保持幂等成功，避免重复点击或旧列表项触发错误提示。
- Django BFF 对后端返回“任务不存在”的删除响应做成功兜底，兼容服务器后端未同步到最新代码的窗口期。
- 前端最近任务删除按钮在请求中会禁用，避免连续触发多次删除。
- 前端删除成功后先从本地最近任务列表移除，再刷新服务端列表，反馈更快。
- 主体替换对象组的折叠区文案从“补充定位信息”改为“补充其他信息”。

## 影响范围

- 后端服务：`backend/src/main/java/com/manga/ai/subject/service/impl/SubjectReplacementServiceImpl.java`
- 后端控制器：`backend/src/main/java/com/manga/ai/subject/controller/SubjectReplacementController.java`
- 后端测试：`backend/src/test/java/com/manga/ai/subject/service/impl/SubjectReplacementServiceImplTest.java`
- Django BFF：`frontend/apps/series/views.py`、`frontend/apps/series/urls.py`
- Django 测试：`frontend/apps/series/tests.py`
- 前端页面：`frontend/templates/subject_replacement/index.html`

## 验证

- `mvn test` 通过。
- `python3 manage.py check` 通过。
- `python3 manage.py test apps.series.tests.AnonymousPublicEndpointTests apps.series.tests.SubjectReplacementDeleteTests apps.auth.tests.LoginRedirectTests` 通过。
- `git diff --check` 通过。
- 本地接口验证：重复删除已不存在的主体替换任务，Django BFF 返回 `200`，不再提示失败。
