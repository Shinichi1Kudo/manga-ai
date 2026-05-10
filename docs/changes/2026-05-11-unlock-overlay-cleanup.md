# 解锁后锁图标残留修复

## 编号

CHG-20260511-001

## 背景

场景/道具解锁后，卡片状态和菜单状态已经更新，但图片左上角仍可能残留锁图标。原因是初始模板渲染的锁标记没有统一使用 `lock-overlay` class，而局部解锁逻辑只删除 `.lock-overlay`。

## 改动

- 初始渲染的场景锁图标增加 `lock-overlay` class。
- 初始渲染的道具锁图标增加 `lock-overlay` class。
- 解锁时会清理图片容器下所有锁标记，兼容旧 DOM 残留。

## 影响范围

- `frontend/templates/episode/episode_detail.html`

## 验证

- `python3 manage.py check` 通过。
- `node --check /tmp/episode_detail_script_check.js` 通过。
- `mvn -DskipTests compile` 通过。
- 前后端已重启。
