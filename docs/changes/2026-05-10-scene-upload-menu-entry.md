# 场景单卡片上传入口补齐

## 编号

CHG-20260510-032

## 背景

场景资产已支持本地上传和裁剪，但具体某个场景的上传入口只放在图片右上角 hover 操作区。实际使用时入口不够明显，用户会以为单个场景没有“上传图片”功能，而道具资产的三点菜单里已经有对应入口。

## 改动

- 在场景资产三点菜单中新增“上传图片”菜单项。
- 点击后复用已有的单场景上传弹窗，并自动带入当前场景名称。
- 保留原图片 hover 上传图标，两种入口都可用。

## 影响范围

- `frontend/templates/episode/episode_detail.html`

## 验证

- `python3 manage.py check` 通过。
- `node --check /tmp/episode_detail_script_check.js` 通过。
- `mvn -DskipTests compile` 通过。
- 前后端已重启。
