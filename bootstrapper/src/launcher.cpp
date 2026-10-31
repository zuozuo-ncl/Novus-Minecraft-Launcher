#include "common.h"

#include <shellapi.h>
#include <algorithm>
#include <vector>

namespace {

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

    size_t p = colon + 1;
    while (p < json.size() && (json[p] == L' ' || json[p] == L'\t'
                               || json[p] == L'\r' || json[p] == L'\n')) {
        ++p;
    }

    if (p >= json.size() || json[p] != L'"') {
        return false;   
    }

    size_t q1 = p;
    size_t q2 = json.find(L'"', q1 + 1);
    if (q2 == std::wstring::npos) {
        return false;
    }

    outValue = json.substr(q1 + 1, q2 - q1 - 1);
    return !outValue.empty();
}

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

} 

namespace Launcher {

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
                    parts.push_back(wcstol(cur.c_str(), nullptr, 10));
                    cur.clear();
                }

                if (c == L'-') {
                    break;
                }
            }
        }
        if (!cur.empty()) {
            parts.push_back(wcstol(cur.c_str(), nullptr, 10));
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

    std::wstring json = Util::Utf8ToWide(raw);
    if (json.empty()) {
        Log::Write(L"版本清单内容为空");
        return StepResult::FileError;
    }

    std::wstring current;
    if (ExtractJsonString(json, L"current", current)
            && IsSafeVersionString(current)) {
        std::wstring jar = Util::JoinPath(
                paths.appData, L"mc-launcher-" + current + L".jar");

        
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

    

    

    

    const std::wstring usableKey = L"\"usable\"";
    std::wstring bestVersion;
    std::wstring bestJar;
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

        size_t searchFrom = up;
        std::wstring ver;

        size_t brace = json.rfind(L'{', up);
        size_t from = (brace == std::wstring::npos) ? 0 : brace;

        size_t scan = from;
        while (scan < up) {
            size_t hit = json.find(L"\"version\"", scan);
            if (hit == std::wstring::npos || hit >= up) {
                break;
            }
            std::wstring tmp;
            if (ExtractJsonString(json.substr(hit, up - hit),
                                  L"version", tmp)) {
                ver = tmp;
            }
            scan = hit + 9;
        }
        (void)searchFrom;

        if (ver.empty() || !IsSafeVersionString(ver)) {
            continue;
        }

        std::wstring jar = Util::JoinPath(
                paths.appData, L"mc-launcher-" + ver + L".jar");
        if (!Util::IsFile(jar) || Util::FileSize(jar) == 0) {
            continue;
        }

        if (bestVersion.empty()
                || CompareVersions(ver, bestVersion) > 0) {
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

        
        if (ver.find(L'-') != std::wstring::npos) {
            continue;
        }

        if (Util::EndsWithNoCase(name, L".part")) {
            continue;
        }

        std::wstring full = Util::JoinPath(paths.appData, name);

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

    

    

    

    std::wstring jarPath = Util::JoinPath(paths.appData, jarName);
    std::wstring partPath = jarPath + L".part";
    Util::DeleteFileQuiet(partPath);

    uint64_t existingSize = Util::FileSize(jarPath);
    if (existingSize > 0) {

        
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

    

    {
        uint64_t freeBytes = 0;
        if (!Util::HasFreeSpace(paths.appData, MIN_FREE_SPACE_BYTES, freeBytes)) {
            Log::WriteF(L"磁盘空间不足：需要 %s，实际剩余 %s",
                        Util::HumanSize(MIN_FREE_SPACE_BYTES).c_str(),
                        Util::HumanSize(freeBytes).c_str());
            return StepResult::FileError;
        }

        
        Log::WriteF(L"磁盘空间检查通过：剩余 %s",
                    Util::HumanSize(freeBytes).c_str());
    }

    std::wstring url = JAR_DOWNLOAD_PREFIX;
    url += jarName;

    Log::WriteF(L"下载启动器: %s", url.c_str());

    StepResult r = Http::DownloadFile(url, partPath,
                                      [&](const std::wstring& detail, uint64_t done, uint64_t total) {
                                          if (progress) {

                                              
                                              progress(L"正在下载启动器主程序\n" + detail,
                                                       done, total);
                                          }
                                      },
                                      cancelFlag);
    if (r != StepResult::Ok) {
        Log::WriteF(L"启动器下载失败: %s", StepResultText(r));

        if (r == StepResult::HttpError) {
            Log::WriteF(L"提示：服务器返回非 2xx，可能该版本的 jar 尚未上传");
        }
        return r;
    }

    if (Util::FileSize(partPath) == 0) {
        Log::Write(L"下载后文件为空");
        Util::DeleteFileQuiet(partPath);
        return StepResult::FileError;
    }

    

    

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
            Log::WriteUtf8("下载内容哈希不符！期望 %s，实际 %s", expected.c_str(), actual.c_str());

            

            Log::WriteF(L"下载文件大小: %llu 字节（应等于期望哈希对应文件的大小）",
                        static_cast<unsigned long long>(Util::FileSize(partPath)));
            Log::WriteF(L"下载地址: %s", url.c_str());
            Log::WriteF(L"可信哈希来源: %s", knownOfficial ? L"官方 .sha256" : L"引导器内置表");
            Util::DeleteFileQuiet(partPath);

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
            Log::Write(L"下载内容哈希校验通过");
        }
    } else {
        Log::Write(L"没有可用的官方哈希（.sha256 不存在且不在内置表），跳过下载后校验");
    }

    Util::DeleteFileQuiet(jarPath);
    if (!MoveFileExW(partPath.c_str(), jarPath.c_str(), MOVEFILE_REPLACE_EXISTING)) {
        Log::WriteF(L"重命名下载文件失败: %s -> %s (err=%lu)",
                    partPath.c_str(), jarPath.c_str(), GetLastError());
        Util::DeleteFileQuiet(partPath);
        return StepResult::FileError;
    }

    
    MarkSelfDownloaded(jarPath);

    outJarPath = jarPath;
    return StepResult::Ok;
}

static std::string ReadTrustedHash(const AppPaths& paths, const std::wstring& version) {

    

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

        

        {

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

        Log::WriteF(L"无法计算 jar 哈希，跳过校验: %s", jarPath.c_str());
        return StepResult::Ok;
    }
    Log::WriteUtf8("jar 哈希: %s = %s", version.c_str(), actual.c_str());

    std::string official = QueryOfficialSha256(version);
    if (!official.empty()) {
        if (Hs::EqualsIgnoreCase(actual, official)) {
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

    if (SelectedFileIsOurs(paths, jarPath)) {
        Log::Write(L"校验放行：该文件是本机启动器登记的当前运行文件");
        return StepResult::Ok;
    }

    if (IsSelfDownloaded(jarPath)) {
        Log::Write(L"没有可信锚点，但文件是本次运行从官方地址下载的，放行并等待启动器自行登记");
        return StepResult::Ok;
    }

    if (SelectedFileIsOurs(paths, jarPath)) {
        Log::Write(L"校验放行：该文件是本机启动器登记的当前运行文件");
        return StepResult::Ok;
    }

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

StepResult Launch(const std::wstring& javawPath,
                  const std::wstring& jarPath,
                  const std::wstring& workDir,
                  ProgressFn progress,
                  DWORD* outChildPid,
                  HANDLE* outChildProcess) {

    (void)progress;

    if (!Util::IsFile(javawPath)) {
        Log::WriteF(L"javaw 不存在: %s", javawPath.c_str());
        return StepResult::JavaNotFound;
    }
    if (!Util::IsFile(jarPath)) {
        Log::WriteF(L"jar 不存在: %s", jarPath.c_str());
        return StepResult::FileError;
    }

    std::wstring cmd = L"\"";
    cmd += javawPath;
    cmd += L"\" -jar \"";
    cmd += jarPath;
    cmd += L"\"";

    Log::WriteF(L"启动命令: %s", cmd.c_str());

    

    

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
        hChildOut = nullptr;    
        Log::Write(L"无法创建子进程输出文件，改为不重定向");
    }

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    ZeroMemory(&pi, sizeof(pi));
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_SHOWDEFAULT;  

    if (hChildOut) {
        si.dwFlags |= STARTF_USESTDHANDLES;
        si.hStdOutput = hChildOut;
        si.hStdError  = hChildOut;
        si.hStdInput  = nullptr;
    }

    
    std::wstring mutableCmd = cmd;
    wchar_t* cmdLine = &mutableCmd[0];

    BOOL ok = CreateProcessW(
            javawPath.c_str(),                  
            cmdLine,                            
            nullptr, nullptr,                   
            hChildOut ? TRUE : FALSE,           
            0,                                  
            nullptr,                            
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

    if (hChildOut) {
        CloseHandle(hChildOut);
        hChildOut = nullptr;
    }

    Log::WriteF(L"已启动主程序 (PID=%lu)", pi.dwProcessId);

    CloseHandle(pi.hThread);

    if (outChildPid) {
        *outChildPid = pi.dwProcessId;
    }

    
    if (outChildProcess) {
        *outChildProcess = pi.hProcess;
    } else {
        CloseHandle(pi.hProcess);
    }

    return StepResult::Ok;
}

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

        DWORD exitCode = 0;
        if (GetExitCodeProcess(childProcess, &exitCode) && exitCode != STILL_ACTIVE) {
            if (exitCode != 0) {
                Log::WriteF(L"子进程异常退出 (exit=%lu)，判定为启动失败", exitCode);
                setWhy(L"启动器进程异常退出");
                return WaitResult::Crashed;
            }

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
        return true;    
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

} 
