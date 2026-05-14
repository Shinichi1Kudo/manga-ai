# CHG-20260514-016 首页新增 GPT-Image2 生图工具

## 背景

用户希望首页增加一个 `gpt-image2` 生图入口，支持文生图和图生图。配置域名为 `https://api.airiver.cn/v1`，模型为 `gpt-image-2`。API Key 属于敏感信息，因此没有写入代码或文档，改为后端从 `GPT_IMAGE2_API_KEY` 环境变量读取。

## 改动

- 首页新增 `GPT-Image2 生图` 面板，支持提示词、图片比例、可选参考图上传和生成结果预览。
- 未登录用户仍可看到首页功能展示，但点击上传/生成会弹出登录提示。
- 新增 Django BFF 接口：
  - `POST /api/v1/gpt-image2/upload-reference/`
  - `POST /api/v1/gpt-image2/generate/`
- 新增 Spring 后端接口：
  - `POST /api/v1/gpt-image2/upload-reference`
  - `POST /api/v1/gpt-image2/generate`
- 参考图和生成结果都会上传到 OSS，避免部署后依赖第三方临时地址或本地文件。
- 后端兼容 GPT-Image2 返回 `data[0].url` 或 `data[0].b64_json` 两种结果格式。

## 影响范围

- 首页模板：`frontend/templates/series/series_list.html`
- Django 路由与视图：`frontend/apps/series/urls.py`、`frontend/apps/series/views.py`
- Spring GPT-Image2 模块：`backend/src/main/java/com/manga/ai/gptimage/`
- 后端配置：`backend/src/main/resources/application.yml`
- 测试：`frontend/apps/series/tests.py`、`backend/src/test/java/com/manga/ai/gptimage/`

## 验证

- `python3 manage.py test apps.series.tests.GptImage2HomeTests`
- `mvn -Dtest=GptImage2ControllerTest,GptImage2ServiceImplTest test`
