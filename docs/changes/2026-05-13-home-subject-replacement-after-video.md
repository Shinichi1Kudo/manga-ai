# CHG-20260513-015 首页主体替换展示区替换后视频接入

## 背景

首页“视频主体替换 - 精准定向修改”展示区的 `AFTER` 演示位原先仍使用外部示例视频占位。用户提供了正式替换后视频 `生成.mp4`，需要接入到首页展示区。

## 改动

- 将用户提供的替换后视频复制到前端静态资源目录。
- 首页展示区的 `AFTER` 视频改为使用本地静态视频。
- 根据视频信息将展示文案调整为 `8s 替换结果视频`。
- 至此首页展示区已接入替换前视频、参考图、替换后视频三类素材。

## 影响范围

- `frontend/templates/series/series_list.html`
- `frontend/static/videos/subject-replacement-after.mp4`

## 验证

- `python3 manage.py check`
- `mvn -DskipTests compile`
- 重启前端 `8000` 与后端 `8081`
- 浏览器打开首页检查替换后视频展示
