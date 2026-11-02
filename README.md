# Novus Minecraft Launcher

> C++ 引导器 + JavaFX 启动器 + Node.js 版本服务器 + TCP 中继 + 虚拟局域网，全栈 Minecraft 启动方案。

---

## 模块一览

| 目录 | 技术栈 | 说明 |
|---|---|---|
| `bootstrapper/` | C++17, Win32, MinGW-w64 | 单文件 32/64 位 exe，双击即可——自动装 Java 17，校验并下载启动器，拉起 GUI |
| `MC launcher/` | Java 17, JavaFX, Maven | 桌面启动器：正版/离线登录，下载游戏与模组，**一键虚拟局域网联机** |
| `MC launcher-Server/` | Node.js, Express | 版本服务器：提供最新版本、SHA-256 哈希、模组简介表 |
| `ncl-download web/` | Node.js, Express | 下载站点（静态页面 + 文件分发） |
| `mc-relay/` | Node.js, 零依赖 | 联机中继服务（TCP + HTTP，端口池 40000-40999） |

---

## 运行链路

```
用户双击 MCLauncherBootstrap.exe
 └─ 引导器检测 / 安装 Java 17
    └─ 校验本地 jar（SHA-256），没有则从 CDN 下载
       └─ 启动 mc-launcher-<版本>.jar
          └─ 启动器：查更新 → 下载游戏 → 安装模组 → 玩
```

---

## 启动器主要功能

- **Microsoft / 离线登录**：OAuth 设备码流程，多账号管理
- **版本管理**：下载原版游戏与依赖库，自动解压 natives，安装 Fabric / NeoForge / Forge / Quilt
- **Modrinth 模组与整合包**：搜索、筛选、下载，模组中文简介来自离线映射表
- **崩溃分析**：自动识别常见崩溃原因并给出建议
- **完整性校验**：引导器 + 启动器双重 SHA-256 校验，防 CDN 投毒
- **一键虚拟局域网**：房主点一下「开一个房间」即可创建虚拟网卡，朋友输入 6 位房间号进入，之后 MC 里直接填 `10.77.0.1:端口`。无需公网 IP，无需第三方 VPN 软件（需要以管理员身份运行启动器）

---

## 各模块详细说明

### bootstrapper —— 引导程序

- **产物**：`MCLauncherBootstrap.exe`（64 位），`MCLauncherBootstrap_x86.exe`（32 位）
- **Java 安装**：按「自装 → JAVA_HOME → 注册表 → PATH」探测，找不到就自动从国内镜像下载
- **jar 校验**：三种锚点——服务器上的 `<版本>.jar.sha256`、exe 内置表、本机登记过可信的哈希。任一命中且一致即放行；都不命中仍放行（记录日志），但哈希不一致时拒绝启动
- **回退**：新版崩溃时自动回退到上一个验证过可用的版本
- **图标与版本信息**：`src/app.ico` 由 `tools/make-icon.py` 从 `NCL_image/NCL_Icon.png` 生成，`app.rc` 里记录文件版本

**编译**

```bash
cd bootstrapper
# x64
windres -I src src/app.rc -O coff -o app_res.o
g++ -std=c++17 -O2 -DUNICODE -D_UNICODE -D_WIN32_WINNT=0x0601 -municode -mwindows \
    -static -static-libgcc -static-libstdc++ -o bin/MCLauncherBootstrap.exe app_res.o \
    src/*.cpp -lwinhttp -lshell32 -lshlwapi -lole32 -loleaut32 -luuid \
    -luser32 -lgdi32 -lcomctl32 -ladvapi32 -lbcrypt
# x86
bash tools/build-x86.sh
```

### MC launcher —— 启动器

- **构建**：`mvn package`，产物 `target/mc-launcher-1.0.3-shaded.jar`
- **图标**：`resources/ui/icon-*.png`，由 `AppIcon.java` 在窗口创建时设置
- **Wintun 驱动**：`resources/native/{amd64,x86,arm64}/wintun.dll`，JNA 加载

### MC launcher-Server —— 版本服务器

| 方法 | 路径 | 需要令牌 | 说明 |
|---|---|---|---|
| GET | `/api/versions` | | 版本列表 + 哈希 |
| POST | `/api/check-update` | | 判断是否更新 |
| POST/PUT/DELETE | `/api/versions` 相关 | ✓ | 增删改版本 |
| GET | `/mcmod_intros.json` | | 模组中文简介（ETag 304） |
| GET | `/health` | | 健康检查 |

令牌通过 `Authorization: Bearer` 或 `X-Admin-Token` 请求头传递；**未配置 `ADMIN_TOKEN` 时写接口返回 503**。

### mc-relay —— 联机中继

- 传统中继房主/玩家模型：房主申请房间 → 朋友连公网端口 → 双向转发
- 新增 **虚拟局域网模式**（`vpn-room.js` + `vpn-tunnel.js`）：房主与玩家各一条长连接，服务器只做帧交换。每个连接有空闲 60 秒超时

主要环境变量：`HTTP_PORT`（8080）、`RANGE`（40000-40999）、`API_KEY`、`MAX_PLAYERS`（20）、`MAX_ROOMS`（100）、`VPN_IDLE_TIMEOUT_MS`（60000）。

---

---

## 常见问题

**引导器提示「启动器文件校验失败」**
下载到的 jar 与可信哈希不一致。查看 `%APPDATA%\NCL_Launcher\bootstrap.log`，日志里有期望、实际哈希和下载地址。

**虚拟局域网无法开启**
需要以管理员身份运行启动器（创建虚拟网卡）。如果一直提示失败，检查 `%APPDATA%\NCL_Launcher\.mc_launcher\logs\launcher.log` 的 VPN 相关报错。

**浏览器下载 exe 时提示「不常下载的文件」**
未签名的正常现象。可在 Release 页面用 `sha256sum -c SHA256SUMS` 校验文件。