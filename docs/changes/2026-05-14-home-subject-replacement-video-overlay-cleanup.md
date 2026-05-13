# CHG-20260514-001 首页主体替换演示视频覆盖文案清理

## 背景

首页底部的“视频主体替换-精准定向修改”展示区，左右两个演示视频画面上有额外覆盖文案和播放按钮，影响用户直接观看替换前后视频。

## 改动

- 移除替换前视频画面上的 `Original video` 角标。
- 移除替换前视频画面底部的 `8s 原始视频素材` 文案和进度装饰条。
- 移除替换后视频画面上的 `Generated preview` 角标。
- 移除替换后视频画面底部的 `8s 替换结果视频` 文案和播放按钮。
- 保留卡片标题区的 `BEFORE / 替换前视频`、`AFTER / 替换后视频`，保证展示区结构仍然清晰。

## 影响范围

- 前端模板：`frontend/templates/series/series_list.html`
- 不涉及后端接口和数据库。

## 验证

- `python3 manage.py check` 通过。
- `mvn -DskipTests compile` 通过。
- `git diff --check` 通过。
- 模板搜索确认 `Original video`、`Generated preview`、`8s 原始视频素材`、`8s 替换结果视频` 已不在首页模板中。
- 前端 `8000`、后端 `8081` 已重启并监听。
