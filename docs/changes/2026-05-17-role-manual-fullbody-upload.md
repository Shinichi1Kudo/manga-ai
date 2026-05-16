# CHG-20260517-009 添加人物支持手动上传人物全身图

## 背景

创建系列和系列详情/角色审核页里，添加人物只能调用生图模型生成角色图。用户已有更精细的人物全身图或三视图时，需要能直接上传并作为待审核角色资产使用。

## 改动

- 创建系列-添加人物增加“自行上传人物全身图【推荐三视图或者更精细的图】”选项。
- 系列详情/角色审核-添加人物弹窗同步增加同一选项。
- 选择手动上传后，会禁用并取消：
  - 人物精细三视图（含具体细节）
  - 人物三视图（含大头特写）
- 上传前要求先选择图片比例；比例不一致时弹出裁剪框，用户需要手动裁剪。
- 上传后如果又切换图片比例，会清空当前裁剪结果并要求重新上传/裁剪。
- 手动上传仍要求选择图片风格；选择真人风格时展示醒目风险提示：
  - 真人风格下展示兼容性提示：上传非即梦生成的人物全身图时，Seedance 2.0 系列模型在分镜视频生成环节可能触发真人隐私校验，建议改用平台生成的角色三视图以提升稳定性。
- 前端先将裁剪后的图片通过 `/api/upload/` 上传到 OSS 的 `characters` 目录，再把 `uploadedImageUrl` 传给后端。
- 后端创建角色时，如果收到 `uploadedImageUrl`：
  - 直接创建待审核角色资产。
  - 跳过角色图片生成任务。
  - 跳过角色生图积分扣除。

## 影响范围

- `frontend/templates/series/series_init.html`
- `frontend/templates/series/series_review.html`
- `frontend/apps/series/views.py`
- `frontend/apps/series/tests.py`
- `backend/src/main/java/com/manga/ai/common/controller/UploadController.java`
- `backend/src/main/java/com/manga/ai/role/dto/RoleCreateRequest.java`
- `backend/src/main/java/com/manga/ai/role/service/impl/RoleServiceImpl.java`
- `backend/src/main/java/com/manga/ai/series/service/impl/SeriesServiceImpl.java`
- `backend/src/test/java/com/manga/ai/role/service/impl/RoleServiceImplTest.java`
- `backend/src/test/java/com/manga/ai/series/service/impl/SeriesServiceImplTest.java`

## 验证

- `python manage.py test apps.series.tests.RoleNameGuidanceTests`
- `python manage.py check`
- `mvn -Dtest=RoleServiceImplTest,SeriesServiceImplTest test`
- 逐个模板提取 `<script>` 后执行 `node --check`
