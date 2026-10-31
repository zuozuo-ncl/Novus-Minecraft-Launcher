#include "common.h"

#include <winhttp.h>
#include <cstdio>

namespace {

constexpr DWORD kChunkSize = 64 * 1024;     
constexpr DWORD kResolveTimeoutMs = 15000;
constexpr DWORD kConnectTimeoutMs = 20000;
constexpr DWORD kSendTimeoutMs    = 30000;
constexpr DWORD kRecvTimeoutMs    = 60000;

std::wstring ApplyMockRedirect(const std::wstring& url) {
    DWORD n = GetEnvironmentVariableW(L"MCLAUNCHER_HTTP_MOCK", nullptr, 0);
    if (n == 0) {
        return url;
    }
    std::wstring spec(n, L'\0');
    DWORD got = GetEnvironmentVariableW(L"MCLAUNCHER_HTTP_MOCK", &spec[0], n);
    spec.resize(got);

    std::wstring out = url;
    size_t start = 0;
    while (start <= spec.size()) {
        size_t semi = spec.find(L';', start);
        std::wstring rule = spec.substr(start, semi == std::wstring::npos
                                                   ? std::wstring::npos : semi - start);
        size_t eq = rule.find(L'=');
        if (eq != std::wstring::npos) {
            std::wstring from = rule.substr(0, eq);
            std::wstring to = rule.substr(eq + 1);
            if (!from.empty() && out.compare(0, from.size(), from) == 0) {
                std::wstring rest = out.substr(from.size());
                out = to + rest;

                if (!to.empty() && to.back() != L'/' && !rest.empty() &&
                    rest[0] != L'/') {
                    out.insert(to.size(), L"/");
                }
                Log::WriteF(L"[诊断] 请求已改写: %s -> %s", url.c_str(), out.c_str());
            }
        }
        if (semi == std::wstring::npos) {
            break;
        }
        start = semi + 1;
    }
    return out;
}

struct WinHttpHandle {
    HINTERNET h = nullptr;
    WinHttpHandle() = default;
    explicit WinHttpHandle(HINTERNET handle) : h(handle) {}
    ~WinHttpHandle() { if (h) WinHttpCloseHandle(h); }
    WinHttpHandle(const WinHttpHandle&) = delete;
    WinHttpHandle& operator=(const WinHttpHandle&) = delete;
    WinHttpHandle(WinHttpHandle&& o) noexcept : h(o.h) { o.h = nullptr; }
    WinHttpHandle& operator=(WinHttpHandle&& o) noexcept {
        if (this != &o) {
            if (h) WinHttpCloseHandle(h);
            h = o.h;
            o.h = nullptr;
        }
        return *this;
    }
    operator HINTERNET() const { return h; }
    bool valid() const { return h != nullptr; }
};

static void DrainResponse(HINTERNET hRequest) {
    DWORD available = 0;
    BYTE dummy[4096];
    while (WinHttpQueryDataAvailable(hRequest, &available) && available > 0) {
        DWORD read = 0;
        if (!WinHttpReadData(hRequest, dummy, sizeof(dummy), &read) || read == 0) {
            break;
        }
    }
}

std::wstring DescribeWinHttpError(DWORD err) {
    switch (err) {
        case ERROR_WINHTTP_TIMEOUT:
            return L"连接超时（服务器无响应）";
        case ERROR_WINHTTP_NAME_NOT_RESOLVED:
            return L"无法解析服务器地址（DNS 失败，请检查网络）";
        case ERROR_WINHTTP_CANNOT_CONNECT:
            return L"无法连接到服务器（可能被防火墙拦截或服务器不可用）";
        case ERROR_WINHTTP_CONNECTION_ERROR:
            return L"连接被中断";
        case ERROR_WINHTTP_SECURE_FAILURE:
            return L"HTTPS 证书校验失败";
        case ERROR_WINHTTP_INVALID_URL:
            return L"下载地址无效";
        case ERROR_WINHTTP_RESEND_REQUEST:
            return L"需要重新发送请求";
        case ERROR_WINHTTP_CLIENT_AUTH_CERT_NEEDED:
            return L"服务器要求客户端证书";
        case ERROR_WINHTTP_OPERATION_CANCELLED:
            return L"操作被取消";
        default: {
            wchar_t buf[256];
            _snwprintf_s(buf, _countof(buf), _TRUNCATE,
                         L"网络错误（代码 %lu）", err);
            return buf;
        }
    }
}

StepResult OpenRequest(const std::wstring& url,
                       const std::wstring& method,
                       WinHttpHandle& outSession,
                       WinHttpHandle& outConnect,
                       WinHttpHandle& outRequest,
                       std::wstring& errMsg) {

    const std::wstring effectiveUrl = ApplyMockRedirect(url);

    std::wstring host, path;
    INTERNET_PORT port = 0;
    bool https = false;

    if (!Http::ParseUrl(effectiveUrl, host, port, path, https)) {
        errMsg = L"下载地址格式不正确: " + url;
        return StepResult::ParseError;
    }

    outSession = WinHttpHandle(WinHttpOpen(
            L"MCLauncherBootstrap/1.0",     
            WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
            WINHTTP_NO_PROXY_NAME,
            WINHTTP_NO_PROXY_BYPASS,
            0));
    if (!outSession.valid()) {
        errMsg = L"初始化网络组件失败: " + Http::DescribeLastError(GetLastError());
        return StepResult::NetworkError;
    }

    WinHttpSetTimeouts(outSession, kResolveTimeoutMs, kConnectTimeoutMs,
                       kSendTimeoutMs, kRecvTimeoutMs);

    outConnect = WinHttpHandle(WinHttpConnect(outSession,
                                              host.c_str(),
                                              port,
                                              0));
    if (!outConnect.valid()) {
        errMsg = L"连接服务器失败: " + DescribeWinHttpError(GetLastError())
                 + L"\n目标: " + host;
        return StepResult::NetworkError;
    }

    DWORD flags = https ? WINHTTP_FLAG_SECURE : 0;
    outRequest = WinHttpHandle(WinHttpOpenRequest(outConnect,
                                                  method.c_str(),
                                                  path.c_str(),
                                                  nullptr,
                                                  WINHTTP_NO_REFERER,
                                                  WINHTTP_DEFAULT_ACCEPT_TYPES,
                                                  flags));
    if (!outRequest.valid()) {
        errMsg = L"创建请求失败: " + DescribeWinHttpError(GetLastError());
        return StepResult::NetworkError;
    }

    
    DWORD optFlags = WINHTTP_DISABLE_REDIRECTS | WINHTTP_DISABLE_KEEP_ALIVE;

    DWORD secureProtocols = WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_2;
    WinHttpSetOption(outSession, WINHTTP_OPTION_SECURE_PROTOCOLS,
                     &secureProtocols, sizeof(secureProtocols));
    WinHttpSetOption(outRequest, WINHTTP_OPTION_DISABLE_FEATURE,
                     &optFlags, sizeof(optFlags));

    return StepResult::Ok;
}

StepResult SendAndReceive(const std::wstring& url,
                          const std::wstring& method,
                          WinHttpHandle& outSession,
                          WinHttpHandle& outConnect,
                          WinHttpHandle& outRequest,
                          DWORD& outStatus,
                          std::wstring& errMsg,
                          int redirectDepth = 0) {
    StepResult r = OpenRequest(url, method, outSession, outConnect, outRequest, errMsg);
    if (r != StepResult::Ok) {
        return r;
    }

    if (!WinHttpSendRequest(outRequest,
                            WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                            WINHTTP_NO_REQUEST_DATA, 0,
                            0, 0)) {
        errMsg = L"发送请求失败: " + DescribeWinHttpError(GetLastError());
        Http::SetLastNetworkDetail(errMsg);
        return StepResult::NetworkError;
    }

    if (!WinHttpReceiveResponse(outRequest, nullptr)) {
        errMsg = L"接收响应失败: " + DescribeWinHttpError(GetLastError());
        Http::SetLastNetworkDetail(errMsg);
        return StepResult::NetworkError;
    }

    DWORD status = 0;
    DWORD len = sizeof(status);
    if (!WinHttpQueryHeaders(outRequest,
                             WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                             WINHTTP_HEADER_NAME_BY_INDEX,
                             &status, &len,
                             WINHTTP_NO_HEADER_INDEX)) {
        errMsg = L"无法读取服务器状态码";
        Http::SetLastNetworkDetail(errMsg);
        return StepResult::NetworkError;
    }
    outStatus = status;

    if (status >= 300 && status < 400) {
        if (redirectDepth >= 5) {
            errMsg = L"重定向次数过多";
            Http::SetLastNetworkDetail(errMsg);
            return StepResult::NetworkError;
        }

        wchar_t locBuf[2048] = {0};
        DWORD locLen = sizeof(locBuf) - sizeof(wchar_t);
        if (!WinHttpQueryHeaders(outRequest, WINHTTP_QUERY_LOCATION,
                                 WINHTTP_HEADER_NAME_BY_INDEX,
                                 locBuf, &locLen, WINHTTP_NO_HEADER_INDEX)) {
            errMsg = L"服务器要求重定向但未给出目标地址";
            return StepResult::NetworkError;
        }

        std::wstring location = locBuf;

        if (location.rfind(L"http://", 0) != 0 &&
            location.rfind(L"https://", 0) != 0) {
            std::wstring host, path;
            INTERNET_PORT port = 0;
            bool https = false;
            Http::ParseUrl(url, host, port, path, https);
            std::wstring base = https ? L"https://" : L"http://";
            base += host;

            if (!((https && port == 443) || (!https && port == 80))) {
                base += L":" + std::to_wstring(port);
            }
            if (location.empty() || location[0] != L'/') {
                location = L"/" + location;
            }
            location = base + location;
        }

        Log::WriteF(L"跟随重定向 -> %s", location.c_str());

        DrainResponse(outRequest);

        outRequest = WinHttpHandle();
        outConnect = WinHttpHandle();
        outSession = WinHttpHandle();
        return SendAndReceive(location, method,
                              outSession, outConnect, outRequest,
                              outStatus, errMsg, redirectDepth + 1);
    }

    return StepResult::Ok;
}

} 

std::wstring BuildProgressText(const std::wstring& name,
                               uint64_t done, uint64_t total, double bps) {
    auto human = [](uint64_t bytes) -> std::wstring {
        const wchar_t* units[] = { L"B", L"KB", L"MB", L"GB" };
        double v = static_cast<double>(bytes);
        int u = 0;
        while (v >= 1024.0 && u < 3) {
            v /= 1024.0;
            ++u;
        }
        wchar_t buf[64];
        _snwprintf_s(buf, _countof(buf), _TRUNCATE, L"%.1f %s", v, units[u]);
        return buf;
    };

    std::wstring text;

    if (!name.empty()) {
        text += name;
        text += L"    ";
    }

    if (total > 0) {
        text += human(done);
        text += L" / ";
        text += human(total);
    } else {
        text += human(done);
    }

    if (bps > 1.0 && total > done) {
        text += L"    ";
        text += human(static_cast<uint64_t>(bps));
        text += L"/s";

        uint64_t remainBytes = total - done;
        uint64_t seconds = static_cast<uint64_t>(remainBytes / bps);
        if (seconds > 0 && seconds < 3600) {
            wchar_t buf[64];
            _snwprintf_s(buf, _countof(buf), _TRUNCATE,
                         L"    剩余 %llu 秒", seconds);
            text += buf;
        }
    }

    return text;
}

namespace Http {

static std::wstring g_lastNetworkDetail;

void SetLastNetworkDetail(const std::wstring& detail) {
    g_lastNetworkDetail = detail;
}

std::wstring GetLastNetworkDetail() {
    return g_lastNetworkDetail;
}

std::wstring DescribeLastError(DWORD err) {
    return DescribeWinHttpError(err);
}

bool ParseUrl(const std::wstring& url,
              std::wstring& host,
              INTERNET_PORT& port,
              std::wstring& path,
              bool& https) {
    host.clear();
    path.clear();
    port = 0;
    https = false;

    std::wstring rest;

    if (url.rfind(L"https://", 0) == 0) {
        https = true;
        port = 443;
        rest = url.substr(8);
    } else if (url.rfind(L"http://", 0) == 0) {
        https = false;
        port = 80;
        rest = url.substr(7);
    } else {

        https = false;
        port = 80;
        rest = url;
    }

    size_t slash = rest.find(L'/');
    std::wstring hostPort;
    if (slash == std::wstring::npos) {
        hostPort = rest;
        path = L"/";
    } else {
        hostPort = rest.substr(0, slash);
        path = rest.substr(slash);
    }

    if (hostPort.empty()) {
        return false;
    }

    size_t colon = hostPort.find(L':');
    if (colon != std::wstring::npos) {
        host = hostPort.substr(0, colon);
        std::wstring portStr = hostPort.substr(colon + 1);
        if (!portStr.empty()) {
            long p = wcstol(portStr.c_str(), nullptr, 10);
            if (p > 0 && p <= 65535) {
                port = static_cast<INTERNET_PORT>(p);
            }
        }
    } else {
        host = hostPort;
    }

    return !host.empty();
}

StepResult GetText(const std::wstring& url,
                   std::wstring& outText,
                   DWORD ) {
    outText.clear();

    WinHttpHandle session, connect, request;
    DWORD status = 0;
    std::wstring errMsg;

    StepResult r = SendAndReceive(url, L"GET", session, connect, request,
                                  status, errMsg);
    if (r != StepResult::Ok) {
        Log::WriteF(L"GetText 失败: %s", errMsg.c_str());
        return r;
    }

    if (status < 200 || status >= 300) {
        Log::WriteF(L"GetText HTTP %lu", status);
        DrainResponse(request);  
        return StepResult::HttpError;
    }

    std::string utf8;
    std::vector<char> buf(kChunkSize);

    for (;;) {
        DWORD avail = 0;
        if (!WinHttpQueryDataAvailable(request, &avail)) {
            DrainResponse(request);
            return StepResult::NetworkError;
        }
        if (avail == 0) {
            break;
        }

        DWORD toRead = (avail < kChunkSize) ? avail : kChunkSize;
        DWORD read = 0;
        if (!WinHttpReadData(request, buf.data(), toRead, &read)) {
            DrainResponse(request);
            return StepResult::NetworkError;
        }
        if (read == 0) {
            break;
        }
        utf8.append(buf.data(), read);
    }

    if (utf8.empty()) {
        outText.clear();
        return StepResult::Ok;
    }
    int wlen = MultiByteToWideChar(CP_UTF8, 0, utf8.data(),
                                   static_cast<int>(utf8.size()),
                                   nullptr, 0);
    if (wlen <= 0) {
        DrainResponse(request);
        return StepResult::ParseError;
    }
    std::wstring wide(wlen, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8.data(),
                        static_cast<int>(utf8.size()),
                        wide.data(), wlen);
    outText = wide;
    return StepResult::Ok;
}

StepResult DownloadFile(const std::wstring& url,
                        const std::wstring& destPath,
                        ProgressFn progress,
                        volatile bool* cancelFlag) {
    Log::WriteF(L"开始下载: %s", url.c_str());

    size_t pos = destPath.find_last_of(L"\\/");
    if (pos != std::wstring::npos) {
        Util::CreateDirectories(destPath.substr(0, pos));
    }

    WinHttpHandle session, connect, request;
    DWORD status = 0;
    std::wstring errMsg;

    StepResult r = SendAndReceive(url, L"GET", session, connect, request,
                                  status, errMsg);
    if (r != StepResult::Ok) {
        Log::WriteF(L"下载失败: %s", errMsg.c_str());
        return r;
    }

    if (status < 200 || status >= 300) {
        Log::WriteF(L"下载失败，HTTP %lu（%s）", status, url.c_str());

        DrainResponse(request);  
        return (status == 404) ? StepResult::HttpError : StepResult::HttpError;
    }

    uint64_t total = 0;
    {
        wchar_t lenBuf[64] = {0};
        DWORD lenSize = sizeof(lenBuf) - sizeof(wchar_t);
        if (WinHttpQueryHeaders(request, WINHTTP_QUERY_CONTENT_LENGTH,
                                WINHTTP_HEADER_NAME_BY_INDEX,
                                lenBuf, &lenSize, WINHTTP_NO_HEADER_INDEX)) {
            total = _wcstoui64(lenBuf, nullptr, 10);
        }
    }

    

    

    std::wstring partPath = destPath;
    Util::DeleteFileQuiet(partPath);

    HANDLE hFile = CreateFileW(partPath.c_str(),
                               GENERIC_WRITE,
                               0,                 
                               nullptr,
                               CREATE_ALWAYS,
                               FILE_ATTRIBUTE_NORMAL,
                               nullptr);
    if (hFile == INVALID_HANDLE_VALUE) {
        DWORD err = GetLastError();
        Log::WriteF(L"创建文件失败: %s (err=%lu)", partPath.c_str(), err);
        errMsg = L"无法创建文件（磁盘空间不足或权限不足）：\n" + partPath;
        return (err == ERROR_ACCESS_DENIED)
                   ? StepResult::PermissionError
                   : StepResult::FileError;
    }

    std::vector<char> buf(kChunkSize);
    uint64_t done = 0;
    StepResult result = StepResult::Ok;

    
    std::wstring displayName = Util::FileName(url);

    size_t q = displayName.find(L'?');
    if (q != std::wstring::npos) {
        displayName = displayName.substr(0, q);
    }

    DWORD lastTick = 0;        
    uint64_t lastBytes = 0;    
    double lastSpeed = 0.0;    

    for (;;) {

        if (cancelFlag && *cancelFlag) {
            result = StepResult::Cancelled;
            break;
        }

        DWORD avail = 0;
        if (!WinHttpQueryDataAvailable(request, &avail)) {
            Log::WriteF(L"读取数据失败: %s",
                        DescribeWinHttpError(GetLastError()).c_str());
            result = StepResult::NetworkError;
            break;
        }
        if (avail == 0) {
            break;      
        }

        DWORD toRead = (avail < kChunkSize) ? avail : kChunkSize;
        DWORD read = 0;
        if (!WinHttpReadData(request, buf.data(), toRead, &read)) {
            Log::WriteF(L"读取数据失败: %s",
                        DescribeWinHttpError(GetLastError()).c_str());
            result = StepResult::NetworkError;
            break;
        }
        if (read == 0) {
            break;
        }

        DWORD written = 0;
        if (!WriteFile(hFile, buf.data(), read, &written, nullptr)
            || written != read) {
            Log::WriteF(L"写文件失败: %s (err=%lu)", partPath.c_str(),
                        GetLastError());
            result = StepResult::FileError;
            break;
        }

        done += read;

        

        
        if (progress) {
            DWORD nowTick = GetTickCount();

            if (lastTick == 0) {
                lastTick = nowTick;
                lastBytes = 0;
            } else if (nowTick - lastTick >= 500) {
                DWORD elapsed = nowTick - lastTick;
                uint64_t deltaBytes = done - lastBytes;

                double bps = elapsed > 0
                        ? static_cast<double>(deltaBytes) * 1000.0 / elapsed
                        : 0.0;

                lastSpeed = bps;
                lastTick = nowTick;
                lastBytes = done;
            }

            std::wstring stage = BuildProgressText(
                    displayName, done, total, lastSpeed);

            progress(stage, done, total);
        }
    }

    
    DrainResponse(request);

    FlushFileBuffers(hFile);
    CloseHandle(hFile);

    if (result != StepResult::Ok) {
        Util::DeleteFileQuiet(partPath);
        return result;
    }

    if (total > 0 && done != total) {
        Log::WriteF(L"下载不完整：期望 %llu 字节，实际 %llu 字节",
                    static_cast<unsigned long long>(total),
                    static_cast<unsigned long long>(done));
        Util::DeleteFileQuiet(partPath);
        return StepResult::NetworkError;
    }

    

    

    

    

    {
        HANDLE hCheck = CreateFileW(partPath.c_str(), GENERIC_READ,
                                    FILE_SHARE_READ, nullptr, OPEN_EXISTING,
                                    FILE_ATTRIBUTE_NORMAL, nullptr);
        if (hCheck != INVALID_HANDLE_VALUE) {
            unsigned char magic[4] = {0};
            DWORD got = 0;
            BOOL okRead = ReadFile(hCheck, magic, 4, &got, nullptr);
            CloseHandle(hCheck);

            if (okRead && got >= 2) {

                bool looksHtml = (magic[0] == '<');

                bool looksZip = (magic[0] == 'P' && magic[1] == 'K');

                if (looksHtml) {
                    Log::WriteF(L"下载到的内容是网页而非文件（服务器返回了错误页）: %s",
                                url.c_str());
                    Util::DeleteFileQuiet(partPath);
                    DrainResponse(request);  
                    return StepResult::HttpError;
                }

                

                
                std::wstring kindName = destPath;
                if (Util::EndsWithNoCase(kindName, L".part")) {
                    kindName.resize(kindName.size() - 5);
                }
                if (!looksZip && (Util::EndsWithNoCase(kindName, L".zip")
                                  || Util::EndsWithNoCase(kindName, L".jar"))) {
                    Log::WriteF(L"文件头不是 ZIP 签名（前两字节: 0x%02X 0x%02X）: %s",
                                magic[0], magic[1], url.c_str());
                    Util::DeleteFileQuiet(partPath);
                    DrainResponse(request);  
                    return StepResult::HttpError;
                }
            }
        }
    }

    

    

    if (_wcsicmp(partPath.c_str(), destPath.c_str()) != 0) {
        Util::DeleteFileQuiet(destPath);
        if (!MoveFileExW(partPath.c_str(), destPath.c_str(),
                         MOVEFILE_REPLACE_EXISTING)) {
            Log::WriteF(L"重命名文件失败: %s -> %s (err=%lu)",
                        partPath.c_str(), destPath.c_str(), GetLastError());
            Util::DeleteFileQuiet(partPath);
            return StepResult::FileError;
        }
    }

    Log::WriteF(L"下载完成: %s（%llu 字节）", destPath.c_str(),
                static_cast<unsigned long long>(done));
    return StepResult::Ok;
}

} 
