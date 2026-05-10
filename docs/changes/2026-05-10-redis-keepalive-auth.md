# CHG-20260510-005 Redis 连接保活与认证快速失败

## 背景

用户反馈：“Redis 隔一段时间就会这样，是不是要保活一下？不然每次都自己断开。”

排查后端日志时看到：

```text
io.lettuce.core.RedisCommandTimeoutException: Command timed out after 30 second(s)
```

该异常出现在认证链路：

```text
AuthInterceptor -> TokenServiceImpl.validateTokenInRedis -> RedisTemplate.hasKey
```

也就是说，页面请求进入业务接口前，先被 Redis token 校验卡住。Redis 连接在空闲一段时间后可能被网络/NAT/服务端断开，下一次请求才触发 Lettuce 发现连接不可用，导致用户看到前端一直停在“加载资产中...”。

## 改动

- 将 Redis 命令超时从 `30000ms` 降到 `2000ms`，避免一次 Redis 抖动卡住页面 30 秒。
- 在 `TokenServiceImpl` 给 token 校验增加 30 秒本地缓存：
  - 30 秒内同一 token 不重复访问 Redis。
  - 登出或清理用户 token 时同步移除缓存。
  - Redis 不可用时继续降级为 JWT 校验，保持原有容错策略。
- 新增 `RedisConfig`：
  - 启用 Lettuce TCP keepalive。
  - 启用 `autoReconnect`。
  - 启用 `pingBeforeActivateConnection`。
  - 每 60 秒执行一次 Redis `PING`，保持连接活跃。

## 涉及文件

- `backend/src/main/resources/application.yml`
- `backend/src/main/java/com/manga/ai/user/service/impl/TokenServiceImpl.java`
- `backend/src/main/java/com/manga/ai/common/config/RedisConfig.java`

## 行为变化

- 空闲一段时间后的首次请求，不应再因为 Redis 连接失活而等待 30 秒。
- Redis 短暂不可用时，请求最多等待约 2 秒后降级为 JWT 校验。
- 高频请求下，token 校验会命中本地 30 秒缓存，减少 Redis 压力。

## 验证状态

当前状态：已编译并重启。

已执行：

```bash
mvn -DskipTests compile
mvn spring-boot:run -DskipTests
```

验证结果：

- 后端编译通过。
- 后端重启成功，PID `39894`，`8081` 正常监听。
- RedisConfig 首次启动出现循环依赖，已改成运行时从 `ApplicationContext` 获取 `StringRedisTemplate` 后启动成功。
- 长时间空闲后的 Redis 连接稳定性仍建议继续观察日志，重点看是否还有 `RedisCommandTimeoutException: Command timed out after 30 second(s)`。

## 注意事项

- 30 秒 token 本地缓存意味着登出后，在同一后端进程内已缓存 token 会被主动移除；如果存在多后端实例，其他实例最多可能保留 30 秒缓存。
- 当前项目是本地开发单实例，这个风险可以接受。
- 如果以后部署为多实例生产环境，可以把 token 校验策略改为“JWT 优先 + Redis 黑名单”或使用更短的本地缓存 TTL。
