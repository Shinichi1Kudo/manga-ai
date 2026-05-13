# CHG-20260513-006 主体替换任务回显与火山错误友好提示

## 背景

主体替换页的“最近任务”只能切换右侧生成状态，不能把原视频、视频比例、时长、替换对象组、参考图和定位信息回显到左侧表单。失败时，火山引擎返回的英文错误码也会直接展示给用户，提示不够可理解。

## 改动

- 点击最近任务后，自动回填原视频、视频生成比例、时长和所有替换对象组。
- 替换对象组会回显替换类型、原视频对象描述、替换后描述、参考图、出现时间、画面位置和外观定位信息。
- 历史参考图会直接显示缩略图，并保留 OSS URL，用户可以直接基于历史任务再次提交或修改。
- 后端 Seedance 调用统一将火山错误码转成中文友好提示。
- 提交阶段和查询阶段的 HTTP 错误都会走同一套错误转换逻辑。
- 兼容火山错误体里 `error.code/message` 和顶层 `code/message` 两种结构。

## 错误提示覆盖

- 缺少参数、非法参数、图片 URL 无效、上下文超限。
- 文本、图片、视频、音频输入或输出敏感内容。
- 版权限制、真人隐私信息。
- 鉴权失败、账号异常、余额不足、模型服务未开通、无权限访问。
- 模型或接入点不可用、限流、额度耗尽、服务过载、并发超限。
- 火山内部异常、数据格式异常。

## 影响范围

- `frontend/templates/subject_replacement/index.html`
- `backend/src/main/java/com/manga/ai/video/service/impl/SeedanceServiceImpl.java`

## 验证

- `python3 manage.py check`
- `node --check /tmp/subject_replacement_script_check.js`
- `mvn -DskipTests compile`
- 重启前端 `8000` 与后端 `8081`
