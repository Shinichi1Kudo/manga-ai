# 资产库增加剧集制作跳转入口

## 编号

CHG-20260512-002

## 背景

用户在首页“我的资产库”选择系列后，希望能直接跳转到对应系列的剧集制作管理页面，避免再回首页手动寻找。

## 改动

- 在资产库“选择系列”旁新增“剧集制作管理”按钮。
- 按当前选中系列直接跳转到 `/<series_id>/episodes/`。
- 切换系列时同步刷新按钮链接。

## 影响范围

- `frontend/templates/asset/library.html`
- `frontend/apps/asset/views.py`
- `frontend/apps/asset/urls.py`

## 验证

- `python3 manage.py check` 通过。
- `node --check /tmp/asset_library_script_check.js` 通过。
- `mvn -DskipTests compile` 通过。
- 前后端已重启。
