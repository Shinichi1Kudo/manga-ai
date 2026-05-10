# CHG-20260510-023 角色版本切换脚本中断修复

## 背景

剧集详情页控制台出现 `Identifier 'roleCard' has already been declared`，随后继续出现 `switchRoleClothing is not defined`。这会导致页面后续脚本没有完成注册，分镜列表里的文字渲染和交互逻辑也会被连带打断。

## 原因

上一次给角色资产增加“当前默认/当前预览”版本标记时，在 `switchRoleClothing(thumbEl)` 内重复声明了 `const roleCard`。浏览器解析脚本时遇到重复声明直接抛出语法错误，后面的函数和初始化代码都不会继续执行。

## 改动

1. 删除 `switchRoleClothing(thumbEl)` 内重复的 `const roleCard` 声明。
2. 复用函数开头已经找到的角色卡片 DOM，继续更新主图预览、缩略图选中态和当前版本标记。

## 影响范围

- `frontend/templates/episode/episode_detail.html`

## 验证

- `python3 manage.py check`
- 已确认模板中 `roleCard` 只保留一处声明。
- 前后端已重启。
