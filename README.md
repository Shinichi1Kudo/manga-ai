# AI 智能短剧制作系统

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

## 项目结构

```
manga-ai/
├── backend/                    # Spring Boot 后端
│   └── src/main/java/com/manga/ai/
│       ├── asset/              # 资产管理模块
│       ├── common/             # 通用配置、枚举、工具类
│       ├── episode/            # 剧集管理模块
│       ├── image/              # 图片生成服务
│       ├── llm/                # LLM服务（豆包）
│       ├── nlp/                # NLP处理模块
│       ├── prop/               # 道具管理模块
│       ├── role/               # 角色管理模块
│       ├── scene/              # 场景管理模块
│       ├── series/             # 系列管理模块
│       ├── shot/               # 分镜管理模块
│       ├── user/               # 用户管理模块
│       └── video/              # 视频生成服务（Seedance）
│
└── frontend/                   # Django 前端
    ├── api/                    # 后端API客户端
    ├── apps/
    │   ├── asset/              # 资产管理
    │   ├── auth/               # 用户认证
    │   ├── role/               # 角色管理
    │   └── series/             # 系列与剧集管理
    ├── manga_ai/               # Django项目配置
    └── templates/              # HTML模板
        ├── auth/               # 登录注册页面
        ├── credits/            # 积分记录页面
        ├── series/             # 系列、角色审核页面
        └── episode/            # 剧集制作页面
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

### 进行中
- [ ] 剧集创建前端优化
- [ ] 场景/道具资产页面优化

### 待开发
- [ ] 批量视频生成进度优化
- [ ] 剧集导出功能

## License

MIT License
