# CHG-20260517-013 视频生成并发限流与队列保护

## 背景

合并后的 `corePoolSize=6` 是后端 Spring 单例线程池 `videoGenerateExecutor` 的全局并发配置，不是每个用户 6 路。视频生成主要在等待外部模型服务返回，本地 CPU 压力相对有限，但仍需要避免单个用户占满资源，也要给全站保留清晰的排队上限。

## 改动

- 视频生成全局线程池改为最多同时处理 100 路任务。
- 视频生成全局队列容量改为 1000 个任务。
- 队列超过 1000 后拒绝提交，并向用户提示“生成人数过多请稍后再试”。
- 新增用户级视频生成限流，每个用户同时最多 15 路视频生成任务。
- 分镜视频生成接入用户级限流和队列满回滚逻辑。
- 主体替换视频生成接入同一套用户级限流和全局线程池。
- 队列满或任务提交失败时，已扣除积分会返还，分镜生成状态会恢复为待生成。
- 新增兜底清理任务：分镜视频生成中超过 1 小时仍未完成时，自动恢复为待生成，避免页面长期卡在生成中。
- 为 `shot` 表补充 `deducted_credits` 字段，用于失败返还积分。
- `application.yml.example` 同步补充视频线程池和用户限流配置，避免部署样例缺失。

## 影响范围

- 分镜视频生成。
- 带参考图的分镜视频生成。
- 主体替换视频生成。
- 后端异步线程池配置。
- 分镜生成中状态兜底恢复。
- `shot` 表结构迁移。

## 关键配置

```yaml
video:
  executor:
    core-pool-size: 100
    max-pool-size: 100
    queue-capacity: 1000
  generation:
    user-concurrency-limit: 15
    limiter-task-ttl: 3600000
```

## 验证

- `mvn test -Dtest=ShotServiceImplTest,SubjectReplacementServiceImplTest,AsyncConfigTest,UserVideoGenerationLimiterTest,CleanupTaskTest`
