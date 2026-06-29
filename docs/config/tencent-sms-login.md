# 腾讯云短信登录配置说明

本文档记录手机验证码登录/注册/绑定手机号新增的后端配置。生产环境请优先使用环境变量注入，不要把真实 `SecretId` / `SecretKey` 写进仓库。

## 配置位置

后端读取配置路径：

```yaml
tencent:
  sms:
    secret-id: ${TENCENT_SMS_SECRET_ID:}
    secret-key: ${TENCENT_SMS_SECRET_KEY:}
    sdk-app-id: ${TENCENT_SMS_SDK_APP_ID:1400632889}
    sign-name: ${TENCENT_SMS_SIGN_NAME:北京乙帧科技有限公司}
    login-template-id: ${TENCENT_SMS_LOGIN_TEMPLATE_ID:2645843}
    reset-template-id: ${TENCENT_SMS_RESET_TEMPLATE_ID:2647581}
    region: ${TENCENT_SMS_REGION:ap-guangzhou}
    code-expire-minutes: ${TENCENT_SMS_CODE_EXPIRE_MINUTES:10}
```

对应文件：

- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/application.yml.example`

`SecretId` / `SecretKey` 不设置默认值，必须通过环境变量注入。

## 环境变量

服务器建议配置：

```bash
export TENCENT_SMS_SECRET_ID="<腾讯云 SecretId>"
export TENCENT_SMS_SECRET_KEY="<腾讯云 SecretKey>"
export TENCENT_SMS_SDK_APP_ID="1400632889"
export TENCENT_SMS_SIGN_NAME="北京乙帧科技有限公司"
export TENCENT_SMS_LOGIN_TEMPLATE_ID="2645843"
export TENCENT_SMS_RESET_TEMPLATE_ID="2647581"
export TENCENT_SMS_REGION="ap-guangzhou"
export TENCENT_SMS_CODE_EXPIRE_MINUTES="10"
```

## 当前业务配置

| 配置项 | 当前值 | 说明 |
| --- | --- | --- |
| `TENCENT_SMS_SDK_APP_ID` | `1400632889` | 腾讯云短信应用 SDKAppID |
| `TENCENT_SMS_SIGN_NAME` | `北京乙帧科技有限公司` | 短信签名内容，不是签名 ID |
| `TENCENT_SMS_LOGIN_TEMPLATE_ID` | `2645843` | 登录、注册、绑定手机号验证码模板 |
| `TENCENT_SMS_RESET_TEMPLATE_ID` | `2647581` | 密码重置验证码模板 |
| `TENCENT_SMS_REGION` | `ap-guangzhou` | 腾讯云 API 地域参数 |
| `TENCENT_SMS_CODE_EXPIRE_MINUTES` | `10` | 登录/注册/绑定验证码有效分钟数 |

## 模板参数规则

登录、注册、绑定手机号共用模板 `2645843`：

```text
{1}为您的登录验证码，请于{2}分钟内填写，如非本人操作，请忽略本短信。
```

后端传参：

```json
["验证码", "有效分钟数"]
```

密码重置模板 `2647581`：

```text
您的动态验证码为：{1}，您正在进行密码重置操作，如非本人操作，请忽略本短信！
```

后端传参：

```json
["验证码"]
```

## 接口行为

- 新用户注册改为手机号 + 验证码 + 密码。
- 登录支持两种方式：
  - 手机号/邮箱 + 密码
  - 手机号 + 短信验证码
- 老邮箱账号登录后，如果没有绑定手机号，会跳转到 `/auth/bind-phone/` 先绑定手机号。
- 老邮箱账号绑定手机号后，邮箱和手机号都可以继续登录。
- 后端发短信接口会把国内手机号转成 E.164 格式，例如 `13800138000` 会发送为 `+8613800138000`。

## 数据库变更

迁移文件：

```text
backend/src/main/resources/db/migration/V2026.05.27__Add_User_Phone_For_Sms_Login.sql
```

主要变更：

- `user` 表新增 `phone` 字段和 `idx_user_phone` 索引。
- `user.email` 调整为允许为空，兼容手机号注册用户。
- `email_verification` 表新增 `phone` 字段和 `idx_email_verification_phone_type` 索引。
- `email_verification.email` 调整为允许为空，兼容短信验证码记录。

## 故障排查

- 页面提示“短信服务未配置，请联系管理员”：检查 `TENCENT_SMS_SECRET_ID`、`TENCENT_SMS_SECRET_KEY`、`TENCENT_SMS_SDK_APP_ID`、`TENCENT_SMS_SIGN_NAME`、`TENCENT_SMS_LOGIN_TEMPLATE_ID` 是否为空。
- 页面提示“request content does not match the template content”：检查模板变量数量是否匹配。登录模板需要 2 个变量，重置模板需要 1 个变量。
- 腾讯云返回签名相关错误：确认 `TENCENT_SMS_SIGN_NAME` 填的是签名内容 `北京乙帧科技有限公司`，不是签名管理 ID。
- 腾讯云返回鉴权错误：确认 `SecretId` / `SecretKey` 属于当前腾讯云账号，且账号有短信 SendSms 权限。
