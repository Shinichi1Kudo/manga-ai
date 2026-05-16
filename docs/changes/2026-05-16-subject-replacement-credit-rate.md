# CHG-20260516-003 主体替换计费调整为 60 积分/秒

## 背景

主体替换当前按 32 积分/秒计费。新的运营定价要求调整为 60 积分/秒，并且前端提交前展示的预计扣费需要与后端真实扣费一致。

## 改动

- 后端主体替换计费常量调整为 `60` 积分/秒。
- 主体替换创建任务时继续按 `duration * 单价` 扣费，5 秒任务由 160 积分调整为 300 积分。
- 主体替换页面的计费说明、预计消耗和提交按钮扣费文案同步更新。
- README 当前积分定价说明同步为主体替换 60 积分/秒。

## 验证

- `mvn -Dtest=SubjectReplacementServiceImplTest test`
- `python3 manage.py test apps.series.tests.SubjectReplacementCreditButtonTests`
