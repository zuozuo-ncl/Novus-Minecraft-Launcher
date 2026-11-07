# 意见反馈（服务端）

启动器里的「设置 → 意见反馈」会 `POST /api/feedback`，由本服务用 SMTP 转发到作者邮箱。
**SMTP 授权码只存在于服务器环境变量里**，不打进客户端 jar —— 否则任何解压 jar 的人
都能拿到邮箱发送权限。

## 接口

```
POST /api/feedback
Content-Type: application/json

{
  "type":       "bug" | "suggestion" | "other",
  "content":    "详细描述（必填，≤2000 字）",
  "contact":    "联系方式（选填，≤100 字）",
  "systemInfo": { "os": "...", "osVersion": "...", "arch": "...", "java": "..." }
}
```

| 状态 | 含义 |
|---|---|
| 200 | `{ success: true, data: { id } }` |
| 400 | 参数不合法（内容为空 / 超长 / type 非法） |
| 413 | 请求体超过 32KB |
| 429 | 限流（带 `Retry-After`） |
| 502 | 邮件发送失败，但反馈已写入本地日志 |
| 503 | 服务端未配置 SMTP（反馈已写入本地日志） |

## 配置

复制 `.env.example` 为 `.env`，填写：

```ini
SMTP_HOST=smtp.qq.com
SMTP_PORT=465
SMTP_SECURE=true
SMTP_USER=bot@example.com
SMTP_PASS=<邮箱授权码，不是登录密码>
TARGET_EMAIL=your@mail.com
```

- **直接运行**：`node server.js` 会自动读取项目目录下的 `.env`（Node 内置的
  `process.loadEnvFile`，需 Node ≥ 20.12）。已在环境里设置的变量优先，不影响 systemd/pm2 的显式配置。
- systemd：`EnvironmentFile=-.env` 已经配好，改完 `.env` 后 `systemctl restart` 即可。
- pm2：变量必须同时出现在 `ecosystem.config.js` 的 `env` / `env_production` 里
  （pm2 中显式给出的变量会遮蔽同名环境变量），已补上。
- 机器上没有 IP 白名单限制时，QQ 邮箱需在「设置 → 账户」里开启 SMTP 服务并生成授权码。

## 防刷与安全

- 限流按 IP：1 分钟 3 条、1 小时 10 条、1 天 30 条。**校验失败的请求也计数**
  （防止用不合规数据刷接口）。
- **真实 IP 与限流**：请求经过代理时 `req.ip` 只是代理地址，所有人会共享同一份额度
  （表现为第 4 个人提交就被 429）。开启 `TRUST_PROXY=1` 后按入口择优取值：
  - Cloudflare（`version-interface.us.ci`）→ `CF-Connecting-IP`
  - frp 隧道 → `X-Forwarded-For` 的第一项

  前提是**服务端口不直接暴露公网**（默认只监听 `127.0.0.1`）。可选 `CF_TRUSTED` 限定
  只在特定直连来源上采信 CF 头，进一步防伪造。
- 邮件正文里的「来源 IP」在开启后才是真实用户 IP。
- 内容进入 HTML 邮件前会转义，控制字符被清除；主题里的换行会被压成空格（防 SMTP 头注入）。
- 每一条反馈都会先追加写入 `data/feedback.log`（JSON Lines），邮件失败也不会丢。

## 测试

```bash
node test-feedback-api.js      # 本地假 SMTP：正常/校验/XSS/限流/未配置 SMTP/.env 读取（27 项）
node test-client-ip.js         # 真实 IP 取值与防伪造（7 项）
```

客户端侧的契约测试在启动器仓库：

```bash
bash tools/run-feedback-e2e.sh
```