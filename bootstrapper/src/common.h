#pragma once

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

#include <winhttp.h>

#include <string>
#include <vector>
#include <functional>
#include <cstdint>
#include <memory>

#define BOOTSTRAPPER_VERSION L"1.0.0"
#define DEFAULT_LAUNCHER_VERSION L"1.0.2"

#ifndef IDI_APPICON
#define IDI_APPICON 101
#endif

#define VERSION_API_BASES {         L"https://version-interface.us.ci",                         L"http://frp.xueyuanpang.dpdns.org:37666",              }
#define VERSION_API_PATH    L"/api/versions"

#define VERSION_MANIFEST_NAME L"versions.json"

#define KNOWN_JAR_SHA256_LIST \
    { L"1.0.0", L"D131F8EF97E6532B34A6BA7582307C2E9A4EAEDAFFA442CFDEAA382456C355BD" }, \
    { L"1.0.1", L"9B42A7FB2B2C6F3E4DB467C0887B7B59DBC3EE1686572D934540250A727EA8BF" }, \
    { L"1.0.2", L"B750419C21BDAEC515EC1E46BCB8F25DC513D170C756D9A3A312DA3D24B133D9" }

#define STARTUP_OK_NAME L"startup.ok"

#define MAX_LOG_BYTES (1024ULL * 1024ULL)

#define MIN_FREE_SPACE_BYTES (300ULL * 1024ULL * 1024ULL)

#define STARTUP_FIRST_CHECK_MS  3000
#define STARTUP_TOTAL_WAIT_MS   15000
#define STARTUP_MORE_WAIT_MS    30000
#define STARTUP_POLL_MS         250

#define MAX_ROLLBACK_ATTEMPTS   3

#define JAR_DOWNLOAD_PREFIX \
    L"https://3984235.xyz/"

#define ADOPTIUM_API_URL \
    L"https://api.adoptium.net/v3/assets/latest/17/hotspot" \
    L"?architecture=x64&image_type=jre&os=windows&vendor=eclipse"

#define JRE_MIRROR_PREFIX_1  L"https://mirror.nju.edu.cn/adoptium/"
#define JRE_MIRROR_PREFIX_2  L"https://mirrors.huaweicloud.com/adoptium/"
#define JRE_MIRROR_PREFIX_3  L"https://mirrors.ustc.edu.cn/adoptium/"

extern std::wstring g_archMirror;    
extern std::wstring g_archApi;       
extern std::wstring g_jreFileName;   
extern std::wstring g_jreVersionPath;

void InitArch();

#define LIBERICA_CDN  L"https://download.bell-sw.com/java/"
#define LIBERICA_VER_8        L"8u513+7"
#define LIBERICA_VER_17       L"17.0.9+11"
#define LIBERICA_VER_21       L"21.0.9+15"

#define ZULU_CDN  L"https://cdn.azul.com/zulu/bin/"
#define ZULU_17_X86  L"zulu17.68.203-ca-fx-jre17.0.20.1-win_i686.zip"
#define ZULU_17_X64  L"zulu17.68.203-ca-fx-jre17.0.20.1-win_x64.zip"

#define APP_DIR_NAME        L"NCL_Launcher"
#define OLD_APP_DIR_NAME    L"MC_Launcher"   
#define JAVA_DIR_NAME       L"java"          
#define JAR_SUBDIR_NAME     L""

enum class StepResult {
    Ok = 0,
    NetworkError,       
    HttpError,          
    FileError,          
    PermissionError,    
    ParseError,         
    ExtractError,       
    JavaNotFound,       
    LaunchFailed,       
    Cancelled,          
    ChecksumMismatch,   
    UntrustedJar,       
};

const wchar_t* StepResultText(StepResult r);

struct AppPaths {
    std::wstring appData;       
    std::wstring javaDir;       
    std::wstring jreHome;       
    std::wstring jarPath;       

    bool Init();
};

using ProgressFn = std::function<void(const std::wstring& stage,
                                      uint64_t done,
                                      uint64_t total)>;

namespace Log {
    void Init(const AppPaths& paths);
    void Write(const std::wstring& msg);
    void WriteF(const wchar_t* fmt, ...);
    void WriteUtf8(const char* fmt, ...);   
}

namespace Util {

    std::wstring GetAppDataPath();

    bool CreateDirectories(const std::wstring& path);
    bool PathExists(const std::wstring& path);
    bool IsFile(const std::wstring& path);
    bool IsDirectory(const std::wstring& path);

    std::wstring FileName(const std::wstring& path);
    std::wstring JoinPath(const std::wstring& a, const std::wstring& b);
    bool DeleteFileQuiet(const std::wstring& path);
    uint64_t FileSize(const std::wstring& path);

    bool ReadSmallFile(const std::wstring& path, std::string& out, size_t maxBytes = 64 * 1024);

    std::wstring FindFileRecursive(const std::wstring& dir,
                                   const std::wstring& fileName,
                                   int maxDepth = 4);

    uint64_t NextCounter();

    bool EndsWithNoCase(const std::wstring& s, const std::wstring& suffix);
    std::wstring Trim(const std::wstring& s);

    bool HasFreeSpace(const std::wstring& path, uint64_t required, uint64_t& outFree);

    std::wstring HumanSize(uint64_t bytes);

    bool CopyDirectory(const std::wstring& from, const std::wstring& to);

    bool ReadTextFileUtf8(const std::wstring& path, std::string& out);
    bool WriteTextFileUtf8(const std::wstring& path, const std::string& data);

    std::wstring Utf8ToWide(const std::string& s);
    std::string  WideToUtf8(const std::wstring& s);

} 

namespace Hs {

    bool Sha256File(const std::wstring& path,
                    std::string& outHex,
                    volatile bool* cancelFlag = nullptr,
                    const std::function<void(uint64_t, uint64_t)>& onProgress = nullptr);

    std::string ExtractHash(const std::string& text);

    bool EqualsIgnoreCase(const std::string& a, const std::string& b);
    std::string NormalizeHash(std::string hex);
    std::wstring ShortHash(const std::string& hex);   
} 

namespace Http {

    void SetLastNetworkDetail(const std::wstring& detail);
    std::wstring GetLastNetworkDetail();

    StepResult GetText(const std::wstring& url,
                       std::wstring& outText,
                       DWORD timeoutMs = 20000);

    StepResult DownloadFile(const std::wstring& url,
                            const std::wstring& destPath,
                            ProgressFn progress,
                            volatile bool* cancelFlag = nullptr);

    bool ParseUrl(const std::wstring& url,
                  std::wstring& host, INTERNET_PORT& port,
                  std::wstring& path, bool& https);

    std::wstring DescribeLastError(DWORD err);

} 

namespace Zip {

    StepResult ExtractTo(const std::wstring& zipPath,
                         const std::wstring& destDir,
                         ProgressFn progress);

} 

namespace Java {

    std::wstring FindJava17(const AppPaths& paths);

    bool IsAtLeastJava17(const std::wstring& javawPath);

    bool IsJavaCompatible(const std::wstring& javawPath);

    StepResult EnsureJava(const AppPaths& paths,
                          ProgressFn progress,
                          volatile bool* cancelFlag,
                          std::wstring& outJavawPath);

} 

namespace Launcher {

    StepResult QueryLatestVersion(std::wstring& outVersion);

    std::string QueryOfficialSha256(const std::wstring& version);

    std::string KnownJarHash(const std::wstring& version);

    StepResult VerifyJar(const AppPaths& paths,
                         const std::wstring& jarPath,
                         ProgressFn progress,
                         volatile bool* cancelFlag,
                         std::wstring* outDetail);

    bool SelectedFileIsOurs(const AppPaths& paths, const std::wstring& jarPath);

    void MarkSelfDownloaded(const std::wstring& jarPath);
    bool IsSelfDownloaded(const std::wstring& jarPath);

    StepResult FindLatestLocalJar(const AppPaths& paths,
                                  std::wstring& outVersion,
                                  std::wstring& outJarPath);

    StepResult LoadPreferredJar(const AppPaths& paths,
                                std::wstring& outVersion,
                                std::wstring& outJarPath);

    int CompareVersions(const std::wstring& a, const std::wstring& b);

    StepResult EnsureJar(const AppPaths& paths,
                         const std::wstring& version,
                         ProgressFn progress,
                         volatile bool* cancelFlag,
                         std::wstring& outJarPath,
                         bool verifyExisting = false,      
                         std::wstring* outDetail = nullptr);

    StepResult Launch(const std::wstring& javawPath,
                      const std::wstring& jarPath,
                      const std::wstring& workDir,
                      ProgressFn progress = nullptr,
                      DWORD* outChildPid = nullptr,
                      HANDLE* outChildProcess = nullptr);

    bool WaitForStartup(const std::wstring& appData,
                        HANDLE childProcess,
                        DWORD childPid,
                        DWORD waitMs,
                        DWORD pollMs,
                        volatile bool* cancelFlag,
                        std::wstring* outWhy);

    enum class WaitResult {
        Started,
        Crashed,
        Exited,
        TimedOut,
        Cancelled,
    };
    WaitResult WaitForStartupEx(const std::wstring& appData,
                                HANDLE childProcess,
                                DWORD childPid,
                                DWORD waitMs,
                                DWORD pollMs,
                                volatile bool* cancelFlag,
                                std::wstring* outWhy);

    void DeleteFailedJar(const AppPaths& paths, const std::wstring& version);

    bool FindRollbackCandidate(const AppPaths& paths,
                               const std::wstring& currentVersion,
                               std::wstring& outVersion,
                               std::wstring& outJarPath);

    bool UpdateManifestCurrent(const AppPaths& paths, const std::wstring& newVersion);
    bool MarkVersionUnusable(const AppPaths& paths, const std::wstring& version);

} 

#define WM_APP_CANCEL_REQ  (WM_APP + 4)

namespace Ui {

    void InitCommonControls();

    HWND CreateProgressWindow(const std::wstring& title);
    void UpdateProgress(HWND hwnd, const std::wstring& stage,
                        uint64_t done, uint64_t total);
    void ShowProgressWindow(HWND hwnd);   
    void CloseProgressWindow(HWND hwnd);
    void PumpMessages();

    void Info(const std::wstring& text, const std::wstring& title = L"提示");
    void Error(const std::wstring& text, const std::wstring& title = L"出错了");
    bool Confirm(const std::wstring& text, const std::wstring& title = L"确认");

    BOOL  AskStartupOk(const std::wstring& version);

    bool IsCancelled(HWND hwnd);

} 
