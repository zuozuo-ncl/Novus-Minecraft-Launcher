// ============================================================================
//  launcher.cpp —— 主程序（jar）的获取与启动
//
//  流程：
//    1. GET 版本 API 拿最新版本号
//    2. 拼出 jar 下载地址，检查本地是否已有
//    3. 缺失则下载
//    4. 用 javaw -jar <jar> 静默拉起，父进程退出
// ============================================================================
#include "common.h"

#include <shellapi.h>
#include <algorithm>
#include <vector>

namespace {

// ---------------------------------------------------------------------------
// 从 {"latest":"1.1.0","versions":[...]} 中抽出 latest 的值
// ---------------------------------------------------------------------------
bool ExtractJsonString(const std::wstring& json,
                       const std::wstring& key,
                       std::wstring& outValue) {
    outValue.clear();

    std::wstring needle = L"\"" + key + L"\"";
    size_t kpos = json.find(needle);
    if (kpos == std::wstring::npos) {
        return false;
    }

    size_t colon = json.find(L':', kpos + needle.size());
    if (colon == std::wstring::npos) {
        return false;
    }

    // 跳过空白
    size_t p = colon + 1;
    while (p < json.size() && (json[p] == L' ' || json[p] == L'\t'
                               || json[p] == L'\r' || json[p] == L'\n')) {
        ++p;
    }

    if (p >= json.size() || json[p] != L'"') {
        return false;   // 值为 null 或非字符串
    }

    size_t q1 = p;
    size_t q2 = json.find(L'"', q1 + 1);
    if (q2 == std::wstring::npos) {
        return false;
    }

    outValue = json.substr(q1 + 1, q2 - q1 - 1);
    return !outValue.empty();
}

// ---------------------------------------------------------------------------
// 判断字符串是否是安全的版本号（防止路径穿越/非法字符）
// 只允许数字、点、连字符
// ---------------------------------------------------------------------------
bool IsSafeVersionString(const std::wstring& v) {
    if (v.empty() || v.size() > 64) {
        return false;
    }
    for (wchar_t c : v) {
        if (!(iswdigit(c) || c == L'.' || c == L'-')) {
            return false;
        }
    }
    return true;
}

} // namespace

// ============================================================================
//  Launcher 命名空间实现
// ============================================================================
namespace Launcher {

// ---------------------------------------------------------------------------
// 查询最新版本号
// ---------------------------------------------------------------------------
StepResult QueryLatestVersion(std::wstring& outVersion) {
    outVersion.clear();

    const std::vector<std::wstring> bases = VERSION_API_BASES;

    StepResult lastError = StepResult::NetworkError;

    for (size_t i = 0; i < bases.size(); ++i) {
        std::wstring url = bases[i] + VERSION_API_PATH;

        Log::WriteF(L"查询最新版本 [%d/%d]: %s",
                    (int)(i + 1), (int)bases.size(), url.c_str());

        std::wstring json;
        StepResult r = Http::GetText(url, json, 15000);

        if (r != StepResult::Ok) {
            Log::WriteF(L"该地址查询失败: %s，尝试下一个", StepResultText(r));
            lastError = r;
            continue;
        }

        Log::WriteF(L"版本接口响应: %s", json.c_str());

        std::wstring latest;
        if (!ExtractJsonString(json, L"latest", latest)) {
            Log::Write(L"响应中没有 latest 字段，尝试下一个");
            lastError = StepResult::ParseError;
            continue;
        }

        if (!IsSafeVersionString(latest)) {
            Log::WriteF(L"latest 字段内容不合法: %s，尝试下一个", latest.c_str());
            lastError = StepResult::ParseError;
            continue;
        }

        outVersion = latest;
        Log::WriteF(L"最新版本: %s（来源 %s）", latest.c_str(), bases[i].c_str());
        return StepResult::Ok;
    }

    Log::Write(L"所有版本接口地址均失败");
    return lastError;
}

// ---------------------------------------------------------------------------
// 查询官方 SHA-256
//
//  文件服务器（HTTPS）上与 jar 同名的哈希文件，格式与 sha256sum 相同
//  （只有哈希，或「哈希 + 文件名」都可以）。
//
//  两种文件名都试，顺序是：
//    1) mc-launcher-<版本>.jar.sha256  —— 发布脚本 publish.py 上传的名字
//    2) mc-launcher-<版本>.sha256      —— 早期约定，兼容手工上传
//  早期只查第 2 种，而 publish.py 传的是第 1 种，导致这个接口永远 404，
//  每次发版都只能靠内置表 —— 等于「换个 jar 就得重编引导器」。
//
//  拿不到（404 / 断网 / 格式不对）一律返回空串 —— 调用方退回内置表，
//  「拿不到哈希」不等于「不是官方文件」。
//
//  为什么不走版本 API：版本 API 有明文 HTTP 备用地址，哈希从那里拿就等于
//  可以被中间人替换；文件服务器固定是 https://。
// ---------------------------------------------------------------------------
std::string QueryOfficialSha256(const std::wstring& version) {
    if (!IsSafeVersionString(version)) {
        return std::string();
    }

    static const wchar_t* kSuffixes[] = {
        L".jar.sha256",
        L".sha256",
    };
    for (const wchar_t* suffix : kSuffixes) {
        std::wstring url = JAR_DOWNLOAD_PREFIX;
        url += L"mc-launcher-" + version + suffix;

        std::wstring body;
        StepResult r = Http::GetText(url, body, 15000);
        if (r != StepResult::Ok) {
            Log::WriteF(L"官方哈希查询失败（%s）: %s", StepResultText(r), url.c_str());
            continue;
        }
        std::string hex = Hs::ExtractHash(Util::WideToUtf8(body));
        if (hex.empty()) {
            Log::WriteF(L"官方哈希文件格式不对: %s", url.c_str());
            continue;
        }
        Log::Write(L"官方哈希: " + version + L" = " + Util::Utf8ToWide(hex));
        return hex;
    }
    return std::string();
}

// ---------------------------------------------------------------------------
// 内置的已知版本哈希（信任锚，编进 exe）
// ---------------------------------------------------------------------------
std::string KnownJarHash(const std::wstring& version) {
    static const std::pair<std::wstring, std::wstring> kTable[] = {
        KNOWN_JAR_SHA256_LIST
    };
    for (const auto& row : kTable) {
        if (row.first == version) {
            return Hs::NormalizeHash(Util::WideToUtf8(row.second));
        }
    }
    return std::string();
}

// ---------------------------------------------------------------------------
// 语义化版本比较
//
//   支持任意位数：1.0.0.1 > 1.0.0
//   段内非数字后缀先截断（如 "1.0.0-beta" 当作 1.0.0 处理）
// ---------------------------------------------------------------------------
// 单个版本段：超过 9 位的数字按 LONG_MAX 处理时，多个溢出段会被当成相等，
// 导致「按顺序而不是按大小」选中旧版本。这里改为饱和到 1e9（远超任何真实版本号）。
static long ParseVersionPart(const std::wstring& digits) {
    long v = 0;
    for (wchar_t c : digits) {
        if (v > 100000000L) {
            return 1000000000L;
        }
        v = v * 10 + (c - L'0');
    }
    return v;
}

int CompareVersions(const std::wstring& a, const std::wstring& b) {
    auto parse = [](const std::wstring& v) {
        std::vector<long> parts;
        std::wstring cur;
        for (size_t i = 0; i <= v.size(); ++i) {
            wchar_t c = (i < v.size()) ? v[i] : L'.';
            if (iswdigit(c)) {
                cur.push_back(c);
            } else {
                if (!cur.empty()) {
                    parts.push_back(ParseVersionPart(cur));
                    cur.clear();
                }
                // 遇到 '-' 或非数字结束（如 1.0.0-beta）
                if (c == L'-') {
                    break;
                }
            }
        }
        if (!cur.empty()) {
            parts.push_back(ParseVersionPart(cur));
        }
        return parts;
    };

    std::vector<long> pa = parse(a);
    std::vector<long> pb = parse(b);

    size_t n = (pa.size() > pb.size()) ? pa.size() : pb.size();
    for (size_t i = 0; i < n; ++i) {
        long va = (i < pa.size()) ? pa[i] : 0;
        long vb = (i < pb.size()) ? pb[i] : 0;
        if (va != vb) {
            return (va > vb) ? 1 : -1;
        }
    }
    return 0;
}

// ---------------------------------------------------------------------------
// 读取版本清单，取出「应当启动的版本」
//
//  versions.json 由 Java 启动器维护。这里只做最朴素的解析 ——
//  提取顶层 current 字段，以及逐个扫描 usable=true 的条目。
//
//  为什么不引第三方 JSON 库：引导器要静态链接、体积尽量小，
//  而这个文件的结构是我们自己写的、格式可控，够用即可。
// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// 清单里该版本是否被标记为可用（usable=true）
//
// 找不到该版本的条目时视为可用（清单可能是旧格式 / 引导器自己刚写的）。
// 不能一律视为不可用：否则「先删 jar+标记不可用、再找候选」的顺序一变，
// 就会把本轮该启动的版本也排除掉。
// ---------------------------------------------------------------------------
static bool IsVersionUsableInManifest(const std::wstring& json,
                                      const std::wstring& version) {
    static const std::wstring versionKey = L"\"version\"";
    static const std::wstring usableKey = L"\"usable\"";
    size_t pos = 0;
    bool sawObject = false;
    while (true) {
        size_t vp = json.find(versionKey, pos);
        if (vp == std::wstring::npos) {
            return true;
        }
        size_t colon = json.find(L':', vp + versionKey.size());
        if (colon == std::wstring::npos) {
            return true;
        }
        std::wstring ver;
        if (!ExtractJsonString(json.substr(colon), L"version", ver) || ver != version) {
            pos = vp + versionKey.size();
            continue;
        }
        size_t objStart = json.rfind(L'{', vp);
        size_t objEnd = json.find(L'}', vp);
        if (objStart == std::wstring::npos || objEnd == std::wstring::npos) {
            return true;
        }
        sawObject = true;
        size_t up = json.find(usableKey, vp);
        if (up == std::wstring::npos || up > objEnd) {
            return true;
        }
        size_t c2 = json.find(L':', up);
        if (c2 == std::wstring::npos) {
            return true;
        }
        size_t v = c2 + 1;
        while (v < json.size() && iswspace(json[v])) {
            ++v;
        }
        return json.compare(v, 4, L"true") == 0;
    }
    (void)sawObject;
}

StepResult LoadPreferredJar(const AppPaths& paths,
                            std::wstring& outVersion,
                            std::wstring& outJarPath) {
    outVersion.clear();
    outJarPath.clear();

    std::wstring manifestPath =
            Util::JoinPath(paths.appData, VERSION_MANIFEST_NAME);

    if (!Util::IsFile(manifestPath)) {
        Log::Write(L"版本清单不存在，按版本号取最高");
        return StepResult::FileError;
    }

    std::string raw;
    if (!Util::ReadTextFileUtf8(manifestPath, raw)) {
        Log::Write(L"版本清单读取失败，按版本号取最高");
        return StepResult::FileError;
    }

    // 清单是 UTF-8，转成宽字符方便用现有工具函数处理
    std::wstring json = Util::Utf8ToWide(raw);
    if (json.empty()) {
        Log::Write(L"版本清单内容为空");
        return StepResult::FileError;
    }

    // ---- 1) 优先 current ----
    std::wstring current;
    if (ExtractJsonString(json, L"current", current)
            && IsSafeVersionString(current)) {
        std::wstring jar = Util::JoinPath(
                paths.appData, L"mc-launcher-" + current + L".jar");
        // usable=false 的版本（Java 端或引导器自己标过）绝不能再被拉起，
        // 否则会形成「启动失败 -> 回退 -> 又被 current 拉回去」的死循环
        if (!IsVersionUsableInManifest(json, current)) {
            Log::WriteF(L"清单里 current=%s 已被标记为不可用，跳过", current.c_str());
        } else if (Util::IsFile(jar) && Util::FileSize(jar) > 0) {
            Log::WriteF(L"版本清单指定 current=%s", current.c_str());
            outVersion = current;
            outJarPath = jar;
            return StepResult::Ok;
        }
        Log::WriteF(L"清单里的 current=%s 对应文件不存在，尝试其它可用版本",
                    current.c_str());
    }

    // ---- 2) 退而求其次：扫 usable=true 的条目，取版本号最高的 ----
    //
    // 条目形如：
    //   { "version": "1.0.0", "file": "mc-launcher-1.0.0.jar",
    //     "usable": true, "failures": 0, ... }
    //
    // 解析方式：先定位每个 "usable"，再**向前找到所在对象的起始 '{'**
    // 与对应结束 '}'，只在 [start, end) 这段里读 "version"。
    // 早期版本直接向前扫 "version" 不看对象边界，会把上一个条目的版本号
    // 误当成当前条目的，导致「版本 A 不可用」却把版本 B 排除掉。
    const std::wstring usableKey = L"\"usable\"";
    const std::wstring versionKey = L"\"version\"";
    std::wstring bestVersion;
    std::wstring bestJar;
    size_t pos = 0;

    while (true) {
        size_t up = json.find(usableKey, pos);
        if (up == std::wstring::npos) {
            break;
        }
        pos = up + usableKey.size();

        // 本对象范围
        size_t objStart = json.rfind(L'{', up);
        size_t objEnd = json.find(L'}', up);
        if (objStart == std::wstring::npos || objEnd == std::wstring::npos || objEnd < up) {
            continue;
        }
        std::wstring obj = json.substr(objStart, objEnd - objStart + 1);

        // usable 必须是 true
        std::wstring usableVal = obj;
        if (!ExtractJsonString(obj, L"version", usableVal)) {
            continue;      // 没有 version 字段：不是版本条目
        }
        size_t u = obj.find(usableKey);
        size_t uc = u == std::wstring::npos ? std::wstring::npos : obj.find(L':', u);
        if (uc == std::wstring::npos) {
            continue;
        }
        size_t v = uc + 1;
        while (v < obj.size() && iswspace(obj[v])) {
            ++v;
        }
        if (obj.compare(v, 4, L"true") != 0) {
            continue;
        }

        std::wstring ver = usableVal;
        if (ver.empty() || !IsSafeVersionString(ver)) {
            continue;
        }

        std::wstring jar = Util::JoinPath(
                paths.appData, L"mc-launcher-" + ver + L".jar");
        if (!Util::IsFile(jar) || Util::FileSize(jar) == 0) {
            continue;
        }

        if (bestVersion.empty() || CompareVersions(ver, bestVersion) > 0) {
            bestVersion = ver;
            bestJar = jar;
        }
    }

    if (!bestVersion.empty()) {
        Log::WriteF(L"版本清单中可用的最高版本: %s", bestVersion.c_str());
        outVersion = bestVersion;
        outJarPath = bestJar;
        return StepResult::Ok;
    }

    Log::Write(L"版本清单里没有可用版本，按版本号取最高");
    return StepResult::FileError;
}

// ---------------------------------------------------------------------------
// 扫描本地已有的最新版本
//
//  文件名格式：mc-launcher-<版本>.jar
//  逐个解析版本号，取最大的那个。
// ---------------------------------------------------------------------------
StepResult FindLatestLocalJar(const AppPaths& paths,
                              std::wstring& outVersion,
                              std::wstring& outJarPath) {
    outVersion.clear();
    outJarPath.clear();

    const std::wstring prefix = L"mc-launcher-";
    const std::wstring suffix = L".jar";

    std::wstring pattern = Util::JoinPath(paths.appData, L"mc-launcher-*.jar");

    WIN32_FIND_DATAW fd;
    HANDLE h = FindFirstFileW(pattern.c_str(), &fd);
    if (h == INVALID_HANDLE_VALUE) {
        Log::Write(L"本地未找到任何已下载的 jar");
        return StepResult::FileError;
    }

    std::wstring bestVersion;
    std::wstring bestPath;
    int found = 0;

    do {
        if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            continue;
        }

        std::wstring name = fd.cFileName;

        // 提取中间那段版本号
        if (name.size() <= prefix.size() + suffix.size()) {
            continue;
        }
        if (_wcsnicmp(name.c_str(), prefix.c_str(), prefix.size()) != 0) {
            continue;
        }
        if (!Util::EndsWithNoCase(name, suffix)) {
            continue;
        }

        std::wstring ver = name.substr(prefix.size(),
                                       name.size() - prefix.size() - suffix.size());
        if (!IsSafeVersionString(ver)) {
            continue;
        }
        // 旧版用 CompareVersions 截断连字符后缀（1.0.2-x 当成 1.0.2），
        // 删掉 1.0.3 后残留的 1.0.2-x 会被当成「1.0.2 还在」，导致反复启动坏 jar
        if (ver.find(L'-') != std::wstring::npos) {
            continue;
        }

        // 跳过 .part 之类的残留
        if (Util::EndsWithNoCase(name, L".part")) {
            continue;
        }

        std::wstring full = Util::JoinPath(paths.appData, name);

        // 文件要真的非空
        if (Util::FileSize(full) == 0) {
            continue;
        }

        ++found;

        if (bestVersion.empty() || CompareVersions(ver, bestVersion) > 0) {
            bestVersion = ver;
            bestPath = full;
        }

    } while (FindNextFileW(h, &fd));

    FindClose(h);

    if (found == 0 || bestVersion.empty()) {
        Log::Write(L"本地未找到任何有效的 jar");
        return StepResult::FileError;
    }

    Log::WriteF(L"本地已有 %d 个版本，选中最新的: %s",
                found, bestVersion.c_str());

    outVersion = bestVersion;
    outJarPath = bestPath;
    return StepResult::Ok;
}

static std::string ReadTrustedHash(const AppPaths& paths, const std::wstring& version);

// ---------------------------------------------------------------------------
// 确保 jar 存在
// ---------------------------------------------------------------------------
StepResult EnsureJar(const AppPaths& paths,
                     const std::wstring& version,
                     ProgressFn progress,
                     volatile bool* cancelFlag,
                     std::wstring& outJarPath,
                     bool verifyExisting,
                     std::wstring* outDetail) {
    outJarPath.clear();

    if (!IsSafeVersionString(version)) {
        Log::WriteF(L"版本号不合法，拒绝构造下载地址: %s", version.c_str());
        return StepResult::ParseError;
    }

    std::wstring jarName = L"mc-launcher-" + version + L".jar";

    // 先写 .part，下载+校验都通过后再改名为正式文件。
    //
    // 早期直接写正式文件名：下载被 CDN 给了旧文件时哈希不符，文件被删，
    // 接着 VerifyJar 又按「没有文件」报一个与真实原因无关的错误；
    // 更糟的是下载中途被杀会留下一个「看起来完整」的坏文件。
    // 用 .part 的好处：失败时正式文件从没出现过，用户删掉它重跑即可；
    // 下次启动扫描本地版本时也不会看到一个校验不过的文件。
    std::wstring jarPath = Util::JoinPath(paths.appData, jarName);
    std::wstring partPath = jarPath + L".part";
    Util::DeleteFileQuiet(partPath);

    // 已存在且非空 -> 直接使用
    uint64_t existingSize = Util::FileSize(jarPath);
    if (existingSize > 0) {
        // 首次安装路径（verifyExisting=true）不能盲信「非空即好」：
        // 上次下载到一半被杀 / 被镜像替换过的文件必须删掉重下。
        if (verifyExisting) {
            std::string expected = QueryOfficialSha256(version);
            if (expected.empty()) {
                expected = KnownJarHash(version);
            }
            if (!expected.empty()) {
                std::string actual;
                if (Hs::Sha256File(jarPath, actual, cancelFlag)
                        && !Hs::EqualsIgnoreCase(actual, expected)) {
                    Log::WriteF(L"已有 jar 与官方哈希不符，删除后重新下载: %s", jarPath.c_str());
                    Util::DeleteFileQuiet(jarPath);
                    existingSize = 0;
                }
            }
        }
    }
    if (existingSize > 0) {
        Log::WriteF(L"jar 已存在（%llu 字节），跳过下载: %s",
                    static_cast<unsigned long long>(existingSize), jarPath.c_str());
        outJarPath = jarPath;
        return StepResult::Ok;
    }

    // ---- 下载前检查磁盘空间 ----
    // 与 EnsureJava 同理：宁可提前拒绝，也不要下到一半才失败。
    // jar 本身约 45MB，这里给足余量。
    {
        uint64_t freeBytes = 0;
        if (!Util::HasFreeSpace(paths.appData, MIN_FREE_SPACE_BYTES, freeBytes)) {
            Log::WriteF(L"磁盘空间不足：需要 %s，实际剩余 %s",
                        Util::HumanSize(MIN_FREE_SPACE_BYTES).c_str(),
                        Util::HumanSize(freeBytes).c_str());
            return StepResult::FileError;
        }
        // 充足时也记一笔：排查"为什么明明有空间却失败了"时，
        // 能看到检查确实执行过、当时的数值是多少
        Log::WriteF(L"磁盘空间检查通过：剩余 %s",
                    Util::HumanSize(freeBytes).c_str());
    }

    // 构造下载地址
    std::wstring url = JAR_DOWNLOAD_PREFIX;
    url += jarName;

    Log::WriteF(L"下载启动器: %s", url.c_str());

    StepResult r = Http::DownloadFile(url, partPath,
                                      [&](const std::wstring& detail, uint64_t done, uint64_t total) {
                                          if (progress) {
                                              // detail 由 DownloadFile 拼好，含文件名/大小/速度/剩余时间。
                                              // 早期这里用固定文字覆盖，用户看不出在下什么。
                                              progress(L"正在下载启动器主程序\n" + detail,
                                                       done, total);
                                          }
                                      },
                                      cancelFlag);
    if (r != StepResult::Ok) {
        Log::WriteF(L"启动器下载失败: %s", StepResultText(r));
        // 明确提示服务器上还没有该文件的情况
        if (r == StepResult::HttpError) {
            Log::WriteF(L"提示：服务器返回非 2xx，可能该版本的 jar 尚未上传");
        }
        return r;
    }

    // 二次确认文件确实写好了（下到的是 .part）
    if (Util::FileSize(partPath) == 0) {
        Log::Write(L"下载后文件为空");
        Util::DeleteFileQuiet(partPath);
        return StepResult::FileError;
    }

    // 下载后立即按「官方哈希（HTTPS）优先，内置表兜底」校验。
    // 这一步挡的是「下载过程中被镜像/中间人替换」：
    //   - 官方 .sha256 走 HTTPS，中间人拿不到也改不了；
    //   - 服务器漏传 .sha256 时退回内置表。
    // 两边都拿不到就保留文件，交给启动前 VerifyJar 统一判断（不在下载阶段误杀）。
    std::string expected = QueryOfficialSha256(version);
    const bool knownOfficial = !expected.empty();
    if (!knownOfficial) {
        expected = KnownJarHash(version);
    }
    if (!expected.empty()) {
        if (progress) {
            progress(L"正在校验下载的启动器…", 0, 0);
        }
        std::string actual;
        const bool hit = Hs::Sha256File(partPath, actual, cancelFlag);
        if (!hit) {
            Log::Write(L"下载后哈希计算失败，保留文件交由启动前校验");
        } else if (!Hs::EqualsIgnoreCase(actual, expected)) {
            if (knownOfficial) {
                // .sha256 与 jar 同域，远端可能整体被替换：只要本机有强锚点就按强锚点拒绝
                std::string builtin = KnownJarHash(version);
                if (!builtin.empty() && Hs::EqualsIgnoreCase(actual, builtin)) {
                    Log::Write(L"远端 .sha256 与本机内置哈希冲突，以内置哈希为准：通过");
                    goto download_verified;
                }
                std::string trusted = ReadTrustedHash(paths, version);
                if (!trusted.empty() && Hs::EqualsIgnoreCase(actual, trusted)) {
                    Log::Write(L"远端 .sha256 与本机信任记录冲突，以本机记录为准：通过");
                    goto download_verified;
                }
            }
            Log::WriteUtf8("下载内容哈希不符！期望 %s，实际 %s", expected.c_str(), actual.c_str());
            // 带上大小与下载地址：CDN 节点缓存了旧文件时，响应头里的
            // Content-Length 是新文件的大小，但下下来的字节数是旧的 ——
            // 这两种情况排查方向完全不同，日志里带上大小一眼就能分辨。
            Log::WriteF(L"下载文件大小: %llu 字节（应等于期望哈希对应文件的大小）",
                        static_cast<unsigned long long>(Util::FileSize(partPath)));
            Log::WriteF(L"下载地址: %s", url.c_str());
            Log::WriteF(L"可信哈希来源: %s", knownOfficial ? L"官方 .sha256" : L"引导器内置表");
            Util::DeleteFileQuiet(partPath);
            // 给用户的提示要和真实原因对上
            std::wstring detail =
                    L"下载到的启动器文件与官方哈希不一致，已删除。\n"
                    L"期望：" + Hs::ShortHash(expected) + L"\n"
                    L"实际：" + Hs::ShortHash(actual) +
                    L"\n\n常见原因：下载地址（CDN）缓存了旧版本文件。请在浏览器中打开下面的\n"
                    L"链接下载并放到数据目录，或稍后再试：\n" + url;
if (outDetail) {
                *outDetail = detail;
            }
            return StepResult::ChecksumMismatch;
        } else {
download_verified:
            Log::Write(L"下载内容哈希校验通过");
        }
    } else {
        Log::Write(L"没有可用的官方哈希（.sha256 不存在且不在内置表），跳过下载后校验");
    }

    // 校验通过（或没有锚点）才把 .part 改名成正式文件
    Util::DeleteFileQuiet(jarPath);
    if (!MoveFileExW(partPath.c_str(), jarPath.c_str(), MOVEFILE_REPLACE_EXISTING)) {
        Log::WriteF(L"重命名下载文件失败: %s -> %s (err=%lu)",
                    partPath.c_str(), jarPath.c_str(), GetLastError());
        Util::DeleteFileQuiet(partPath);
        return StepResult::FileError;
    }

    // 本次会话内这份文件是「刚从固定 HTTPS 地址下载下来」的，
    // 记下来：即使没有哈希锚点，VerifyJar 也允许它启动一次（否则新版本永远装不上）。
    MarkSelfDownloaded(jarPath);

    outJarPath = jarPath;
    return StepResult::Ok;
}

// ---------------------------------------------------------------------------
// 本机已信任清单（%APPDATA%\NCL_Launcher\trusted-hashes.json）
//
//  由 Java 启动器在「启动成功后」写入：{ "1.0.2": "<sha256>", ... }
//  只认「版本 + 哈希」双匹配，按版本去重、不限条数（几十字节一条）。
//  与 config.json 分开存放，避免用户重置设置 / 配置损坏时被一并清掉。
// ---------------------------------------------------------------------------
static std::string ReadTrustedHash(const AppPaths& paths, const std::wstring& version) {
    // 文件格式：{ "1.0.2": "<64 位十六进制>", ... }
    // 不用通用 JSON 解析：只要「版本号 + 64 位哈希」双匹配，
    // 避免 extract-string 类朴素解析在嵌套结构上误抓。
    std::wstring p = Util::JoinPath(paths.appData, L"trusted-hashes.json");
    std::string raw;
    if (!Util::ReadSmallFile(p, raw, 256 * 1024)) {
        return std::string();
    }
    const std::string text = Hs::NormalizeHash(raw);
    const std::string key = "\"" + Util::WideToUtf8(version) + "\"";
    size_t pos = 0;
    while ((pos = text.find(key, pos)) != std::string::npos) {
        size_t colon = text.find(':', pos + key.size());
        if (colon == std::string::npos) {
            break;
        }
        size_t q1 = text.find('"', colon);
        size_t q2 = q1 == std::string::npos ? std::string::npos : text.find('"', q1 + 1);
        if (q1 == std::string::npos || q2 == std::string::npos || q2 - q1 - 1 != 64) {
            pos += key.size();
            continue;
        }
        std::string hex = text.substr(q1 + 1, 64);
        bool ok = true;
        for (char c : hex) {
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                ok = false;
                break;
            }
        }
        if (ok) {
            return hex;
        }
        pos += key.size();
    }
    return std::string();
}

// 本次运行中自己下载并（尽可能）校验过的 jar
static std::vector<std::wstring> g_selfDownloaded;

void MarkSelfDownloaded(const std::wstring& jarPath) {
    std::wstring norm = jarPath;
    for (auto& c : norm) {
        if (c == L'/') c = L'\\';
    }
    if (std::find(g_selfDownloaded.begin(), g_selfDownloaded.end(), norm) == g_selfDownloaded.end()) {
        g_selfDownloaded.push_back(norm);
    }
}

static std::wstring FullPathNorm(const std::wstring& p) {
    wchar_t full[1024];
    std::wstring norm = p;
    DWORD n = GetFullPathNameW(p.c_str(), 1024, full, nullptr);
    if (n > 0 && n < 1024) {
        norm = full;
    }
    for (auto& c : norm) {
        if (c == L'/') c = L'\\';
        if (c >= L'A' && c <= L'Z') c = static_cast<wchar_t>(c - L'A' + L'a');
    }
    return norm;
}

bool SelectedFileIsOurs(const AppPaths& paths, const std::wstring& jarPath) {
    std::wstring p = Util::JoinPath(paths.appData, L"selected.json");
    std::string raw;
    if (!Util::ReadSmallFile(p, raw, 16 * 1024)) {
        return false;
    }
    std::wstring json = Util::Utf8ToWide(raw);
    std::wstring self;
    if (!ExtractJsonString(json, L"path", self) || self.empty()) {
        return false;
    }
    if (FullPathNorm(self) != FullPathNorm(jarPath)) {
        return false;
    }
    // 反查版本：文件大小必须与记录一致（防止记录指向的文件后来被换掉）
    std::wstring name = Util::FileName(self);
    std::wstring suffix = L".jar";
    std::wstring stem = name;
    if (stem.size() > suffix.size()) {
        stem = stem.substr(0, stem.size() - suffix.size());
    }
    std::wstring markedSize;
    if (ExtractJsonString(json, L"size", markedSize)) {
        uint64_t expect = _wcstoui64(markedSize.c_str(), nullptr, 10);
        uint64_t actual = Util::FileSize(jarPath);
        if (expect != 0 && actual != expect) {
            Log::WriteF(L"selected.json 记录的大小 %llu 与实际 %llu 不符",
                        static_cast<unsigned long long>(expect),
                        static_cast<unsigned long long>(actual));
            return false;
        }
    }
    Log::WriteF(L"%s 由本机启动器登记为已选中文件", stem.c_str());
    return true;
}

bool IsSelfDownloaded(const std::wstring& jarPath) {
    wchar_t full[1024];
    std::wstring norm = jarPath;
    DWORD n = GetFullPathNameW(jarPath.c_str(), 1024, full, nullptr);
    if (n > 0 && n < 1024) {
        norm = full;
    }
    for (auto& c : norm) {
        if (c == L'/') c = L'\\';
    }
    return std::find(g_selfDownloaded.begin(), g_selfDownloaded.end(), norm) != g_selfDownloaded.end();
}

// ---------------------------------------------------------------------------
// 校验 jar（信任锚）
//
//  原则：宁可「无法判定时放行并记日志」，也不能因为服务器没传哈希、
//  用户换了目录、内置表还没收录新版本，就把人锁在启动器外面。
//  但一旦任何可信锚点与文件内容**不一致**，立即拒绝 —— 这一定是篡改或损坏。
// ---------------------------------------------------------------------------
StepResult VerifyJar(const AppPaths& paths,
                     const std::wstring& jarPath,
                     ProgressFn progress,
                     volatile bool* cancelFlag,
                     std::wstring* outDetail) {
    std::wstring name = Util::FileName(jarPath);
    std::wstring version;
    const std::wstring prefix = L"mc-launcher-";
    const std::wstring suffix = L".jar";
    if (name.size() > prefix.size() + suffix.size()
            && _wcsnicmp(name.c_str(), prefix.c_str(), prefix.size()) == 0) {
        version = name.substr(prefix.size(), name.size() - prefix.size() - suffix.size());
    }

    if (progress) {
        progress(L"正在校验启动器文件…", 0, 0);
    }

    if (version.empty()) {
        // 文件名不完全是 mc-launcher-<ver>.jar（自己编译的、带 -shaded 后缀等）：
        // 内置器读不到正确版本号就查不到任何锚点，会白白把文件判成「无锚点」。
        // 做两次大小探测来反查版本，探测到就用它的版本号继续校验。
        {
            // 先查内置表：按文件大小可比时直接确认
            static const std::pair<std::wstring, std::wstring> kTable[] = {KNOWN_JAR_SHA256_LIST};
            for (const auto& row : kTable) {
                std::wstring p = Util::JoinPath(paths.appData, L"mc-launcher-" + row.first + L".jar");
                if (Util::FileSize(p) == Util::FileSize(jarPath) && Util::FileSize(jarPath) > 0) {
                    version = row.first;
                    Log::WriteF(L"按文件大小推断版本: %s", version.c_str());
                    break;
                }
            }
        }
        if (version.empty()) {
            Log::Write(L"无法从文件名/大小推断版本，按无锚点处理");
        }
    }

    std::string actual;
    if (!Hs::Sha256File(jarPath, actual, cancelFlag)) {
        // 算不出来（文件被锁/读失败）：不阻断启动，交给 Java 进程自己报错
        Log::WriteF(L"无法计算 jar 哈希，跳过校验: %s", jarPath.c_str());
        return StepResult::Ok;
    }
    Log::WriteUtf8("jar 哈希: %s = %s", version.c_str(), actual.c_str());

    // 1) 官方 .sha256（HTTPS）
    //
    // 注意信任根：远端 .sha256 只做「弱锚点」。jar 和 .sha256 放在同一个域名下，
    // 域名一旦被接管（过期、托管方投毒、从 git 历史拿到某版本哈希后一起替换），
    // 远端校验就形同虚设。所以本地已有强锚点（内置表 / 本机信任表）时，
    // 必须先和强锚点比对；强锚点缺失时才退回 .sha256。
    std::string official = QueryOfficialSha256(version);
    if (!official.empty()) {
        std::string builtinEarly = KnownJarHash(version);
        std::string trustedEarly = ReadTrustedHash(paths, version);
        if (Hs::EqualsIgnoreCase(actual, official)) {
            if (!builtinEarly.empty() && !Hs::EqualsIgnoreCase(actual, builtinEarly)) {
                Log::Write(L"校验失败：与官方 .sha256 一致，但与本机强锚点（内置表）不一致");
                if (outDetail) {
                    *outDetail = L"文件与远端 .sha256 一致，但与引导器内置哈希不一致，"
                                 L"远端清单可能已被篡改。";
                }
                return StepResult::ChecksumMismatch;
            }
            if (!trustedEarly.empty() && !Hs::EqualsIgnoreCase(actual, trustedEarly)) {
                Log::Write(L"校验失败：与官方 .sha256 一致，但与本机信任记录不一致");
                if (outDetail) {
                    *outDetail = L"文件与远端 .sha256 一致，但与本机已信任记录的哈希不一致，"
                                 L"远端清单可能已被篡改。";
                }
                return StepResult::ChecksumMismatch;
            }
            Log::Write(L"校验通过：与官方 .sha256 一致");
            return StepResult::Ok;
        }
        Log::WriteUtf8("校验失败：与官方 .sha256 不一致（官方 %s，实际 %s）",
                    official.c_str(), actual.c_str());
        if (outDetail) {
            *outDetail = L"文件内容与官方 SHA-256 不一致。\n期望：" + Hs::ShortHash(official)
                    + L"\n实际：" + Hs::ShortHash(actual);
        }
        return StepResult::ChecksumMismatch;
    }

    // 2) 内置已知表
    std::string builtin = KnownJarHash(version);
    if (!builtin.empty()) {
        if (Hs::EqualsIgnoreCase(actual, builtin)) {
            Log::Write(L"校验通过：与内置已知哈希一致");
            return StepResult::Ok;
        }
        Log::WriteUtf8("校验失败：与内置哈希不一致（内置 %s，实际 %s）",
                    builtin.c_str(), actual.c_str());
        if (outDetail) {
            *outDetail = L"文件内容与内置的已知 SHA-256 不一致。\n期望：" + Hs::ShortHash(builtin)
                    + L"\n实际：" + Hs::ShortHash(actual);
        }
        return StepResult::ChecksumMismatch;
    }

    // 3) 本机已信任清单（Java 启动器登记过）
    std::string trusted = ReadTrustedHash(paths, version);
    if (!trusted.empty()) {
        if (Hs::EqualsIgnoreCase(actual, trusted)) {
            Log::Write(L"校验通过：与本机已信任记录一致");
            return StepResult::Ok;
        }
        Log::WriteUtf8("校验失败：与本机信任记录不一致（记录 %s，实际 %s）",
                    trusted.c_str(), actual.c_str());
        if (outDetail) {
            *outDetail = L"文件内容与本机记录的 SHA-256 不一致（文件可能已被替换）。\n"
                    L"记录：" + Hs::ShortHash(trusted) + L"\n实际：" + Hs::ShortHash(actual);
        }
        return StepResult::ChecksumMismatch;
    }

    // 3.5) 本机启动器登记过「这是我自己」的 jar（用户手动拷贝/离线安装时唯一的锚点）
    if (SelectedFileIsOurs(paths, jarPath)) {
        Log::Write(L"校验放行：该文件是本机启动器登记的当前运行文件");
        return StepResult::Ok;
    }

    // 4) 本次会话内自己从固定 HTTPS 地址下载的 —— 放行一次（由 Java 启动后自行登记）
    if (IsSelfDownloaded(jarPath)) {
        Log::Write(L"没有可信锚点，但文件是本次运行从官方地址下载的，放行并等待启动器自行登记");
        return StepResult::Ok;
    }

    Log::WriteF(L"没有可信锚点（无 .sha256、不在内置表、未登记）：%s", jarPath.c_str());
    if (outDetail) {
        *outDetail = L"既没有官方 .sha256、也不在引导器内置表、本机也没有登记过。\n"
                L"实际 SHA-256：" + Hs::ShortHash(actual);
    }
    return StepResult::UntrustedJar;
}

// ---------------------------------------------------------------------------
// 拉起主程序
//
// 关键点：
//   - 用 CreateProcessW（宽字符），路径含空格/中文都能正确处理
//   - 命令行是「拼接成单个字符串」传给 CreateProcessW —— 这是 Win32 的要求；
//     但每个路径都用双引号包住，且不做 shell 解析，因此空格安全
//   - 设置工作目录为 appData，让主程序读写自己的文件
//   - 创建后立即关闭句柄，不等待：引导器退出，主程序继续跑
// ---------------------------------------------------------------------------
StepResult Launch(const std::wstring& javawPath,
                  const std::wstring& jarPath,
                  const std::wstring& workDir,
                  ProgressFn progress,
                  DWORD* outChildPid,
                  HANDLE* outChildProcess) {
    // 参数保留是为了接口稳定；启动阶段已改为静默，不再需要进度反馈
    (void)progress;

    if (!Util::IsFile(javawPath)) {
        Log::WriteF(L"javaw 不存在: %s", javawPath.c_str());
        return StepResult::JavaNotFound;
    }
    if (!Util::IsFile(jarPath)) {
        Log::WriteF(L"jar 不存在: %s", jarPath.c_str());
        return StepResult::FileError;
    }

    // 组装命令行（每个路径加引号，防止空格问题）
    //
    // 中继的 API 密钥：从引导器进程的环境变量 MC_RELAY_API_KEY 读取，
    // 转成 -D 参数传给启动器。双击启动的快捷方式、其它程序拉起引导器时
    // 都可以直接在环境里设置，不需要额外带命令行参数。
    std::wstring cmd = L"\"";
    cmd += javawPath;
    cmd += L"\"";
    {
        DWORD n = GetEnvironmentVariableW(L"MC_RELAY_API_KEY", nullptr, 0);
        if (n > 0 && n < 4096) {
            std::wstring key(n, L'\0');
            DWORD got = GetEnvironmentVariableW(L"MC_RELAY_API_KEY", &key[0], n);
            key.resize(got);
            const wchar_t* kSpace = L" \t\r\n";
            size_t b = key.find_first_not_of(kSpace);
            if (b == std::wstring::npos) {
                key.clear();
            } else {
                key = key.substr(b, key.find_last_not_of(kSpace) - b + 1);
            }
            // -D 参数在 CreateProcess 的命令行里会按空白切分，含空白就宁可不传
            if (!key.empty() && key.find_first_of(kSpace) == std::wstring::npos
                    && key.find(L'"') == std::wstring::npos) {
                cmd += L" -Dmclauncher.relayApiKey=";
                cmd += key;
            } else if (!key.empty()) {
                Log::Write(L"\u73af\u5883\u53d8\u91cf MC_RELAY_API_KEY \u542b\u7a7a\u767d\u6216\u5f15\u53f7\uff0c\u5df2\u5ffd\u7565");
            }
        }
    }
    cmd += L" -jar \"";
    cmd += jarPath;
    cmd += L"\"";

    Log::WriteF(L"启动命令: %s", cmd.c_str());

    // 关键：把子进程的标准输出/错误重定向到日志文件。
    //
    // 若不给 javaw 任何输出目标，它在没有控制台的情况下写 stdout/stderr
    // 可能阻塞（JavaFX 启动时会打印警告）。重定向到文件既解决这个问题，
    // 又让主程序的输出可追溯，便于排查。
    std::wstring childLogPath = Util::JoinPath(workDir, L"launcher-stdout.log");

    SECURITY_ATTRIBUTES sa;
    ZeroMemory(&sa, sizeof(sa));
    sa.nLength = sizeof(sa);
    sa.bInheritHandle = TRUE;

    HANDLE hChildOut = CreateFileW(childLogPath.c_str(),
                                   FILE_APPEND_DATA,
                                   FILE_SHARE_READ | FILE_SHARE_WRITE,
                                   &sa,
                                   OPEN_ALWAYS,
                                   FILE_ATTRIBUTE_NORMAL,
                                   nullptr);
    if (hChildOut == INVALID_HANDLE_VALUE) {
        hChildOut = nullptr;    // 打不开就退回不重定向
        Log::Write(L"无法创建子进程输出文件，改为不重定向");
    }

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    ZeroMemory(&pi, sizeof(pi));
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_SHOWDEFAULT;  // 使用默认显示状态，避免 JavaFX 窗口初始隐藏

    if (hChildOut) {
        si.dwFlags |= STARTF_USESTDHANDLES;
        si.hStdOutput = hChildOut;
        si.hStdError  = hChildOut;
        si.hStdInput  = nullptr;
    }

    // CreateProcessW 的 lpCommandLine 需要非 const 指针，且字符串必须可修改
    // 使用 &mutableCmd[0] 获取非 const 指针（C++11 保证连续存储）
    std::wstring mutableCmd = cmd;
    wchar_t* cmdLine = &mutableCmd[0];

    BOOL ok = CreateProcessW(
            javawPath.c_str(),                  // 应用程序路径
            cmdLine,                            // 命令行（可修改缓冲区）
            nullptr, nullptr,                   // 进程/线程安全属性
            hChildOut ? TRUE : FALSE,           // 有重定向句柄时必须允许继承
            0,                                  // 无特殊标志
            nullptr,                            // 环境块（继承父进程）
            workDir.empty() ? nullptr : workDir.c_str(),
            &si, &pi);

    if (!ok) {
        DWORD err = GetLastError();
        Log::WriteF(L"CreateProcessW 失败 (err=%lu)", err);

        switch (err) {
            case ERROR_FILE_NOT_FOUND:
                return StepResult::JavaNotFound;
            case ERROR_ACCESS_DENIED:
                return StepResult::PermissionError;
            default:
                return StepResult::LaunchFailed;
        }
    }

    // 父进程不再需要这个写句柄（子进程已持有自己的副本）
    if (hChildOut) {
        CloseHandle(hChildOut);
        hChildOut = nullptr;
    }

    Log::WriteF(L"已启动主程序 (PID=%lu)", pi.dwProcessId);

    CloseHandle(pi.hThread);

    // 记录本次期望出现在 startup.ok 里的 PID，供等待判定使用
    if (outChildPid) {
        *outChildPid = pi.dwProcessId;
    }

    // 进程句柄交给调用方（等待判定要用它查是否已退出）；
    // 若调用方不需要，这里直接关掉避免泄漏。
    if (outChildProcess) {
        *outChildProcess = pi.hProcess;
    } else {
        CloseHandle(pi.hProcess);
    }

    return StepResult::Ok;
}

// ---- 等待主程序「正常起来」—— 双重判定 ----
//
//  判定 A：进程仍存活（检测到退出时再分「异常退出」与「正常退出但没写标记」）
//  判定 B：startup.ok 里的 PID == childPid —— 真正的成功信号，
//          由 Java 启动器主窗口显示成功后主动写入。
//
//  等满时间仍未就绪且进程还活着 -> TimedOut（很可能只是慢，不代表失败）。
//
//  waitMs / pollMs：总等待时长与轮询间隔。
//  outWhy：失败原因。
WaitResult WaitForStartupEx(const std::wstring& appData,
                                HANDLE childProcess,
                                DWORD childPid,
                                DWORD waitMs,
                                DWORD pollMs,
                                volatile bool* cancelFlag,
                                std::wstring* outWhy) {
    auto setWhy = [&](const wchar_t* s) {
        if (outWhy) {
            *outWhy = s;
        }
    };

    if (childProcess == nullptr || childPid == 0) {
        setWhy(L"没有可用的进程句柄或 PID");
        return WaitResult::Crashed;
    }

    std::wstring okPath = Util::JoinPath(appData, STARTUP_OK_NAME);

    DWORD waited = 0;
    if (pollMs == 0) {
        pollMs = 200;
    }

    while (waited < waitMs) {
        if (cancelFlag && *cancelFlag) {
            setWhy(L"用户取消");
            return WaitResult::Cancelled;
        }

        // --- 判定 B（主判据）：startup.ok 是否匹配本次 PID ---
        std::string raw;
        if (Util::ReadTextFileUtf8(okPath, raw)) {
            std::wstring text = Util::Trim(Util::Utf8ToWide(raw));
            wchar_t* end = nullptr;
            unsigned long long pidInFile = wcstoull(text.c_str(), &end, 10);
            if (end != text.c_str()
                    && pidInFile == static_cast<unsigned long long>(childPid)) {
                Log::WriteF(L"收到启动成功标记（PID=%llu），主程序已就绪", pidInFile);
                return WaitResult::Started;
            }
        }

        // --- 判定 A：进程是否已退出 ---
        DWORD exitCode = 0;
        if (GetExitCodeProcess(childProcess, &exitCode) && exitCode != STILL_ACTIVE) {
            if (exitCode != 0) {
                Log::WriteF(L"子进程异常退出 (exit=%lu)，判定为启动失败", exitCode);
                setWhy(L"启动器进程异常退出");
                return WaitResult::Crashed;
            }
            // exit=0：要么界面起来过、用户主动关闭；要么界面根本没出现。
            Log::Write(L"子进程正常退出 (exit=0)，退出码无异常");
            std::string raw2;
            if (Util::ReadTextFileUtf8(okPath, raw2)) {
                std::wstring t2 = Util::Trim(Util::Utf8ToWide(raw2));
                wchar_t* e2 = nullptr;
                unsigned long long pid2 = wcstoull(t2.c_str(), &e2, 10);
                if (e2 != t2.c_str()
                        && pid2 == static_cast<unsigned long long>(childPid)) {
                    Log::Write(L"该进程曾写过启动成功标记，视为用户主动关闭");
                    return WaitResult::Started;
                }
            }
            Log::Write(L"进程退出但从未写过启动成功标记 -> 界面未出现，判为失败");
            setWhy(L"启动器进程已退出，但界面未出现");
            return WaitResult::Exited;
        }

        Sleep(pollMs);
        waited += pollMs;
    }

    Log::WriteF(L"等待 %lu ms 仍未确认启动成功（进程仍存活）", waitMs);
    setWhy(L"启动器没有正常起来");
    return WaitResult::TimedOut;
}

bool WaitForStartup(const std::wstring& appData,
                    HANDLE childProcess,
                    DWORD childPid,
                    DWORD waitMs,
                    DWORD pollMs,
                    volatile bool* cancelFlag,
                    std::wstring* outWhy) {
    return WaitForStartupEx(appData, childProcess, childPid, waitMs, pollMs,
                            cancelFlag, outWhy) == WaitResult::Started;
}

// ---------------------------------------------------------------------------
// 找出可回退的下一个版本
//
//  规则：在清单里「已验证可用（usable=true）」的版本中，取版本号**低于**
//  current 且最高的那个。找不到更低的就返回 false。
//
//  只退到验证过可用的版本：退到一个同样没验证过的版本没有意义，
//  很可能一起崩。
// ---------------------------------------------------------------------------
bool FindRollbackCandidate(const AppPaths& paths,
                           const std::wstring& currentVersion,
                           std::wstring& outVersion,
                           std::wstring& outJarPath) {
    outVersion.clear();
    outJarPath.clear();

    std::string raw;
    if (!Util::ReadTextFileUtf8(
            Util::JoinPath(paths.appData, VERSION_MANIFEST_NAME), raw)) {
        Log::Write(L"回退失败：读不到版本清单");
        return false;
    }
    std::wstring json = Util::Utf8ToWide(raw);
    if (json.empty()) {
        return false;
    }

    // 扫描所有 usable=true 的对象，收集其版本号
    const std::wstring usableKey = L"\"usable\"";
    std::wstring best;
    size_t pos = 0;

    while (true) {
        size_t up = json.find(usableKey, pos);
        if (up == std::wstring::npos) {
            break;
        }
        pos = up + usableKey.size();

        size_t colon = json.find(L':', pos);
        if (colon == std::wstring::npos) {
            break;
        }
        size_t v = colon + 1;
        while (v < json.size() && iswspace(json[v])) {
            ++v;
        }
        if (json.compare(v, 4, L"true") != 0) {
            continue;
        }

        // 在本对象内向前找 version 字段
        size_t brace = json.rfind(L'{', up);
        size_t from = (brace == std::wstring::npos) ? 0 : brace;
        std::wstring ver;
        size_t scan = from;
        while (scan < up) {
            size_t hit = json.find(L"\"version\"", scan);
            if (hit == std::wstring::npos || hit >= up) {
                break;
            }
            std::wstring tmp;
            if (ExtractJsonString(json.substr(hit, up - hit), L"version", tmp)) {
                ver = tmp;
            }
            scan = hit + 9;
        }

        if (ver.empty() || !IsSafeVersionString(ver)) {
            continue;
        }

        // 必须是「比当前低」的版本
        if (CompareVersions(ver, currentVersion) >= 0) {
            continue;
        }

        std::wstring jar = Util::JoinPath(
                paths.appData, L"mc-launcher-" + ver + L".jar");
        if (!Util::IsFile(jar) || Util::FileSize(jar) == 0) {
            continue;
        }

        if (best.empty() || CompareVersions(ver, best) > 0) {
            best = ver;
        }
    }

    if (best.empty()) {
        Log::Write(L"回退失败：没有更低的、已验证可用的版本");
        return false;
    }

    outVersion = best;
    outJarPath = Util::JoinPath(paths.appData,
                                L"mc-launcher-" + best + L".jar");
    Log::WriteF(L"找到可回退版本: %s", best.c_str());
    return true;
}

// ---------------------------------------------------------------------------
// 把清单里的 current 改成指定版本（回退后写回）
//
//  朴素文本替换：找 "current" 后的字符串值并替换。
//  清单是我们自己写的，格式可控，不引 JSON 库。
// ---------------------------------------------------------------------------
bool UpdateManifestCurrent(const AppPaths& paths,
                           const std::wstring& newVersion) {
    std::wstring path = Util::JoinPath(paths.appData, VERSION_MANIFEST_NAME);

    std::string raw;
    if (!Util::ReadTextFileUtf8(path, raw)) {
        Log::Write(L"回退写入失败：读不到版本清单");
        return false;
    }
    std::wstring json = Util::Utf8ToWide(raw);

    size_t key = json.find(L"\"current\"");
    if (key == std::wstring::npos) {
        return false;
    }
    size_t colon = json.find(L':', key);
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

    std::wstring updated = json.substr(0, q1 + 1)
                         + newVersion
                         + json.substr(q2);

    // pendingSince 不清零的话，Java 端下一次启动会认为「先去向 0 已确认」，
    // 把回退选的版本重新改写回刚失败的那个，回退等于没做
    {
        static const std::wstring pendingKey = L"\"pendingSince\"";
        size_t pk = updated.find(pendingKey);
        if (pk != std::wstring::npos) {
            size_t pc = updated.find(L':', pk);
            if (pc != std::wstring::npos) {
                size_t numStart = pc + 1;
                while (numStart < updated.size() && iswspace(updated[numStart])) {
                    ++numStart;
                }
                size_t numEnd = numStart;
                while (numEnd < updated.size()
                       && (iswdigit(updated[numEnd]) || updated[numEnd] == L'-')) {
                    ++numEnd;
                }
                if (numEnd > numStart) {
                    updated.replace(numStart, numEnd - numStart, L"0");
                }
            }
        }
    }

    // 原子写：先临时文件再替换，避免写一半损坏清单
    std::wstring tmp = path + L".tmp";
    if (!Util::WriteTextFileUtf8(tmp, Util::WideToUtf8(updated))) {
        Log::Write(L"回退写入失败：无法写临时文件");
        return false;
    }
    if (!MoveFileExW(tmp.c_str(), path.c_str(),
                     MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        Log::WriteF(L"回退写入失败：替换清单出错 (err=%lu)", GetLastError());
        Util::DeleteFileQuiet(tmp);
        return false;
    }

    Log::WriteF(L"清单 current 已改为 %s", newVersion.c_str());
    return true;
}

// ---------------------------------------------------------------------------
// 把某个版本标记为不可用（回退时调用）
//
//  同样用朴素文本处理：找到该版本的对象，把其 usable 改成 false。
// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// 删除指定失败版本的 jar 文件
//
// 调用时机：回退前把当前版本的 jar 删掉，避免下次引导器从磁盘
// 扫描时又把它当成候选。删除失败（权限不足等）不阻断流程。
// ---------------------------------------------------------------------------
void DeleteFailedJar(const AppPaths& paths, const std::wstring& version) {
    std::wstring jar = Util::JoinPath(paths.appData, L"mc-launcher-" + version + L".jar");
    if (Util::IsFile(jar)) {
        if (Util::DeleteFileQuiet(jar)) {
            Log::WriteF(L"已删除失败版本的 jar: %s", jar.c_str());
        } else {
            Log::WriteF(L"删除 jar 失败（可能正被占用）: %s", jar.c_str());
        }
    } else {
        Log::WriteF(L"jar 不存在，无需删除: %s", jar.c_str());
    }
}

bool MarkVersionUnusable(const AppPaths& paths,
                         const std::wstring& version) {
    std::wstring path = Util::JoinPath(paths.appData, VERSION_MANIFEST_NAME);

    std::string raw;
    if (!Util::ReadTextFileUtf8(path, raw)) {
        return false;
    }
    std::wstring json = Util::Utf8ToWide(raw);

    // 定位这个版本所在的条目。
    // Gson 缩进输出是 "version": "1.0.2"，但空格数量随版本/配置可变，
    // 以前用带空格的精确字符串匹配，匹配不上就静默失败 —— 改按值解析。
    size_t vp = std::wstring::npos;
    {
        size_t scan = 0;
        while (true) {
            size_t hit = json.find(L"\"version\"", scan);
            if (hit == std::wstring::npos) {
                break;
            }
            size_t colon = json.find(L':', hit);
            std::wstring found;
            if (colon != std::wstring::npos
                    && ExtractJsonString(json.substr(colon), L"version", found)
                    && found == version) {
                vp = hit;
                break;
            }
            scan = hit + 9;
        }
    }
    if (vp == std::wstring::npos) {
        Log::WriteF(L"清单里找不到版本 %s，无法标记不可用", version.c_str());
        return false;
    }

    // 在该对象范围内（到下一个 '{' 或 '}' 为止）找 usable 并改为 false
    size_t objEnd = json.find(L'}', vp);
    if (objEnd == std::wstring::npos) {
        return false;
    }

    size_t up = json.find(L"\"usable\"", vp);
    if (up == std::wstring::npos || up > objEnd) {
        return false;
    }
    size_t colon = json.find(L':', up);
    if (colon == std::wstring::npos) {
        return false;
    }
    size_t t = colon + 1;
    while (t < json.size() && iswspace(json[t])) {
        ++t;
    }
    if (json.compare(t, 4, L"true") != 0) {
        return true;    // 已经是 false，无需修改
    }
    json.replace(t, 4, L"false");

    std::wstring tmp = path + L".tmp";
    if (!Util::WriteTextFileUtf8(tmp, Util::WideToUtf8(json))) {
        return false;
    }
    if (!MoveFileExW(tmp.c_str(), path.c_str(),
                     MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
        Util::DeleteFileQuiet(tmp);
        return false;
    }

    Log::WriteF(L"版本 %s 已在清单中标记为不可用", version.c_str());
    return true;
}

} // namespace Launcher