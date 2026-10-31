#include "common.h"

#include <shlwapi.h>
#include <shellapi.h>
#include <shlobj.h>      
#include <cstdarg>
#include <cstdio>
#include <atomic>
#include <cctype>
#include <new>
#include <bcrypt.h>

namespace Util {

std::wstring GetAppDataPath() {

    

    DWORD tn = GetEnvironmentVariableW(L"MCLAUNCHER_TEST_APPDATA", nullptr, 0);
    if (tn > 0) {
        std::wstring tv(tn, L'\0');
        DWORD tgot = GetEnvironmentVariableW(L"MCLAUNCHER_TEST_APPDATA", tv.data(), tn);
        if (tgot > 0 && tgot < tn) {
            tv.resize(tgot);
            return tv;
        }
    }

    wchar_t buf[MAX_PATH] = {0};
    if (SUCCEEDED(SHGetFolderPathW(nullptr, CSIDL_APPDATA, nullptr, 0, buf))) {
        if (buf[0] != L'\0') {
            return std::wstring(buf);
        }
    }

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

bool CreateDirectories(const std::wstring& path) {
    if (path.empty()) {
        return false;
    }
    if (IsDirectory(path)) {
        return true;
    }

    std::wstring cur;
    for (size_t i = 0; i < path.size(); ++i) {
        cur.push_back(path[i]);
        bool atSep = (path[i] == L'\\' || path[i] == L'/');

        bool driveOnly = (cur.size() == 2 && cur[1] == L':');

        if ((atSep || i == path.size() - 1) && !driveOnly && cur.size() > 1) {

            std::wstring toCreate = cur;
            while (!toCreate.empty() &&
                   (toCreate.back() == L'\\' || toCreate.back() == L'/')) {
                toCreate.pop_back();
            }
            if (!toCreate.empty() && !IsDirectory(toCreate)) {
                if (!CreateDirectoryW(toCreate.c_str(), nullptr)) {
                    DWORD err = GetLastError();

                    if (err != ERROR_ALREADY_EXISTS) {
                        return false;
                    }
                }
            }
        }
    }
    return IsDirectory(path);
}

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

std::wstring JoinPath(const std::wstring& a, const std::wstring& b) {
    if (a.empty()) return b;
    if (b.empty()) return a;

    std::wstring r = a;
    wchar_t last = r.back();
    if (last != L'\\' && last != L'/') {
        r.push_back(L'\\');
    }

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

bool DeleteFileQuiet(const std::wstring& path) {
    if (path.empty()) return true;
    if (!PathExists(path)) return true;

    SetFileAttributesW(path.c_str(), FILE_ATTRIBUTE_NORMAL);
    if (DeleteFileW(path.c_str()) != FALSE) {
        return true;
    }
    DWORD err = GetLastError();

    if (err == ERROR_SHARING_VIOLATION || err == ERROR_ACCESS_DENIED) {
        MoveFileExW(path.c_str(), nullptr, MOVEFILE_DELAY_UNTIL_REBOOT);
        return true;
    }

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

            if (_wcsicmp(name.c_str(), fileName.c_str()) == 0) {
                FindClose(h);
                return full;
            }
        }
    } while (FindNextFileW(h, &fd));

    FindClose(h);

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

uint64_t NextCounter() {
    static std::atomic<uint64_t> counter{0};
    return ++counter;
}

bool EndsWithNoCase(const std::wstring& s, const std::wstring& suffix) {
    if (suffix.size() > s.size()) return false;
    size_t off = s.size() - suffix.size();
    return _wcsicmp(s.c_str() + off, suffix.c_str()) == 0;
}

bool HasFreeSpace(const std::wstring& path, uint64_t required, uint64_t& outFree) {
    outFree = 0;

    std::wstring probe = path;
    while (!probe.empty() && !IsDirectory(probe)) {
        size_t sep = probe.find_last_of(L"\\/");
        if (sep == std::wstring::npos) {
            break;
        }
        probe = probe.substr(0, sep);
    }
    if (probe.empty()) {
        probe = L".";   
    }

    ULARGE_INTEGER freeBytesAvailable{};
    ULARGE_INTEGER totalBytes{};
    ULARGE_INTEGER totalFreeBytes{};

    if (!GetDiskFreeSpaceExW(probe.c_str(),
                             &freeBytesAvailable, &totalBytes, &totalFreeBytes)) {

        
        return true;
    }

    outFree = totalFreeBytes.QuadPart;
    return outFree >= required;
}

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

bool ReadTextFileUtf8(const std::wstring& path, std::string& out) {
    out.clear();

    

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

} 

std::wstring g_archMirror = L"x64";
std::wstring g_archApi    = L"x64";
std::wstring g_jreFileName;
std::wstring g_jreVersionPath;

void InitArch() {

    

    
    SYSTEM_INFO si;
    ZeroMemory(&si, sizeof(si));
    GetNativeSystemInfo(&si);

    bool systemIs64 = (si.wProcessorArchitecture == PROCESSOR_ARCHITECTURE_AMD64
                    || si.wProcessorArchitecture == PROCESSOR_ARCHITECTURE_ARM64);

    bool proc32 = (sizeof(void*) == 4);

    if (systemIs64) {
        g_archMirror = L"x64";
        g_archApi    = L"x64";
        g_jreFileName = L"OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip";
    } else {
        g_archMirror = L"x32";      
        g_archApi    = L"x86-32";   

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

bool Util::CopyDirectory(const std::wstring& from, const std::wstring& to) {
    if (!Util::IsDirectory(from)) {
        return false;
    }

    std::wstring src = from;
    std::wstring dst = to;
    src.push_back(L'\0');
    dst.push_back(L'\0');

    SHFILEOPSTRUCTW op;
    ZeroMemory(&op, sizeof(op));
    op.wFunc = FO_COPY;
    op.pFrom = src.c_str();
    op.pTo = dst.c_str();

    

    
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

    

    

    

    
    try {
        uint64_t size = Util::FileSize(g_logPath);
        if (size > MAX_LOG_BYTES) {
            std::wstring oldPath = g_logPath + L".old";

            Util::DeleteFileQuiet(oldPath);

            if (MoveFileExW(g_logPath.c_str(), oldPath.c_str(),
                            MOVEFILE_REPLACE_EXISTING)) {

                
                Write(L"日志已轮转（上一份重命名为 bootstrap.log.old）");
            } else {

                DWORD err = GetLastError();

                (void)err;
            }
        }
    } catch (...) {

    }
}

void Write(const std::wstring& msg) {
    if (g_logPath.empty()) {
        return;
    }
    EnsureLogCs();
    EnterCriticalSection(&g_logCs);

    

    
    {

        SYSTEMTIME st;
        GetLocalTime(&st);

        wchar_t line[2200];
        _snwprintf_s(line, _countof(line), _TRUNCATE,
                     L"[%04d-%02d-%02d %02d:%02d:%02d] %s\r\n",
                     st.wYear, st.wMonth, st.wDay,
                     st.wHour, st.wMinute, st.wSecond,
                     msg.c_str());

        int u8len = WideCharToMultiByte(CP_UTF8, 0, line, -1,
                                        nullptr, 0, nullptr, nullptr);
        if (u8len > 1) {
            std::string u8(static_cast<size_t>(u8len - 1), '\0');   
            WideCharToMultiByte(CP_UTF8, 0, line, -1,
                                u8.data(), u8len, nullptr, nullptr);

            HANDLE h = CreateFileW(g_logPath.c_str(),
                                   FILE_APPEND_DATA,
                                   FILE_SHARE_READ,      
                                   nullptr,
                                   OPEN_ALWAYS,
                                   FILE_ATTRIBUTE_NORMAL,
                                   nullptr);
            if (h != INVALID_HANDLE_VALUE) {

                if (GetFileSize(h, nullptr) == 0) {
                    const unsigned char bom[3] = {0xEF, 0xBB, 0xBF};
                    DWORD written = 0;
                    WriteFile(h, bom, 3, &written, nullptr);
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

void WriteUtf8(const char* fmt, ...) {
    char buf[2048];
    va_list args;
    va_start(args, fmt);
    _vsnprintf_s(buf, _countof(buf), _TRUNCATE, fmt, args);
    va_end(args);
    Write(Util::Utf8ToWide(buf));
}

} 

namespace Hs {

namespace {
constexpr uint32_t kSha256Len = 32;
constexpr size_t kChunk = 1 << 20;   

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

} 

std::string NormalizeHash(std::string hex) {
    for (char& c : hex) {
        if (c >= 'A' && c <= 'F') {
            c = static_cast<char>(c - 'A' + 'a');
        }
    }
    return hex;
}

std::string ExtractHash(const std::string& text) {

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

} 
