# CHG-20260513-017 首页参考图遮罩文案移除

## 背景

首页“视频主体替换 - 精准定向修改”展示区的参考图上叠加了 `Reference image` 浮层，遮挡了图片主体，影响视觉展示。

## 改动

- 移除参考图图片内部的 `Reference image` 浮层。
- 保留参考图卡片顶部的 `REFERENCE / 参考图` 标识。

## 影响范围

- `frontend/templates/series/series_list.html`

## 验证

- `python3 manage.py check`
- `mvn -DskipTests compile`
- 重启前端 `8000` 与后端 `8081`
- 浏览器打开首页检查参考图不再被浮层遮挡
