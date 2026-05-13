# CHG-20260513-004 主体替换人物身份与人脸强约束

## 背景

主体替换任务中，用户写了“粉色衣服成熟女性”，结果模型主要替换了衣服，人脸仍偏原视频人物。日志确认参考图已随 `reference_image` 传给火山，问题是提示词没有明确禁止“只换衣服、不换脸”。

## 改动

- 主体替换 prompt 将人物替换改为“完整迁移参考图人物身份”。
- 明确要求人物替换必须覆盖脸型、五官、发型、年龄感、肤色、妆容、服装和整体气质。
- 明确禁止只替换服装、颜色或发型，禁止保留原视频人物的脸、五官、脸型或年龄感。
- 当用户描述较短时，后端要求模型以参考图完整视觉身份为主。
- 前端“替换成”输入框 placeholder 改成更适合换脸/换身份的示例。

## 影响范围

- `backend/src/main/java/com/manga/ai/subject/service/impl/SubjectReplacementServiceImpl.java`
- `frontend/templates/subject_replacement/index.html`

## 验证

- `python3 manage.py check`
- `mvn -DskipTests compile`
- 重启前端 `8000` 与后端 `8081`
