// ============================================================================
//  util.cpp —— 路径、文件与字符串工具
//
//  全部使用宽字符版本 API（GetEnvironmentVariableW / CreateDirectoryW ...），
//  保证中文用户名、中文路径都能正确处理。
// ============================================================================
#include "common.h"

#include <shlwapi.h>
#include <shellapi.h>
#include <shlobj.h>      // SHGetFolderPathW
#include <cstdarg>
#include <cstdio>
#include <atomic>
#include <cctype>
#include <new>
#include <bcrypt.h>


namespace Util {

// ---------------------------------------------------------------------------
//  %APPDATA% 路径
// ---------------------------------------------------------------------------
std::wstring GetAppDataPath() {
    // 测试隔离：设置了该环境变量时，用它的值代替 %APPDATA%。
    //   tools/run-bootstrap-test.py 用它在沙箱目录里跑完整引导流程，
    //   不碰玩家真实的数据目录、不拉起真实启动器界面。
    DWORD tn = GetEnvironmentVariableW(L"MCLAUNCHER_TEST_APPDATA", nullptr, 0);
    if (tn > 0) {
        std::wstring tv(tn, L'\0');
        DWORD tgot = GetEnvironmentVariableW(L"MCLAUNCHER_TEST_APPDATA", tv.data(), tn);
        if (tgot > 0 && tgot < tn) {
            tv.resize(tgot);
            return tv;
        }
    }

    // 优先用 SHGetFolderPath，它比读环境变量更可靠（能处理重定向的情况）
    wchar_t buf[MAX_PATH] = {0};
    if (SUCCEEDED(SHGetFolderPathW(nullptr, CSIDL_APPDATA, nullptr, 0, buf))) {
        if (buf[0] != L'\0') {
            return std::wstring(buf);
        }
    }

    // 退回读环境变量
    DWORD n = GetEnvironmentVariableW(L"APPDATA", nullptr, 0);
    if (n > 0) {
        std::wstring v(n, L'\0');
        DWORD got = GetEnvironmentVariableW(L"APPDATA", v.data(), n);
        if (got > 0 && got < n) {
            v.resize(got);
            return v;
        }
    }

    return L"";
}

// ---------------------------------------------------------------------------
//  创建目录（递归）
// ---------------------------------------------------------------------------
bool CreateDirectories(const std::wstring& path) {
    if (path.empty()) {
        return false;
    }
    if (IsDirectory(path)) {
        return true;
    }

    // 逐级创建：从根开始，遇到分隔符就尝试建一层
    std::wstring cur;
    for (size_t i = 0; i < path.size(); ++i) {
        cur.push_back(path[i]);
        bool atSep = (path[i] == L'\\' || path[i] == L'/');

        // 跳过 "C:" 这类只有盘符的前缀
        bool driveOnly = (cur.size() == 2 && cur[1] == L':');

        if ((atSep || i == path.size() - 1) && !driveOnly && cur.size() > 1) {
            // 去掉末尾分隔符再创建
            std::wstring toCreate = cur;
            while (!toCreate.empty() &&
                   (toCreate.back() == L'\\' || toCreate.back() == L'/')) {
                toCreate.pop_back();
            }
            if (!toCreate.empty() && !IsDirectory(toCreate)) {
                if (!CreateDirectoryW(toCreate.c_str(), nullptr)) {
                    DWORD err = GetLastError();
                    // 已存在不算失败（并发创建/竞态）
                    if (err != ERROR_ALREADY_EXISTS) {
                        return false;
                    }
                }
            }
        }
    }
    return IsDirectory(path);
}

// ---------------------------------------------------------------------------
//  存在性判断
// ---------------------------------------------------------------------------
bool PathExists(const std::wstring& path) {
    if (path.empty()) return false;
    DWORD attr = GetFileAttributesW(path.c_str());
    return attr != INVALID_FILE_ATTRIBUTES;
}

bool IsFile(const std::wstring& path) {
    if (path.empty()) return false;
    DWORD attr = GetFileAttributesW(path.c_str());
    return attr != INVALID_FILE_ATTRIBUTES && !(attr & FILE_ATTRIBUTE_DIRECTORY);
}

bool IsDirectory(const std::wstring& path) {
    if (path.empty()) return false;
    DWORD attr = GetFileAttributesW(path.c_str());
    return attr != INVALID_FILE_ATTRIBUTES && (attr & FILE_ATTRIBUTE_DIRECTORY);
}

// ---------------------------------------------------------------------------
//  路径拼接
// ---------------------------------------------------------------------------
std::wstring JoinPath(const std::wstring& a, const std::wstring& b) {
    if (a.empty()) return b;
    if (b.empty()) return a;

    std::wstring r = a;
    wchar_t last = r.back();
    if (last != L'\\' && last != L'/') {
        r.push_back(L'\\');
    }
    // b 若以分隔符开头，去掉一个，避免出现双斜杠
    size_t start = 0;
    if (b[0] == L'\\' || b[0] == L'/') {
        start = 1;
    }
    r.append(b, start, std::wstring::npos);
    return r;
}

std::wstring FileName(const std::wstring& path) {
    size_t pos = path.find_last_of(L"\\/");
    if (pos == std::wstring::npos) {
        return path;
    }
    return path.substr(pos + 1);
}

// ---------------------------------------------------------------------------
//  文件操作
// ---------------------------------------------------------------------------
bool DeleteFileQuiet(const std::wstring& path) {
    if (path.empty()) return true;
    if (!PathExists(path)) return true;
    // 先去掉只读属性，否则删不掉
    SetFileAttributesW(path.c_str(), FILE_ATTRIBUTE_NORMAL);
    if (DeleteFileW(path.c_str()) != FALSE) {
        return true;
    }
    DWORD err = GetLastError();
    // 文件被占用或权限不足：标记重启时删除，视为“已处理”
    if (err == ERROR_SHARING_VIOLATION || err == ERROR_ACCESS_DENIED) {
        MoveFileExW(path.c_str(), nullptr, MOVEFILE_DELAY_UNTIL_REBOOT);
        return true;
    }
    // 其他错误（如路径不存在）也视为成功
    if (err == ERROR_FILE_NOT_FOUND || err == ERROR_PATH_NOT_FOUND) {
        return true;
    }
    return false;
}

uint64_t FileSize(const std::wstring& path) {
    WIN32_FILE_ATTRIBUTE_DATA fad;
    if (!GetFileAttributesExW(path.c_str(), GetFileExInfoStandard, &fad)) {
        return 0;
    }
    if (fad.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
        return 0;
    }
    ULARGE_INTEGER li;
    li.LowPart  = fad.nFileSizeLow;
    li.HighPart = fad.nFileSizeHigh;
    return li.QuadPart;
}

// ---------------------------------------------------------------------------
//  递归查找文件
// ---------------------------------------------------------------------------
static std::wstring FindFileRecursiveImpl(const std::wstring& dir,
                                          const std::wstring& fileName,
                                          int depth, int maxDepth) {
    if (depth > maxDepth) {
        return L"";
    }

    std::wstring pattern = JoinPath(dir, L"*");
    WIN32_FIND_DATAW fd;
    HANDLE h = FindFirstFileW(pattern.c_str(), &fd);
    if (h == INVALID_HANDLE_VALUE) {
        return L"";
    }

    std::vector<std::wstring> subDirs;

    do {
        std::wstring name = fd.cFileName;
        if (name == L"." || name == L"..") {
            continue;
        }

        std::wstring full = JoinPath(dir, name);

        if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            subDirs.push_back(full);
        } else {
            // 忽略大小写比较文件名
            if (_wcsicmp(name.c_str(), fileName.c_str()) == 0) {
                FindClose(h);
                return full;
            }
        }
    } while (FindNextFileW(h, &fd));

    FindClose(h);

    // 广度优先向下找，越浅层越先命中
    for (const auto& sub : subDirs) {
        std::wstring found = FindFileRecursiveImpl(sub, fileName, depth + 1, maxDepth);
        if (!found.empty()) {
            return found;
        }
    }
    return L"";
}

std::wstring FindFileRecursive(const std::wstring& dir,
                               const std::wstring& fileName,
                               int maxDepth) {
    if (!IsDirectory(dir)) {
        return L"";
    }
    return FindFileRecursiveImpl(dir, fileName, 0, maxDepth);
}

// ---------------------------------------------------------------------------
//  计数器 / 字符串
// ---------------------------------------------------------------------------
uint64_t NextCounter() {
    static std::atomic<uint64_t> counter{0};
    return ++counter;
}

bool EndsWithNoCase(const std::wstring& s, const std::wstring& suffix) {
    if (suffix.size() > s.size()) return false;
    size_t off = s.size() - suffix.size();
    return _wcsicmp(s.c_str() + off, suffix.c_str()) == 0;
}

// ---------------------------------------------------------------------------
//  查询指定路径所在磁盘的剩余空间
//
//  required 为需要的字节数；空间不足时 outFree 返回实际剩余量。
//  路径不存在时向上逐级查找，直到找到一个存在的父目录。
// ---------------------------------------------------------------------------
bool HasFreeSpace(const std::wstring& path, uint64_t required, uint64_t& outFree) {
    outFree = 0;

    // 找到第一个存在的父目录（GetDiskFreeSpaceExW 要求路径存在）
    std::wstring probe = path;
    while (!probe.empty() && !IsDirectory(probe)) {
        size_t sep = probe.find_last_of(L"\\/");
        if (sep == std::wstring::npos) {
            break;
        }
        probe = probe.substr(0, sep);
    }
    if (probe.empty()) {
        probe = L".";   // 兜底：当前目录
    }

    ULARGE_INTEGER freeBytesAvailable{};
    ULARGE_INTEGER totalBytes{};
    ULARGE_INTEGER totalFreeBytes{};

    if (!GetDiskFreeSpaceExW(probe.c_str(),
                             &freeBytesAvailable, &totalBytes, &totalFreeBytes)) {
        // 查询失败时不阻断流程 —— 宁可让后续写入自己报错，
        // 也不要因为一个探测失败就把用户挡在门外
        return true;
    }

    outFree = totalFreeBytes.QuadPart;
    return outFree >= required;
}

// ---------------------------------------------------------------------------
//  把字节数变成给人看的字符串（如 "1.2 GB"）
// ---------------------------------------------------------------------------
std::wstring HumanSize(uint64_t bytes) {
    const wchar_t* units[] = { L"B", L"KB", L"MB", L"GB", L"TB" };
    double v = static_cast<double>(bytes);
    int u = 0;
    while (v >= 1024.0 && u < 4) {
        v /= 1024.0;
        ++u;
    }
    wchar_t buf[64];
    _snwprintf_s(buf, _countof(buf), _TRUNCATE, L"%.1f %s", v, units[u]);
    return buf;
}

// ---------------------------------------------------------------------------
//  文本文件读取（原函数之前）
// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
bool ReadTextFileUtf8(const std::wstring& path, std::string& out) {
    out.clear();

    // FILE_SHARE_WRITE 必须有：Java 启动器可能正在改写 versions.json，
    // 只共享读会让这里的 CreateFileW 直接失败，进而让引导器退化成
    // 「按版本号取最高」—— 那正好会把刚崩掉的版本又拉起来。
    HANDLE h = CreateFileW(path.c_str(), GENERIC_READ,
                           FILE_SHARE_READ | FILE_SHARE_WRITE,
                           nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        return false;
    }

    LARGE_INTEGER size{};
    if (!GetFileSizeEx(h, &size) || size.QuadPart <= 0) {
        CloseHandle(h);
        return false;
    }
    // 清单文件不该有几十 MB，给个上限防止误读大文件把内存吃光
    if (size.QuadPart > (16 * 1024 * 1024)) {
        CloseHandle(h);
        Log::Write(L"ReadTextFileUtf8: 文件过大，拒绝读取");
        return false;
    }

    out.resize(static_cast<size_t>(size.QuadPart));
    DWORD read = 0;
    BOOL ok = ReadFile(h, out.data(), static_cast<DWORD>(out.size()), &read, nullptr);
    CloseHandle(h);

    if (!ok || read == 0) {
        out.clear();
        return false;
    }
    out.resize(read);

    // 跳过 UTF-8 BOM（EF BB BF）
    if (out.size() >= 3
            && static_cast<unsigned char>(out[0]) == 0xEF
            && static_cast<unsigned char>(out[1]) == 0xBB
            && static_cast<unsigned char>(out[2]) == 0xBF) {
        out.erase(0, 3);
    }
    return true;
}

bool WriteTextFileUtf8(const std::wstring& path, const std::string& data) {
    HANDLE h = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr,
                           CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        return false;
    }

    DWORD written = 0;
    BOOL ok = WriteFile(h, data.data(), static_cast<DWORD>(data.size()),
                        &written, nullptr);
    // 关键：FlushFileBuffers 确保数据真正落盘。
    // 回退逻辑紧接着就要读这个文件，不刷盘可能读到旧内容。
    if (ok) {
        FlushFileBuffers(h);
    }
    CloseHandle(h);

    return ok != FALSE && written == data.size();
}

std::wstring Utf8ToWide(const std::string& s) {
    if (s.empty()) {
        return L"";
    }
    int n = MultiByteToWideChar(CP_UTF8, 0, s.data(),
                                static_cast<int>(s.size()), nullptr, 0);
    if (n <= 0) {
        return L"";
    }
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()),
                        w.data(), n);
    return w;
}

std::string WideToUtf8(const std::wstring& s) {
    if (s.empty()) {
        return "";
    }
    int n = WideCharToMultiByte(CP_UTF8, 0, s.data(),
                                static_cast<int>(s.size()),
                                nullptr, 0, nullptr, nullptr);
    if (n <= 0) {
        return "";
    }
    std::string out(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()),
                        out.data(), n, nullptr, nullptr);
    return out;
}

std::wstring Trim(const std::wstring& s) {
    size_t b = 0;
    size_t e = s.size();
    auto isSpace = [](wchar_t c) {
        return c == L' ' || c == L'\t' || c == L'\r' || c == L'\n';
    };
    while (b < e && isSpace(s[b])) ++b;
    while (e > b && isSpace(s[e - 1])) --e;
    return s.substr(b, e - b);
}

bool ReadSmallFile(const std::wstring& path, std::string& out, size_t maxBytes) {
    out.clear();
    HANDLE f = CreateFileW(path.c_str(), GENERIC_READ,
                           FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) {
        return false;
    }
    LARGE_INTEGER size;
    if (!GetFileSizeEx(f, &size) || size.QuadPart <= 0 || size.QuadPart > static_cast<LONGLONG>(maxBytes)) {
        CloseHandle(f);
        return false;
    }
    out.resize(static_cast<size_t>(size.QuadPart));
    DWORD read = 0;
    BOOL ok = ReadFile(f, &out[0], static_cast<DWORD>(out.size()), &read, nullptr);
    CloseHandle(f);
    if (!ok || read != out.size()) {
        out.clear();
        return false;
    }
    return true;
}




} // namespace Util

// ============================================================================
//  架构检测
//
//  判据是**操作系统的原生位数**，不是进程位数。
//
//  为什么：引导器自己不加载任何 Java native 库 —— 它只是下载、解压，
//  最后用 CreateProcess 拉起 javaw。所以「引导器是 32 位」这件事
//  对选择 JRE 架构没有影响。
//
//  真正需要匹配的是「JVM 架构 ↔ 系统架构」：
//    - 64 位 JVM 无法在 32 位系统上运行
//    - 但 32 位 JVM 能在 64 位系统上运行（只是堆上限约 1.5G，
//      跑大整合包会 OOM）
//
//  所以：64 位系统一律下 64 位 JRE（性能最优）；
//        只有真正的 32 位系统才下 32 位 JRE。
//
//  这解决了「32 位引导器装在 64 位机器上会浪费性能」的问题。
// ============================================================================
std::wstring g_archMirror = L"x64";
std::wstring g_archApi    = L"x64";
std::wstring g_jreFileName;
std::wstring g_jreVersionPath;

void InitArch() {
    // ---- 判断操作系统的原生位数 ----
    //
    // GetNativeSystemInfo 在 WoW64 下返回**真实系统架构**
    // （而 GetSystemInfo 会返回被模拟的架构，不能用）。
    SYSTEM_INFO si;
    ZeroMemory(&si, sizeof(si));
    GetNativeSystemInfo(&si);

    bool systemIs64 = (si.wProcessorArchitecture == PROCESSOR_ARCHITECTURE_AMD64
                    || si.wProcessorArchitecture == PROCESSOR_ARCHITECTURE_ARM64);

    // 进程位数（仅用于日志对比，帮助排查"为什么选了另一个架构"）
    bool proc32 = (sizeof(void*) == 4);

    if (systemIs64) {
        g_archMirror = L"x64";
        g_archApi    = L"x64";
        g_jreFileName = L"OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip";
    } else {
        g_archMirror = L"x32";      // 镜像目录名（不是 x86-32！南大上是 404）
        g_archApi    = L"x86-32";   // Adoptium API 参数名
        // 32 位：实测镜像上 17.0.17 可用（17.0.20.1 没有 32 位构建）
        g_jreFileName = L"OpenJDK17U-jre_x86-32_windows_hotspot_17.0.17_10.zip";
    }

    g_jreVersionPath = std::wstring(L"17/jre/") + g_archMirror + L"/windows/";

    const wchar_t* nativeArch =
            (si.wProcessorArchitecture == PROCESSOR_ARCHITECTURE_AMD64) ? L"x64"
          : (si.wProcessorArchitecture == PROCESSOR_ARCHITECTURE_INTEL) ? L"x86"
          : (si.wProcessorArchitecture == PROCESSOR_ARCHITECTURE_ARM64) ? L"ARM64"
          : L"未知";

    Log::WriteF(L"架构检测: 进程=%s, 系统=%s -> 将使用 %s JRE",
                proc32 ? L"32位" : L"64位", nativeArch,
                systemIs64 ? L"64位" : L"32位");
    Log::WriteF(L"JRE 目录: %s", g_jreVersionPath.c_str());
    Log::WriteF(L"JRE 文件名: %s", g_jreFileName.c_str());
}

// ============================================================================
//  AppPaths::Init
// ============================================================================
// ============================================================================
//  目录复制（用于从旧数据目录迁移）
//
//  用 SHFileOperationW 而不是手写递归：
//    - 能正确处理超长路径、中文路径、隐藏文件
//    - 目标已存在时的合并语义由系统负责
//  代价是要 CoInitialize 并包含 <shellapi.h>。
// ============================================================================
bool Util::CopyDirectory(const std::wstring& from, const std::wstring& to) {
    if (!Util::IsDirectory(from)) {
        return false;
    }

    // SHFileOperationW 的路径必须是**双 null 结尾**的缓冲区
    std::wstring src = from;
    std::wstring dst = to;
    src.push_back(L'\0');
    dst.push_back(L'\0');

    SHFILEOPSTRUCTW op;
    ZeroMemory(&op, sizeof(op));
    op.wFunc = FO_COPY;
    op.pFrom = src.c_str();
    op.pTo = dst.c_str();
    // FOF_NOCONFIRMATION: 不弹"是否覆盖"对话框
    // FOF_SILENT:         不显示进度条（迁移是在后台悄悄做的）
    // FOF_NOERRORUI:      出错不弹窗（失败会走返回值）
    // FOF_NOCONFIRMMKDIR: 不询问是否创建目录
    op.fFlags = FOF_NOCONFIRMATION | FOF_SILENT | FOF_NOERRORUI
              | FOF_NOCONFIRMMKDIR;

    int rc = SHFileOperationW(&op);
    if (rc != 0 || op.fAnyOperationsAborted) {
        Log::WriteF(L"CopyDirectory 失败: rc=%d aborted=%d", rc,
                    op.fAnyOperationsAborted ? 1 : 0);
        return false;
    }
    return Util::IsDirectory(to);
}

// 目录里是否已有启动器 jar（只看顶层：jar 直接放在数据目录下）
//
//  「有 jar」与「有目录」是两回事：NCL_Launcher 可能早就存在，
//  里面却只有 java/ 之类的缓存，这种情况仍需要从旧目录迁移。
static bool HasLauncherJar(const std::wstring& dir) {
    WIN32_FIND_DATAW fd;
    HANDLE h = FindFirstFileW((dir + L"\\mc-launcher-*.jar").c_str(), &fd);
    if (h == INVALID_HANDLE_VALUE) {
        return false;
    }
    FindClose(h);
    return true;
}

bool AppPaths::Init() {
    std::wstring roaming = Util::GetAppDataPath();
    if (roaming.empty()) {
        return false;
    }

    appData = Util::JoinPath(roaming, APP_DIR_NAME);
    javaDir = Util::JoinPath(appData, JAVA_DIR_NAME);
    jreHome.clear();
    jarPath.clear();

    // ---- 从旧目录迁移（启动器改名：MC_Launcher -> NCL_Launcher）----
    //
    // 只在「新目录还没有启动器 jar」时做一次。
    //
    // 早期条件是「新目录不存在」：老玩家升级时 NCL_Launcher 已经存在
    // （里面有 java/ho 缓存等），于是旧的 mc-launcher-*.jar 永远搬不过来，
    // 每次启动都被当成首次运行去重新下载。现在改为检查 jar 是否在，
    // 安全的理由：迁移只复制不删除；新目录里有 jar 时不会触发。
    {
        std::wstring oldDir = Util::JoinPath(roaming, OLD_APP_DIR_NAME);
        if (!HasLauncherJar(appData) && Util::IsDirectory(oldDir)) {
            Log::WriteF(L"检测到旧数据目录，开始迁移: %s -> %s",
                        oldDir.c_str(), appData.c_str());

            if (Util::CopyDirectory(oldDir, appData)) {
                Log::WriteF(L"旧数据迁移完成（旧目录保留未删除）");
            } else {
                Log::Write(L"旧数据迁移失败，将使用全新目录");
            }
        }
    }

    if (!Util::CreateDirectories(appData)) {
        return false;
    }
    if (!Util::CreateDirectories(javaDir)) {
        return false;
    }
    return true;
}

// ============================================================================
//  错误码文案
// ============================================================================
const wchar_t* StepResultText(StepResult r) {
    switch (r) {
        case StepResult::Ok:             return L"成功";
        case StepResult::NetworkError:   return L"网络连接失败（请检查网络或代理设置）";
        case StepResult::HttpError:      return L"服务器返回了错误";
        case StepResult::FileError:      return L"文件读写失败";
        case StepResult::PermissionError:return L"权限不足（请尝试以管理员身份运行）";
        case StepResult::ParseError:     return L"服务器响应格式不正确";
        case StepResult::ExtractError:   return L"解压失败";
        case StepResult::JavaNotFound:   return L"未能找到可用的 Java 17";
        case StepResult::LaunchFailed:   return L"启动主程序失败";
        case StepResult::Cancelled:      return L"用户取消";
        case StepResult::ChecksumMismatch:  return L"启动器文件校验失败（文件可能已损坏或被篡改）";
        case StepResult::UntrustedJar:      return L"无法确认启动器文件的来源";
    }
    return L"未知错误";
}

// ============================================================================
//  日志
// ============================================================================
namespace {
    std::wstring g_logPath;
    CRITICAL_SECTION g_logCs;
    bool g_logCsReady = false;

    void EnsureLogCs() {
        if (!g_logCsReady) {
            InitializeCriticalSection(&g_logCs);
            g_logCsReady = true;
        }
    }
}

namespace Log {

void Init(const AppPaths& paths) {
    EnsureLogCs();
    g_logPath = Util::JoinPath(paths.appData, L"bootstrap.log");

    // ---- 日志轮转 ----
    //
    // 早期日志只追加、永不清理。而 WriteF 会记录完整 URL 和接口响应，
    // 长期使用（一年以上）日志能涨到几百 MB，占用户的磁盘。
    //
    // 策略：启动时若发现日志超过 MAX_LOG_BYTES，把它改名为 .old
    // （覆盖上一次的 .old），然后从头写。这样最多保留两份日志，
    // 既能追溯本次问题，也留了一份上次的作对照。
    try {
        uint64_t size = Util::FileSize(g_logPath);
        if (size > MAX_LOG_BYTES) {
            std::wstring oldPath = g_logPath + L".old";
            // 旧文件可能被占用，删不掉也不影响后续 Rename
            Util::DeleteFileQuiet(oldPath);

            if (MoveFileExW(g_logPath.c_str(), oldPath.c_str(),
                            MOVEFILE_REPLACE_EXISTING)) {
                // 轮转成功：写一条新日志说明一下，方便排查时知道发生过轮转
                // （此时 g_logPath 尚未创建，下面这次 Write 会新建文件）
                Write(L"日志已轮转（上一份重命名为 bootstrap.log.old）");
            } else {
                // 轮转失败（文件被占用等）：继续追加，不阻断启动
                DWORD err = GetLastError();
                // 此时只能用 OutputDebugString，因为日志本身写不了
                (void)err;
            }
        }
    } catch (...) {
        // 轮转失败绝不该影响启动
    }
}

void Write(const std::wstring& msg) {
    if (g_logPath.empty()) {
        return;
    }
    EnsureLogCs();
    EnterCriticalSection(&g_logCs);

    // 用 Win32 API 写文件，并手动把宽字符转成 UTF-8。
    //
    // 不能用 _wfopen(path, L"a, ccs=UTF-8")：MinGW 的 C 运行库不支持
    // ccs= 修饰符，会退化成按本地 ANSI 编码写，日志变成乱码。
    {
        // 组装一行："[时间] 消息\r\n"
        SYSTEMTIME st;
        GetLocalTime(&st);

        wchar_t line[2200];
        // 时间前缀单独拼，正文只放进剩余空间：以前把整条消息（可能含服务端返回的
        // 超长字符串）一直往格式串里塞，_TRUNCATE 会从中间截断，结果连日期都丢了
        wchar_t stamp[64];
        _snwprintf_s(stamp, _countof(stamp), _TRUNCATE,
                     L"[%04d-%02d-%02d %02d:%02d:%02d] ",
                     st.wYear, st.wMonth, st.wDay,
                     st.wHour, st.wMinute, st.wSecond);
        _snwprintf_s(line, _countof(line), _TRUNCATE, L"%s%s\r\n", stamp, msg.c_str());

        // UTF-16 -> UTF-8
        int u8len = WideCharToMultiByte(CP_UTF8, 0, line, -1,
                                        nullptr, 0, nullptr, nullptr);
        if (u8len > 1) {
            // 缓冲要留出结尾 '\0' 的位置（u8len 是含终止符的长度）：
            // 以前按 u8len-1 分配却按 u8len 写入，最后一个字节越界
            std::string u8(static_cast<size_t>(u8len), '\0');
            WideCharToMultiByte(CP_UTF8, 0, line, -1,
                                u8.data(), u8len, nullptr, nullptr);
            u8.resize(static_cast<size_t>(u8len - 1));   // 去掉结尾 \0

            // 追加写入
            HANDLE h = CreateFileW(g_logPath.c_str(),
                                   FILE_APPEND_DATA,
                                   FILE_SHARE_READ,      // 允许其它程序读取
                                   nullptr,
                                   OPEN_ALWAYS,
                                   FILE_ATTRIBUTE_NORMAL,
                                   nullptr);
            if (h != INVALID_HANDLE_VALUE) {
                // 首次创建时写 UTF-8 BOM，方便记事本正确识别编码
                if (GetFileSize(h, nullptr) == 0) {
                    const unsigned char bom[3] = {0xEF, 0xBB, 0xBF};
                    DWORD bw = 0;
                    WriteFile(h, bom, sizeof(bom), &bw, nullptr);
                }
                DWORD written = 0;
                WriteFile(h, u8.data(), static_cast<DWORD>(u8.size()),
                          &written, nullptr);
                CloseHandle(h);
            }
        }
    }

    LeaveCriticalSection(&g_logCs);
}

void WriteF(const wchar_t* fmt, ...) {
    wchar_t buf[2048];
    va_list args;
    va_start(args, fmt);
    _vsnwprintf_s(buf, _countof(buf), _TRUNCATE, fmt, args);
    va_end(args);
    Write(buf);
}

// 格式化宽/窄字符串混用的日志
//
//  _vsnwprintf_s 只认宽字符格式符（%s = wchar_t*），不认 %S（NARROW
//  转换）—— 在 MSVC 的宽字符 printf 里 %S 不是标准写法，会按 %s 处理，
//  把 char* 当 wchar_t* 解引用，输出乱码（曾把版本号打成 "1"，
//  导致「jar 哈希: 1 = …」这类日志完全读不了）。
//  哈希、版本号都是 std::string，调用方不想每次手动转宽字符，
//  所以这里先按原格式写到窄字符缓冲，再统一转成宽字符。
void WriteUtf8(const char* fmt, ...) {
    char buf[2048];
    va_list args;
    va_start(args, fmt);
    _vsnprintf_s(buf, _countof(buf), _TRUNCATE, fmt, args);
    va_end(args);
    Write(Util::Utf8ToWide(buf));
}

} // namespace Log

// ============================================================================
//  SHA-256（Windows BCrypt）
// ============================================================================
//
//  用系统自带 BCrypt，不引第三方库：
//    - 兼容 Win7+，MinGW 链接 -lbcrypt，MSVC 链接 bcrypt.lib
//    - 分块读取，45 MB 的 jar 只占 1 MB 内存
namespace Hs {

namespace {
constexpr uint32_t kSha256Len = 32;
constexpr size_t kChunk = 1 << 20;   // 1 MB

void ToHex(const unsigned char* in, uint32_t len, std::string& out) {
    static const char* digits = "0123456789abcdef";
    out.clear();
    out.reserve(static_cast<size_t>(len) * 2);
    for (uint32_t i = 0; i < len; ++i) {
        out.push_back(digits[in[i] >> 4]);
        out.push_back(digits[in[i] & 0x0F]);
    }
}

bool IsHex64(const std::string& s) {
    if (s.size() != 64) {
        return false;
    }
    for (char c : s) {
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
            return false;
        }
    }
    return true;
}



} // namespace

std::string NormalizeHash(std::string hex) {
    for (char& c : hex) {
        if (c >= 'A' && c <= 'F') {
            c = static_cast<char>(c - 'A' + 'a');
        }
    }
    return hex;
}

std::string ExtractHash(const std::string& text) {
    // 文件里可能只有哈希，也可能带文件名（sha256sum 输出格式）
    for (size_t i = 0; i + 64 <= text.size(); ++i) {
        std::string cand = text.substr(i, 64);
        if (IsHex64(cand) && (i == 0 || !isalnum(static_cast<unsigned char>(text[i - 1])))) {
            return Hs::NormalizeHash(cand);
        }
    }
    return std::string();
}

std::wstring ShortHash(const std::string& hex) {
    if (hex.size() != 64) {
        return Util::Utf8ToWide(hex);
    }
    std::string s = hex.substr(0, 8) + "..." + hex.substr(56);
    return Util::Utf8ToWide(s);
}

bool EqualsIgnoreCase(const std::string& a, const std::string& b) {
    if (!IsHex64(a) || !IsHex64(b)) {
        return false;
    }
    return Hs::NormalizeHash(a) == Hs::NormalizeHash(b);
}

bool Sha256File(const std::wstring& path,
                std::string& outHex,
                volatile bool* cancelFlag,
                const std::function<void(uint64_t, uint64_t)>& onProgress) {
    outHex.clear();

    HANDLE f = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                           nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) {
        Log::WriteF(L"SHA256: 打不开文件 %s (err=%lu)", path.c_str(), GetLastError());
        return false;
    }

    BCRYPT_ALG_HANDLE alg = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    bool ok = false;
    std::unique_ptr<unsigned char[]> buf(new (std::nothrow) unsigned char[kChunk]);

    do {
        if (!buf) {
            break;
        }
        if (!BCRYPT_SUCCESS(BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM,
                                                        nullptr, 0))) {
            Log::Write(L"SHA256: 系统不支持 SHA-256（BCryptOpenAlgorithmProvider 失败）");
            break;
        }
        if (!BCRYPT_SUCCESS(BCryptCreateHash(alg, &hash, nullptr, 0, nullptr, 0, 0))) {
            Log::Write(L"SHA256: BCryptCreateHash 失败");
            break;
        }

        const uint64_t total = Util::FileSize(path);
        uint64_t done = 0;
        bool ioError = false;
        bool cancelled = false;
        while (true) {
            if (cancelFlag && *cancelFlag) {
                cancelled = true;
                break;
            }
            DWORD read = 0;
            if (!ReadFile(f, buf.get(), static_cast<DWORD>(kChunk), &read, nullptr)) {
                Log::WriteF(L"SHA256: 读取失败 (err=%lu)", GetLastError());
                ioError = true;
                break;
            }
            if (read == 0) {
                break;
            }
            if (!BCRYPT_SUCCESS(BCryptHashData(hash, buf.get(), read, 0))) {
                Log::Write(L"SHA256: BCryptHashData 失败");
                ioError = true;
                break;
            }
            done += read;
            if (onProgress) {
                onProgress(done, total);
            }
        }
        if (cancelled) {
            Log::Write(L"SHA256: 校验被取消");
            break;
        }
        if (ioError) {
            break;
        }

        unsigned char digest[kSha256Len];
        if (!BCRYPT_SUCCESS(BCryptFinishHash(hash, digest, kSha256Len, 0))) {
            Log::Write(L"SHA256: BCryptFinishHash 失败");
            break;
        }
        ToHex(digest, kSha256Len, outHex);
        ok = true;
    } while (false);

    if (hash) {
        BCryptDestroyHash(hash);
    }
    if (alg) {
        BCryptCloseAlgorithmProvider(alg, 0);
    }
    CloseHandle(f);
    return ok;
}

} // namespace Hs
