# CHG-20260517-004 分镜剧情资产缩略图 URL 外露修复

## 背景

系列 `百分百兼容 Oracle` 第 1 集 `第一章：100%兼容` 中，分镜 2 和分镜 12 的前端展示出现 `data-asset-url` 和 OSS 签名 URL 片段。

## 排查结论

数据库中的分镜 2、分镜 12 数据是干净的：

- `description` 字段没有 URL。
- `sound_effect` 字段没有 URL。
- 问题只发生在前端渲染时。

根因是剧集详情页会自动识别分镜文本里的资产名并追加缩略图。旧逻辑用字符串全局替换整段 HTML：先把 `会议室`、`大屏幕` 等资产名替换成缩略图 HTML，之后又继续扫描已经生成的 HTML 属性、标题、标签文本，导致 HTML 被二次替换打坏，最终把 `data-asset-url` 暴露成页面文字。

## 改动

- 将分镜剧情自动资产渲染改为“只扫描原始纯文本一次”。
- 资产名先去重，并按名称长度排序，避免同名场景或包含关系造成重复替换。
- 文本片段统一 `escapeHtml` 后再拼接缩略图，避免 HTML 属性被重新扫描。
- 格式化分镜文本中只对 `剧情【...】` 段做自动缩略图识别，`时间【...】`、`镜头【...】`、`音效【...】` 仅作为纯文本显示，避免 `会议室环境音` 这类音效描述误触发场景资产。
- 缩略图 HTML 的 `data-*`、`src`、`alt`、`title` 等属性统一做转义。

## 涉及文件

- `frontend/templates/episode/episode_detail.html`
- `frontend/apps/series/tests.py`

## 验证

- `python manage.py test apps.series.tests.EpisodeDescriptionAssetRenderTests`
- `python manage.py test apps.series.tests.EpisodeDescriptionAssetRenderTests apps.series.tests.EpisodeAssetSelectionModalTests apps.series.tests.EpisodeVideoCreditDisplayTests`
- `python manage.py check`

验证结果均通过，前端服务已重启。
