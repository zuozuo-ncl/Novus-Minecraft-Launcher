// ============================================================================
//  java.cpp —— Java 17 的检测与自动安装
//
//  检测顺序（从快到慢、从准到糙）：
//    1) 引导器自己下载的 %APPDATA%\MC_Launcher\java
//    2) JAVA_HOME 环境变量
//    3) 注册表（JavaSoft\Java Runtime Environment / Java Development Kit）
//    4) PATH 中的 java.exe
//
//  每一处候选都会实际执行 `javaw -version` 校验主版本号，避免误判。
// ============================================================================
#include "common.h"

#include <shlwapi.h>
#include <cstdio>
#include <regex>


namespace {

// ---------------------------------------------------------------------------
// 执行 `javaw.exe -version` 并解析主版本号
//
// 注意：java -version 把版本信息输出到 **stderr**。
// 这里用管道捕获 stderr 并解析形如 "17.0.20" / "1.8.0_292" 的版本号。
// ---------------------------------------------------------------------------
bool QueryJavaMajorVersion(const std::wstring& javawPath, int& outMajor) {
    outMajor = 0;

    if (!Util::IsFile(javawPath)) {
        return false;
    }

    // 用 -version 需要控制台；javaw 无控制台，因此改用同目录的 java.exe
    std::wstring dir = javawPath.substr(0, javawPath.find_last_of(L"\\/"));
    std::wstring javaExe = Util::JoinPath(dir, L"java.exe");
    if (!Util::IsFile(javaExe)) {
        javaExe = javawPath;    // 退回 javaw（某些发行版只有它）
    }

    SECURITY_ATTRIBUTES sa;
    ZeroMemory(&sa, sizeof(sa));
    sa.nLength = sizeof(sa);
    sa.bInheritHandle = TRUE;

    HANDLE hRead = nullptr, hWrite = nullptr;
    if (!CreatePipe(&hRead, &hWrite, &sa, 0)) {
        return false;
    }
    SetHandleInformation(hRead, HANDLE_FLAG_INHERIT, 0);

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    ZeroMemory(&pi, sizeof(pi));
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_HIDE;
    si.hStdOutput = hWrite;
    si.hStdError  = hWrite;      // 关键：版本信息在 stderr
    si.hStdInput  = nullptr;

    std::wstring cmd = L"\"" + javaExe + L"\" -version";

    BOOL started = CreateProcessW(nullptr,
                                  cmd.data(),
                                  nullptr, nullptr,
                                  TRUE,                 // 继承句柄
                                  CREATE_NO_WINDOW,
                                  nullptr, nullptr,
                                  &si, &pi);
    // 父进程不再需要写端，否则 ReadFile 永不返回
    CloseHandle(hWrite);

    if (!started) {
        CloseHandle(hRead);
        return false;
    }

    std::string output;
    char buf[512];
    DWORD read = 0;
    while (ReadFile(hRead, buf, sizeof(buf) - 1, &read, nullptr) && read > 0) {
        buf[read] = '\0';
        output.append(buf, read);
    }
    CloseHandle(hRead);

    WaitForSingleObject(pi.hProcess, 10000);
    DWORD code = 0;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);

    if (output.empty()) {
        return false;
    }

    // 解析版本号
    //   "17.0.20" -> 17
    //   "1.8.0_292" -> 8
    //   也兼容 "openjdk version \"17.0.20\" ..."
    std::regex re("\"?([0-9]+)(?:\\.([0-9]+))?");
    std::smatch m;
    if (std::regex_search(output, m, re)) {
        int first = std::atoi(m[1].str().c_str());
        if (first == 1 && m[2].matched) {
            // 1.x 形式：1.8 -> 8
            outMajor = std::atoi(m[2].str().c_str());
        } else {
            outMajor = first;
        }
        return outMajor > 0;
    }

    return false;
}

// ---------------------------------------------------------------------------
// 在注册表中枚举已安装的 Java
// ---------------------------------------------------------------------------
bool FindJavaInRegistry(std::wstring& outJavaw) {
    struct KeySpec {
        HKEY root;
        const wchar_t* sub;
        const wchar_t* valueName;
    };

    const KeySpec specs[] = {
        { HKEY_LOCAL_MACHINE, L"SOFTWARE\\JavaSoft\\Java Runtime Environment", L"JavaHome" },
        { HKEY_LOCAL_MACHINE, L"SOFTWARE\\JavaSoft\\Java Development Kit",     L"JavaHome" },
        { HKEY_LOCAL_MACHINE, L"SOFTWARE\\JavaSoft\\JRE",                      L"JavaHome" },
        { HKEY_LOCAL_MACHINE, L"SOFTWARE\\JavaSoft\\JDK",                      L"JavaHome" },
        // 32 位程序装在 64 位系统上的情形
        { HKEY_LOCAL_MACHINE, L"SOFTWARE\\WOW6432Node\\JavaSoft\\Java Runtime Environment", L"JavaHome" },
        { HKEY_LOCAL_MACHINE, L"SOFTWARE\\WOW6432Node\\JavaSoft\\Java Development Kit",     L"JavaHome" },
    };

    for (const auto& spec : specs) {
        HKEY hKey = nullptr;
        if (RegOpenKeyExW(spec.root, spec.sub, 0, KEY_READ, &hKey) != ERROR_SUCCESS) {
            continue;
        }

        // 该键下有一系列版本子键，逐个枚举
        wchar_t subName[256];
        DWORD index = 0;
        DWORD nameLen = _countof(subName);

        while (RegEnumKeyExW(hKey, index, subName, &nameLen,
                             nullptr, nullptr, nullptr, nullptr) == ERROR_SUCCESS) {
            HKEY hVer = nullptr;
            if (RegOpenKeyExW(hKey, subName, 0, KEY_READ, &hVer) == ERROR_SUCCESS) {
                wchar_t home[MAX_PATH] = {0};
                DWORD homeLen = sizeof(home) - sizeof(wchar_t);
                DWORD type = 0;

                if (RegQueryValueExW(hVer, spec.valueName, nullptr, &type,
                                     reinterpret_cast<LPBYTE>(home),
                                     &homeLen) == ERROR_SUCCESS
                    && (type == REG_SZ || type == REG_EXPAND_SZ)) {
                    std::wstring javaw = Util::JoinPath(home, L"bin\\javaw.exe");
                    if (Util::IsFile(javaw)) {
                        int major = 0;
                        if (QueryJavaMajorVersion(javaw, major) && major >= 17
                                && Java::IsJavaCompatible(javaw)) {
                            Log::WriteF(L"注册表命中 Java %d: %s", major, javaw.c_str());
                            outJavaw = javaw;
                            RegCloseKey(hVer);
                            RegCloseKey(hKey);
                            return true;
                        }
                    }
                }
                RegCloseKey(hVer);
            }
            ++index;
            nameLen = _countof(subName);
        }
        RegCloseKey(hKey);
    }

    return false;
}

// ---------------------------------------------------------------------------
// 在 PATH 中查找 java
// ---------------------------------------------------------------------------
bool FindJavaInPath(std::wstring& outJavaw) {
    wchar_t found[MAX_PATH] = {0};
    // 找 javaw.exe；找不到再找 java.exe
    DWORD n = SearchPathW(nullptr, L"javaw.exe", nullptr,
                          _countof(found), found, nullptr);
    if (n == 0) {
        n = SearchPathW(nullptr, L"java.exe", nullptr,
                        _countof(found), found, nullptr);
    }
    if (n == 0 || n >= _countof(found)) {
        return false;
    }

    int major = 0;
    if (QueryJavaMajorVersion(found, major) && major >= 17
            && Java::IsJavaCompatible(found)) {
        Log::WriteF(L"PATH 命中 Java %d: %s", major, found);
        outJavaw = found;
        return true;
    }
    return false;
}

// ---------------------------------------------------------------------------
// 从 Adoptium 的 JSON 中抽出 zip 下载地址
//
// 响应形如：
//   [{ "binary": { "package": { "link": "https://...zip", "name": "...", "size": N }}}]
//
// 这里不引 JSON 库，直接找 "link" 字段 —— 对固定格式足够稳健。
// ---------------------------------------------------------------------------
bool ExtractAdoptiumZipUrl(const std::wstring& json,
                           std::wstring& outUrl,
                           uint64_t& outSize) {
    outUrl.clear();
    outSize = 0;

    // 定位 "link":"..."
    const std::wstring key = L"\"link\"";
    size_t kpos = json.find(key);
    if (kpos == std::wstring::npos) {
        return false;
    }
    size_t colon = json.find(L':', kpos + key.size());
    if (colon == std::wstring::npos) {
        return false;
    }
    size_t q1 = json.find(L'"', colon);
    if (q1 == std::wstring::npos) {
        return false;
    }
    size_t q2 = json.find(L'"', q1 + 1);
    if (q2 == std::wstring::npos) {
        return false;
    }

    outUrl = json.substr(q1 + 1, q2 - q1 - 1);

    // 反转义 \" 之类（URL 里基本不会出现，稳妥起见处理一下）
    for (size_t i = 0; i + 1 < outUrl.size();) {
        if (outUrl[i] == L'\\' && outUrl[i + 1] == L'/') {
            outUrl.erase(i, 1);
        } else {
            ++i;
        }
    }

    // 顺带解析 size（用于进度显示）
    const std::wstring sizeKey = L"\"size\"";
    size_t spos = json.find(sizeKey);
    if (spos != std::wstring::npos) {
        size_t sc = json.find(L':', spos + sizeKey.size());
        if (sc != std::wstring::npos) {
            size_t begin = sc + 1;
            while (begin < json.size() && (json[begin] == L' ' || json[begin] == L'\t')) {
                ++begin;
            }
            size_t end = begin;
            while (end < json.size() && iswdigit(json[end])) {
                ++end;
            }
            if (end > begin) {
                outSize = _wcstoui64(json.substr(begin, end - begin).c_str(),
                                     nullptr, 10);
            }
        }
    }

    return !outUrl.empty();
}

} // namespace

// ============================================================================
//  Java 命名空间实现
// ============================================================================
namespace Java {

// ---------------------------------------------------------------------------
// 校验某个 javaw.exe 是否 >= 17
// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// 读取 PE 文件头判断 exe 的架构
//
//  返回 0x14C (x86) / 0x8664 (x64) / 0xAA64 (ARM64)，失败返回 0。
//
//  为什么不用执行 java.exe 来判断：在位数不兼容的系统上它**根本起不来**，
//  拿不到任何输出。直接读 PE 头是唯一可靠的方式。
// ---------------------------------------------------------------------------
static WORD ReadPeMachine(const std::wstring& exePath) {
    HANDLE h = CreateFileW(exePath.c_str(), GENERIC_READ,
                           FILE_SHARE_READ | FILE_SHARE_WRITE,
                           nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        return 0;
    }

    // ---- DOS 头：e_lfanew 在偏移 0x3C ----
    DWORD read = 0;
    BYTE dosHeader[64];
    if (!ReadFile(h, dosHeader, sizeof(dosHeader), &read, nullptr)
            || read < 64
            || dosHeader[0] != 'M' || dosHeader[1] != 'Z') {
        CloseHandle(h);
        return 0;
    }

    LONG e_lfanew = *reinterpret_cast<LONG*>(dosHeader + 0x3C);
    if (e_lfanew <= 0 || e_lfanew > (64 * 1024 * 1024)) {
        CloseHandle(h);
        return 0;   // 异常的偏移，拒绝
    }

    // ---- PE 签名 + Machine 字段 ----
    if (SetFilePointer(h, e_lfanew, nullptr, FILE_BEGIN) == INVALID_SET_FILE_POINTER) {
        CloseHandle(h);
        return 0;
    }

    BYTE peHeader[6];
    if (!ReadFile(h, peHeader, sizeof(peHeader), &read, nullptr) || read < 6) {
        CloseHandle(h);
        return 0;
    }
    CloseHandle(h);

    if (peHeader[0] != 'P' || peHeader[1] != 'E') {
        return 0;
    }

    WORD machine = *reinterpret_cast<WORD*>(peHeader + 4);
    return machine;
}

// ---------------------------------------------------------------------------
// 该 javaw 能否在当前系统上运行
// ---------------------------------------------------------------------------
bool IsJavaCompatible(const std::wstring& javawPath) {
    if (javawPath.empty()) {
        return false;
    }

    // 同目录的 java.exe 才是真正的可执行体（javaw 只是无控制台版本）
    std::wstring dir = javawPath.substr(0, javawPath.find_last_of(L"\\/"));
    std::wstring javaExe = Util::JoinPath(dir, L"java.exe");
    if (!Util::IsFile(javaExe)) {
        javaExe = javawPath;
    }

    WORD machine = ReadPeMachine(javaExe);
    if (machine == 0) {
        // 读不到 PE 头：可能是脚本包装或权限问题。
        // 保守放行 —— 让后面真正执行时的错误来暴露问题。
        Log::WriteF(L"无法读取 PE 头，跳过架构校验: %s", javaExe.c_str());
        return true;
    }

    // 当前系统是否为 64 位（用 GetNativeSystemInfo，WoW64 下也准确）
    SYSTEM_INFO si;
    ZeroMemory(&si, sizeof(si));
    GetNativeSystemInfo(&si);
    bool systemIs64 = (si.wProcessorArchitecture == PROCESSOR_ARCHITECTURE_AMD64
                    || si.wProcessorArchitecture == PROCESSOR_ARCHITECTURE_ARM64);

    if (machine == IMAGE_FILE_MACHINE_I386) {
        return true;    // 32 位 JVM 在任何系统上都能跑（64 位系统走 WoW64）
    }
    if (machine == IMAGE_FILE_MACHINE_AMD64
            || machine == IMAGE_FILE_MACHINE_ARM64) {
        if (!systemIs64) {
            Log::WriteF(L"拒绝：%s 是 64 位 JVM，但当前是 32 位系统",
                        javaExe.c_str());
            return false;
        }
        return true;
    }

    // 其它架构（理论上的 ARM32 等）：不拦，交给实际执行去判定
    Log::WriteF(L"未知的 PE 架构 0x%04X，跳过校验", machine);
    return true;
}

bool IsAtLeastJava17(const std::wstring& javawPath) {
    int major = 0;
    return QueryJavaMajorVersion(javawPath, major) && major >= 17;
}

// ---------------------------------------------------------------------------
// 探测系统中可用的 Java 17
// ---------------------------------------------------------------------------
std::wstring FindJava17(const AppPaths& paths) {
    // 1) 引导器自己安装的
    if (Util::IsDirectory(paths.javaDir)) {
        std::wstring javaw = Util::FindFileRecursive(paths.javaDir, L"javaw.exe", 4);
        if (!javaw.empty() && IsAtLeastJava17(javaw) && Java::IsJavaCompatible(javaw)) {
            Log::WriteF(L"使用引导器安装的 Java: %s", javaw.c_str());
            return javaw;
        }
    }

    // 2) JAVA_HOME
    {
        DWORD n = GetEnvironmentVariableW(L"JAVA_HOME", nullptr, 0);
        if (n > 0) {
            std::wstring home(n, L'\0');
            DWORD got = GetEnvironmentVariableW(L"JAVA_HOME", home.data(), n);
            if (got > 0 && got < n) {
                home.resize(got);
                std::wstring javaw = Util::JoinPath(home, L"bin\\javaw.exe");
                if (Util::IsFile(javaw) && IsAtLeastJava17(javaw)) {
                    Log::WriteF(L"JAVA_HOME 命中 Java: %s", javaw.c_str());
                    return javaw;
                }
            }
        }
    }

    // 3) 注册表
    {
        std::wstring javaw;
        if (FindJavaInRegistry(javaw)) {
            return javaw;
        }
    }

    // 4) PATH
    {
        std::wstring javaw;
        if (FindJavaInPath(javaw)) {
            return javaw;
        }
    }

    Log::Write(L"未找到可用的 Java 17");
    return L"";
}

// ---------------------------------------------------------------------------
// 确保有 Java 17：探测，找不到就下载安装
// ---------------------------------------------------------------------------
StepResult EnsureJava(const AppPaths& paths,
                      ProgressFn progress,
                      volatile bool* cancelFlag,
                      std::wstring& outJavawPath) {
    outJavawPath.clear();

    // ---- 先探测 ----
    if (progress) {
        progress(L"正在检测 Java 环境...", 0, 0);
    }

    std::wstring existing = FindJava17(paths);
    if (!existing.empty()) {
        Log::WriteF(L"检测到已有 Java 17：%s", existing.c_str());
        outJavawPath = existing;
        return StepResult::Ok;
    }

    Log::Write(L"未检测到 Java 17，开始自动下载安装");

    // ---- 下载前检查磁盘空间 ----
    //
    // 早期不检查，玩家磁盘不够时会下到一半才失败，白等几分钟，
    // 而且报错只笼统地说"磁盘空间不足或权限不足"，分不清是哪个。
    {
        uint64_t freeBytes = 0;
        if (!Util::HasFreeSpace(paths.javaDir, MIN_FREE_SPACE_BYTES, freeBytes)) {
            Log::WriteF(L"磁盘空间不足：需要 %s，实际剩余 %s",
                        Util::HumanSize(MIN_FREE_SPACE_BYTES).c_str(),
                        Util::HumanSize(freeBytes).c_str());
            return StepResult::FileError;
        }
        Log::WriteF(L"磁盘空间检查通过：剩余 %s",
                    Util::HumanSize(freeBytes).c_str());
    }

    // ---- 确定下载地址：国内镜像优先，官方兜底 ----
    //
    // 顺序：
    //   1. 三个国内镜像 + 已知文件名（实测最快、最稳）
    //   2. Adoptium API 取最新地址（网络允许时）
    //   3. 官方 GitHub 直链（最后的兜底）
    //
    // 之前的实现直接用 API 返回的地址，而那个地址指向 GitHub，
    // 国内会完全超时 —— 这是「下载失败」的根因。
    std::wstring zipPath = Util::JoinPath(paths.javaDir, L"jre17.zip");

    // 候选地址列表
    std::vector<std::wstring> candidates;

    // 0) Azul Zulu FX（**首选**）
    //
    //    唯一自带 JavaFX 的 32 位构建 —— 启动器界面靠它。
    //    Adoptium / Liberica 的 32 位包都不含 JavaFX，装了界面起不来。
    {
        bool sys64 = (g_archMirror == L"x64");
        std::wstring fn = sys64 ? ZULU_17_X64 : ZULU_17_X86;
        std::wstring zuluUrl = std::wstring(ZULU_CDN) + fn;
        candidates.push_back(zuluUrl);
        Log::WriteF(L"Zulu FX 候选（含 JavaFX）: %s", zuluUrl.c_str());
    }

    // 1) BellSoft Liberica 官方 CDN（备选）
    //
    //    为什么排第一：
    //      - 它是 32 位 Java 21 的唯一来源（Adoptium 没有）
    //      - 实测响应 1.2~4.1 秒，比国内镜像还快
    //      - URL 由版本号直接拼出，不依赖镜像目录结构
    {
        bool sys64 = (g_archMirror == L"x64");
        std::wstring fn = sys64
                ? L"bellsoft-jre17.0.9+11-windows-amd64.zip"
                : L"bellsoft-jre17.0.9+11-windows-i586.zip";
        std::wstring ver = std::wstring(LIBERICA_VER_17);

        std::wstring libUrl = std::wstring(LIBERICA_CDN) + ver + L"/" + fn;
        candidates.push_back(libUrl);
        Log::WriteF(L"Liberica 候选: %s", libUrl.c_str());
    }

    // 2) 国内镜像（Adoptium 结构，架构由 InitArch 决定）
    {
        std::wstring rel = g_jreVersionPath + g_jreFileName;
        candidates.push_back(std::wstring(JRE_MIRROR_PREFIX_1) + rel);
        candidates.push_back(std::wstring(JRE_MIRROR_PREFIX_2) + rel);
        candidates.push_back(std::wstring(JRE_MIRROR_PREFIX_3) + rel);
    }

    // 尝试 Adoptium API 补充一个候选（失败不影响主流程）
    {
        if (progress) {
            progress(L"正在获取下载地址...", 0, 0);
        }

        std::wstring json;
        // API 的 architecture 参数按运行时架构拼接
        std::wstring apiUrl = std::wstring(L"https://api.adoptium.net/v3/assets/latest/17/hotspot")
                + L"?architecture=" + g_archApi
                + L"&image_type=jre&os=windows&vendor=eclipse";
        StepResult apiResult = Http::GetText(apiUrl, json, 8000);
        if (apiResult == StepResult::Ok) {
            std::wstring apiUrl;
            uint64_t apiSize = 0;
            if (ExtractAdoptiumZipUrl(json, apiUrl, apiSize)) {
                Log::WriteF(L"Adoptium API 提供地址: %s", apiUrl.c_str());
                candidates.push_back(apiUrl);
            }
        } else {
            Log::Write(L"Adoptium API 不可达，将只使用国内镜像");
        }
    }

    Log::WriteF(L"共 %d 个候选下载地址", static_cast<int>(candidates.size()));

    // ---- 逐个尝试下载 ----
    StepResult r = StepResult::NetworkError;
    std::wstring usedUrl;

    for (size_t i = 0; i < candidates.size(); ++i) {
        const std::wstring& url = candidates[i];

        if (cancelFlag && *cancelFlag) {
            return StepResult::Cancelled;
        }

        Log::WriteF(L"[%d/%d] 尝试下载: %s",
                    static_cast<int>(i + 1), static_cast<int>(candidates.size()),
                    url.c_str());

        if (progress) {
            progress(L"正在下载 Java 17 运行环境（源 " + std::to_wstring(i + 1)
                     + L"/" + std::to_wstring(candidates.size()) + L"）", 0, 0);
        }

        // 每个源都从头下载；失败时清掉半成品
        Util::DeleteFileQuiet(zipPath);

        r = Http::DownloadFile(url, zipPath,
                               [&](const std::wstring& detail, uint64_t done, uint64_t total) {
                                   if (progress) {
                                       // 透传文件與/速度信息，让用户看清进度
                                       progress(L"正在下载 Java 17 运行环境\n" + detail,
                                                done, total);
                                   }
                               },
                               cancelFlag);

        if (r == StepResult::Ok) {
            usedUrl = url;
            Log::WriteF(L"下载成功，使用源: %s", url.c_str());
            break;
        }

        if (r == StepResult::Cancelled) {
            Util::DeleteFileQuiet(zipPath);
            return StepResult::Cancelled;
        }

        Log::WriteF(L"该源失败（%s），尝试下一个", StepResultText(r));
    }

    if (r != StepResult::Ok) {
        Log::WriteF(L"所有 %d 个源都下载失败",
                    static_cast<int>(candidates.size()));
        Util::DeleteFileQuiet(zipPath);
        return r;
    }

    Log::WriteF(L"JRE 下载完成（来源: %s）", usedUrl.c_str());

    // 二次确认文件确实拿到了（避免留下 0 字节文件）
    if (Util::FileSize(zipPath) < 1024 * 1024) {
        Log::WriteF(L"下载得到的文件异常小（%llu 字节），判定失败",
                    static_cast<unsigned long long>(Util::FileSize(zipPath)));
        Util::DeleteFileQuiet(zipPath);
        return StepResult::NetworkError;
    }

    // ---- 解压 ----
    r = Zip::ExtractTo(zipPath, paths.javaDir,
                       [&](const std::wstring& stage, uint64_t, uint64_t) {
                           if (progress) {
                               progress(stage, 0, 0);
                           }
                       });
    if (r != StepResult::Ok) {
        Log::WriteF(L"JRE 解压失败");
        Util::DeleteFileQuiet(zipPath);
        return r;
    }

    // 解压成功后删掉 zip，省空间
    Util::DeleteFileQuiet(zipPath);

    // ---- 定位 javaw.exe ----
    if (progress) {
        progress(L"正在校验 Java 安装...", 0, 0);
    }

    std::wstring javaw = Util::FindFileRecursive(paths.javaDir, L"javaw.exe", 4);
    if (javaw.empty()) {
        Log::Write(L"解压完成但找不到 javaw.exe");
        return StepResult::ExtractError;
    }

    if (!IsAtLeastJava17(javaw)) {
        Log::WriteF(L"安装的 Java 版本低于 17: %s", javaw.c_str());
        return StepResult::JavaNotFound;
    }

    Log::WriteF(L"Java 17 安装完成: %s", javaw.c_str());
    outJavawPath = javaw;
    return StepResult::Ok;
}

} // namespace Java