# 手机验证码登录

App 默认输入手机号和短信验证码。手机号首次验证成功时创建普通采集账号，之后验证码登录复用同一用户ID。用户无需单独注册、设置密码或绑定账号。账号密码登录作为旧账号的次要入口保留；专家、管理员权限仍由后台授予。

## 配置

API 环境安装 `server/requirements-sms.txt`。使用阿里云号码认证 PNVS，发送和核验接口分别为 `SendSmsVerifyCode`、`CheckSmsVerifyCode`，RAM 最小权限策略见 `ram-policy.json`。

在服务器 root 专用环境文件配置：

```
APP_SMS_ENABLED=true
ALIBABA_CLOUD_ACCESS_KEY_ID=
ALIBABA_CLOUD_ACCESS_KEY_SECRET=
APP_SMS_SIGN_NAME=
APP_SMS_TEMPLATE_CODE=100001
APP_SMS_IDENTITY_SECRET=
```

签名和模板使用控制台当前可用的配套项，不能在代码中随意填写品牌名称。身份摘要密钥应为持久随机秘密，部署后保留原值；更换会导致既有手机号绑定无法找到。未设置该值时使用已有的诊断签名密钥。密钥只保存在服务器，不能放入代码或APK。缺少配置时返回503，不提供模拟验证码或生产绕过开关。

## 接口

- `POST /api/v1/auth/phone/code`：请求 `{phone}`；返回 `{challenge_id,resend_after_seconds,expires_in}`，不返回验证码。
- `POST /api/v1/auth/phone/login`：请求 `{phone,challenge_id,code}`；只有阿里云返回 `Code=OK`、`Success=true` 且 `Model.VerifyResult=PASS` 才建立会话。首次创建collector账号，再次登录使用原账号。
- 保留原有账号密码登录。无独立注册或重置密码接口，不支持在登录表单中合并两个账号。

验证码有效5分钟，60秒后可重发；重发替换旧挑战。每个挑战最多核验5次，只能成功登录一次。发送前在数据库中预留额度，超时/失败也保留占用，避免请求重试造成额外短信：全局100次/日、同来源IP10次/小时、同手机号10次/日。客户端转发头不作为限额依据；CDN场景可能共享来源IP额度，适用于当前小规模测试。

手机号身份使用带密钥摘要，另存脱敏展示值，不保存完整手机号；不在应用库中保存明文手机号、验证码或SDK原始异常。新账号的内部登录名随机生成，不包含手机号，原用户ID和照片归属保持不变。schema4新增的phone_identities与sms_states用于身份映射与持久状态，SQL行锁及唯一约束保护并发核验。

会话保持原有30分钟访问令牌、30天刷新令牌与自动轮换；App本地持久保存，关闭或重启不主动退出。持续使用可续期，超过刷新有效期未使用或主动退出后需要重新验证。验证码真实送达与登录必须在用户授权后进行实际验收，注入测试供应商的单元测试不证明送达。
