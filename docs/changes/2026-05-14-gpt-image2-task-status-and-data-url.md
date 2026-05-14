# CHG-20260514-017 GPT-Image2 任务状态持久化与 data URL 兼容

## 背景

用户反馈首页 GPT-Image2 生图失败，并且生成中状态只存在当前页面，新开标签页无法看到任务状态。后台日志显示上游生成接口可能连接中断，同时实测成功响应里 `data[0].url` 可能直接返回 `data:image/png;base64,...`，原实现会把它当普通 URL 下载，导致保存不稳定。

## 改动内容

- 后端新增 `gpt_image2_task` 任务表，记录提示词、比例、参考图、状态、结果图、错误信息和生成耗时。
- `POST /v1/gpt-image2/generate` 改为创建任务后立即返回 `id/status/progressPercent`，真正生图放到 `imageGenerateExecutor` 后台执行。
- 新增任务查询接口：
  - `GET /v1/gpt-image2/{taskId}`
  - `GET /v1/gpt-image2/latest`
- 兼容 GPT-Image2 返回的 `data:image/...;base64`，后端直接解码并上传 OSS。
- Django BFF 增加对应转发接口：
  - `GET /api/v1/gpt-image2/<task_id>/`
  - `GET /api/v1/gpt-image2/latest/`
- 首页提交生图后改为轮询任务状态；页面加载时自动恢复最近任务，新标签页能显示生成中/成功/失败。
- README 增加 GPT-Image2 状态持久化说明。

## 影响范围

- 首页 GPT-Image2 生图面板
- Spring GPT-Image2 模块
- Django `series` BFF
- 本地/部署启动时的轻量 schema 自迁移

## 验证

- `mvn -Dtest=GptImage2ServiceImplTest,GptImage2ControllerTest test`
- `python3 manage.py test apps.series.tests.GptImage2HomeTests`

完整验证和服务重启在本次提交前执行。
