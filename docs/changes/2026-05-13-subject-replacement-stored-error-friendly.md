# CHG-20260513-009 主体替换已落库错误二次友好化

## 背景

主体替换任务失败后，部分火山引擎英文错误已经原样写入任务表。即使后续 Seedance 调用层新增了错误码翻译，历史任务或已落库失败任务在查询时仍会把英文错误返回给前端，例如：

`The request failed because the input video may contain real person. Request id: ...`

这类提示对用户不友好。

## 改动

- 主体替换失败落库前再次调用友好错误转换。
- 查询任务详情和最近任务列表时，对 `errorMessage` 做二次友好化。
- 补充 `input video may contain real person`、`input image may contain real person` 等真人隐私类英文错误转换。
- 返回前移除 `Request ID`，避免把平台内部请求号直接展示给用户。

## 影响范围

- `backend/src/main/java/com/manga/ai/subject/service/impl/SubjectReplacementServiceImpl.java`

## 验证

- `mvn -DskipTests compile`
- 重启后端 `8081`
