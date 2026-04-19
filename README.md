# Manga AI - AI漫画角色生成系统

一个基于AI的漫画角色图片生成管理系统，支持角色创建、服装管理、图片生成与审核等功能。

## 项目结构

```
manga-ai/
├── backend/                # Spring Boot 后端
│   └── src/main/java/com/manga/ai/
│       ├── asset/          # 资产管理模块
│       ├── common/         # 通用配置（WebSocket等）
│       ├── image/          # 图片生成服务
│       ├── nlp/            # NLP处理模块
│       ├── role/           # 角色管理模块
│       └── series/         # 系列管理模块
│
└── frontend/               # Django 前端
    ├── api/                # 后端API客户端
    ├── apps/
    │   ├── asset/          # 资产管理
    │   ├── role/           # 角色管理
    │   └── series/         # 系列管理
    ├── manga_ai/           # Django项目配置
    └── templates/          # HTML模板
```

## 技术栈

### 后端
- Java 17
- Spring Boot 3.2.0
- MyBatis Plus
- MySQL
- 阿里云OSS（图片存储）
- WebSocket（实时进度推送）

### 前端
- Python 3.x
- Django
- Tailwind CSS
- JavaScript（原生）

## 主要功能

### 系列管理
- 创建漫画系列
- 系列状态流转（处理中、待审核、已锁定）
- 实时处理进度显示

### 角色管理
- 从系列文本中自动提取角色信息
- 角色图片生成与重新生成
- 角色审核与确认

### 服装管理
- 多服装版本支持
- 服装图片生成（文生图/图生图）
- 历史版本管理
- 默认服装设置

### 图片生成
- 火山引擎AI图片生成
- 异步处理与状态轮询
- 内容审核检测
- 失败重试与版本回滚

## 快速开始

### 环境要求
- JDK 17+
- Python 3.8+
- MySQL 8.0+
- Maven 3.x

### 后端启动
```bash
cd backend
mvn spring-boot:run
```
默认端口：8081

### 前端启动
```bash
cd frontend
python manage.py runserver 8000
```
默认端口：8000

## API接口

### 系列相关
- `GET /v1/series` - 获取系列列表
- `POST /v1/series` - 创建系列
- `GET /v1/series/{id}` - 获取系列详情
- `POST /v1/series/{id}/lock` - 锁定系列

### 角色相关
- `GET /v1/roles/series/{seriesId}` - 获取系列下的角色
- `POST /v1/roles/{id}/regenerate` - 重新生成角色图片

### 资产相关
- `GET /v1/assets/role/{roleId}/clothings` - 获取角色服装列表
- `GET /v1/assets/{id}/prompt` - 获取资产生成提示词
- `POST /v1/assets/{id}/rollback` - 回滚到指定版本

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
```

### 前端配置
修改 `frontend/api/backend_client.py` 中的后端API地址。

## 开发说明

### 图片生成流程
1. 用户点击"重新生成"
2. 后端创建生成中的asset记录
3. 异步调用AI生成服务
4. 前端轮询生成状态
5. 生成完成/失败后更新UI

### 状态码说明
| 状态码 | 说明 |
|--------|------|
| 0 | 生成中 |
| -1 | 生成失败 |
| 1 | 待审核 |
| 2 | 已确认 |

## License

MIT License
