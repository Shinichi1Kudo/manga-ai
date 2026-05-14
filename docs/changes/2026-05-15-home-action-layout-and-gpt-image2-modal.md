# CHG-20260515-002 首页入口重排与 GPT-Image2 独立工作台

## 背景

首页顶部操作入口增加到创建新系列、剧集制作、主体替换、GPT-Image2 生图、我的资产库和积分记录后，原来单排按钮显得拥挤；同时 GPT-Image2 生图不适合放在小弹窗里，提示词、参考图、结果和任务状态都需要更大的工作区承载。

## 改动

- 首页顶部入口改为三组：
  - 基础工作台：创建新系列、剧集制作
  - AI 工具：主体替换、GPT-Image2 生图
  - 资产与账户：我的资产库、积分记录
- GPT-Image2 生图从首页弹层改为独立页面 `/gpt-image2/`，入口直接跳转到工作台。
- 新增 `frontend/templates/series/gpt_image2.html`，左侧生成表单、右侧最近任务，页面空间与主体替换工作台一致。
- 新增前端代理接口 `GET /api/v1/gpt-image2/?limit=50`，并由后端提供 `GET /v1/gpt-image2` 最近任务列表。
- 图片生成继续走后台任务：提交后立即返回任务，页面轮询任务详情并刷新最近任务；新标签页打开时以后台任务列表为准恢复状态。
- 最近任务显示任务状态、进度、提示词、比例、模式、耗时、参考图和结果图，参考图/结果图都可以查看大图。
- 未登录用户点击 GPT-Image2 生图入口仍走登录提示，不会直接进入生成页面。

## 影响范围

- 首页顶部功能入口布局
- GPT-Image2 生图工作台页面
- GPT-Image2 任务列表查询接口
- 首页匿名访问时的登录拦截体验

## 验证

- `python3 manage.py test apps.series.tests.GptImage2HomeTests`
- `mvn -Dtest=GptImage2ControllerTest,GptImage2ServiceImplTest test`
