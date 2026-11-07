# 本轮改动清单

日期：2026-11-07

## 文件放置

文件已按仓库原有目录结构覆盖到对应位置：

| 来源 | 仓库内路径 |
|---|---|
| `MC launcher/`（Java 源码） | `MC launcher/src/main/java/...`、`src/main/resources/...` |
| `MC launcher/bootstrapper/` | `bootstrapper/`（仓库里是顶层目录） |
| `MC launcher/mc-relay-go/` | `mc-relay-go/` |
| `MC launcher/MC launcher-Server/` | `MC launcher-Server/` |
| `MC launcher/tools/` | `tools/` |

## 主要改动

### 联机（VPN）
- `VpnSession`：控制通道心跳（4 秒 ping）、断线自动重连（最多 5 次）、玩家转发监听口改为 `P+10000`
- `VpnTunnel`：断线回调 `onLost`、网卡依赖收窄为 `PacketIo` 接口
- `RelayEndpoints`（新增）：中继地址可配置（`-D` > `relay-endpoints.json` > 内置默认）
- `GameLauncher`：VPN 转发在游戏进程启动后按实际端口建立
- `mc-relay-go`：新增 `vpn-probe` 探测类型（无副作用）

### 下载与网络
- `Downloader`：取消改为按任务（`cancelToken`）+ `Call.cancel()`；修复取消无法打断阻塞读
- `DownloadThrottle`：不再替换 `Semaphore` 对象
- `GameDownloader`：线程池复用，避免泄漏
- `UpdateClient`：备用地址改为 `https://api.rxfmprc89.nyat.app:57294`

### 架构与兼容
- `GameLauncher`：库规则区分 `x86` / `x86_64` / `arm64`（此前 ARM64 会被误判）
- `AppPaths`：移除 `MC_Launcher` 旧目录迁移逻辑

### 意见反馈（新增）
- `FeedbackDialog`：设置页弹窗，自动收集系统信息
- `LauncherSettingsPage`：加入「意见反馈」入口
- i18n：中英文各 26 条文案

### 服务端
- `feedback.js`（新增）：校验、限流、XSS 过滤、SMTP 转发、本地日志
- `server.js`：加载 `.env`、`POST /api/feedback`、`trust proxy`
- `clientIp()`：支持 `CF-Connecting-IP`（Cloudflare）与 `X-Forwarded-For`（frp）

### 引导器
- `common.h`：备用版本接口地址替换；`KNOWN_JRE_SHA256_LIST` 填入 32 位 Zulu 包哈希
- `launcher.cpp`：移除跨作用域 `goto`
- `util.cpp`：新增 `DeleteDirectoryQuiet`，移除旧目录迁移

## 测试

```bash
# 启动器：离线测试 + 端到端
bash tools/run-tests.sh              # 28 项
bash tools/run-e2e-tests.sh          # 3 项（需 go）
bash tools/run-feedback-e2e.sh       # 3 项（需 node）

# 服务端
node MC\ launcher-Server/test-feedback-api.js   # 27 项
node MC\ launcher-Server/test-client-ip.js      # 7 项
```

## 打包

```bash
python tools/package.py            # 输出 target/mc-launcher-<版本>-shaded.jar
```

离线打包，不需要 Maven（依赖取自 `target/cp.txt`），约 35 秒。

## 注释

已用 `tools/compress_comments.py` 把过长注释块压缩为 1-2 行要点：

- 484 个注释块，删除 3516 行
- Javadoc 保留 `/** */` 形式
- 代码内容经逐文件比对确认未变（102 个文件全部一致）