# CHG-20260510-017 道具待审核资产按剧集隔离

## 背景

用户反馈：

“第2集 回门打脸，霸气护妻 道具资产怎么看到了第一集里生成的待审核图片红烛？应该待审核只能看到本集生产的，锁定的才是所有集都能共享的。”

## 日志和数据结论

- 第 2 集页面访问 `GET /79/episodes/10/` 后持续请求 `GET /api/v1/props/series/79/`。
- 旧接口返回系列 79 下全部道具，未区分当前剧集。
- 数据库中 `红烛` 为 `propId=97`，状态是 `待审核`。
- `红烛` 没有分镜关联到第 2 集；只在第 1 集 `episodeId=9` 的 `props_json` 中出现。
- 因为道具资产版本没有记录来源剧集，前端只能看到系列级“待审核红烛”，所以第 2 集也显示了第 1 集生成的图。

## 规则

- `生成中` / `待审核`：只在生成它的剧集中可见。
- `已锁定`：作为系列资产，在所有剧集中可见。
- 锁定道具时，优先把当前剧集生成的版本设为全系列共享版本。

## 改动

- `prop_asset` 增加 `episode_id` 字段，用来记录每个道具图片版本的来源剧集。
- 手动添加道具、重新生成道具、批量解析后生成道具，都把当前 `episodeId` 写入新资产版本。
- 后端道具列表接口支持 `episodeId` 查询参数：
  - 已锁定道具返回全系列共享版本。
  - 未锁定道具只返回当前剧集的资产版本。
- 后端道具详情接口支持 `episodeId` 查询参数，保证轮询、历史弹窗和卡片更新口径一致。
- 前端剧集详情首屏、跨标签同步、轮询详情、重生成、锁定请求都带上当前 `episodeId`。
- 前端同步时会清理当前剧集不可见的未锁定道具卡片，已打开的旧页面无需手动刷新也能纠正串集显示。
- 分镜资产引用也按相同规则选择道具图，避免后续生成视频时引用到其他剧集未锁定版本。
- 已对现有红烛数据回填：`propId=97` 的两个资产版本都标记为 `episode_id=9`。

## 涉及文件

- `backend/src/main/java/com/manga/ai/common/config/SchemaMigrationConfig.java`
- `backend/src/main/java/com/manga/ai/prop/controller/PropController.java`
- `backend/src/main/java/com/manga/ai/prop/dto/PropDetailVO.java`
- `backend/src/main/java/com/manga/ai/prop/entity/PropAsset.java`
- `backend/src/main/java/com/manga/ai/prop/service/PropService.java`
- `backend/src/main/java/com/manga/ai/prop/service/impl/PropServiceImpl.java`
- `backend/src/main/java/com/manga/ai/episode/service/impl/EpisodeServiceImpl.java`
- `backend/src/main/java/com/manga/ai/shot/service/impl/ShotServiceImpl.java`
- `backend/src/main/resources/db/migration/V2026.05.10__Add_Episode_Id_To_Prop_Asset.sql`
- `backend/src/main/resources/mysql_episode_tables.sql`
- `backend/src/main/resources/schema.sql`
- `frontend/apps/series/views.py`
- `frontend/templates/episode/episode_detail.html`

## 验证状态

当前状态：已编译、已检查、已重启前后端。

已执行：

```bash
mvn -DskipTests compile
python3 manage.py check
ALTER TABLE prop_asset ADD COLUMN episode_id BIGINT NULL COMMENT '生成来源剧集ID'
CREATE INDEX idx_prop_asset_episode_id ON prop_asset (episode_id)
UPDATE prop_asset ... SET episode_id = 9 WHERE propId=97
```

验证结果：

- Java 编译通过。
- Django 检查通过。
- 数据库已存在 `prop_asset.episode_id` 和 `idx_prop_asset_episode_id`。
- `红烛` 的 `episode_id` 已回填为第 1 集 `episodeId=9`。
