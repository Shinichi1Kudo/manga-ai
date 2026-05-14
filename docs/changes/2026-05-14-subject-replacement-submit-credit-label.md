# CHG-20260514-014 主体替换确认生成按钮显示扣费积分

## 背景

主体替换已经按 `1 秒 32 积分` 计费，页面上方也展示了预计消耗，但底部提交按钮仍只显示“确认生成”。用户在最后确认提交前看不到按钮级别的扣费提示，容易误以为没有显示本次扣除积分。

## 改动

- 主体替换页的“确认生成”按钮增加 `扣除160积分` 文案。
- 切换视频时长后，按钮里的扣费积分会跟随 `duration * 32` 实时更新。
- 增加 Django 模板回归测试，防止后续改页面时再次漏掉按钮扣费文案。

## 影响范围

- `frontend/templates/subject_replacement/index.html`
- `frontend/apps/series/tests.py`

## 验证

- `python3 manage.py test apps.series.tests.SubjectReplacementCreditButtonTests`
- `python3 manage.py check`
- `python3 manage.py test apps.series.tests.AnonymousPublicEndpointTests apps.series.tests.SubjectReplacementDeleteTests apps.series.tests.SeriesListWebSocketTests apps.series.tests.SiteBrandingTests apps.series.tests.SubjectReplacementCreditButtonTests apps.auth.tests.LoginRedirectTests`
- 已重启本地前后端服务。
