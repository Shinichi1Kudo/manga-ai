# CHG-20260515-005 GPT-Image2 12 积分计费与失败返还

## 背景

GPT-Image2 生图已经改为后台任务制，但提交任务时没有积分扣费，前端也没有明确提示本次生成会消耗多少积分。用户需要在提交前看到扣费信息，积分记录也需要能查到 GPT-Image2 的扣除和失败返还。

## 改动

- GPT-Image2 生图任务新增 `creditCost` 和 `creditsRefunded` 字段。
- 每次创建 GPT-Image2 生图任务后立即扣除 12 积分，积分用途记录为图像生成，关联类型为 `GPT_IMAGE2_TASK`。
- 扣费失败时任务标记失败，不启动后台生成。
- 后台生成失败时自动返还本次扣除积分，并用 `creditsRefunded` 防止重复返还。
- 任务响应返回 `creditCost`，最近任务卡片显示“消耗 12 积分”。
- 前端生成按钮改为“提交后台生成 · 扣除12积分”，表单区域提示失败自动返还。
- 最近任务中的模型名从裸文本改为带图标的 `GPT-Image 2` 徽章。
- 启动迁移为 `gpt_image2_task` 自动补齐 `credit_cost` 和 `credits_refunded` 字段。

## 影响范围

- GPT-Image2 任务创建、执行失败处理和任务响应
- GPT-Image2 最近任务 UI
- 积分扣除、返还和积分流水
- 数据库启动迁移

## 验证

- `mvn test -Dtest=GptImage2ServiceImplTest,GptImage2ControllerTest`
- `python3 manage.py test apps.series.tests.GptImage2HomeTests`
- `git diff --check`
