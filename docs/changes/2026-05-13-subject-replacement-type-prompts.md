# CHG-20260513-005 主体替换人物/物品两套提示词

## 背景

主体替换前一次增强把人物身份、人脸和五官约束写得更强，但这类约束不适合物品替换。物品替换应该只关注物体外观、材质、纹理、位置和交互关系，不应该带上换脸或人物身份迁移要求。

## 改动

- 每组主体替换对象新增“替换类型”：人物、物品。
- 前端提交时会把 `replacementType` 一起传给后端。
- 后端对旧数据保持兼容：未传类型时默认按人物处理。
- 人物类型继续使用身份迁移提示词，强调脸型、五官、发型、年龄感、肤色、妆容、服装和整体气质。
- 物品类型使用物品提示词，只替换物品本身，强调形状、材质、颜色、纹理、结构和可见文字/图案。
- 后端替换要求改成按任务中实际出现的类型追加约束，避免物品任务被人物换脸要求污染。
- 日志增加替换类型，方便后续排查模型效果。
- 后端兼容 `prop`、`product` 等旧值，但统一归一为物品；前端只展示人物和物品两个选项。

## 当前提示词结构

### 人物

- 映射中标记“替换类型：人物”。
- 目标为“第 N 张参考图/图 N 中的同一人物/角色身份”。
- 要求完整迁移参考图人物身份。
- 明确禁止只替换服装、颜色或发型，也禁止保留原视频人物脸型、五官和年龄感。

### 物品

- 映射中标记“替换类型：物品”。
- 目标为“第 N 张参考图/图 N 中的同一物品外观”。
- 只替换指定物品本身。
- 强调参考图里的形状、材质、颜色、纹理、结构和可见文字/图案。
- 保留原物品的位置、大小关系、运动轨迹、遮挡关系和被手持/触碰方式。

## 影响范围

- `backend/src/main/java/com/manga/ai/subject/dto/SubjectReplacementItemDTO.java`
- `backend/src/main/java/com/manga/ai/subject/service/impl/SubjectReplacementServiceImpl.java`
- `frontend/templates/subject_replacement/index.html`

## 验证

- `python3 manage.py check`
- `node --check /tmp/subject_replacement_script_check.js`
- `mvn -DskipTests compile`
- 重启前端 `8000` 与后端 `8081`
