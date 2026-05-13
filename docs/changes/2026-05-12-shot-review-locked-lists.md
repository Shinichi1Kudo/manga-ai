# 分镜列表拆分为待审核和已锁定

## 编号

CHG-20260512-001

## 背景

剧集详情页原来只显示一个“分镜列表”，审核通过后的分镜和待审核分镜混在一起，不利于用户区分当前还需要处理的分镜。

## 改动

- 将原“分镜列表”标题改为“待审核分镜列表”。
- 新增“已锁定分镜列表”区域。
- 页面加载后按分镜审核状态拆分：
  - `status != 1` 保留在待审核分镜列表。
  - `status == 1` 移动到已锁定分镜列表。
- 审核通过后不再整页刷新，直接把分镜移动到已锁定分镜列表并刷新计数。
- 拖拽排序仍只作用于待审核分镜列表，避免已锁定分镜被混入排序。

## 影响范围

- `frontend/apps/series/views.py`
- `frontend/templates/episode/episode_detail.html`

## 验证

- `python3 manage.py check` 通过。
- `node --check /tmp/episode_detail_script_check.js` 通过。
- `mvn -DskipTests compile` 通过。
- 前后端已重启。
