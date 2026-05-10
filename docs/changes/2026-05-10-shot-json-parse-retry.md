# CHG-20260510-013 分镜 JSON 解析失败即时重试

## 背景

系列《替嫁狂妃：王爷请自重》的第 1 集《重生洞房，一针定情》在分镜解析阶段一直显示“正在解析剧本”。日志显示模型已经返回了分镜 JSON，但 `description` 中对白使用了未转义的英文双引号，例如 `"这一世..."`，导致 fastjson 报 `JSON解析失败`。

## 改动

- 分镜解析遇到 JSON 解析失败时不再等待退避时间，立即重新请求模型。
- 分镜解析最多请求 3 次，3 次仍失败后返回失败状态。
- 分镜提示词补充 JSON 字符串转义要求：英文双引号必须写成 `\"`，优先使用中文引号。
- 取消“坏 JSON 截取部分分镜也算成功”的兜底，避免保存半截分镜。
- 后端进度接口新增 `shotParseFailed` 和 `errorMessage`，让前端能识别分镜解析失败。
- 分镜失败后保留已解析的场景和道具，清除 `assetsConfirmed`，把总分镜数和总时长归零，用户可重新提交分镜解析。
- 前端轮询到 `shotParseFailed=true` 时关闭“分镜生成中”遮罩，清理本地轮询标记，并回到资产选择弹窗。
- 解析中超时清理任务增加保护：`totalShots=0` 或 `shotParseFailed=true` 的剧集不会被自动改成“待审核”。
- 已修正 episodeId=9 的旧异常状态：从“待审核但 0 个分镜”恢复为“解析中/分镜解析失败/可重试”。

## 影响范围

- 后端：分镜解析重试逻辑、剧集进度接口、分镜失败后的 episode 状态写入。
- 前端：剧集详情页的分镜解析轮询和页面加载状态恢复。
- 数据：仅修正 episodeId=9 的解析状态字段和统计字段，不修改剧本文本、资产清单或其他剧集。

## 验证

- 已通过：`mvn -DskipTests compile`
- 已通过：`python3 manage.py check`
- 已重启：后端 `8081`，前端 `8000`
- 已验证：`/api/v1/episodes/9/progress` 返回 `shotParseFailed=true`、`assetsReady=true`、`shotsParsing=false`、`totalShots=0`。
