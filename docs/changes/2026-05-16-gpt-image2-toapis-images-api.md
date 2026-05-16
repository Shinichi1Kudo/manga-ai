# CHG-20260516-001 GPT-Image2 切换 Toapis 图片生成接口

## 背景

首页 GPT-Image2 生图原先配置为 `https://api.airiver.cn/v1`，请求体和响应解析按同步生图接口处理。用户要求切换到底层 Toapis 图片生成接口 `https://toapis.com/v1/images/generations`，该接口返回异步任务状态，需要后端提交后继续查询任务状态，直到完成或失败。

## 改动

- GPT-Image2 后端默认 `base-url` 改为 `https://toapis.com/v1`。
- GPT-Image2 API Key 配置改为优先读取 `TOAPIS_API_KEY`，兼容旧的 `GPT_IMAGE2_API_KEY`。
- Toapis 返回 401/403 时前端任务错误信息会明确提示 API Key 无效或服务未开通，不再显示笼统的“服务暂时不可用”。
- 提交图片生成时使用 Toapis 请求体：
  - `model`
  - `prompt`
  - `size`
  - `resolution`
  - `n`
  - `response_format`
  - `reference_images`
- 参考图图生图不再传旧字段 `image`，统一传公开可访问的 OSS URL 到 `reference_images`。
- 后端兼容 Toapis 异步任务状态：
  - `queued` 映射为排队中
  - `in_progress` 映射为生成中
  - `completed` 映射为已生成
  - `failed` 映射为失败并触发积分返还
- 后端根据 Toapis 规则校验清晰度和图片比例组合：
  - 1K：`1:1`、`3:2`、`2:3`
  - 2K：全部支持比例
  - 4K：`16:9`、`9:16`、`2:1`、`1:2`、`21:9`、`9:21`
- 前端 GPT-Image2 工作台按清晰度动态刷新可选比例，避免用户提交 Toapis 不支持的组合。
- README 同步更新 GPT-Image2 的 Toapis 配置说明。

## 影响范围

- 后端 GPT-Image2 生图任务提交和执行链路
- 首页 GPT-Image2 独立工作台的比例选择控件
- GPT-Image2 配置示例与 README

## 验证

- `mvn -Dtest=GptImage2ServiceImplTest,GptImage2ControllerTest test`
- `mvn -q -DskipTests compile`
- `python manage.py test apps.series.tests`
- `git diff --check`
