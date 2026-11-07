// ============================================================================
//  common.h —— 公共定义：常量、配置、工具声明
//
//  全程使用宽字符（wchar_t），以正确处理中文路径与中文提示。
// ============================================================================
#pragma once

// 这些宏通常由构建系统定义；这里兜底，避免直接编译单文件时缺失。
// 用 #ifndef 包住，避免与命令行 -D 定义冲突产生告警。
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif

#include <windows.h>
// INTERNET_PORT 定义在 winhttp.h（WinINet 的 wininet.h 也有同名定义）
#include <winhttp.h>

#include <string>
#include <vector>
#include <functional>
#include <cstdint>
#include <memory>

// ============================================================================
//  版本与接口配置
// ============================================================================

// 引导器自身版本
#define BOOTSTRAPPER_VERSION L"1.0.0"

// 程序图标（src/app.ico，由 tools/make-icon.py 从 NCL_Icon.png 生成）
// 资源脚本 app.rc 里也用同一个数字，改的时候两处一起改
#ifndef IDI_APPICON
#define IDI_APPICON 101
#endif

// 启动器 jar 的版本（会在运行时被 API 返回的最新版本覆盖）
#define DEFAULT_LAUNCHER_VERSION L"1.0.3"

// 版本 API 地址列表（按优先级排列，第一个是主力）
// 为什么要多个：主接口曾多次因中转服务不稳定而连不上。任一地址失败
#define VERSION_API_BASES { L"https://version-interface.us.ci", L"https://api.rxfmprc89.nyat.app:57294", }

#define VERSION_API_PATH    L"/api/versions"

// 版本清单文件名（与 jar 同目录：%APPDATA%\NCL_Launcherersions.json）
// 这样「新版崩了自动回退」才能生效 —— 否则引导器只按版本号取最高，
#define VERSION_MANIFEST_NAME L"versions.json"

// 已知 jar SHA-256 表（信任锚）
// 只有命中其中之一才接受。发布新版本时必须
#define KNOWN_JAR_SHA256_LIST \
    { L"1.0.0", L"D131F8EF97E6532B34A6BA7582307C2E9A4EAEDAFFA442CFDEAA382456C355BD" }, \
    { L"1.0.1", L"9B42A7FB2B2C6F3E4DB467C0887B7B59DBC3EE1686572D934540250A727EA8BF" }, \
    { L"1.0.2", L"B750419C21BDAEC515EC1E46BCB8F25DC513D170C756D9A3A312DA3D24B133D9" }, \
    { L"1.0.3", L"88D664EA95D746D2D070EBCFA867AE83BD9DD5D1B14F5368586F414C4E5A6B25" }, \
    { L"1.0.4", L"CE5568061FA0F4F54B0C2C1B67CED3E5C60F8C47FE7257CCDE1DB1861D2809B2" }, \
    { L"1.0.5", L"62100ED9095E48CAA38FB1083ECB085BD8C7FAB8EFEEF6A9D6D987AF1A65FA34" }

// 启动成功标记文件名（Java 侧在主窗口显示成功后写入，内容是本次进程 PID）
#define STARTUP_OK_NAME L"startup.ok"

// 日志文件上限：超过就在启动时轮转（重命名为 .old，只保留一份历史）
//
//  1 MB 足够放下十几次完整启动流程的记录，又不会让日志失控增长。
#define MAX_LOG_BYTES (1024ULL * 1024ULL)

// 首次运行需要的最小可用磁盘空间
// => 合计约 250 MB。取 300 MB 留出安全边际。
#define MIN_FREE_SPACE_BYTES (300ULL * 1024ULL * 1024ULL)

// 等待启动成功的时长与轮询间隔（毫秒）
//
//  节奏：第 3 秒先检一次（多数机器此时已就绪，可提前退出），
//  没成功则轮询到第 15 秒；之后每 30 秒续一次、继续轮询，
//  直到进程退出或写入 startup.ok —— 慢启动不弹窗、不判失败。
#define STARTUP_FIRST_CHECK_MS  3000
#define STARTUP_TOTAL_WAIT_MS   15000
// 超过总时长仍未就绪时，每次续等的时长（循环续等，直到进程退出或写入标记）
#define STARTUP_MORE_WAIT_MS    30000
#define STARTUP_POLL_MS         250

// 回退重试的最大次数（防止每个版本都有问题时无限循环）
#define MAX_ROLLBACK_ATTEMPTS   3

// 启动器 jar 的下载地址前缀
//   完整地址 = JAR_DOWNLOAD_PREFIX + "mc-launcher-" + 版本号 + ".jar"
#define JAR_DOWNLOAD_PREFIX \
    L"https://3984235.xyz/"

// Adoptium JRE 17 下载 API（返回 JSON，从中解析出真实 zip 地址）
#define ADOPTIUM_API_URL \
    L"https://api.adoptium.net/v3/assets/latest/17/hotspot" \
    L"?architecture=x64&image_type=jre&os=windows&vendor=eclipse"

// ---------------------------------------------------------------------------

// 镜像前缀（按实测速度排序，程序会逐个尝试）
#define JRE_MIRROR_PREFIX_1  L"https://mirror.nju.edu.cn/adoptium/"
#define JRE_MIRROR_PREFIX_2  L"https://mirrors.huaweicloud.com/adoptium/"
#define JRE_MIRROR_PREFIX_3  L"https://mirrors.ustc.edu.cn/adoptium/"

// ---------------------------------------------------------------------------
// 两者架构必须匹配，否则 JavaFX 的 native 库会加载失败。

// 当前进程的架构标识（运行时初始化）
extern std::wstring g_archMirror;    // 镜像目录用：x64 / x32
extern std::wstring g_archApi;       // Adoptium API 用：x64 / x86-32
extern std::wstring g_jreFileName;   // 完整文件名（含架构）
extern std::wstring g_jreVersionPath;// 版本目录，如 "17/jre/x64/windows/"

// 检测当前进程架构并填充上面的变量。
// 必须在任何 JRE 下载逻辑之前调用（main 里尽早）。
void InitArch();

// ============================================================================
#define LIBERICA_CDN  L"https://download.bell-sw.com/java/"

// Liberica 已知文件名（版本@架构 -> 文件名）
// 这些版本号需随 Liberica 发版更新；失效时镜像发现逻辑会兜底。
#define LIBERICA_VER_8        L"8u513+7"
#define LIBERICA_VER_17       L"17.0.9+11"
#define LIBERICA_VER_21       L"21.0.9+15"

// ============================================================================
#define ZULU_CDN  L"https://cdn.azul.com/zulu/bin/"

// JRE 包的内置 SHA-256：{ 架构(x64/x32), 哈希 }。
// 只填实际核验过的架构；未核验的保持空串，不要猜测。
#define KNOWN_JRE_SHA256_LIST \
    { L"x64", L"" }, \
    { L"x32", L"5ccb711c9b80fd4760b5a6096fc7741d74a22ef19783d77da8850ec60e6181a0" }

// Zulu FX 文件名（32 位 i686 / 64 位 x64）
#define ZULU_17_X86  L"zulu17.68.203-ca-fx-jre17.0.20.1-win_i686.zip"
#define ZULU_17_X64  L"zulu17.68.203-ca-fx-jre17.0.20.1-win_x64.zip"

// ============================================================================
//  文件与目录名
// ============================================================================

// AppData\Roaming 下的目录名
#define APP_DIR_NAME        L"NCL_Launcher"

// JRE 解压根目录（其下会有一层 jdk-17.x.x+x 目录）
#define JAVA_DIR_NAME       L"java"
// 下载的 jar 存放目录
#define JAR_SUBDIR_NAME     L""

// ============================================================================
//  错误码
// ============================================================================

enum class StepResult {
    Ok = 0,
    NetworkError,       // 网络请求失败/超时
    HttpError,          // 服务器返回非 2xx
    FileError,          // 本地文件读写失败
    PermissionError,    // 权限不足
    ParseError,         // 响应解析失败
    ExtractError,       // 解压失败
    JavaNotFound,       // 找不到可用的 Java
    LaunchFailed,       // 拉起主程序失败
    Cancelled,          // 用户取消
    ChecksumMismatch,   // jar 的 SHA-256 与已知值不符（可能被篡改或下载损坏）
    UntrustedJar,       // jar 没有任何可信来源（既不在内置表，也没有官方哈希）
};

// 把错误码转成给用户看的中文说明
const wchar_t* StepResultText(StepResult r);

// ============================================================================
//  全局路径（启动时初始化一次）
// ============================================================================

struct AppPaths {
    std::wstring appData;       // %APPDATA%\NCL_Launcher
    std::wstring javaDir;       // %APPDATA%\NCL_Launcher\java
    std::wstring jreHome;       // 实际含 bin\javaw.exe 的目录
    std::wstring jarPath;       // %APPDATA%\NCL_Launcher\mc-launcher-x.y.z.jar

    // 解析并创建所需目录；失败返回 false
    bool Init();
};

// ============================================================================
//  进度回调
// ============================================================================

// 阶段名 + 当前/总量（总量为 0 表示未知，界面显示"不确定进度"）
using ProgressFn = std::function<void(const std::wstring& stage,
                                      uint64_t done,
                                      uint64_t total)>;

// ============================================================================
//  日志（写到 %APPDATA%\NCL_Launcher\bootstrap.log）
// ============================================================================

namespace Log {
    void Init(const AppPaths& paths);
    void Write(const std::wstring& msg);
    void WriteF(const wchar_t* fmt, ...);
    // 与 WriteF 相同，但格式串是 UTF-8 的窄字符串（%s 接 std::string / char*）
    void WriteUtf8(const char* fmt, ...);
}

// ============================================================================
//  工具函数（util.cpp）
// ============================================================================

namespace Util {

    // 取 %APPDATA% 路径；失败返回空串
    std::wstring GetAppDataPath();

    // 递归创建目录（等价 mkdir -p）
    bool CreateDirectories(const std::wstring& path);

    // 路径是否存在（文件或目录）
    bool PathExists(const std::wstring& path);

    // 是否是文件
    bool IsFile(const std::wstring& path);

    // 是否是目录
    bool IsDirectory(const std::wstring& path);

    // 取文件名（不含目录）
    std::wstring FileName(const std::wstring& path);

    // 拼接路径（自动处理分隔符）
    std::wstring JoinPath(const std::wstring& a, const std::wstring& b);

    // 删除文件（不存在也算成功）
    bool DeleteFileQuiet(const std::wstring& path);

    // 递归删除目录（不存在也算成功）。用于清理解压/下载失败留下的半成品：
    // 残留在 java 目录里的 javaw.exe 可能损坏，却又会被下一次探测当成可用，
    // 让玩家反复遇到「Java 已装好但启动失败」。
    bool DeleteDirectoryQuiet(const std::wstring& path);

    // 取文件大小（字节）；不存在返回 0
    uint64_t FileSize(const std::wstring& path);

    // 读取整个小文件为 std::string（失败返回空）
    bool ReadSmallFile(const std::wstring& path, std::string& out, size_t maxBytes = 64 * 1024);

    // 在指定目录下递归查找文件名匹配的第一个文件
    // 用于在解压后的 jre 目录里定位 bin\javaw.exe
    std::wstring FindFileRecursive(const std::wstring& dir,
                                   const std::wstring& fileName,
                                   int maxDepth = 4);

    // 自增计数器（用于生成临时文件名）
    uint64_t NextCounter();

    // 字符串辅助
    bool EndsWithNoCase(const std::wstring& s, const std::wstring& suffix);
    std::wstring Trim(const std::wstring& s);

    // 查询指定路径所在磁盘是否有 required 字节的剩余空间
    //
    //  路径不存在时会向上找父目录。查询失败（无权限等）返回 true，
    //  不阻断流程 —— 让后续实际写入去报错更准确。
    //  outFree 返回实际剩余字节数，供提示文案使用。
    bool HasFreeSpace(const std::wstring& path, uint64_t required, uint64_t& outFree);

    // 字节数转可读字符串（"1.2 GB"）
    std::wstring HumanSize(uint64_t bytes);

    // 读取整个文件为 UTF-8 字节串；失败返回 false
    //
    // 自动跳过 UTF-8 BOM —— 清单文件可能被记事本另存过，
    // 带 BOM 会让 JSON 解析的首个字段名匹配不上。
    bool ReadTextFileUtf8(const std::wstring& path, std::string& out);

    // 把 UTF-8 内容整体写入文件（覆盖），失败返回 false
    bool WriteTextFileUtf8(const std::wstring& path, const std::string& data);

    // UTF-8 <-> UTF-16 转换
    std::wstring Utf8ToWide(const std::string& s);
    std::string  WideToUtf8(const std::wstring& s);

} // namespace Util

// ============================================================================
//  SHA-256 校验（util.cpp）
// ============================================================================
namespace Hs {

    // 计算文件 SHA-256，输出小写十六进制。
    // cancelFlag 非空时每块检查一次；onProgress 可为空。
    bool Sha256File(const std::wstring& path,
                    std::string& outHex,
                    volatile bool* cancelFlag = nullptr,
                    const std::function<void(uint64_t, uint64_t)>& onProgress = nullptr);

    // 取哈希字符串本身（从 .sha256 文件内容里挑出 64 位十六进制），没有则返回空
    std::string ExtractHash(const std::string& text);

    bool EqualsIgnoreCase(const std::string& a, const std::string& b);
    // 统一转小写
    std::string NormalizeHash(std::string hex);
    // 把哈希转成“前 8 位…后 8 位”的短形式，用于错误提示
    std::wstring ShortHash(const std::string& hex);
} // namespace Hs

// ============================================================================
//  HTTP 下载（http.cpp）
// ============================================================================

namespace Http {

    // ---- 最近一次网络失败的详情 ----
    void SetLastNetworkDetail(const std::wstring& detail);
    std::wstring GetLastNetworkDetail();


    // 下载源白名单：返回 true 才允许请求/跟随重定向。
    // 用于 JRE 这类「下载后会被执行」的文件 —— 地址只允许来自固定的官方/CDN 域名。
    using AllowRedirectFn = bool (*)(const std::wstring& url);

    // 简单的 GET，把响应体作为字符串返回（用于 JSON API）
    StepResult GetText(const std::wstring& url,
                       std::wstring& outText,
                       DWORD timeoutMs = 20000);

    // 下载文件到磁盘，支持进度回调与断点续传（若服务器支持 Range）
    StepResult DownloadFile(const std::wstring& url,
                            const std::wstring& destPath,
                            ProgressFn progress,
                            volatile bool* cancelFlag = nullptr,
                            AllowRedirectFn allowUrl = nullptr);

    // 解析一个 URL 的 host / port / path
    bool ParseUrl(const std::wstring& url,
                  std::wstring& host, INTERNET_PORT& port,
                  std::wstring& path, bool& https);

    // 解读 WinHTTP 错误，返回给用户看的中文说明
    std::wstring DescribeLastError(DWORD err);

} // namespace Http

// ============================================================================
//  ZIP 解压（zip.cpp）
// ============================================================================

namespace Zip {

    // 调用系统自带能力解压 zip 到目标目录。
    StepResult ExtractTo(const std::wstring& zipPath,
                         const std::wstring& destDir,
                         ProgressFn progress);

    // 只校验压缩包结构（中央目录 / 条目名 / zip-slip / 符号链接），不解压。
    // JRE 这类「解压后立即执行」的包必须在解压前过这一关。
    StepResult Validate(const std::wstring& zipPath);

} // namespace Zip

// ============================================================================
//  Java 环境（java.cpp）
// ============================================================================

namespace Java {

    // 在常见位置探测 Java 17+，找到返回其 javaw.exe 完整路径
    std::wstring FindJava17(const AppPaths& paths);

    // 校验某个 javaw.exe 是否是 17 或更高版本
    bool IsAtLeastJava17(const std::wstring& javawPath);

    // 该 javaw 能否在当前系统上运行
    //
    //  关键：64 位 JVM 在 32 位系统上无法启动，反之可以。
    //  所以「版本够」不等于「能用」—— 还必须架构兼容。
    //  早期只看版本号，导致 32 位系统上选中系统的 64 位 Java 后启动失败。
    bool IsJavaCompatible(const std::wstring& javawPath);

    // 确保有可用的 Java 17：先探测，找不到就下载安装
    StepResult EnsureJava(const AppPaths& paths,
                          ProgressFn progress,
                          volatile bool* cancelFlag,
                          std::wstring& outJavawPath);

} // namespace Java

// ============================================================================
//  启动器 jar（launcher.cpp）
// ============================================================================

namespace Launcher {

    // 向版本 API 查询最新版本号。失败时 out 保持为空。
    StepResult QueryLatestVersion(std::wstring& outVersion);

    // 查询指定版本的官方 SHA-256（文件服务器上的 <版本>.sha256，走 HTTPS）。
    // 拿不到时返回空串 —— 调用方会退回内置表，不把「拿不到」当成失败。
    std::string QueryOfficialSha256(const std::wstring& version);

    // 把 string 按 ':' 切成两段（用于内置表的 {"版本", "哈希"}）
    std::string KnownJarHash(const std::wstring& version);

    // 校验一个 jar 是否可信。必须在**每一次** Launcher::Launch 之前调用
    StepResult VerifyJar(const AppPaths& paths,
                         const std::wstring& jarPath,
                         ProgressFn progress,
                         volatile bool* cancelFlag,
                         std::wstring* outDetail);

    // 本次运行中从 HTTPS 渠道「新下载并校验通过」的 jar 绝对路径。
    // 这种情况不能把用户卡死：自己刚下的文件放行一次（路径+大小记账）。
    bool SelectedFileIsOurs(const AppPaths& paths, const std::wstring& jarPath);

    void MarkSelfDownloaded(const std::wstring& jarPath);
    bool IsSelfDownloaded(const std::wstring& jarPath);

    // 扫描数据目录，找出本地已存在的**最新版本** jar。
    StepResult FindLatestLocalJar(const AppPaths& paths,
                                  std::wstring& outVersion,
                                  std::wstring& outJarPath);

    // 读取版本清单，取出「应当启动的版本号」。
    // 为什么优先 current 而不是「版本最高的」：
    StepResult LoadPreferredJar(const AppPaths& paths,
                                std::wstring& outVersion,
                                std::wstring& outJarPath);

    // 比较两个语义化版本号：a > b 返回正数，a < b 返回负数，相等返回 0
    // 支持任意位数（1.0.0.1 > 1.0.0）
    int CompareVersions(const std::wstring& a, const std::wstring& b);

    // 确保 jar 存在（不存在则下载）。返回 jar 的完整路径。
    StepResult EnsureJar(const AppPaths& paths,
                         const std::wstring& version,
                         ProgressFn progress,
                         volatile bool* cancelFlag,
                         std::wstring& outJarPath,
                         // 已有文件时：true = 按期望哈希校验，不符则删除重下（首次安装用）
                         //           false = 沿用「非空即用」（更新/回退路径用，校验交给 VerifyJar）
                         bool verifyExisting = false,
                         // 下载后哈希不符时的说明（可空）：写清期望/实际哈希与下载地址，
                         // 让错误弹窗能指出真正原因（如 CDN 缓存了旧文件），而不是只说「校验失败」
                         std::wstring* outDetail = nullptr);

    // 用 javaw 拉起启动器
    StepResult Launch(const std::wstring& javawPath,
                      const std::wstring& jarPath,
                      const std::wstring& workDir,
                      ProgressFn progress = nullptr,
                      DWORD* outChildPid = nullptr,
                      HANDLE* outChildProcess = nullptr);

    // 等待主程序「正常起来」——双重判定，超时返回 false
    bool WaitForStartup(const std::wstring& appData,
                        HANDLE childProcess,
                        DWORD childPid,
                        DWORD waitMs,
                        DWORD pollMs,
                        volatile bool* cancelFlag,
                        std::wstring* outWhy);

    // 等待结果
    // 不能只用 bool：「等满时间还没写 startup.ok」和「进程崩了/自己退了」
    enum class WaitResult {
        Started,    // startup.ok 匹配本次 PID，或进程正常退出（用户主动关闭）
        Crashed,    // 进程以非 0 退出码结束
        Exited,     // 进程以 0 退出但从未写过 startup.ok（界面没出现）
        TimedOut,   // 等满了时间，进程还活着但没写 startup.ok（只是慢）
        Cancelled,  // 用户取消
    };
    WaitResult WaitForStartupEx(const std::wstring& appData,
                                HANDLE childProcess,
                                DWORD childPid,
                                DWORD waitMs,
                                DWORD pollMs,
                                volatile bool* cancelFlag,
                                std::wstring* outWhy);

    // 删除指定失败版本的 jar 文件（避免下次从磁盘扫描时发现它）
    // 失败不抛出：文件不存在也算成功
    void DeleteFailedJar(const AppPaths& paths, const std::wstring& version);

    // 找可回退的下一个版本：清单里 usable=true 且版本号低于 current 的最高者
    bool FindRollbackCandidate(const AppPaths& paths,
                               const std::wstring& currentVersion,
                               std::wstring& outVersion,
                               std::wstring& outJarPath);

    // 把清单的 current 改成指定版本（回退后写回）
    bool UpdateManifestCurrent(const AppPaths& paths,
                               const std::wstring& newVersion);

    // 把指定版本在清单里标记为 usable=false
    bool MarkVersionUnusable(const AppPaths& paths,
                             const std::wstring& version);

} // namespace Launcher

// ============================================================================
//  原生界面（ui.cpp）
// ============================================================================

// 主线程需要的自定义消息（与 ui.cpp 中的定义保持一致）
//   WM_APP + 4：窗口请求取消 -> 主线程置位全局取消标志
#define WM_APP_CANCEL_REQ  (WM_APP + 4)

namespace Ui {

    // 初始化通用控件（进度条需要）
    void InitCommonControls();

    // 显示一个带进度条的小窗口。返回窗口句柄。
    HWND CreateProgressWindow(const std::wstring& title);

    // 更新进度窗口中的文字与进度（线程安全，内部会 PostMessage）
    void UpdateProgress(HWND hwnd, const std::wstring& stage,
                        uint64_t done, uint64_t total);

    // 显示进度窗口
    //
    //   **必须在创建窗口的线程（主线程）上调用**。
    //   窗口由主线程提前创建（隐藏），工作线程发 WM_APP_SHOW 请求显示。
    void ShowProgressWindow(HWND hwnd);

    // 关闭并销毁进度窗口
    void CloseProgressWindow(HWND hwnd);

    // 处理消息队列（在工作线程等待时调用，避免窗口假死）
    void PumpMessages();

    // 原生消息框
    void Info(const std::wstring& text, const std::wstring& title = L"提示");
    void Error(const std::wstring& text, const std::wstring& title = L"出错了");
    bool Confirm(const std::wstring& text, const std::wstring& title = L"确认");

    // 「启动器是否正常显示」的三选一询问
    BOOL  AskStartupOk(const std::wstring& version);

    // 判断用户是否点了取消
    bool IsCancelled(HWND hwnd);

} // namespace Ui