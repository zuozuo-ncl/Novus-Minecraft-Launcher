// ============================================================================
// 为什么用 WinHTTP 而不是 WinINet：
#include "common.h"

#include <winhttp.h>
#include <cstdio>


namespace {

using Http::AllowRedirectFn;   // 内部辅助函数要引用它

constexpr DWORD kChunkSize = 64 * 1024;     // 64KB
constexpr DWORD kResolveTimeoutMs = 15000;
constexpr DWORD kConnectTimeoutMs = 20000;
constexpr DWORD kSendTimeoutMs    = 30000;
constexpr DWORD kRecvTimeoutMs    = 60000;

// ---------------------------------------------------------------------------
// 历史问题：这个钩子在正式版里也会生效，任何本机进程只要写一个用户级
std::wstring ApplyMockRedirect(const std::wstring& url) {
#if !defined(MCLAUNCHER_DEBUG_MOCK)
    (void)url;
    return url;
#else
    DWORD n = GetEnvironmentVariableW(L"MCLAUNCHER_HTTP_MOCK", nullptr, 0);
    if (n == 0) {
        return url;
    }
    std::wstring spec(n, static_cast<wchar_t>(0));
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
            // 即使调试构建，也只允许把 https 改写到 https（禁止降级到明文）
            std::wstring from = rule.substr(0, eq);
            std::wstring to = rule.substr(eq + 1);
            const bool fromHttps = from.rfind(L"https://", 0) == 0;
            const bool toHttps = to.rfind(L"https://", 0) == 0;
            if (!from.empty() && (!fromHttps || toHttps)
                && out.compare(0, from.size(), from) == 0) {
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
#endif
}

// RAII：自动关闭 WinHTTP 句柄
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

// ---------------------------------------------------------------------------
//  排空响应体：在关闭请求句柄前必须读到 QueryDataAvailable 返回 0，
//  否则 WinHTTP 底层连接池会被标记为脏状态，污染后续全新 Session。
// ---------------------------------------------------------------------------
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

// 把 WinHTTP 的错误码转成可读说明
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

// 打开会话 + 连接 + 请求，返回已发送的请求句柄
StepResult OpenRequest(const std::wstring& url,
                       const std::wstring& method,
                       WinHttpHandle& outSession,
                       WinHttpHandle& outConnect,
                       WinHttpHandle& outRequest,
                       std::wstring& errMsg,
                       AllowRedirectFn allowUrl = nullptr) {
    // 诊断钩子：环境变量存在时可能改写到别的地址（正常用户不生效）
    const std::wstring effectiveUrl = ApplyMockRedirect(url);

    // 首跳同样要过白名单（下载 JRE 时，Adoptium API 返回的 link 字段也是「外部给的地址」）
    if (allowUrl && !allowUrl(effectiveUrl)) {
        errMsg = L"下载地址不在允许的源列表内，已拒绝: " + effectiveUrl;
        Log::WriteF(L"拒绝下载地址: %s", effectiveUrl.c_str());
        return StepResult::NetworkError;
    }

    std::wstring host, path;
    INTERNET_PORT port = 0;
    bool https = false;

    if (!Http::ParseUrl(effectiveUrl, host, port, path, https)) {
        errMsg = L"下载地址格式不正确: " + url;
        return StepResult::ParseError;
    }

    outSession = WinHttpHandle(WinHttpOpen(
            L"MCLauncherBootstrap/1.0",     // User-Agent
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

    // 关闭自动重定向，自己控制（便于感知 302 并跟随）
    // 禁用 Keep-Alive：避免连接复用导致跨调用污染（前一个请求未读完响应体就关闭句柄）
    DWORD optFlags = WINHTTP_DISABLE_REDIRECTS | WINHTTP_DISABLE_KEEP_ALIVE;
    // TLS 1.2+（部分 CDN 要求）
    DWORD secureProtocols = WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_2;
    WinHttpSetOption(outSession, WINHTTP_OPTION_SECURE_PROTOCOLS,
                     &secureProtocols, sizeof(secureProtocols));
    WinHttpSetOption(outRequest, WINHTTP_OPTION_DISABLE_FEATURE,
                     &optFlags, sizeof(optFlags));

    return StepResult::Ok;
}

// 发送请求并读取状态码；自动跟随 3xx 重定向（最多 5 跳）
StepResult SendAndReceive(const std::wstring& url,
                          const std::wstring& method,
                          WinHttpHandle& outSession,
                          WinHttpHandle& outConnect,
                          WinHttpHandle& outRequest,
                          DWORD& outStatus,
                          std::wstring& errMsg,
                          int redirectDepth = 0,
                          AllowRedirectFn allowRedirect = nullptr) {
    StepResult r = OpenRequest(url, method, outSession, outConnect, outRequest, errMsg, allowRedirect);
    if (r != StepResult::Ok) {
        return r;
    }

    // 跟随重定向时，WinHTTP 会自动添加 Referer 等，这里用默认
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

    // 处理重定向
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

        // 相对路径 -> 补全为绝对地址
        if (location.rfind(L"http://", 0) != 0 &&
            location.rfind(L"https://", 0) != 0) {
            std::wstring host, path;
            INTERNET_PORT port = 0;
            bool https = false;
            Http::ParseUrl(url, host, port, path, https);
            std::wstring base = https ? L"https://" : L"http://";
            base += host;
            // 非标准端口要带上
            if (!((https && port == 443) || (!https && port == 80))) {
                base += L":" + std::to_wstring(port);
            }
            if (location.empty() || location[0] != L'/') {
                location = L"/" + location;
            }
            location = base + location;
        }

        // 调用方给的域名白名单：不允许就中止（例如 JRE 包只允许官方/CDN 域名，
        // 且必须仍是 https）。以前这里无条件跟随，包括降级到 http://
        if (allowRedirect && !allowRedirect(location)) {
            errMsg = L"重定向目标不在允许的下载源列表内，已拒绝: " + location;
            Log::WriteF(L"拒绝重定向: %s", location.c_str());
            DrainResponse(outRequest);
            return StepResult::NetworkError;
        }
        const bool locLowerHttps = location.rfind(L"https://", 0) == 0
                                   || location.rfind(L"HTTPS://", 0) == 0;
        const bool curHttps = url.rfind(L"https://", 0) == 0
                              || url.rfind(L"HTTPS://", 0) == 0;
        if (curHttps && !locLowerHttps) {
            errMsg = L"拒绝把 HTTPS 请求降级重定向到非 HTTPS 地址: " + location;
            Log::WriteF(L"拒绝降级重定向: %s", location.c_str());
            DrainResponse(outRequest);
            return StepResult::NetworkError;
        }

        Log::WriteF(L"跟随重定向 -> %s", location.c_str());

        // 释放旧句柄前，必须排空响应体，避免污染连接池
        DrainResponse(outRequest);

        // 释放旧句柄，递归请求新地址
        outRequest = WinHttpHandle();
        outConnect = WinHttpHandle();
        outSession = WinHttpHandle();
        // 跟随重定向可能改变域名：需要白名单的调用方（JRE 下载）据此拒绝
        return SendAndReceive(location, method,
                              outSession, outConnect, outRequest,
                              outStatus, errMsg, redirectDepth + 1, allowRedirect);
    }

    return StepResult::Ok;
}

} // namespace

// ============================================================================
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

    // 速度与剩余时间
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

// ---------------------------------------------------------------------------
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

// ---------------------------------------------------------------------------
//  URL 解析
// ---------------------------------------------------------------------------
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
        // 无协议：按 http 处理（例如 "host:port/path"）
        https = false;
        port = 80;
        rest = url;
    }

    // 分离 host[:port] 与 /path
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

    // 解析端口
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

// ---------------------------------------------------------------------------
//  GET 文本（用于 JSON API）
// ---------------------------------------------------------------------------
StepResult GetText(const std::wstring& url,
                   std::wstring& outText,
                   DWORD /*timeoutMs*/) {
    outText.clear();

    WinHttpHandle session, connect, request;
    DWORD status = 0;
    std::wstring errMsg;

    StepResult r = SendAndReceive(url, L"GET", session, connect, request,
                                  status, errMsg, 0, nullptr);
    if (r != StepResult::Ok) {
        Log::WriteF(L"GetText 失败: %s", errMsg.c_str());
        return r;
    }

    if (status < 200 || status >= 300) {
        Log::WriteF(L"GetText HTTP %lu", status);
        DrainResponse(request);  // 排空响应体，避免污染连接池
        return StepResult::HttpError;
    }

    // 逐块读取，按 UTF-8 累积（调用方的 JSON 都是 ASCII/UTF-8）
    //
    // 响应体上限：版本接口、.sha256 都只有几 KB。以前没有任何上限，
    // 被劫持的服务器返回超大响应体会把内存吃光（32 位进程直接 bad_alloc）。
    std::string utf8;
    std::vector<char> buf(kChunkSize);
    const size_t kMaxTextBytes = 4 * 1024 * 1024;   // 4 MB

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
        if (utf8.size() > kMaxTextBytes) {
            Log::WriteF(L"响应体超过上限（%zu 字节），已中止: %s", utf8.size(), url.c_str());
            DrainResponse(request);
            return StepResult::NetworkError;
        }
    }

    // UTF-8 -> 宽字符
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

// ---------------------------------------------------------------------------
//  下载文件（分块写盘 + 进度 + 可取消）
// ---------------------------------------------------------------------------
StepResult DownloadFile(const std::wstring& url,
                        const std::wstring& destPath,
                        ProgressFn progress,
                        volatile bool* cancelFlag,
                        AllowRedirectFn allowUrl) {
    Log::WriteF(L"开始下载: %s", url.c_str());

    // 先确保目标目录存在
    size_t pos = destPath.find_last_of(L"\\/");
    if (pos != std::wstring::npos) {
        Util::CreateDirectories(destPath.substr(0, pos));
    }

    WinHttpHandle session, connect, request;
    DWORD status = 0;
    std::wstring errMsg;

    StepResult r = SendAndReceive(url, L"GET", session, connect, request,
                                  status, errMsg, 0, allowUrl);
    if (r != StepResult::Ok) {
        Log::WriteF(L"下载失败: %s", errMsg.c_str());
        return r;
    }

    if (status < 200 || status >= 300) {
        Log::WriteF(L"下载失败，HTTP %lu（%s）", status, url.c_str());
        // 404 单独提示，便于定位「服务器上还没有这个文件」
        DrainResponse(request);  // 排空响应体，避免污染连接池
        return (status == 404) ? StepResult::HttpError : StepResult::HttpError;
    }

    // 读取 Content-Length（可能缺失，此时进度显示为不确定）
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

    // 下载直接写到调用方给的路径。
    //
    // 不要在这里再加一层 ".part" 后缀：EnsureJar 已经用
    // mc-launcher-<版本>.jar.part 作为临时文件，再加会变成 .part.part，
    // 完成后改名到的是 .part —— 正式文件永远不出现，下次启动又从头下载。
    std::wstring partPath = destPath;
    Util::DeleteFileQuiet(partPath);

    HANDLE hFile = CreateFileW(partPath.c_str(),
                               GENERIC_WRITE,
                               0,                 // 独占
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

    // ---- 进度显示辅助状态 ----
    // 从 URL 里取出文件名（用于界面显示「正在下载什么」）
    std::wstring displayName = Util::FileName(url);
    // 去掉可能的查询串
    size_t q = displayName.find(L'?');
    if (q != std::wstring::npos) {
        displayName = displayName.substr(0, q);
    }

    DWORD lastTick = 0;        // 上次算速度的时刻
    uint64_t lastBytes = 0;    // 上次算速度时的字节数
    double lastSpeed = 0.0;    // 最近一次算出的速度（字节/秒）

    for (;;) {
        // 用户取消
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
            break;      // 传输结束
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

        // ---- 上报进度：带文件名、速度、剩余时间 ----
        //
        // 早期这里传的是空字符串，界面上只有一个光秃秃的进度条，
        // 用户不知道在下载什么、还要多久。现在拼出完整信息。
        if (progress) {
            DWORD nowTick = GetTickCount();

            // 每 500ms 更新一次速度/剩余时间（算得太频繁数字会乱跳）
            if (lastTick == 0) {
                lastTick = nowTick;
                lastBytes = 0;
            } else if (nowTick - lastTick >= 500) {
                DWORD elapsed = nowTick - lastTick;
                uint64_t deltaBytes = done - lastBytes;

                // 速度（字节/秒）
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

    // 无论成功还是失败，都要把响应体读干净（含分块编码尾部等），
    // 否则 WinHTTP 连接池会被标记为脏状态，污染后续全新 Session。
    DrainResponse(request);

    // 刷新到磁盘再关闭，避免掉电丢数据
    FlushFileBuffers(hFile);
    CloseHandle(hFile);

    if (result != StepResult::Ok) {
        Util::DeleteFileQuiet(partPath);
        return result;
    }

    // 完整性校验：如果服务器给了长度，就对一下
    if (total > 0 && done != total) {
        Log::WriteF(L"下载不完整：期望 %llu 字节，实际 %llu 字节",
                    static_cast<unsigned long long>(total),
                    static_cast<unsigned long long>(done));
        Util::DeleteFileQuiet(partPath);
        return StepResult::NetworkError;
    }

    // ---- 内容校验 ----
    // 这里检查文件头：ZIP 文件必须以 'P','K' 开头（PKZIP 签名）。
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
                // 检测 HTML（错误页）：以 '<' 开头，或包含 "<!DOCTYPE"
                bool looksHtml = (magic[0] == '<');

                // 检测 ZIP 签名
                bool looksZip = (magic[0] == 'P' && magic[1] == 'K');

                if (looksHtml) {
                    Log::WriteF(L"下载到的内容是网页而非文件（服务器返回了错误页）: %s",
                                url.c_str());
                    Util::DeleteFileQuiet(partPath);
                    DrainResponse(request);  // 排空响应体，避免污染连接池
                    return StepResult::HttpError;
                }

                // 只有当扩展名提示应该是 ZIP/JAR 时才强制要求 PK 头
                //
                // 用「去掉结尾 .part」的名字判断：EnsureJar 传进来的目标
                // 是 mc-launcher-x.y.z.jar.part，按原名以 .jar 结尾的规则匹配不上
                std::wstring kindName = destPath;
                if (Util::EndsWithNoCase(kindName, L".part")) {
                    kindName.resize(kindName.size() - 5);
                }
                if (!looksZip && (Util::EndsWithNoCase(kindName, L".zip")
                                  || Util::EndsWithNoCase(kindName, L".jar"))) {
                    Log::WriteF(L"文件头不是 ZIP 签名（前两字节: 0x%02X 0x%02X）: %s",
                                magic[0], magic[1], url.c_str());
                    Util::DeleteFileQuiet(partPath);
                    DrainResponse(request);  // 排空响应体，避免污染连接池
                    return StepResult::HttpError;
                }
            }
        }
    }

    // 完成 -> 原子改名
    //
    // 只有 partPath 和 destPath 不同才需要改名。现在调用方（EnsureJar）
    // 自己已经用 .part 作临时文件，这里 partPath == destPath ——
    // 早期无条件改名会先把刚下好的文件删掉、再把它改名成自己，报 err=2。
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

} // namespace Http
