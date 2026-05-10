# CHG-20260510-004 剧集详情分镜内联资产显示增强

## 背景

剧集详情页的分镜内容、场景和道具展示需要更稳定地显示缩略图。当前工作区已有相关改动：后端返回更多分镜关联资产信息，前端优先使用后端实时返回的资产 URL 渲染内联缩略图。

## 改动

- `ShotDetailVO` 增加 `sceneAssetUrl` 字段，用于返回场景资产缩略图 URL。
- `ShotServiceImpl` 批量查询场景激活资产，避免逐条查场景图。
- `ShotServiceImpl` 针对道具：
  - 批量查询系列道具和激活道具资产。
  - 建立道具名称到资产的映射。
  - 从 `propsJson` 补充 `ShotProp` 表中没有的道具。
- `episode_detail.html`：
  - 在分镜卡片和描述编辑器上挂载 `shot.props` 数据。
  - 场景编辑器增加 `data-scene-asset-url`。
  - 渲染描述时优先使用分镜实时返回的角色/道具资产。
  - 渲染场景时优先使用后端返回的 `sceneAssetUrl`。

## 涉及文件

- `backend/src/main/java/com/manga/ai/shot/dto/ShotDetailVO.java`
- `backend/src/main/java/com/manga/ai/shot/service/impl/ShotServiceImpl.java`
- `frontend/templates/episode/episode_detail.html`

## 行为变化

- 分镜详情里的场景、角色、道具缩略图更依赖后端实时数据，而不是只依赖页面预加载的资产库。
- `propsJson` 中存在但 `ShotProp` 关联表没有同步的数据，也可以尝试展示道具名称和资产图。

## 验证状态

当前状态：待回归验证。

建议回归页面：

- 剧集详情页分镜列表是否正常打开。
- 场景名称旁是否能显示对应缩略图。
- 分镜描述中的角色、道具缩略图是否正常显示。
- 手动编辑过的场景和描述是否仍能正确渲染 `@{名称}`。

## 注意事项

- `propsJson` 解析失败时只记录 warn，不中断页面。
- 道具去重目前按名称判断，若同名不同道具，可能只显示第一份资产。

