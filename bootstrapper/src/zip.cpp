// ============================================================================
//  zip.cpp —— 使用 Windows 自带能力解压 ZIP
//
//  为什么不用第三方库（如 miniz / libzip）：
//    需求明确要求避免第三方依赖导致的编译问题。
//    Windows 自带两种可靠的解压途径，按顺序尝试：
//      1) Shell.Application COM —— 原生、无额外进程
//      2) PowerShell Expand-Archive —— 兼容性最好的兜底
//
//  两者都通过 CreateProcessW 拉起，全程宽字符，中文路径安全。
// ============================================================================
#include "common.h"

#include <shlobj.h>
#include <shellapi.h>
#include <objbase.h>
#include <vector>
#include <cstring>


namespace {

// 转义单引号，供 PowerShell 单引号字符串使用
std::wstring EscapeForPowerShellSingleQuote(const std::wstring& s) {
    std::wstring out;
    out.reserve(s.size() + 8);
    for (wchar_t c : s) {
        if (c == L'\'') {
            out += L"''";       // PowerShell 中用两个单引号表示一个
        } else {
            out += c;
        }
    }
    return out;
}

// 运行一个命令并等待结束，返回退出码（-1 表示启动失败）
DWORD RunAndWait(const std::wstring& cmdLine, bool hidden = true) {
    std::wstring mutableCmd = cmdLine;      // CreateProcessW 需要可写缓冲

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    ZeroMemory(&pi, sizeof(pi));
    si.cb = sizeof(si);

    if (hidden) {
        si.dwFlags = STARTF_USESHOWWINDOW;
        si.wShowWindow = SW_HIDE;
    }

    DWORD flags = CREATE_NO_WINDOW;

    if (!CreateProcessW(nullptr,
                        mutableCmd.data(),
                        nullptr, nullptr,
                        FALSE,
                        flags,
                        nullptr,
                        nullptr,
                        &si, &pi)) {
        Log::WriteF(L"CreateProcess 失败 (err=%lu): %s",
                    GetLastError(), cmdLine.c_str());
        return static_cast<DWORD>(-1);
    }

    WaitForSingleObject(pi.hProcess, INFINITE);

    DWORD exitCode = static_cast<DWORD>(-1);
    GetExitCodeProcess(pi.hProcess, &exitCode);

    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);

    return exitCode;
}

// ---------------------------------------------------------------------------
// Shell CopyHere 是**异步**的（无消息泵时也照样立即返回 S_OK）。
// 以前把 S_OK 当「解压完成」，紧接着去找 javaw.exe 会找不到（解压还没写完）。
// 这里解析 CopyHere 的返回文件夹对象，轮询其条目数是否稳定；
// 拿不到条目数时退回「轮询目标目录字节数是否稳定」。
// ---------------------------------------------------------------------------
static unsigned long long DirByteSizeNoWait(const std::wstring& dir);

// 对象的命名里面有 Items / Count / Item —— 就是我们要找的「文件夹」对象
bool LooksLikeFolderObject(IDispatch* p) {
    if (!p) return false;
    OLECHAR* names[] = {const_cast<OLECHAR*>(L"Items"),
                        const_cast<OLECHAR*>(L"Count"),
                        const_cast<OLECHAR*>(L"Item")};
    DISPID ids[3] = {0, 0, 0};
    return SUCCEEDED(p->GetIDsOfNames(IID_NULL, names, 3, LOCALE_USER_DEFAULT, ids));
}

// 调用 p 的 name 属性（0 参数），结果写入 out
bool InvokeNoArgs(IDispatch* p, const wchar_t* name, VARIANT& out) {
    DISPID id = 0;
    OLECHAR* n = const_cast<OLECHAR*>(name);
    if (FAILED(p->GetIDsOfNames(IID_NULL, &n, 1, LOCALE_USER_DEFAULT, &id))) return false;
    DISPPARAMS dp = {nullptr, nullptr, 0, 0};
    return SUCCEEDED(p->Invoke(id, IID_NULL, LOCALE_USER_DEFAULT, DISPATCH_PROPERTYGET,
                               &dp, &out, nullptr, nullptr));
}

// 等 Shell 真正写完：轮询目标文件夹的条目数（拿不到就轮询目录字节数）直到稳定
bool WaitForShellFolderDone(IDispatch* folder, const std::wstring& destDir) {
    // 目标目录在解压前就存在（可能为空），先记录基线，避免「空目录稳定」被当成完成
    const unsigned long long baseline = DirByteSizeNoWait(destDir);
    auto shellItemCount = [&]() -> long {
        VARIANT vItems;
        VariantInit(&vItems);
        long n = -1;
        if (InvokeNoArgs(folder, L"Items", vItems) && vItems.vt == VT_DISPATCH && vItems.pdispVal) {
            VARIANT vCount;
            VariantInit(&vCount);
            if (InvokeNoArgs(vItems.pdispVal, L"Count", vCount)
                && (vCount.vt == VT_I4 || vCount.vt == VT_UI4)) {
                n = vCount.lVal;
            }
            VariantClear(&vCount);
            vItems.pdispVal->Release();
        }
        VariantClear(&vItems);
        return n;
    };

    const DWORD started = GetTickCount();
    long last = -2;
    int stable = 0;
    unsigned long long lastBytes = ~0ull;
    while (GetTickCount() - started < 10 * 60 * 1000) {
        Sleep(300);
        long n = folder ? shellItemCount() : -1;
        if (n >= 0) {
            if (n == last) {
                if (++stable >= 3) {
                    Log::WriteF(L"Shell 解压完成：顶层条目 %ld 个", n);
                    return true;
                }
            } else {
                stable = 0;
                last = n;
            }
            continue;
        }
        unsigned long long bytes = DirByteSizeNoWait(destDir);
        if (bytes == lastBytes && bytes != baseline) {
            if (++stable >= 3) {
                Log::WriteF(L"Shell 解压完成（按目录大小判定）：%llu",
                            static_cast<unsigned long long>(bytes));
                return true;
            }
        } else {
            stable = 0;
            lastBytes = bytes;
        }
    }
    Log::Write(L"等待 Shell 解压超时");
    return false;
}

// 递归统计目录内容（字节数之和 + 固定偏置，用 FindFirstFileW，不依赖 Shell）
static unsigned long long DirByteSizeNoWait(const std::wstring& dir) {
    unsigned long long total = 0;
    WIN32_FIND_DATAW fd;
    std::wstring pattern = dir;
    if (!pattern.empty() && pattern.back() != L'\\' && pattern.back() != L'/') pattern += L'\\';
    pattern += L'*';
    HANDLE h = FindFirstFileW(pattern.c_str(), &fd);
    if (h == INVALID_HANDLE_VALUE) return 0;
    do {
        if (wcscmp(fd.cFileName, L".") == 0 || wcscmp(fd.cFileName, L"..") == 0) continue;
        std::wstring child = pattern.substr(0, pattern.size() - 1) + fd.cFileName;
        if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            total += DirByteSizeNoWait(child) + 7;
        } else {
            total += ((static_cast<unsigned long long>(fd.nFileSizeHigh) << 32)
                      | fd.nFileSizeLow) + 13;
        }
    } while (FindNextFileW(h, &fd));
    FindClose(h);
    return total;
}

// ---------------------------------------------------------------------------
// 方式 1：Shell.Application COM
// ---------------------------------------------------------------------------
bool ExtractWithShellCom(const std::wstring& zipPath,
                         const std::wstring& destDir) {
    HRESULT hrInit = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    bool needUninit = SUCCEEDED(hrInit);

    // RPC_E_CHANGED_MODE 表示当前线程已是别的套间模型，仍可继续尝试
    bool ok = false;

    {
        // Shell.Application 是后期绑定对象
        CLSID clsid;
        hrInit = CLSIDFromProgID(L"Shell.Application", &clsid);
        if (SUCCEEDED(hrInit)) {
            IDispatch* pShell = nullptr;
            hrInit = CoCreateInstance(clsid, nullptr, CLSCTX_LOCAL_SERVER,
                                      IID_IDispatch,
                                      reinterpret_cast<void**>(&pShell));
            if (SUCCEEDED(hrInit) && pShell) {
                // 取 NameSpace 方法：用于获取文件夹对象
                DISPID dispidNameSpace = 0;
                OLECHAR* nameNameSpace = const_cast<OLECHAR*>(L"NameSpace");
                hrInit = pShell->GetIDsOfNames(IID_NULL, &nameNameSpace, 1,
                                               LOCALE_USER_DEFAULT,
                                               &dispidNameSpace);
                if (SUCCEEDED(hrInit)) {
                    // 取 zip 所在目录
                    size_t pos = zipPath.find_last_of(L"\\/");
                    std::wstring zipDir = (pos == std::wstring::npos)
                                              ? L"." : zipPath.substr(0, pos);
                    std::wstring zipName = (pos == std::wstring::npos)
                                               ? zipPath : zipPath.substr(pos + 1);

                    VARIANT vDir;
                    VariantInit(&vDir);
                    vDir.vt = VT_BSTR;
                    vDir.bstrVal = SysAllocString(zipDir.c_str());

                    DISPPARAMS dpDir = {nullptr, nullptr, 0, 0};
                    dpDir.rgvarg = &vDir;
                    dpDir.cArgs = 1;

                    VARIANT vFolder;
                    VariantInit(&vFolder);

                    hrInit = pShell->Invoke(dispidNameSpace, IID_NULL,
                                            LOCALE_USER_DEFAULT,
                                            DISPATCH_METHOD,
                                            &dpDir, &vFolder, nullptr, nullptr);
                    VariantClear(&vDir);

                    if (SUCCEEDED(hrInit) && vFolder.vt == VT_DISPATCH
                        && vFolder.pdispVal) {
                        IDispatch* pFolder = vFolder.pdispVal;

                        // 取 ParseName 方法：定位到 zip 文件
                        DISPID dispidParse = 0;
                        OLECHAR* nameParse = const_cast<OLECHAR*>(L"ParseName");
                        hrInit = pFolder->GetIDsOfNames(IID_NULL, &nameParse, 1,
                                                        LOCALE_USER_DEFAULT,
                                                        &dispidParse);
                        if (SUCCEEDED(hrInit)) {
                            VARIANT vName;
                            VariantInit(&vName);
                            vName.vt = VT_BSTR;
                            vName.bstrVal = SysAllocString(zipName.c_str());

                            DISPPARAMS dpName = {nullptr, nullptr, 0, 0};
                            dpName.rgvarg = &vName;
                            dpName.cArgs = 1;

                            VARIANT vItem;
                            VariantInit(&vItem);

                            hrInit = pFolder->Invoke(dispidParse, IID_NULL,
                                                     LOCALE_USER_DEFAULT,
                                                     DISPATCH_METHOD,
                                                     &dpName, &vItem,
                                                     nullptr, nullptr);
                            VariantClear(&vName);

                            if (SUCCEEDED(hrInit) && vItem.vt == VT_DISPATCH
                                && vItem.pdispVal) {
                                IDispatch* pItem = vItem.pdispVal;

                                // 取 CopyHere 方法：把内容复制到目标目录
                                DISPID dispidCopy = 0;
                                OLECHAR* nameCopy = const_cast<OLECHAR*>(L"CopyHere");
                                hrInit = pItem->GetIDsOfNames(IID_NULL, &nameCopy, 1,
                                                              LOCALE_USER_DEFAULT,
                                                              &dispidCopy);
                                if (SUCCEEDED(hrInit)) {
                                    VARIANT args[2];
                                    VariantInit(&args[0]);
                                    VariantInit(&args[1]);

                                    // 参数顺序：CopyHere(target, flags)
                                    // DISPPARAMS 中参数是倒序存放的
                                    args[0].vt = VT_I4;
                                    args[0].lVal = 4;   // 4 = 不显示进度对话框
                                    args[1].vt = VT_BSTR;
                                    args[1].bstrVal = SysAllocString(destDir.c_str());

                                    DISPPARAMS dpCopy = {nullptr, nullptr, 0, 0};
                                    dpCopy.rgvarg = args;
                                    dpCopy.cArgs = 2;

                                    VARIANT vRet;
                                    VariantInit(&vRet);

                                    hrInit = pItem->Invoke(dispidCopy, IID_NULL,
                                                           LOCALE_USER_DEFAULT,
                                                           DISPATCH_METHOD,
                                                           &dpCopy, &vRet,
                                                           nullptr, nullptr);

                                    VariantClear(&args[1]);

                                    ok = SUCCEEDED(hrInit);
                                    if (!ok) {
                                        Log::Write(L"Shell CopyHere 调用失败");
                                    }
                                    // CopyHere 成功后必须等它真正写完：
                                    // 返回值要么是脚本对象（MyPick 占位），要么是
                                    // 目标文件夹对象；我们更直接地轮询目标目录是否稳定。
                                    else if (WaitForShellFolderDone(
                                                 (vRet.vt == VT_DISPATCH && LooksLikeFolderObject(vRet.pdispVal))
                                                     ? vRet.pdispVal : nullptr,
                                                 destDir)) {
                                        Log::Write(L"Shell 解压内容已落盘");
                                    } else {
                                        ok = false;
                                    }
                                    VariantClear(&vRet);
                                }
                                pItem->Release();
                            }
                            VariantClear(&vItem);
                        }
                        pFolder->Release();
                    }
                    VariantClear(&vFolder);
                }
                pShell->Release();
            }
        }
    }

    if (needUninit) {
        CoUninitialize();
    }

    return ok;
}

// ---------------------------------------------------------------------------
// 方式 2：PowerShell Expand-Archive
// ---------------------------------------------------------------------------
bool ExtractWithPowerShell(const std::wstring& zipPath,
                           const std::wstring& destDir) {
    std::wstring cmd =
        L"powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass "
        L"-Command \"Expand-Archive -LiteralPath '";
    cmd += EscapeForPowerShellSingleQuote(zipPath);
    cmd += L"' -DestinationPath '";
    cmd += EscapeForPowerShellSingleQuote(destDir);
    cmd += L"' -Force\"";

    Log::WriteF(L"用 PowerShell 解压: %s", cmd.c_str());

    DWORD code = RunAndWait(cmd);
    if (code == 0) {
        return true;
    }
    Log::WriteF(L"PowerShell 解压退出码: %lu", code);
    return false;
}

// ---------------------------------------------------------------------------
// ZIP 条目名安全检查（防 zip-slip）
//
// Shell CopyHere 与 Expand-Archive 对 ../、绝对路径条目的处理各不相同，
// 历史上多次被利用把文件写进解压目录之外。解压前先读中央目录，
// 任何可疑条目都直接拒绝整个包。
// ---------------------------------------------------------------------------
bool IsSafeZipEntryName(const wchar_t* name) {
    if (!name || !*name) {
        return false;
    }
    std::wstring n(name);
    for (wchar_t c : n) {
        if (c < 0x20) {
            return false;
        }
    }
    // 统一分隔符后判断
    for (auto& c : n) {
        if (c == L'\\') c = L'/';
    }
    if (n[0] == L'/' || n.find(L':') != std::wstring::npos) {
        return false;       // 绝对路径 / 盘符 / NTFS 流
    }
    if (n.compare(0, 4, L"//?/") == 0 || n.compare(0, 2, L"//") == 0) {
        return false;       // UNC
    }
    size_t start = 0;
    while (start <= n.size()) {
        size_t slash = n.find(L'/', start);
        std::wstring seg = n.substr(start, slash == std::wstring::npos
                                               ? std::wstring::npos
                                               : slash - start);
        if (seg == L".." || seg == L".") {
            return false;
        }
        if (slash == std::wstring::npos) {
            break;
        }
        start = slash + 1;
    }
    return true;
}

// 从文件指定偏移读取指定字节数（按需读取，不把整个 ZIP 读进内存）
bool ReadAt(HANDLE f, unsigned long long offset, void* out, DWORD len) {
    LARGE_INTEGER pos;
    pos.QuadPart = static_cast<LONGLONG>(offset);
    if (!SetFilePointerEx(f, pos, nullptr, FILE_BEGIN)) {
        return false;
    }
    DWORD read = 0;
    return ReadFile(f, out, len, &read, nullptr) && read == len;
}

// 读 ZIP 的中央目录，逐个检查条目名；按需分段读取，不整包载入内存。
//
// 安全要点（历史缺陷）：
//   · 以前 EOCD 里「条目数 == 0xFFFF」（ZIP64 占位）时直接返回「校验通过」，
//     恶意 zip 只要改写这一个字段就能让 zip-slip 检查整体失效；
//   · 以前把整个包 malloc 进内存、ReadFile 长度按 DWORD 截断，大包直接失败。
//   现在：识别到 ZIP64 就去解析 ZIP64 EOCD 拿到真实条目数；解析不了就拒绝（fail-closed）。
bool ValidateZipEntries(const std::wstring& zipPath) {
    HANDLE f = CreateFileW(zipPath.c_str(), GENERIC_READ, FILE_SHARE_READ,
                           nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) {
        return false;
    }
    bool ok = false;
    do {
        LARGE_INTEGER size;
        if (!GetFileSizeEx(f, &size) || size.QuadPart < 22) {
            Log::Write(L"ZIP 校验失败：文件过短，不是合法 ZIP");
            break;
        }
        const unsigned long long fileSize = static_cast<unsigned long long>(size.QuadPart);

        // 尾部注释最长 65535 字节，EOCD 固定 22 字节：只需读最后 65557 字节
        unsigned long long tailLen = fileSize < 65557 ? fileSize : 65557;
        unsigned long long tailStart = fileSize - tailLen;
        std::vector<unsigned char> tail(static_cast<size_t>(tailLen), 0);
        if (!ReadAt(f, tailStart, tail.data(), static_cast<DWORD>(tailLen))) {
            Log::Write(L"ZIP 校验失败：读取文件尾部失败");
            break;
        }

        // 找 EOCD（签名 0x06054b50），从尾部往前
        const unsigned char eocdSig[4] = {0x50, 0x4b, 0x05, 0x06};
        long long eocdInTail = -1;
        for (long long i = static_cast<long long>(tailLen) - 22; i >= 0; --i) {
            if (memcmp(tail.data() + i, eocdSig, 4) == 0) {
                eocdInTail = i;
                break;
            }
        }
        if (eocdInTail < 0) {
            Log::Write(L"ZIP 校验失败：找不到中央目录");
            break;
        }
        const unsigned char* eocd = tail.data() + eocdInTail;
        const unsigned long long eocdOffset = tailStart + static_cast<unsigned long long>(eocdInTail);

        unsigned long long total = 0;
        unsigned long long cdSize = 0, cdOffset = 0;
        unsigned short total16 = 0;
        memcpy(&total16, eocd + 10, 2);          // 本盘条目数
        total = total16;
        memcpy(&cdSize, eocd + 12, 4);
        memcpy(&cdOffset, eocd + 16, 4);

        // ZIP64：任一字段为占位值时，往前找 ZIP64 EOCD locator（0x07064b50，20 字节）
        const bool needZip64 = (total16 == 0xFFFF) || (cdSize == 0xFFFFFFFF)
                               || (cdOffset == 0xFFFFFFFF) || eocdOffset < 20;
        if (needZip64) {
            unsigned char loc[20];
            if (eocdOffset < 20 || !ReadAt(f, eocdOffset - 20, loc, 20)
                || memcmp(loc, "\x50\x4b\x06\x07", 4) != 0) {
                Log::Write(L"ZIP 校验失败：ZIP64 占位但找不到 ZIP64 EOCD locator，拒绝解压");
                break;
            }
            unsigned long long z64Offset = 0;
            memcpy(&z64Offset, loc + 8, 8);
            unsigned char ze[56];
            if (z64Offset + 56 > fileSize || !ReadAt(f, z64Offset, ze, 56)
                || memcmp(ze, "\x50\x4b\x06\x06", 4) != 0) {
                Log::Write(L"ZIP 校验失败：ZIP64 EOCD 记录无效，拒绝解压");
                break;
            }
            unsigned long long zTotal = 0, zCdSize = 0, zCdOffset = 0;
            memcpy(&zTotal, ze + 32, 8);         // 本盘条目数
            memcpy(&zCdSize, ze + 40, 8);
            memcpy(&zCdOffset, ze + 48, 8);
            total = zTotal;
            cdSize = zCdSize;
            cdOffset = zCdOffset;
            Log::WriteF(L"ZIP 校验：ZIP64 包，条目数 %llu",
                        static_cast<unsigned long long>(total));
        }

        // 条目数合理性：每个中央目录记录至少 46 字节
        if (total > cdSize / 46 || cdOffset + cdSize > fileSize) {
            Log::Write(L"ZIP 校验失败：中央目录越界或条目数不合理");
            break;
        }

        // 分块读取中央目录（最多一次读 1MB）
        std::vector<unsigned char> cd;
        unsigned long long remaining = cdSize;
        if (remaining > 64ull * 1024 * 1024) {
            Log::Write(L"ZIP 校验失败：中央目录过大");
            break;
        }
        cd.resize(static_cast<size_t>(remaining));
        if (remaining > 0 && !ReadAt(f, cdOffset, cd.data(), static_cast<DWORD>(remaining))) {
            Log::Write(L"ZIP 校验失败：读取中央目录失败");
            break;
        }

        unsigned long long p = 0;
        ok = true;
        for (unsigned long long k = 0; k < total; ++k) {
            if (p + 46 > cdSize) {
                ok = false;
                Log::Write(L"ZIP 校验失败：中央目录记录截断");
                break;
            }
            const unsigned char* rec = cd.data() + p;
            if (memcmp(rec, "\x50\x4b\x01\x02", 4) != 0) {
                ok = false;
                break;
            }
            unsigned short nameLen = 0, extraLen = 0, commentLen = 0;
            memcpy(&nameLen, rec + 28, 2);
            memcpy(&extraLen, rec + 30, 2);
            memcpy(&commentLen, rec + 32, 2);
            if (p + 46 + nameLen > cdSize) {
                ok = false;
                break;
            }
            // 外部属性高 16 位是 Unix 权限：S_IFLNK = 0xA000 的符号链接条目拒绝
            unsigned long extAttr = 0;
            memcpy(&extAttr, rec + 38, 4);
            if (((extAttr >> 16) & 0xF000) == 0xA000) {
                Log::Write(L"ZIP 校验失败：包含符号链接条目");
                ok = false;
                break;
            }
            std::string name(reinterpret_cast<const char*>(rec + 46), nameLen);
            std::wstring wname = Util::Utf8ToWide(name);
            if (wname.empty()) {
                int need = MultiByteToWideChar(CP_ACP, 0, name.data(),
                                               static_cast<int>(name.size()), nullptr, 0);
                if (need > 0) {
                    wname.resize(static_cast<size_t>(need));
                    MultiByteToWideChar(CP_ACP, 0, name.data(), static_cast<int>(name.size()),
                                        wname.data(), need);
                }
            }
            if (!IsSafeZipEntryName(wname.c_str())) {
                Log::WriteF(L"ZIP 校验失败：不安全条目 %s", wname.c_str());
                ok = false;
                break;
            }
            p += 46 + nameLen + extraLen + commentLen;
        }
    } while (false);

    CloseHandle(f);
    return ok;
}
} // namespace

// ============================================================================
//  Zip 实现
// ============================================================================
namespace Zip {

StepResult Validate(const std::wstring& zipPath) {
    if (!Util::IsFile(zipPath)) {
        return StepResult::FileError;
    }
    if (!ValidateZipEntries(zipPath)) {
        Log::WriteF(L"ZIP 结构校验失败（条目非法或文件损坏）: %s", zipPath.c_str());
        return StepResult::ExtractError;
    }
    return StepResult::Ok;
}

StepResult ExtractTo(const std::wstring& zipPath,
                     const std::wstring& destDir,
                     ProgressFn progress) {
    if (!Util::IsFile(zipPath)) {
        Log::WriteF(L"解压失败：文件不存在 %s", zipPath.c_str());
        return StepResult::FileError;
    }

    if (!Util::CreateDirectories(destDir)) {
        Log::WriteF(L"解压失败：无法创建目标目录 %s", destDir.c_str());
        return StepResult::PermissionError;
    }

    // 先检查所有条目名：带 ../ 或绝对路径的包一律拒绝，不解压
    if (!ValidateZipEntries(zipPath)) {
        Log::WriteF(L"解压中止：ZIP 包含不安全的路径条目 %s", zipPath.c_str());
        return StepResult::ExtractError;
    }

    if (progress) {
        progress(L"正在解压...", 0, 0);     // 0/0 表示不确定进度
    }

    // 方式 1：Shell COM
    Log::Write(L"尝试使用 Shell 原生解压...");
    if (ExtractWithShellCom(zipPath, destDir)) {
        Log::Write(L"Shell 原生解压成功");
        return StepResult::Ok;
    }

    // 方式 2：PowerShell 兜底
    Log::Write(L"Shell 解压未成功，改用 PowerShell 兜底...");
    if (ExtractWithPowerShell(zipPath, destDir)) {
        Log::Write(L"PowerShell 解压成功");
        return StepResult::Ok;
    }

    Log::Write(L"两种解压方式均失败");
    return StepResult::ExtractError;
}

} // namespace Zip