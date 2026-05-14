# 海带 AI 智能短剧制作系统

一个基于 AI 的智能短剧制作系统，支持从剧本输入到分镜视频输出的完整闭环。

## 功能概览

### 用户系统（已完成）
- ✅ 邮箱注册与登录
- ✅ JWT Token 认证
- ✅ 积分系统
- ✅ 积分消费记录
- ✅ 兑换码功能（区分大小写，Redis 分布式锁防并发）
- ✅ 用户个人设置（头像上传、昵称修改）

### 阶段一：角色资产管理（已完成）
- ✅ 系列创建与管理
- ✅ 角色提取与图片生成
- ✅ 多服装版本管理
- ✅ 火山引擎 Seedream 图片生成
- ✅ 角色审核与锁定
- ✅ 图片自动上传 OSS（防止临时 URL 过期）
- ✅ 创建角色支持"大头特写+三视图"布局选项

### 阶段二：剧集循环制作（已完成）
- ✅ 剧集创建与管理
- ✅ LLM 剧本解析（豆包大模型）
- ✅ 自动分镜拆分（≤15秒/镜头）
- ✅ 场景与道具资产管理
- ✅ Seedance 2.0 / 2.0 Fast 视频生成（支持 480p/720p/1080p）
- ✅ 分镜审核与导出
- ✅ 视频版本管理与回滚
- ✅ 待审核/已锁定分镜列表拆分
- ✅ 分镜手动上传视频、比例裁剪、锁定/解锁与排序
- ✅ 分镜视频来源标记（手动上传/系统生成）
- ✅ 分镜文本格式化存储
- ✅ 中文错误信息提示
- ✅ 资产库大图预览与视频播放
- ✅ 角色@mention与缩略图系统
- ✅ 场景/道具历史版本预览
- ✅ 角色服装切换
- ✅ 分镜视频下载
- ✅ 剧集删除
- ✅ Redis Token 管理
- ✅ 分镜生成耗时按版本记录与回滚同步
- ✅ 分镜编辑状态标记与保存一致性保护
- ✅ 场景/道具资产手动上传、裁剪、历史版本与回滚
- ✅ 场景/道具生成中状态跨标签页同步
- ✅ 系列风格注入图片生成提示词
- ✅ 剧集详情页、我的系列、影视资产接口性能优化
- ✅ Redis 连接保活与快速失败配置

### 视频主体替换（已完成）
- ✅ 首页营销展示区与匿名可浏览首页
- ✅ 主体替换任务创建、最近任务、分页、重命名与删除
- ✅ 人物/物品两类提示词模板
- ✅ 语言修改提示词：支持“语言改成xxx”
- ✅ 原视频、参考图、替换对象组和生成参数回显
- ✅ 参考图大图预览
- ✅ 火山引擎错误码友好提示
- ✅ 演示素材接入 OSS（替换前视频、参考图、替换后视频）

### GPT-Image2 生图（已完成）
- ✅ 首页内嵌 GPT-Image2 生图工具
- ✅ 支持文生图和上传参考图后的图生图
- ✅ 参考图与生成结果自动上传 OSS
- ✅ 生成任务状态后台持久化，刷新或新标签页可恢复生成中/成功/失败状态
- ✅ 未登录用户可浏览入口，上传/生成前统一提示登录

## 近期更新

- GPT-Image2 状态持久化：生图改为后端任务制，支持刷新/新标签页恢复状态，并兼容上游返回 `data:image/...;base64` 图片数据后上传 OSS。
- 首页新增 GPT-Image2 生图工具：支持提示词文生图和参考图图生图，生成结果保存到 OSS。
- 首页匿名访问：未登录用户可以浏览营销内容，业务按钮统一提示先登录。
- 登录跳转修复：匿名 API 请求不再污染登录后的 `next`，避免登录后跳到 JSON 页面。
- 联系我们二维码修复：公共二维码接口匿名可访问，并增加加载失败/重试状态。
- 首页主体替换演示素材迁移到 OSS：部署环境通过公共接口获取签名 URL，不再依赖本地静态大文件。
- 主体替换能力上线：支持人物/物品两类定向替换、任务管理、参数回显、错误友好提示和首页演示区。
- 分镜列表升级：拆分待审核/已锁定分镜列表，支持视频手动上传、锁定、解锁、排序和来源标记。
- 剧集详情页加载优化：系列、剧集、分镜、场景、道具、角色资产并发加载，减少首屏等待。
- 我的系列和资产库性能优化：减少不必要的首屏阻塞请求，优化影视资产查询路径。
- 分镜体验增强：新增已编辑/未编辑标记，切换视频模型不再覆盖用户手动改过的剧情。
- 分镜资产实时绑定：场景/道具变化后自动刷新分镜中的缩略图和彩色标签。
- JSON 解析稳定性：模型返回非法 JSON 时自动重试，最多重试 3 次。
- 场景/道具资产上传：支持本地上传图片、比例裁剪、历史版本查看、回滚与锁定态保护。
- 资产状态一致性：生成、重新生成、上传、回滚、锁定/解锁后，前端跨标签状态保持一致。
- 生成质量修复：图片生成结合系列风格，道具上传和生成流程支持更稳定的透明背景/裁剪链路。
- 角色资产增强：剧集详情可跳转修改角色，默认生效角色版本有明确标记。
- 运维稳定性：Redis 连接保活，Token 认证失败更快返回，减少页面长时间等待。
- 变更文档目录：所有近期修复都记录在 `docs/changes/README.md`，可按编号查看明细。

## 项目结构

```
manga-ai/
├── backend/                    # Spring Boot 后端
│   └── src/main/java/com/manga/ai/
│       ├── asset/              # 资产管理模块
│       ├── common/             # 通用配置、枚举、工具类
│       ├── episode/            # 剧集管理模块
│       ├── gptimage/           # GPT-Image2 生图模块
│       ├── image/              # 图片生成服务
│       ├── llm/                # LLM服务（豆包）
│       ├── nlp/                # NLP处理模块
│       ├── prop/               # 道具管理模块
│       ├── role/               # 角色管理模块
│       ├── scene/              # 场景管理模块
│       ├── series/             # 系列管理模块
│       ├── shot/               # 分镜管理模块
│       ├── subject/            # 视频主体替换模块
│       ├── user/               # 用户管理模块
│       └── video/              # 视频生成服务（Seedance）
│
├── frontend/                   # Django 前端
    ├── api/                    # 后端API客户端
    ├── apps/
    │   ├── asset/              # 资产管理
    │   ├── auth/               # 用户认证
    │   ├── role/               # 角色管理
    │   └── series/             # 系列与剧集管理
    ├── static/                 # 首页演示图片/视频等静态素材
    ├── manga_ai/               # Django项目配置
    └── templates/              # HTML模板
        ├── auth/               # 登录注册页面
        ├── credits/            # 积分记录页面
        ├── subject_replacement/# 视频主体替换页面
        ├── series/             # 系列、角色审核页面
        └── episode/            # 剧集制作页面
│
└── docs/
    └── changes/                # 变更目录与明细文档
```

## 技术栈

### 后端
- Java 17
- Spring Boot 3.2.0
- MyBatis Plus
- MySQL
- 阿里云 OSS（图片/视频存储）
- WebSocket（实时进度推送）

### AI 服务
- 火山引擎 Seedream（图片生成）
- 火山引擎 Seedance 2.0（视频生成）
- 火山引擎豆包大模型（剧本解析）
- GPT-Image2（首页生图）

### 前端
- Python 3.x
- Django
- Tailwind CSS
- JavaScript（原生）

## 数据库设计

### 核心表
| 表名 | 说明 |
|------|------|
| `user` | 用户信息 |
| `credit_record` | 积分消费记录 |
| `email_verification` | 邮箱验证码 |
| `series` | 系列信息 |
| `role` | 角色信息 |
| `role_asset` | 角色资产（图片） |
| `episode` | 剧集信息 |
| `scene` | 场景信息 |
| `prop` | 道具信息 |
| `shot` | 分镜信息 |
| `shot_character` | 分镜-角色关联 |
| `shot_prop` | 分镜-道具关联 |
| `shot_video_asset` | 分镜视频版本资产（含生成耗时） |
| `shot_video_asset_metadata` | 视频生成参数记录 |
| `subject_replacement_task` | 视频主体替换任务记录 |
| `credit_record` | 积分消费记录 |

## 工作流程

### 1. 系列创建流程
```
创建系列 → 添加角色 → AI生成角色图片 → 审核确认 → 锁定系列
```

### 2. 剧集制作流程
```
选择系列 → 创建剧集 → 输入剧本 → LLM解析 →
审核分镜 → 生成场景/道具资产 → 生成分镜视频 → 导出
```

## 快速开始

### 环境要求
- JDK 17+
- Python 3.8+
- MySQL 8.0+
- Maven 3.x

### 后端启动
```bash
cd backend
mvn spring-boot:run -DskipTests
```
默认端口：8081，API 前缀：`/api`

### 前端启动
```bash
cd frontend
python manage.py runserver 8000
```
默认端口：8000

## API 接口

### 用户认证
- `POST /v1/auth/send-code` - 发送验证码
- `POST /v1/auth/register` - 用户注册
- `POST /v1/auth/login` - 用户登录
- `GET /v1/user/info` - 获取当前用户信息

### 积分相关
- `GET /v1/credits/records` - 获取积分记录（分页）
- `POST /v1/credits/redeem` - 兑换码兑换积分

### 系列相关
- `GET /v1/series/list` - 获取系列列表（分页）
- `POST /v1/series/init` - 创建系列
- `GET /v1/series/{id}` - 获取系列详情
- `POST /v1/series/{id}/lock` - 锁定系列
- `GET /v1/series/{id}/progress` - 获取处理进度

### 角色相关
- `GET /v1/roles/series/{seriesId}` - 获取系列下的角色
- `POST /v1/roles/{id}/regenerate` - 重新生成角色图片
- `POST /v1/roles/{id}/confirm` - 确认角色

### 资产相关
- `GET /v1/assets/role/{roleId}/clothings` - 获取角色服装列表
- `GET /v1/assets/series/{seriesId}/clothings` - 批量获取系列所有角色资产
- `POST /v1/assets/{id}/rollback` - 回滚到指定版本

### 场景/道具相关
- `GET /v1/scenes/series/{seriesId}` - 获取系列场景资产
- `POST /v1/scenes/upload` - 手动上传场景图片
- `POST /v1/scenes/{id}/upload` - 为已有场景上传新版本
- `POST /v1/scenes/{id}/regenerate` - 重新生成场景图片
- `POST /v1/scenes/{id}/rollback` - 回滚场景版本
- `POST /v1/scenes/{id}/lock` - 锁定场景
- `POST /v1/scenes/{id}/unlock` - 解锁场景
- `GET /v1/props/series/{seriesId}` - 获取系列/剧集可见道具资产
- `POST /v1/props/upload` - 手动上传道具图片
- `POST /v1/props/{id}/upload` - 为已有道具上传新版本
- `POST /v1/props/{id}/regenerate` - 重新生成道具图片
- `POST /v1/props/{id}/rollback` - 回滚道具版本
- `POST /v1/props/{id}/lock` - 锁定道具
- `POST /v1/props/{id}/unlock` - 解锁道具

### 剧集相关
- `GET /v1/episodes/series/{seriesId}` - 获取系列下的剧集
- `POST /v1/episodes/series/{seriesId}` - 创建剧集
- `POST /v1/episodes/{id}/parse` - 解析剧本
- `GET /v1/episodes/{id}/progress` - 获取解析进度

### 分镜相关
- `GET /v1/shots/episode/{episodeId}` - 获取剧集分镜
- `POST /v1/shots/{id}/generate` - 生成分镜视频
- `POST /v1/shots/episode/{episodeId}/generate` - 批量生成视频
- `GET /v1/shots/{id}/video-history` - 获取视频版本历史
- `POST /v1/shots/{id}/rollback-video/{assetId}` - 回滚视频版本
- `POST /v1/shots/{id}/upload-video` - 手动上传分镜视频
- `POST /v1/shots/{id}/review` - 锁定分镜
- `POST /v1/shots/{id}/unlock` - 解锁分镜
- `POST /v1/shots/episode/{episodeId}/reorder` - 调整分镜排序

### 视频主体替换
- `POST /v1/subject-replacements` - 创建主体替换任务
- `GET /v1/subject-replacements` - 获取最近任务列表
- `GET /v1/subject-replacements/{id}` - 获取任务详情
- `PUT /v1/subject-replacements/{id}` - 修改任务名称
- `DELETE /v1/subject-replacements/{id}` - 删除任务

## 积分定价

| 模型 | 480p | 720p | 1080p |
|------|------|------|-------|
| Seedance 2.0 VIP | 15/秒 | 32/秒 | 69/秒 |
| Seedance 2.0 Fast | 12/秒 | 25/秒 | 不支持 |

其他操作：图片生成 6 积分/张，剧本解析 2 积分/次

## 分镜文本格式

系统支持标准化的分镜文本格式，LLM解析和用户手动编辑统一使用此格式：

```
时间【00:00-00:08】
镜头【全景+推镜头】
剧情【会议室大门被推开。林知夏抱着厚厚的设计图纸，自信满满地走进来。阳光打在她脸上，她微微眯眼，适应光线。】
音效【大门推开声，高跟鞋清脆的走步声】
```

## 配置说明

### 后端配置（application.yml）
```yaml
server:
  port: 8081
  servlet:
    context-path: /api

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/manga_ai
    username: your_username
    password: your_password

# 阿里云OSS配置
aliyun:
  oss:
    endpoint: your_endpoint
    bucket: your_bucket
    access_key: your_access_key
    secret_key: your_secret_key

# 火山引擎配置
volcengine:
  # 图片生成
  seedream:
    api-key: ${VOLCENGINE_API_KEY}
    model: seedream-5.0-lite

  # 视频生成
  seedance:
    api-key: ${VOLCENGINE_SEEDANCE_API_KEY}
    model: seedance-2.0

  # LLM
  llm:
    api-key: ${VOLCENGINE_LLM_API_KEY}
    model: doubao-pro-32k

gpt-image2:
  api-key: ${GPT_IMAGE2_API_KEY}
  base-url: https://api.airiver.cn/v1
  model: gpt-image-2
```

### 前端配置
```python
# frontend/manga_ai/settings.py
BACKEND_API_URL = 'http://localhost:8081/api'
```

## 状态码说明

### 系列状态
| 状态码 | 说明 |
|--------|------|
| 0 | 处理中 |
| 1 | 待审核 |
| 2 | 已锁定 |

### 剧集状态
| 状态码 | 说明 |
|--------|------|
| 0 | 待解析 |
| 1 | 解析中 |
| 2 | 待审核 |
| 3 | 制作中 |
| 4 | 已完成 |

### 资产状态
| 状态码 | 说明 |
|--------|------|
| -1 | 生成失败 |
| 0 | 生成中 |
| 1 | 待审核 |
| 2 | 已确认 |
| 3 | 已锁定 |

### 视频生成状态
| 状态码 | 说明 |
|--------|------|
| 0 | 待生成 |
| 1 | 生成中 |
| 2 | 已完成 |
| 3 | 生成失败 |

## 开发进度

### 已完成
- [x] 用户注册登录系统
- [x] JWT Token 认证 + Redis Token 管理
- [x] 积分系统与消费记录（按模型+分辨率差异化定价）
- [x] 兑换码功能（区分大小写，Redis 分布式锁）
- [x] 用户个人设置（头像上传、昵称修改）
- [x] 系列管理 CRUD
- [x] 角色提取与图片生成
- [x] 多服装版本管理
- [x] 角色审核流程
- [x] 图片自动上传 OSS（防止临时 URL 过期）
- [x] 剧集管理基础架构
- [x] LLM 剧本解析服务
- [x] Seedance 2.0 / 2.0 Fast 视频生成服务
- [x] 分镜管理基础架构
- [x] 视频版本管理与回滚（含耗时同步）
- [x] 待审核/已锁定分镜列表
- [x] 分镜手动上传视频、锁定、解锁和排序
- [x] 分镜文本格式化存储
- [x] 中文错误信息提示
- [x] 分镜分辨率与模型选择
- [x] 资产库大图预览与视频播放
- [x] 角色@mention与缩略图系统
- [x] 场景/道具历史版本预览
- [x] 角色服装切换
- [x] 分镜视频下载（BFF 代理）
- [x] 剧集删除
- [x] 首页背景视觉优化（粒子动画、网格背景）
- [x] 登录过期自动弹窗提示
- [x] 重新生成支持"大头特写+三视图"布局选项
- [x] 头像裁剪功能优化
- [x] 剧集详情页性能优化
- [x] 首页我的系列加载优化
- [x] 我的资产库影视资产查询优化
- [x] 场景/道具本地上传与裁剪
- [x] 场景/道具历史版本、回滚与锁定态保护
- [x] 分镜编辑状态标记与保存隔离
- [x] 分镜资产缩略图实时刷新
- [x] Redis 保活与认证快速失败
- [x] 首页匿名营销态、登录跳转保护、联系我们二维码公共访问
- [x] 视频主体替换功能与首页演示区
- [x] 变更文档目录

### 进行中
- [ ] 剧集导出功能
- [ ] 批量视频生成进度体验继续优化
- [ ] 主体替换营销展示素材持续替换与补充

### 待开发
- [ ] 更细粒度的资产权限与团队协作

## 变更文档

近期修复和功能增强均记录在 [docs/changes/README.md](docs/changes/README.md)。
每条变更包含背景、改动范围、影响面和验证方式，便于回溯问题和查找实现细节。

## License

MIT License
