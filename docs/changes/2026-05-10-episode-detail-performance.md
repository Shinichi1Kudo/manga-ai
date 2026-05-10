# CHG-20260510-006 剧集详情查看入口加载优化

## 背景

用户反馈：

“系列锦衣藏娇：将军的掌心娇的剧集制作-第1集 大婚惊变，洞房花烛夜藏刀-查看详情怎么这么慢，点开等了好久才进来。”

排查目标页面：

- 前端页面：`/85/episodes/13/`
- 后端剧集详情：`/api/v1/episodes/13`
- 后端分镜列表：`/api/v1/shots/episode/13`
- 后端场景、道具、角色资产接口

初始测得：

```text
/api/v1/episodes/13        8.45s - 10.9s
/api/v1/shots/episode/13   约 1.31s
/api/v1/scenes/series/85   约 0.28s
/api/v1/props/series/85    约 0.28s
```

慢点主要在 `/api/v1/episodes/13`：该接口返回剧集基础信息时还额外组装分镜列表和角色资产，和页面后续单独请求的分镜、角色资产重复。

## 改动

- 后端新增剧集基础详情模式：
  - `/api/v1/episodes/{episodeId}?basic=true`
  - 只返回页面头部、剧本、状态等基础字段。
  - 不再附带分镜列表和角色资产，避免重复查询。
- 前端剧集详情页入口改为并发请求：
  - 系列基础信息
  - 剧集基础详情
  - 分镜列表
  - 场景列表
  - 道具列表
  - 系列角色服装资产
- 角色资产展示改为使用轻量的系列角色服装资产接口，不再依赖完整剧集详情里的角色组装结果。
- 场景、道具历史版本改为点击历史按钮时按需加载，不再把完整 `assets` 历史 JSON 重复注入 HTML。
- 页面统计从后端直接计算，不再在模板里额外注入完整 `shots` JSON 做统计。
- 场景、道具、角色缩略图增加 `loading="lazy"` 和 `decoding="async"`，降低首屏图片解析压力。
- 分镜服务复用静态 `ObjectMapper` 解析 `propsJson`，减少循环中反复创建对象的开销。

## 涉及文件

- `backend/src/main/java/com/manga/ai/episode/controller/EpisodeController.java`
- `backend/src/main/java/com/manga/ai/episode/service/EpisodeService.java`
- `backend/src/main/java/com/manga/ai/episode/service/impl/EpisodeServiceImpl.java`
- `backend/src/main/java/com/manga/ai/shot/service/impl/ShotServiceImpl.java`
- `frontend/apps/series/views.py`
- `frontend/templates/episode/episode_detail.html`

## 行为变化

- 详情页入口不再等待完整剧集详情里的重复分镜和角色资产组装。
- 场景、道具历史版本弹窗第一次打开时才请求对应资产详情。
- 页面首屏仍展示原有剧本、场景、道具、角色、分镜列表，交互行为保持不变。
- 当前页面主要剩余耗时来自 `/api/v1/shots/episode/13`，约 1.4 秒。

## 验证状态

当前状态：已验证。

已执行：

```bash
python3 manage.py check
mvn -DskipTests compile
mvn spring-boot:run -DskipTests
curl -w 'episode_basic time=%{time_total} size=%{size_download} code=%{http_code}\n' 'http://127.0.0.1:8081/api/v1/episodes/13?basic=true'
curl -w 'shots time=%{time_total} size=%{size_download} code=%{http_code}\n' http://127.0.0.1:8081/api/v1/shots/episode/13
curl -b 'sessionid=<local-session>' -w 'django_page_auth time=%{time_total} size=%{size_download} code=%{http_code}\n' http://127.0.0.1:8000/85/episodes/13/
```

验证结果：

- Django 检查通过。
- Java 编译通过。
- 后端已重启，PID `55193`，`8081` 正常监听。
- 前端 Django 仍在 `8000` 正常监听。
- `/api/v1/episodes/13?basic=true` 耗时约 `0.33s`，响应约 `4KB`。
- `/api/v1/shots/episode/13` 耗时约 `1.44s`，响应约 `38KB`。
- 登录态访问 `/85/episodes/13/` 耗时约 `1.45s`，HTTP `200`。
- HTML 体积从约 `517KB` 降到约 `476KB`。

## 后续建议

- 如果还要继续压到 1 秒以内，下一步应优化 `/api/v1/shots/episode/{episodeId}`：
  - 减少分镜列表查询字段。
  - 把角色、道具资产 URL 映射改成更轻量的返回结构。
  - 避免把未用于首屏的 `charactersJson`、`propsJson`、`referencePrompt`、`userPrompt` 等字段返回到列表接口。
