# CHG-20260510-002 我的资产库首屏与角色资产加载优化

## 背景

点击“我的资产库”时页面响应慢，主要原因有两类：

- 页面首屏渲染前，服务端同步请求 `/v1/series/locked`，导致页面必须等后端返回。
- 角色资产接口按角色串行请求 `/v1/assets/role/{roleId}`，角色多时会出现 N+1 风格的等待。

## 改动

- `asset_library_page` 不再首屏同步请求已锁定系列，页面先返回，再由浏览器异步请求系列列表。
- 给前端代理层增加 30 秒内存缓存，缓存 key 包含用户 token 和后端 endpoint。
- 角色资产由串行请求改成最多 8 个并发请求。
- 场景、道具、影视资产接口复用同一套短缓存，减少切换标签时重复打后端。
- 浏览器端增加同一系列同一资产类型的响应缓存。
- 资产库图片增加 `loading="lazy"` 和 `decoding="async"`，减少首屏一次性加载大图。
- 增加过期请求保护，快速切换系列或标签时，旧请求返回不会覆盖新内容。

## 涉及文件

- `frontend/apps/asset/views.py`
- `frontend/templates/asset/library.html`

## 行为变化

- 点击“我的资产库”后页面先打开，不再等待系列列表接口完成。
- 默认系列和资产内容异步加载。
- 同一个系列切换回已加载过的标签时，会直接使用浏览器缓存。
- 30 秒内重复请求同一后端资源，会走 Django 进程内短缓存。

## 验证

- `python3 manage.py check` 通过。
- 使用项目内 pycache 目录执行语法检查通过：

```bash
PYTHONPYCACHEPREFIX=/Users/zhangkuncheng/Downloads/aiwork/.pycache python3 -m py_compile apps/asset/views.py manga_ai/urls.py manga_ai/middleware.py
```

## 注意事项

- 缓存 TTL 为 30 秒，资产刚生成或刚删除时，资产库可能短暂显示旧数据。
- 缓存是 Django 进程内缓存，重启前端后会清空。
- 如果后续需要更精确的实时性，可以只对只读列表缓存，对生成状态类接口缩短 TTL 或加刷新按钮。

