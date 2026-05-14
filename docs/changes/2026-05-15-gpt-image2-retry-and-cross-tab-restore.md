# CHG-20260515-001 GPT-Image2 瞬断重试与跨标签生成中恢复

## 背景

用户反馈首页 GPT-Image2 生成任务在当前页显示“生成中，当前进度35%”，打开新标签页后生成中标志消失；随后任务失败，只显示“GPT-Image2生成失败，请稍后重试”。后台日志显示任务已落库为 `running`，轮询接口也能查到任务状态，但上游响应读取阶段出现 `Premature EOF`，当前实现一次连接中断就直接把任务置为失败。

## 改动内容

- GPT-Image2 调用上游生成接口时，对 `Premature EOF`、`Unexpected end of file`、连接重置和超时类异常最多重试 3 次。
- 错误友好化逻辑改为读取整条异常链，能识别 `RestClientException` 里的根因 `Premature EOF`。
- 首页提交或轮询 GPT-Image2 任务时，把最近任务状态写入 `localStorage`。
- 新标签页加载首页时先从 `localStorage` 恢复生成中/成功/失败状态，再调用后端最近任务接口校准。
- 增加 `storage` 事件监听，让同浏览器不同标签页之间同步最新 GPT-Image2 状态。

## 影响范围

- 首页 GPT-Image2 生图面板
- Spring GPT-Image2 服务调用上游生成接口的稳定性
- GPT-Image2 任务跨标签页状态同步

## 验证

- `mvn -Dtest=GptImage2ServiceImplTest test`
- `python3 manage.py test apps.series.tests.GptImage2HomeTests`

完整验证和前后端重启在本次提交前执行。
