# CHG-20260510-030 道具回滚后锁定不再覆盖版本

## 背景

`佩剑“破军”` 回滚到版本 1 后，刷新又显示版本 2。日志确认回滚请求进入后端时 `episodeId=null`，版本 1 没有补上当前剧集归属；随后触发锁定时，旧锁定逻辑按当前剧集最高版本选择资产，把版本 2 再次激活。

## 改动

- 道具锁定逻辑改为优先尊重当前 `isActive=1` 的资产，不再无条件按当前剧集最高版本覆盖用户刚回滚的版本。
- 锁定时如果当前激活资产缺少 `episode_id`，会补齐当前剧集 ID，保证刷新后仍能在本集可见。
- 回滚接口在缺少 `episodeId` 时，会从回滚前的当前激活资产推断剧集 ID，兼容用户打开的旧页面脚本。

## 影响范围

- `backend/src/main/java/com/manga/ai/prop/service/impl/PropServiceImpl.java`

## 验证

- `mvn -DskipTests compile`
- `python3 manage.py check`
- `node --check /tmp/episode_detail_script_check.js`

验证时间：2026-05-10 22:43:30 CST
