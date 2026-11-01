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
                                    VariantClear(&vRet);

                                    ok = SUCCEEDED(hrInit);
                                    if (!ok) {
                                        Log::Write(L"Shell CopyHere 调用失败");
                                    }
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

// 读到 ZIP 的中央目录，逐个检查条目名；不需要解压任何数据
bool ValidateZipEntries(const std::wstring& zipPath) {
    HANDLE f = CreateFileW(zipPath.c_str(), GENERIC_READ, FILE_SHARE_READ,
                           nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) {
        return false;
    }
    bool ok = false;
    unsigned char* b = nullptr;
    do {
        LARGE_INTEGER size;
        if (!GetFileSizeEx(f, &size) || size.QuadPart < 22) {
            break;
        }
        LARGE_INTEGER zero;
        zero.QuadPart = 0;
        if (!SetFilePointerEx(f, zero, nullptr, FILE_BEGIN)) {
            break;
        }
        b = static_cast<unsigned char*>(malloc(static_cast<size_t>(size.QuadPart)));
        if (!b) {
            break;
        }
        DWORD read = 0;
        if (!ReadFile(f, b, static_cast<DWORD>(size.QuadPart), &read, nullptr)
                || read != size.QuadPart) {
            break;
        }

        // 找 EOCD（签名 0x06054b50），从尾部往前最多 65KB+（注释长度上限）
        const unsigned char eocdSig[4] = {0x50, 0x4b, 0x05, 0x06};
        long long eocd = -1;
        long long lowest = read > 65557 ? read - 65557 : 0;
        for (long long i = read - 22; i >= lowest; --i) {
            if (memcmp(b + i, eocdSig, 4) == 0) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            Log::Write(L"ZIP 校验失败：找不到中央目录");
            break;
        }
        unsigned long long total = 0;
        memcpy(&total, b + eocd + 10, 2);        // 本盘条目数
        unsigned long long cdSize = 0, cdOffset = 0;
        memcpy(&cdSize, b + eocd + 12, 4);
        memcpy(&cdOffset, b + eocd + 16, 4);
        if (total == 0xFFFF) {
            Log::Write(L"ZIP 校验：ZIP64 条目数占位，跳过详细校验");
            ok = true;
            break;
        }
        if (cdOffset + cdSize > static_cast<unsigned long long>(read)) {
            Log::Write(L"ZIP 校验失败：中央目录越界");
            break;
        }

        unsigned long long p = cdOffset;
        ok = true;
        for (unsigned long long k = 0; k < total; ++k) {
            if (p + 46 > cdOffset + cdSize) {
                ok = false;
                break;
            }
            unsigned char* rec = b + p;
            const unsigned char cdSig[4] = {0x50, 0x4b, 0x01, 0x02};
            if (memcmp(rec, cdSig, 4) != 0) {
                ok = false;
                break;
            }
            unsigned short nameLen = 0, extraLen = 0, commentLen = 0;
            memcpy(&nameLen, rec + 28, 2);
            memcpy(&extraLen, rec + 30, 2);
            memcpy(&commentLen, rec + 32, 2);
            if (p + 46 + nameLen > static_cast<unsigned long long>(read)) {
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
            // 条目名是 UTF-8（可能不是）；按本地代码页转宽字符后检查
            std::string name(reinterpret_cast<char*>(rec + 46), nameLen);
            std::wstring wname = Util::Utf8ToWide(name);
            if (wname.empty()) {
                int need = MultiByteToWideChar(CP_ACP, 0, name.data(),
                                               static_cast<int>(name.size()), nullptr, 0);
                wname.resize(need);
                MultiByteToWideChar(CP_ACP, 0, name.data(), static_cast<int>(name.size()),
                                    &wname[0], need);
            }
            if (!IsSafeZipEntryName(wname.c_str())) {
                Log::WriteF(L"ZIP 校验失败：不安全条目 %s", wname.c_str());
                ok = false;
                break;
            }
            p += 46 + nameLen + extraLen + commentLen;
        }
    } while (false);

    if (b) {
        free(b);
    }
    CloseHandle(f);
    return ok;
}

} // namespace

// ============================================================================
//  Zip 实现
// ============================================================================
namespace Zip {

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