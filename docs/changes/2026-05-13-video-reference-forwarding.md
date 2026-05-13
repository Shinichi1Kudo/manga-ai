# CHG-20260513-003 视频生成参考图传递与主体替换映射增强

## 背景

用户反馈视频时长从 4 秒改成 8 秒后生效，但视频里人物没有按参考图替换。排查发现主体替换页面会上传并提交参考图，火山 Seedance 2.0 官方结构也是 `image_url role=reference_image` 与 `video_url role=reference_video`；风险点在于业务提示词里参考图编号与替换对象绑定不够强，普通分镜生成也可能只带编辑器内显式缩略图，漏掉分镜绑定角色的生效图片。

## 改动

- 主体替换提交前重新按当前替换项构建 prompt，明确声明第 N 个 `image_url` 即图 N。
- 主体替换 prompt 增加“一一对应、不得串用参考图”的强约束。
- 主体替换执行时记录 `taskId、图N、原对象、目标描述、参考图 URL`，后续查日志可以直接判断参考图是否传给火山。
- 分镜生成参考图收集改为前后端双保险：
  - 前端点击生成/重新生成时收集编辑器内缩略图、场景图、角色图、道具图。
  - 后端再合并当前分镜绑定的场景、角色、道具生效图，避免前端漏传导致角色参考图缺失。
- 分镜生成优先保留 prompt 内 `[图N]` 已匹配的参考图顺序，再追加页面/数据库兜底参考图。
- 分镜生成 prompt 末尾追加最终参考图映射，确保 `[图N]` 与火山 `content` 中第 N 张图片一致。
- 视频版本元数据保存最终实际传入的参考图 URL，便于历史排查。

## 影响范围

- `frontend/templates/episode/episode_detail.html`
- `backend/src/main/java/com/manga/ai/subject/service/impl/SubjectReplacementServiceImpl.java`
- `backend/src/main/java/com/manga/ai/shot/service/impl/ShotServiceImpl.java`

## 验证

- `python3 manage.py check`
- `node --check /tmp/episode_detail_script_check.js`
- `mvn -DskipTests compile`
- 重启前端 `8000` 与后端 `8081`
