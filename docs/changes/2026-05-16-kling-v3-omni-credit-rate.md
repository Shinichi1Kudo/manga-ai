# CHG-20260516-002 Kling v3 Omni 分镜视频计费调整为 11 积分/秒

## 背景

分镜生成视频里的 `Kling v3 Omni` 之前复用了 Seedance VIP 的分辨率阶梯计费，720p 8 秒会扣除 256 积分。用户要求 Kling v3 Omni 改为每秒 11 积分。

## 改动

- 后端 `CreditConstants` 为 `kling-v3-omni` 增加独立计费档：`11 积分/秒`。
- 分镜普通生成和带参考图生成继续共用统一扣费方法，因此都会按新价格扣费。
- 剧集详情页前端本地积分预估同步改为 `11 * duration`，切换模型、时长和分辨率时显示一致。
- README 积分定价表同步更新 Kling v3 Omni 价格。

## 影响范围

- 分镜生成视频扣费与失败返还金额。
- 剧集详情页生成/重新生成按钮上的积分预估。
- 积分记录里的视频生成扣除金额。

## 验证

- `mvn -Dtest=ShotServiceImplTest#generateWithReferencesUsesClientStartTimeAndSavesShotUpdateBeforeAsyncWork test`
- `mvn -Dtest=ShotServiceImplTest test`
- `python manage.py test apps.series.tests`
- `git diff --check`
