# 剧集制作角色名提示增强

## 背景

剧集制作创建页面的“已有角色”提示不够明确，用户可能在剧本中使用角色别称、职业称呼或其他不一致的人物名，导致后续分镜解析和角色资产匹配不稳定。

## 改动

- 将提示文案改为“已有角色（请在剧本中使用下方名字作为剧本里出现的人物角色名）”。
- 将提示块改为琥珀色高亮样式，并加入警示图标。
- 角色名标签也同步改为高亮样式，让可复制/可参考的角色名更醒目。

## 影响范围

- `frontend/templates/episode/episode_create.html`
- `frontend/apps/series/tests.py`

## 验证

- `python manage.py test apps.series.tests.EpisodeCreateRoleNameHintTests`
- `python manage.py check`
