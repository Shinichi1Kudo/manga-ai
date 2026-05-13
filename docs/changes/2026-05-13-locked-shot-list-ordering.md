# CHG-20260513-002 已锁定分镜列表命名与排序独立化

## 背景

已锁定分镜列表由待审核分镜移动而来，但默认命名和排序仍主要沿用待审核列表逻辑。未自定义名称的已锁定分镜可能不会按已锁定列表内的 `分镜1、分镜2...` 展示，且已锁定列表不支持拖动调整顺序。

## 改动

- 已锁定分镜列表默认名称按本列表位置重新显示：未自定义名称时展示 `分镜1、分镜2...`。
- 锁定分镜后默认追加到已锁定列表最下面，并持久化为已锁定列表内的最大排序号 + 1。
- 解锁分镜后默认追加到待审核列表最下面，并回填已锁定列表剩余分镜排序号。
- 待审核列表和已锁定列表各自支持拖拽排序，排序请求会携带当前列表的审核状态，只调整当前列表内分镜。
- 后端分镜排序号按待审核和已锁定两个列表分别维护，避免两个列表互相影响默认编号。

## 影响范围

- `frontend/templates/episode/episode_detail.html`
- `backend/src/main/java/com/manga/ai/shot/controller/ShotController.java`
- `backend/src/main/java/com/manga/ai/shot/service/ShotService.java`
- `backend/src/main/java/com/manga/ai/shot/service/impl/ShotServiceImpl.java`
- `backend/src/main/java/com/manga/ai/shot/mapper/ShotMapper.java`

## 验证

- `python3 manage.py check`
- `node --check /tmp/episode_detail_script_check.js`
- `mvn -DskipTests compile`
- 重启前端 `8000` 与后端 `8081`
