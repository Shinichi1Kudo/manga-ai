# CHG-20260515-003 GPT-Image2 清晰度选择与按比例预览

## 背景

GPT-Image2 工作台的参考图和生成图在最近任务里使用固定缩略图裁切展示，横图、竖图和方图都会被压进同一尺寸，不利于用户判断构图。同时用户需要在生成前选择输出清晰度。

## 改动

- GPT-Image2 生图表单新增清晰度选择：`1K`、`2K`、`4K`，默认 `2K`。
- 前端提交生成任务时携带 `resolution`，Django 代理会转发到后端。
- 后端 `gpt_image2_task` 增加 `resolution` 字段，并在启动迁移中自动补列。
- 任务响应增加 `resolution`，最近任务列表会显示清晰度。
- 生成请求按 `aspectRatio + resolution` 映射到模型 `size`：
  - `1K` 使用原有 1K 尺寸。
  - `2K` 使用 2K 尺寸。
  - `4K` 使用 4K 尺寸。
- 最近任务里的参考图和生成图改为按图片比例的预览框，并使用 `object-contain` 展示，避免裁切。

## 影响范围

- GPT-Image2 生图工作台表单
- GPT-Image2 最近任务列表
- GPT-Image2 任务表和任务响应
- GPT-Image2 调用外部模型时的 `size` 参数

## 验证

- `python3 manage.py test apps.series.tests.GptImage2HomeTests`
- `mvn -Dtest=GptImage2ServiceImplTest test`
