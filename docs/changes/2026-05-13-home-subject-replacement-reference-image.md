# CHG-20260513-013 首页主体替换展示区参考图接入

## 背景

首页“视频主体替换 - 精准定向修改”展示区原先使用外部参考图 URL 占位。用户提供了正式参考图 `模特.png`，需要替换演示区里的参考图素材。

## 改动

- 将用户提供的参考图复制到前端静态资源目录。
- 首页展示区的 `REFERENCE` 卡片改为使用本地静态图片。
- 移除该位置对 Unsplash 外链占位图的依赖。

## 影响范围

- `frontend/templates/series/series_list.html`
- `frontend/static/images/subject-replacement-reference-model.png`

## 验证

- `python3 manage.py check`
- `mvn -DskipTests compile`
- 重启前端 `8000` 与后端 `8081`
- 浏览器打开首页检查参考图展示
