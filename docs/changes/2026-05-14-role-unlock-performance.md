# CHG-20260514-018 系列详情角色解锁性能优化

## 背景

用户反馈系列详情页里“已锁定角色”点击解锁反应慢。之前前端已经去掉了整页刷新，但后端解锁链路仍会读取完整角色和完整系列记录。角色、系列记录里包含较长提示词、描述和资产相关字段，解锁实际只需要角色 `id/status/series_id`，多余字段会放大数据库读取和对象映射成本。

## 改动内容

- 后端 `RoleMapper` 新增轻量查询 `selectUnlockStateById`，只读取解锁判断需要的 `id/series_id/status`。
- 后端 `RoleMapper` 新增条件更新 `updateStatusIfUnlockable`，只在角色仍处于“已确认/已锁定”时改回待审核，避免并发误改。
- 后端 `SeriesMapper` 新增 `markLockedSeriesPendingReview`，只在系列仍为已锁定时用单条 SQL 改为待审核，不再读取完整系列对象。
- `RoleServiceImpl.unlockRole` 改为轻量查询 + 条件更新，不再调用 `selectById/updateById` 读取和回写整条角色、系列。
- Django 解锁接口返回角色和系列的新状态，前端成功后即时把角色卡片和顶部系列状态更新为“待审核”，减少用户等待刷新后的体感延迟。

## 影响范围

- 系列详情/角色审核页的角色解锁动作
- Spring 角色服务与系列 Mapper
- Django `role_unlock` BFF 接口
- 前端 `series_review.html` 局部状态同步

## 验证

- `mvn -Dtest=RoleServiceImplTest test`
- `mvn test`
- `python3 manage.py test apps.role.tests.RoleUnlockTests apps.series.tests.GptImage2HomeTests`
- `python3 manage.py check`
- `git diff --check`

完整验证和前后端重启在本次提交前执行。
