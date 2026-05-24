# CHG-20260524-001 短剧角色提示词与电商试穿工作流集中升级

## 背景

近期连续调整了短剧角色生图、电商模特、电商商品图、场景库和试穿视频生成。原 README 还停留在电商 MVP 阶段，`docs/changes` 最新只到 2026-05-17，已经不能反映当前真实功能和接口行为。

## 改动

- 短剧添加人物生图提示词规范化：
  - 角色图不再直接把整个系列风格塞进图片生成提示词。
  - 每个人物按自己选择的图片风格映射具体风格描述。
  - “大头特写+三视图”只补充布局、构图、一致性和完整全身约束，避免重复带入形象描述。
  - 角色审核页的创建配置绑定当前图片版本，切换缩略图或回滚版本时同步展示该版本的风格、比例、清晰度和高级方式。
- 电商模特生成优化：
  - 模特列表按最新生成倒序展示，并记录/展示创建时间。
  - 模特列表里的模特提示词默认收起，用户点击“展开提示词”后再查看完整内容，减少卡片高度。
  - 电商模特三视图提示词增加侧面/背面头发、发根、发缝、后脑勺、肩线和站姿重心约束，减少头发漂移和侧背面换人感。
- 电商商品图管理重构：
  - 商品图最多 5 张，按主图、细节图、上身参考分组上传。
  - 商品详情支持缩略图切换、大图预览、替换当前商品图、添加图片和删除图片。
  - 替换、添加、删除尽量局部更新，不再依赖整页刷新。
  - 商品类型增加一级/二级分类筛选，“其他”独立成组。
- 电商场景库独立工作区：
  - 新增基础商拍和出片氛围两类场景。
  - 支持品牌场景、直播间背景、棚拍场景、商场试衣间、室内全身镜、街拍、田间、海边、城市夜景、艺术馆、酒店民宿等固定场景，以及其他场景聚合。
  - 品牌场景仅在选中时填写品牌名，并要求品牌字样、Logo 或广告牌自然出现在场景中。
  - 场景图批量生成拆成 `prepare` 和 `start` 两步：先落库生成中占位，再异步调用图片生成，前端轮询刷新状态。
  - 场景提示词根据场景类型、时间氛围和人群氛围补充电影感光影、景深、空间透视和空场景约束。
- 电商试穿视频链路重构：
  - 取消试穿首帧确认入口，正式生成直接使用模特参考图、最多 5 张商品图和 1 张场景图。
  - 试穿支持展示方式、表演风格、动作表情、视频比例、模型、清晰度和秒数选择。
  - 展示方式最多 4 个，默认 Seedance 2.0 VIP、480p、8 秒。
  - Seedance 2.0 VIP / Fast VIP 使用火山 Ark 内容生成接口，Kling v3 Omni 保留 Toapis 兼容格式。
  - 视频生成拆成“创建项目”和“启动生成”两步，前端立即显示生成中卡片，刷新或新标签页可恢复状态。
  - 试穿提示词逐张说明商品图用途，从图2开始标注主图、细节图、上身参考；场景图固定为单张并作为唯一场景基准。
  - 试穿提示词强化动作连贯、表演感、镜头推进/拉远/角度变化、眼神互动、纯音乐 BGM、大片氛围和道具入画来源。
  - 调用视频接口前对用户上传的商品图做 OpenCV / JavaCV 人脸检测脱敏；模特基础图不脱敏。
  - 视频管理支持生成中轮询、部分完成状态、大窗播放、下载和防连点提交。
- README 同步更新：
  - 功能概览、近期更新、技术栈、数据表、工作流、API 列表、配置示例和开发进度均补齐近期变更。
  - 删除不存在的 `commerce.try-on-video` 和 `commerce.product-image-privacy` 配置示例，改为说明电商试穿视频复用 `volcengine.seedance`，商品图脱敏使用内置 OpenCV Haar cascade。

## 影响范围

- `README.md`
- `docs/changes/README.md`
- `backend/src/main/java/com/manga/ai/commerce/**`
- `backend/src/main/java/com/manga/ai/image/service/impl/ImageGenerateServiceImpl.java`
- `backend/src/main/java/com/manga/ai/video/service/impl/SeedanceServiceImpl.java`
- `backend/src/main/resources/opencv/haarcascade_frontalface_alt.xml`
- `backend/src/test/java/com/manga/ai/commerce/**`
- `backend/src/test/java/com/manga/ai/image/service/impl/ImageGenerateServiceImplTest.java`
- `frontend/apps/series/views.py`
- `frontend/apps/series/urls.py`
- `frontend/apps/series/tests.py`
- `frontend/templates/commerce/**`
- `frontend/templates/series/series_review.html`
- `frontend/templates/series/series_init.html`

## 验证

- `python manage.py test apps.series.tests --keepdb`
- `git diff --check`

## 注意

- 试穿首帧确认相关数据库字段和后端兼容方法仍保留，但当前前端流程不再展示和使用首帧确认。
- `volcengine.seedance.fast-base-url` 仍保留给 Toapis 兼容路径使用；当前 `SeedanceServiceImpl` 只有 Kling v3 Omni 会走 Toapis。
- 商品图人脸脱敏是降低上游真人隐私拦截概率，不保证所有侧脸、遮挡、复杂角度都能被 OpenCV Haar cascade 命中。
