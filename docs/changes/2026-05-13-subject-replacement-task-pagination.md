# CHG-20260513-008 主体替换最近任务分页

## 背景

主体替换页右侧“最近任务”会一次性展示所有已加载任务，任务稍多时侧栏变长，不方便浏览。

## 改动

- 最近任务每页最多展示 5 条。
- 最近任务区域新增上一页、下一页和页码显示。
- 翻页在前端本地完成，避免每次翻页都重新请求接口。
- 空任务时隐藏分页控件。

## 影响范围

- `frontend/templates/subject_replacement/index.html`

## 验证

- `python3 manage.py check`
- `node --check /tmp/subject_replacement_script_check.js`
- 重启前端 `8000` 与后端 `8081`
