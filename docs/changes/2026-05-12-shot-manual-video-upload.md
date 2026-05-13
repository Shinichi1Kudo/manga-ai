# 待审核分镜支持手动上传视频

## 编号

CHG-20260512-003

## 背景

待审核分镜原来只能通过火山引擎生成视频。用户已经有本地成片或需要替换某个分镜视频时，缺少直接上传并进入当前分镜版本的能力。

## 改动

- 待审核分镜卡片新增“上传视频”按钮，已锁定分镜不显示该入口。
- 上传弹窗使用分镜同一套比例选项：`16:9`、`4:3`、`1:1`、`3:4`、`9:16`、`21:9`。
- 前端读取本地视频尺寸：
  - 比例匹配时直接上传原视频。
  - 比例不匹配时进入视频裁剪面板，支持拖动取景和缩放后再上传。
- 上传成功后即时更新当前分镜卡片的视频预览、状态和历史按钮。
- 后端新增手动上传接口，把上传视频保存到 OSS，写入 `shot_video_asset` 历史版本，并标记当前激活版本。
- 视频历史中对手动上传版本增加“手动上传”标记。
- 上传成功后分镜状态更新为“已完成”，审核状态仍保持待审核，方便用户继续审核锁定。

## 影响范围

- `backend/src/main/java/com/manga/ai/common/service/OssService.java`
- `backend/src/main/java/com/manga/ai/shot/controller/ShotController.java`
- `backend/src/main/java/com/manga/ai/shot/service/ShotService.java`
- `backend/src/main/java/com/manga/ai/shot/service/impl/ShotServiceImpl.java`
- `backend/src/main/resources/application.yml`
- `frontend/apps/series/urls.py`
- `frontend/apps/series/views.py`
- `frontend/manga_ai/settings.py`
- `frontend/templates/episode/episode_detail.html`

## 验证

- `mvn -DskipTests compile` 通过。
- `python3 manage.py check` 通过。
- `node --check /tmp/episode_detail_script_check.js` 通过。
- 前后端已重启，`8000` 和 `8081` 均已监听。
