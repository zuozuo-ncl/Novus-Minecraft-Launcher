<<<<<<< HEAD
License: AGPL-3.0
# Novus Minecraft Launcher

一个 Minecraft 启动器项目，由五部分组成：桌面启动器、引导程序、版本服务器、下载页，以及联机中继服务。

```
share/
├── bootstrapper/          引导程序（C++ / Win32），负责装 Java、下载启动器并拉起
├── MC launcher/           启动器主体（Java 17 / JavaFX）
├── MC launcher-Server/    版本服务器（Node.js / Express），提供版本列表与哈希
├── ncl-download web/      下载站点 + 静态文件服务（Node.js / Express）
└── mc-relay/              联机中继服务（Node.js，TCP / HTTP）
```

## 快速开始
- 进入https://ncl.cc.cd
- 下载 NCL.exe 或 NCL_x86.exe。
- 双击运行，等待 Java 环境自动配置完成。
- 开始游戏。


## 运行链路

```
用户双击 NCL.exe/NCL_x86.exe(MCLauncherBootstrap.exe/MCLauncherBootstrap_x86.exe)
   └─ 引导器检测 / 安装 Java 17
      └─ 校验本地 jar（哈希校验），没有就下载
         └─ 启动 mc-launcher-<版本>.jar
            └─ 启动器从版本服务器查更新、从 CDN 下载游戏与模组
```

版本服务器负责记录"最新版本 + 每个版本的 SHA-256"，引导器与启动器都据此判断是否需要更新。

---

## bootstrapper —— 引导程序

Windows 原生的单文件 exe，无外部依赖，负责把用户从"什么都没有"带到"启动器界面出现"。

| 项目 | 说明 |
|---|---|
| 语言 / 构建 | C++17，CMake 或直接 g++ (MinGW-w64) |
| 目标平台 | Windows 7+，32 位与 64 位 |
| 产物 | `MCLauncherBootstrap.exe`（64 位）、`MCLauncherBootstrap_x86.exe`（32 位） |

**主要逻辑**

- **Java 环境**：按"引导器自装 → `JAVA_HOME` → 注册表 → `PATH`"的顺序探测 Java 17+，并校验架构与当前进程一致；找不到就下载安装。JRE 走国内镜像（南大、华为云、中科大），32 位 Java 21 用 Liberica / Zulu FX。
- **jar 获取与校验**：本地没有 jar 时从下载地址拉取，先写 `.part`，校验通过才落盘。可信锚点有三个，任一命中即可：服务器上的 `<版本>.jar.sha256`、编进 exe 的内置哈希表、本机启动器登记过的哈希。
- **启动判定**：拉起启动器后等待其写入 `startup.ok`（内容为 PID）。超过 15 秒仍未写入但进程还活着，会继续等待而不是弹窗询问；只有进程真的崩溃或退出才提示用户是否回退到旧版本。
- **回退**：新版本启动失败时，按 `versions.json` 回退到上一个验证过可用的版本。

**编译**

```bash
cd bootstrapper

# 64 位
windres -I src src/app.rc -O coff -o app_res.o
g++ -std=c++17 -O2 -DUNICODE -D_UNICODE -D_WIN32_WINNT=0x0601 -municode -mwindows \
    -static -static-libgcc -static-libstdc++ -o bin/MCLauncherBootstrap.exe app_res.o \
    src/main.cpp src/util.cpp src/http.cpp src/zip.cpp src/java.cpp src/launcher.cpp src/ui.cpp \
    -lwinhttp -lshell32 -lshlwapi -lole32 -loleaut32 -luuid -luser32 -lgdi32 \
    -lcomctl32 -ladvapi32 -lbcrypt

# 32 位
bash tools/build-x86.sh
```

也可以直接 `cmake -B build -DCMAKE_BUILD_TYPE=Release && cmake --build build --config Release`。

**运行时日志**：`%APPDATA%\NCL_Launcher\bootstrap.log`。设置环境变量 `MCLAUNCHER_TEST_APPDATA` 可把数据目录改到别处，方便测试。

---

## MC launcher —— 启动器主体

| 项目 | 说明 |
|---|---|
| 语言 / 框架 | Java 17 + JavaFX |
| 构建 | Maven（`maven-shade-plugin` 打出含依赖的单文件 jar） |
| 产物 | `target/mc-launcher-<版本>-shaded.jar` |

**主要功能**

- Microsoft 账号登录（OAuth 设备码流程）
- 版本管理：下载原版游戏与依赖，安装 Fabric / Forge 等模组加载器
- Modrinth 模组与整合包浏览、下载，中文简介来自离线映射表
- 崩溃日志分析、多账号管理、皮肤预览

**构建**

```bash
cd "MC launcher"
mvn package            # 产出 target/mc-launcher-<版本>-shaded.jar
```

> 注意：不要用裸 javac 往 `target/classes` 里编译，Maven 的增量编译检测不到，会把高版本字节码打进 jar 导致启动失败。

## MC launcher-Server —— 版本服务器

启动器与引导器共用的版本接口。

| 项目 | 说明 |
|---|---|
| 技术栈 | Node.js + Express |
| 默认端口 | `5003`（`PORT` 环境变量可改） |
| 入口 | `server.js` |

**接口**

| 方法 | 路径 | 需要令牌 | 说明 |
|---|---|---|---|
| GET | `/api/versions` | | 最新版本与全部版本的哈希 |
| POST | `/api/check-update` | | 判断是否需要更新 |
| POST | `/api/versions` | ✓ | 添加版本 |
| PUT | `/api/versions/latest` | ✓ | 设置最新版本 |
| DELETE | `/api/versions/:version` | ✓ | 删除版本 |
| GET | `/mcmod_intros.json` | | 模组中文简介表（带缓存与 ETag） |
| GET | `/health` | | 健康检查 |

写接口通过请求头 `X-Admin-Token` 或 `Authorization: Bearer` 鉴权，令牌来自环境变量 `ADMIN_TOKEN`；**未配置令牌时写接口一律返回 503**，不会无鉴权放行。

**运行**

```bash
cd "MC launcher-Server"
npm install
ADMIN_TOKEN=<你的令牌> npm start
```

自带了 `deploy.sh`、`start.sh`、`ecosystem.config.js`（PM2）和 `mc-launcher-server.service`（systemd），按服务器环境选用。

---

## ncl-download web —— 下载站点

给用户下载引导程序的静态站点，同时提供文件服务。

| 项目 | 说明 |
|---|---|
| 技术栈 | Node.js + Express |
| 入口 | `server/server.js`（生产，端口 5005） |
| 页面 | `public/index.html` |

`server/index.js` 是本地开发用的变体（端口 3000，静态目录为上级目录），生产部署用 `server/server.js`。

**运行**

```bash
cd "ncl-download web/server"
npm install
node server.js          # 默认 5005
```

`start.sh` 里把项目路径写死为 `/www/wwwroot/ncl-download-web/server`，部署系统为ubuntu，部署到别的要先改路径。

---

## mc-relay —— 联机中继

把无法直连的玩家通过公网服务器转接，实现联机。

| 项目 | 说明 |
|---|---|
| 技术栈 | Node.js，仅用内置 `net` / `http` / `crypto` |
| `relay-server.js` | 部署在公网服务器上 |
| `relay-host.js` | 房主本地运行，连接中继并转发到本机服务 |

**运行**

```bash
# 公网服务器
API_KEY=<密钥> node relay-server.js

# 房主本地
node relay-host.js
```

主要环境变量：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `HTTP_PORT` | `8080` | 中继 HTTP 端口 |
| `HOST` | `0.0.0.0` | 监听地址 |
| `RANGE` | `40000-40999` | 分配给房间的端口范围 |
| `API_KEY` | 空 | 请求房间时的鉴权密钥 |
| `MAX_PLAYERS` | `20` | 单房间人数上限 |
| `MAX_ROOMS` | `100` | 房间总数上限 |

部署脚本见 `deploy-relay.sh`。

---

## 常见问题

**引导器提示"启动器文件校验失败"**
下载到的 jar 与可信哈希不一致。常见原因是下载源（CDN）缓存了旧文件，刷新 CDN 或等待缓存过期后重试；也可能是文件被安全软件改动。日志会打印期望哈希、实际哈希和下载地址。

**引导器提示"HTTPS 证书校验失败"**
说明下载域名当前拿到的是错误证书。多为 CDN 节点配置问题，或本机 DNS 被劫持——可用 `nslookup` 核对域名解析结果是否为 Cloudflare / 腾讯云的正常 IP。

**启动器双击后界面不出现**
引导器判定失败后会询问是否回退。先看 `%APPDATA%\NCL_Launcher\bootstrap.log` 与同目录下的 `launcher-stdout.log`。

**浏览器下载 exe 时提示"不常下载的文件"**
未签名的程序与新建域名的正常现象。可提供 SHA-256 供用户校验，或对 exe 做代码签名来消除 SmartScreen 警告。

---

## 说明

- `MC launcher-Server/version.json` 中的版本号是部署时的快照，与线上实际值可能不同，以服务器返回为准
=======
# Novus-Minecraft-Launcher
一个由 C++ 引导器 + JavaFX 核心 + Node.js 联机中继组成的 Minecraft 全栈启动器。支持老旧系统、底层防篡改与一键联机。
>>>>>>> 79e9bc11ce225bc8ac089d9701a3262ae004089d
