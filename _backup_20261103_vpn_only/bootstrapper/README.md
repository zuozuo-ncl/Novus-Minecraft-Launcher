# MC Launcher 引导器（Bootstrapper）

C++ 原生引导程序。用户双击即可运行，无需预装 Java。

## 它做什么

1. 读取版本接口，拿到最新启动器版本号
2. 检测系统 Java 17；没有就从**国内镜像**下载 JRE 并解压
3. 检查本地已下载的启动器 jar，**选最新版本**（旧版保留）
4. 静默拉起 `javaw -jar`，然后自己退出

**进度条只在真正需要下载时出现**；Java 与 jar 都就绪时秒启动、全程无窗口。

## 目录结构

```
bootstrapper/
├── CMakeLists.txt
├── src/
│   ├── common.h      公共定义：常量、接口、配置、镜像地址
│   ├── main.cpp      入口 + 引导流程（工作线程）
│   ├── util.cpp      路径/文件/日志工具
│   ├── http.cpp      WinHTTP 下载（分块写盘、进度、可取消）
│   ├── zip.cpp       ZIP 解压（Shell COM + PowerShell 双方案）
│   ├── java.cpp      Java 检测与自动安装
│   ├── launcher.cpp  版本查询、jar 选择/下载、启动进程
│   └── ui.cpp        原生进度窗口与消息框
└── bin/              构建产物
```

## 构建

### 方式一：CMake（推荐）

```bash
cd bootstrapper
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release
```

### 方式二：直接用 g++（无需 CMake）

```bash
cd bootstrapper
mkdir -p bin
# 1) 先编译图标/版本信息（app.rc 引用 src/app.ico）
windres -I src src/app.rc -O coff -o app_res.o
# 2) 编译并链接
g++ -std=c++17 -O2 -Wall -Wextra \
    -DUNICODE -D_UNICODE -D_WIN32_WINNT=0x0601 \
    -municode -mwindows \
    -static -static-libgcc -static-libstdc++ \
    -o bin/MCLauncherBootstrap.exe \
    app_res.o \
    src/main.cpp src/util.cpp src/http.cpp src/zip.cpp \
    src/java.cpp src/launcher.cpp src/ui.cpp \
    -lwinhttp -lshell32 -lshlwapi -lole32 -loleaut32 -luuid \
    -luser32 -lgdi32 -lcomctl32 -ladvapi32 -lbcrypt
rm -f app_res.o
```

**32 位（x86）构建**：`bash tools/build-x86.sh`（内部调用 `tools/build-x86.cmd`）。
本机 32 位工具链需要精简过的 PATH 才能运行，脚本已经处理好了。

**程序图标**：`src/app.ico` 由 `tools/make-icon.py` 从
`NCL_image/NCL_Icon.png` 生成（9 种尺寸，16/20/24 单独优化过）。
换图标时改源图后重跑一次脚本即可，不需要改代码。

### 编译参数为什么必须这样写

| 参数 | 作用 | 漏了会怎样 |
| :--- | :--- | :--- |
| `-municode` | 入口点解析为 `wWinMain` | **链接失败**：`undefined reference to 'WinMain'` |
| `-mwindows` | 标记 GUI 子系统 | 运行时会弹**控制台黑框** |
| `-static` `-static-libgcc` `-static-libstdc++` | 静态链接运行时 | 对方机器缺运行库会**打不开** |
| `-loleaut32` `-luuid` | Shell 解压所需的 OLE 函数 | **链接失败**：`VariantInit` / `GUID_NULL` 未定义 |
| `-lbcrypt` | 计算 jar 的 SHA-256（见 `tools/JAR-INTEGRITY.md`） | **链接失败**：`BCryptOpenAlgorithmProvider` 未定义 |
| `-D_WIN32_WINNT=0x0601` | 允许使用 Win7+ 的 API | 部分 API 不可用 |

## 关键实现说明

### 为什么 JRE 必须从国内镜像下载

Adoptium 的 API（`api.adoptium.net`）本身可访问，但它返回的下载地址最终指向
**`github.com` / `release-assets.githubusercontent.com`**，在国内网络下常常**完全超时**。

实测（2026-10-25）：

| 源 | 结果 |
| :--- | :--- |
| **南京大学** `mirror.nju.edu.cn/adoptium/` | **41.8 MB / 1.5 秒（约 28 MB/s）** |
| 华为云 `mirrors.huaweicloud.com/adoptium/` | 返回 HTML 错误页（已识别并跳过） |
| 清华 / 中科大 / 阿里云 | 路径不匹配（403 / 404） |
| 官方 GitHub | 国内基本不可用 |

因此 `java.cpp` 采用**多源回退**：三个国内镜像 → Adoptium API 地址 → 官方直链，
逐个尝试直到成功。

### 为什么要校验下载内容

部分镜像在文件不存在时返回 **HTTP 200 + HTML 错误页**（而非 404）。
若只看状态码，会把 HTML 当成 JRE 压缩包下载下来，解压时报出无从排查的错误。

`http.cpp` 因此在写盘后校验**文件头**：
- 以 `PK` 开头 = 合法的 ZIP/JAR
- 以 `<` 开头 = HTML 错误页，判定失败并换源

### 版本选择逻辑

引导器扫描 `%APPDATA%\MC_Launcher\` 下的 `mc-launcher-<版本>.jar`，
按**语义化版本**比较（`1.2.0 > 1.1.10`，不是字符串排序），选最新的启动。
旧版本文件**不删除**，便于回滚。

## 数据目录

```
%APPDATA%\MC_Launcher\
├── bootstrap.log          引导器日志（UTF-8，出错时看这里）
├── launcher-stdout.log    启动器的控制台输出
├── java/                  自动下载的 JRE
└── mc-launcher-<版本>.jar 各版本启动器（可共存）
```

## 文件位置

- 引导器：`bin/MCLauncherBootstrap.exe`（约 2.8 MB，单文件，无外部依赖）
- 版本管理接口：`https://version-interface.us.ci/api/versions`
- jar 下载：`https://3984235.xyz/mc-launcher-<版本>.jar`

## 排错

| 现象 | 原因 | 处理 |
| :--- | :--- | :--- |
| 双击无反应 | 被杀软拦截 / 文件损坏 | 加入白名单；检查文件大小是否 2.8 MB |
| 提示缺少 DLL | 未静态链接运行时 | 用上面的参数重新编译 |
| "Java 运行环境准备失败" | 所有镜像都不通 | 查看 `bootstrap.log` 里的具体源与错误 |
| 提示成功但界面不出 | 启动器自身报错 | 看 `launcher-stdout.log` |