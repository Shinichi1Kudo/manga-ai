# CHG-20260513-018 主体替换补充定位信息改为语言

## 背景

主体替换功能里的“补充定位信息”包含一个“衣着/颜色/道具”字段。现在需要把它改成“语言”，让用户填写如 `英语`、`中文`、`日语` 等语言要求，并让后端提示词按该语言执行替换。

## 改动

- 前端主体替换页将“衣着/颜色/道具”字段改名为“语言”。
- 前端语言输入框 placeholder 改为 `可选，如 英语、中文、日语`。
- 后端仍复用 `appearanceHint` 字段存储，兼容历史任务回显和已有接口结构。
- 后端提示词把该字段从 `外观定位信息：xxx` 改为 `语言改成xxx`。
- 后端提示词增加规则：如果映射中填写了 `语言改成xxx`，则替换对象涉及的口播、字幕、可见文字或语言风格改成 `xxx`；没有填写语言时，不额外改变语言。
- 新增单测覆盖“填写英语时 prompt 包含语言要求且不再输出外观定位信息”。

## 影响范围

- `frontend/templates/subject_replacement/index.html`
- `backend/src/main/java/com/manga/ai/subject/dto/SubjectReplacementItemDTO.java`
- `backend/src/main/java/com/manga/ai/subject/service/impl/SubjectReplacementServiceImpl.java`
- `backend/src/test/java/com/manga/ai/subject/service/impl/SubjectReplacementServiceImplTest.java`

## 验证

- `mvn -Dtest=SubjectReplacementServiceImplTest test`
- `python3 manage.py check`
- `mvn -DskipTests compile`
- 重启前端 `8000` 与后端 `8081`
- 浏览器打开主体替换页检查字段文案
