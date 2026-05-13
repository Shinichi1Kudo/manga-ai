# 待审核分镜增加锁定入口

## 编号

CHG-20260512-004

## 背景

待审核分镜上传或生成视频后，用户还需要一个明确动作把该分镜确认锁定，并移动到已锁定分镜列表。

## 改动

- 已完成视频且仍处于待审核状态的分镜卡片新增“锁定分镜”按钮。
- 点击后复用现有分镜审核通过接口，将分镜状态更新为已通过。
- 锁定成功后不整页刷新，直接把卡片移动到“已锁定分镜列表”并刷新两侧计数。
- 上传视频成功后动态重绘的分镜卡片也会带上“锁定分镜”按钮。
- 已锁定分镜会移除上传视频和锁定入口，避免重复操作。

## 影响范围

- `frontend/templates/episode/episode_detail.html`
- `docs/changes/README.md`

## 验证

- `node --check /tmp/episode_detail_script_check.js` 通过。
- `python3 manage.py check` 通过。
- `mvn -DskipTests compile` 通过。
- 前后端已重启，`8000` 和 `8081` 均已监听。
