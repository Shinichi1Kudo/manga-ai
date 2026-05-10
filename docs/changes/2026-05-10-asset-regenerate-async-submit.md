# CHG-20260510-016 场景/道具重生成提交超时修复

## 背景

用户反馈：

“http://localhost:8000/79/episodes/9/ 前端页面一直卡在提交中，我点了重新生成红烛。”

随后页面提示：

“请求失败: Unexpected token '<', "<!DOCTYPE "... is not valid JSON”

## 日志结论

- 前端请求 `POST /api/v1/props/97/regenerate/`。
- Django 代理等待后端超过 60 秒，抛出 `ReadTimeout`。
- Django 返回了 500 HTML 错误页，前端仍按 JSON 解析，导致 `Unexpected token '<'`。
- 后端日志显示旧请求实际在 HTTP 请求线程中同步等待火山引擎生成图片，直到图片生成完成才继续返回。
- 红烛这次旧请求后来已生成成功，生成版本为 `红烛_v2.png`，道具状态已回到 `待审核`。

## 原因

`generatePropAssetsWithCredit` / `regeneratePropAssetWithCredit` 以及同类场景接口在同一个 Service 内部调用带 `@Async` 的方法。Spring 的 `@Async` 通过代理生效，自调用不会走代理，因此实际变成同步执行，HTTP 请求线程会一直等火山引擎图片生成完成。

## 改动

- 场景和道具的含扣费生成/重生成接口改为显式提交到 `imageGenerateExecutor`。
- 后端接口现在只做校验、扣费和任务提交，随后立即返回 JSON。
- 真正调用火山引擎生成图片的耗时逻辑在后台线程执行。
- Django 代理补充 `ReadTimeout` JSON 返回，避免把 500 HTML 错误页透给前端。
- 前端重生成弹窗改用安全 JSON 解析：
  - 如果服务端返回 HTML 或其他非 JSON 响应，显示可读错误。
  - 不再出现 `Unexpected token '<'` 这种解析异常。

## 涉及文件

- `backend/src/main/java/com/manga/ai/prop/service/impl/PropServiceImpl.java`
- `backend/src/main/java/com/manga/ai/scene/service/impl/SceneServiceImpl.java`
- `frontend/api/backend_client.py`
- `frontend/apps/series/views.py`
- `frontend/templates/episode/episode_detail.html`

## 行为变化

- 点击“重新生成红烛”等道具重生成时，弹窗提交会快速完成并关闭。
- 页面会进入生成中状态并继续轮询，不再等图片生成全过程。
- 后端生成耗时超过 60 秒时，也不会再返回 HTML 导致前端 JSON 解析报错。

## 验证状态

当前状态：已编译、已检查、已重启前后端。

已执行：

```bash
mvn -DskipTests compile
python3 manage.py check
tmux new-session -d -s aiwork-backend ...
tmux new-session -d -s aiwork-frontend ...
lsof -nP -iTCP:8081 -sTCP:LISTEN
lsof -nP -iTCP:8000 -sTCP:LISTEN
```

验证结果：

- Java 编译通过。
- Django 检查通过。
- 后端 `8081` 正常监听，PID 为 `54445`。
- 前端 `8000` 正常监听，PID 为 `54234`。
