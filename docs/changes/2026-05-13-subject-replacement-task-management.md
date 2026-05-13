# CHG-20260513-007 主体替换最近任务管理

## 背景

主体替换页的最近任务只能点击回显，不能修改任务名称，也不能删除不需要的任务。替换对象组里的参考图只能在小预览里看，不方便确认图片细节。

## 改动

- 最近任务卡片新增“改名”按钮。
- 最近任务卡片新增“删除”按钮。
- 后端新增主体替换任务改名接口，并校验只能修改当前用户自己的任务。
- 后端新增主体替换任务删除接口，并校验只能删除当前用户自己的任务。
- 前端 Django 代理新增对应改名和删除接口。
- 参考图预览支持点击查看大图。
- 删除当前正在查看或轮询的任务时，会停止轮询并清空右侧状态面板。

## 影响范围

- `backend/src/main/java/com/manga/ai/subject/controller/SubjectReplacementController.java`
- `backend/src/main/java/com/manga/ai/subject/service/SubjectReplacementService.java`
- `backend/src/main/java/com/manga/ai/subject/service/impl/SubjectReplacementServiceImpl.java`
- `frontend/apps/series/urls.py`
- `frontend/apps/series/views.py`
- `frontend/templates/subject_replacement/index.html`

## 验证

- `python3 manage.py check`
- `node --check /tmp/subject_replacement_script_check.js`
- `mvn -DskipTests compile`
- 重启前端 `8000` 与后端 `8081`
