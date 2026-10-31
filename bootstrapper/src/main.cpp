#include "common.h"

namespace {

struct BootstrapOutcome {
    StepResult result = StepResult::Ok;
    std::wstring message;       
};

BootstrapOutcome g_outcome;
HANDLE g_workerThread = nullptr;
HWND   g_progressWnd  = nullptr;

volatile bool g_progressWndCreated = false;

volatile bool g_cancelled = false;

volatile bool g_cancelAllowed = true;

constexpr UINT WM_APP_SHOW_REQ = WM_APP + 3;

std::wstring AppendNetworkDetail(const std::wstring& base) {
    std::wstring detail = Http::GetLastNetworkDetail();
    if (detail.empty()) {
        return base;
    }
    return base + L"\n\n技术细节：" + detail;
}

std::wstring BuildNoSpaceMessage(const std::wstring& appData) {
    uint64_t freeBytes = 0;
    Util::HasFreeSpace(appData, MIN_FREE_SPACE_BYTES, freeBytes);

    std::wstring msg;
    msg += L"磁盘空间不足，无法完成安装。\n\n";
    msg += L"需要至少：" + Util::HumanSize(MIN_FREE_SPACE_BYTES) + L"\n";
    msg += L"当前剩余：" + Util::HumanSize(freeBytes) + L"\n\n";
    msg += L"请清理磁盘后重新运行。\n";
    msg += L"（这些空间用于存放启动器本体与 Java 运行环境）\n\n";
    msg += L"详细日志：\n" + appData + L"\\bootstrap.log";
    return msg;
}

HWND EnsureProgressWindow() {

    if (!g_progressWnd || !IsWindow(g_progressWnd)) {
        Log::Write(L"进度窗口尚未就绪，将继续静默下载");
        return nullptr;
    }

    if (!g_progressWndCreated) {

        PostMessageW(g_progressWnd, WM_APP_SHOW_REQ, 0, 0);
        g_progressWndCreated = true;
        Log::Write(L"已请求主线程显示进度窗口");
    }

    return g_progressWnd;
}

void CloseProgressWindowIfAny() {
    if (g_progressWndCreated && g_progressWnd && IsWindow(g_progressWnd)) {
        Ui::CloseProgressWindow(g_progressWnd);
        g_progressWnd = nullptr;
    }
}

std::wstring chosenJar;

DWORD WINAPI BootstrapThread(LPVOID ) {
    Log::Write(L"===== 引导流程开始 =====");

    AppPaths paths;
    if (!paths.Init()) {
        g_outcome.result = StepResult::PermissionError;
        g_outcome.message =
                L"无法创建启动器数据目录。\n\n"
                L"请检查 %APPDATA% 目录是否存在且可写，"
                L"或以管理员身份重新运行。";
        Log::Write(L"AppPaths::Init 失败");
        goto finish;
    }

    Log::WriteF(L"数据目录: %s", paths.appData.c_str());

    {
        volatile bool* cancelFlag = &g_cancelled;

        StepResult r = StepResult::Ok;

        

        
        ProgressFn progress = [](const std::wstring& stage,
                                 uint64_t done, uint64_t total) {
            if (g_progressWndCreated && g_progressWnd) {
                Ui::UpdateProgress(g_progressWnd, stage, done, total);
            }
        };

        

        

        
        {
            std::wstring existingJava = Java::FindJava17(paths);
            if (!existingJava.empty()) {

                Log::WriteF(L"已有可用 Java，跳过下载: %s", existingJava.c_str());

                std::wstring javawPath;
                r = Java::EnsureJava(paths, progress, cancelFlag, javawPath);
                if (r != StepResult::Ok) {
                    g_outcome.result = r;
                    g_outcome.message =
                            std::wstring(L"Java 运行环境检测失败。\n\n原因：")
                            + StepResultText(r)
                            + L"\n\n详细日志：\n" + paths.appData + L"\\bootstrap.log";
                    goto finish;
                }
                Log::WriteF(L"Java 就绪: %s", javawPath.c_str());
                r = StepResult::Ok;
            } else {

                Log::Write(L"未检测到 Java 17，将下载（显示进度窗口）");
                EnsureProgressWindow();

                std::wstring javawPath;
                r = Java::EnsureJava(paths, progress, cancelFlag, javawPath);

                if (r == StepResult::Cancelled || g_cancelled) {
                    g_outcome.result = StepResult::Cancelled;
                    goto finish;
                }
                if (r != StepResult::Ok) {
                    g_outcome.result = r;

                    if (r == StepResult::FileError) {
                        uint64_t freeBytes2 = 0;
                        if (!Util::HasFreeSpace(paths.appData,
                                MIN_FREE_SPACE_BYTES, freeBytes2)) {
                            g_outcome.message = BuildNoSpaceMessage(paths.appData);
                            goto finish;
                        }
                    }

                    
                    if (r == StepResult::FileError) {
                        uint64_t freeBytes = 0;
                        bool noSpace = !Util::HasFreeSpace(
                                paths.appData, MIN_FREE_SPACE_BYTES, freeBytes);
                        if (noSpace) {
                            g_outcome.message = BuildNoSpaceMessage(paths.appData);
                            goto finish;
                        }
                    }
                    g_outcome.message = AppendNetworkDetail(
                            std::wstring(L"Java 运行环境准备失败。\n\n原因：")
                            + StepResultText(r)
                            + L"\n\n"
                              L"你可以手动安装 Java 17 后重新运行本引导器，\n"
                              L"或检查网络连接后重试。\n\n"
                              L"详细日志：\n" + paths.appData + L"\\bootstrap.log");
                    goto finish;
                }
                Log::WriteF(L"Java 下载安装完成: %s", javawPath.c_str());
            }
        }

        std::wstring javawPath = Java::FindJava17(paths);
        if (javawPath.empty()) {
            g_outcome.result = StepResult::JavaNotFound;
            g_outcome.message = L"未能找到可用的 Java 17。\n\n"
                                L"详细日志：\n" + paths.appData + L"\\bootstrap.log";
            goto finish;
        }

        if (g_cancelled) {
            g_outcome.result = StepResult::Cancelled;
            goto finish;
        }

        

        

        

        

        

        
        std::wstring jarPath;

        {
            std::wstring localVersion, localJar;

            

            bool picked = (Launcher::LoadPreferredJar(paths, localVersion, localJar)
                           == StepResult::Ok);

            if (!picked) {

                picked = (Launcher::FindLatestLocalJar(paths, localVersion, localJar)
                          == StepResult::Ok);
                if (picked) {
                    Log::WriteF(L"按版本号取最高的本地 jar: %s", localVersion.c_str());
                }
            }

            if (picked) {

                Log::WriteF(L"本地已有启动器 jar（%s），直接使用，跳过版本查询: %s",
                            localVersion.c_str(), localJar.c_str());
                jarPath = localJar;
                r = StepResult::Ok;
            } else {

                Log::Write(L"本地没有任何启动器 jar，属于首次运行，将查询版本并下载");

                std::wstring version;
                StepResult vr = Launcher::QueryLatestVersion(version);

                if (vr != StepResult::Ok) {

                    Log::WriteF(L"查询版本失败（%s），使用内置默认版本 %s",
                                StepResultText(vr), DEFAULT_LAUNCHER_VERSION);
                    version = DEFAULT_LAUNCHER_VERSION;
                } else {
                    Log::WriteF(L"接口返回最新版本: %s", version.c_str());
                }

                if (g_cancelled) {
                    g_outcome.result = StepResult::Cancelled;
                    goto finish;
                }

                EnsureProgressWindow();

                std::wstring dlDetail;
                r = Launcher::EnsureJar(paths, version, progress, cancelFlag, jarPath, true,
                                        &dlDetail);

                if (r == StepResult::Ok) {
                    Log::WriteF(L"首次下载完成: %s", jarPath.c_str());
                } else {
                    g_outcome.result = r;

                    std::wstring extra;
                    if (r == StepResult::HttpError) {
                        extra = L"\n\n可能原因：该版本的启动器文件尚未上传到下载服务器。\n"
                                L"版本号：" + version;
                    } else if (r == StepResult::NetworkError) {
                        extra = L"\n\n请检查网络连接，或确认是否需要代理才能访问下载地址。";
                    } else if (r == StepResult::ChecksumMismatch) {

                        
                        if (!dlDetail.empty()) {
                            extra = L"\n\n" + dlDetail;
                        } else {
                            extra = L"\n\n下载到的文件与官方 SHA-256 不一致，已删除。\n"
                                    L"可能是网络被劫持或下载源异常，请稍后重试。";
                        }
                    }

                    g_outcome.message = AppendNetworkDetail(
                            std::wstring(L"启动器主程序下载失败。\n\n原因：")
                            + StepResultText(r) + extra
                            + L"\n\n版本号：" + version
                            + L"\n详细日志：\n" + paths.appData + L"\\bootstrap.log");
                    goto finish;
                }
            }
        }

        if (r == StepResult::Cancelled || g_cancelled) {
            g_outcome.result = StepResult::Cancelled;
            goto finish;
        }

        if (r != StepResult::Ok) {
            g_outcome.result = r;
            g_outcome.message =
                    std::wstring(L"未能找到可用的启动器主程序。\n\n原因：")
                    + StepResultText(r)
                    + L"\n\n详细日志：\n" + paths.appData + L"\\bootstrap.log";
            goto finish;
        }

        Log::WriteF(L"jar 就绪: %s", jarPath.c_str());

        

        
        {
            std::wstring verifyDetail;
            StepResult vr = Launcher::VerifyJar(paths, jarPath, progress, cancelFlag, &verifyDetail);
            chosenJar = jarPath;
            if (vr != StepResult::Ok) {
                g_outcome.result = vr;
                if (vr == StepResult::UntrustedJar) {

                    Log::Write(L"没有可信锚点，放行启动并记录实际哈希（启动器启动后会自行登记）");
                } else {
                g_outcome.message =
                        std::wstring(L"启动器文件校验未通过。\n\n")
                        + verifyDetail
                        + L"\n\n文件：\n" + jarPath
                        + L"\n\n可以尝试：\n"
                          L"· 删除上面这个文件后重新运行，引导器会重新下载；\n"
                          L"· 在启动器「设置 → 检查更新」里手动更新到最新版本。\n\n"
                          L"详细日志：\n" + paths.appData + L"\\bootstrap.log";
                Log::WriteF(L"jar 校验未通过（%s），拒绝启动", StepResultText(vr));
                goto finish;
                }
            }
        }

        

        

        

        

        

        

        
        {

            std::wstring runVersion;
            {

                std::wstring name = Util::FileName(jarPath);
                const std::wstring prefix = L"mc-launcher-";
                const std::wstring suffix = L".jar";
                if (name.size() > prefix.size() + suffix.size()
                        && _wcsnicmp(name.c_str(), prefix.c_str(), prefix.size()) == 0) {
                    runVersion = name.substr(prefix.size(),
                            name.size() - prefix.size() - suffix.size());
                }
            }

            int attempt = 0;
            DWORD lastChildPid = 0;   

            while (true) {
                ++attempt;
                Log::WriteF(L"[尝试 %d/%d] 启动版本 %s",
                            attempt, MAX_ROLLBACK_ATTEMPTS, runVersion.c_str());

                
                Util::DeleteFileQuiet(
                        Util::JoinPath(paths.appData, STARTUP_OK_NAME));

                

                if (jarPath != chosenJar) {
                    std::wstring vd;
                    if (progress) {
                        progress(L"正在校验启动器文件…", 0, 0);
                    }
                    StepResult vr = Launcher::VerifyJar(paths, jarPath, nullptr, cancelFlag, &vd);
                    if (vr != StepResult::Ok && vr != StepResult::UntrustedJar) {
                        Log::WriteF(L"回退候选 %s 校验未通过（%s），跳过", runVersion.c_str(),
                                    StepResultText(vr));
                        Launcher::MarkVersionUnusable(paths, runVersion);
                        std::wstring nv2, nj2;
                        if (!Launcher::FindRollbackCandidate(paths, runVersion, nv2, nj2)) {
                            g_outcome.result = vr;
                            g_outcome.message = std::wstring(L"可用的启动器版本全部校验失败。\n\n")
                                    + vd
                                    + L"\n\n详细日志：\n" + paths.appData + L"\\bootstrap.log";
                            goto finish;
                        }
                        runVersion = nv2;
                        jarPath = nj2;
                        Launcher::UpdateManifestCurrent(paths, nv2);
                        --attempt;       
                        continue;
                    }
                    chosenJar = jarPath;
                }

                DWORD childPid = lastChildPid;  
                lastChildPid = 0;
                HANDLE childProcess = nullptr;

                r = Launcher::Launch(javawPath, jarPath, paths.appData, nullptr,
                                     &childPid, &childProcess);

                if (r != StepResult::Ok) {
                    g_outcome.result = r;
                    g_outcome.message =
                            std::wstring(L"启动主程序失败。\n\n原因：")
                            + StepResultText(r)
                            + L"\n\nJava 路径：\n" + javawPath
                            + L"\n\n主程序路径：\n" + jarPath
                            + L"\n\n详细日志：\n" + paths.appData + L"\\bootstrap.log";
                    if (childProcess) {
                        CloseHandle(childProcess);
                        childProcess = nullptr;
                    }
                    goto finish;
                }

                

                
                CloseProgressWindowIfAny();
                g_cancelAllowed = false;

                

                

                

                std::wstring why;
                Launcher::WaitResult wr = Launcher::WaitForStartupEx(
                        paths.appData, childProcess, childPid,
                        STARTUP_FIRST_CHECK_MS, STARTUP_POLL_MS,
                        &g_cancelled, &why);

                if (wr == Launcher::WaitResult::TimedOut) {
                    Log::Write(L"首次检查未通过，继续等待（进程仍存活，不视为失败）");

                    
                    while (wr == Launcher::WaitResult::TimedOut) {
                        wr = Launcher::WaitForStartupEx(
                                paths.appData, childProcess, childPid,
                                STARTUP_MORE_WAIT_MS,
                                STARTUP_POLL_MS, &g_cancelled, &why);
                    }
                }

                if (wr == Launcher::WaitResult::Started) {
                    Log::WriteF(L"版本 %s 已确认正常启动", runVersion.c_str());
                    if (childProcess) {
                        CloseHandle(childProcess);
                    }
                    g_outcome.result = StepResult::Ok;
                    Log::Write(L"启动成功，引导器即将退出");
                    break;
                }

                if (wr == Launcher::WaitResult::Cancelled || g_cancelled) {
                    if (childProcess) {
                        CloseHandle(childProcess);
                        childProcess = nullptr;
                    }
                    Log::Write(L"等待期间被取消，保持当前版本");
                    g_outcome.result = StepResult::Cancelled;
                    goto finish;
                }

                if (wr == Launcher::WaitResult::TimedOut) {
                    Log::WriteF(L"版本 %s 迟迟未写启动标记但进程仍在，按已启动处理",
                                runVersion.c_str());
                    if (childProcess) {
                        CloseHandle(childProcess);
                    }
                    g_outcome.result = StepResult::Ok;
                    break;
                }

                Log::WriteF(L"版本 %s 启动判定失败：%s", runVersion.c_str(), why.c_str());

                if (childProcess) {
                    CloseHandle(childProcess);
                    childProcess = nullptr;
                }

                

                CloseProgressWindowIfAny();

                BOOL userResult = Ui::AskStartupOk(runVersion);

                Log::WriteF(L"用户选择结果: %d (FALSE=出问题, TRUE=正常/再等等)", userResult);

                if (userResult == FALSE) {

                    Log::Write(L"用户确认出了问题，准备回退");
                } else {

                    Log::Write(L"用户选择继续观察或确认正常，保持当前版本");
                    g_outcome.result = StepResult::Ok;
                    break;
                }
                if (attempt >= MAX_ROLLBACK_ATTEMPTS) {
                    Log::WriteF(L"已达最大回退次数 %d，停止重试", MAX_ROLLBACK_ATTEMPTS);
                    g_outcome.result = StepResult::LaunchFailed;
                    g_outcome.message =
                            std::wstring(L"启动器多次启动均未成功，已停止自动回退。\n\n")
                            + L"最后一次尝试的版本：" + runVersion + L"\n"
                            + L"原因：" + why + L"\n\n"
                            + L"请到设置里检查更新，或联系开发者。\n\n"
                            + L"详细日志：\n" + paths.appData + L"\\bootstrap.log";
                    goto finish;
                }

                
                if (childPid != 0) {
                    HANDLE h = OpenProcess(SYNCHRONIZE | PROCESS_TERMINATE, FALSE, childPid);
                    if (h) {
                        DWORD ec = 0;
                        if (GetExitCodeProcess(h, &ec) && ec == STILL_ACTIVE) {
                            Log::Write(L"终止旧版本进程");
                            TerminateProcess(h, 1);
                            WaitForSingleObject(h, 3000);
                        }
                        CloseHandle(h);
                    } else {
                        Log::WriteF(L"无法重新打开进程 PID=%lu（可能已退出）", childPid);
                    }
                }

                

                
                std::wstring nextVersion, nextJar;
                if (!Launcher::FindRollbackCandidate(paths, runVersion,
                                                     nextVersion, nextJar)) {

                    Launcher::MarkVersionUnusable(paths, runVersion);
                    Log::Write(L"没有可回退的版本（已保留当前 jar 并标记失败）");
                    g_outcome.result = StepResult::LaunchFailed;
                    g_outcome.message =
                            std::wstring(L"当前版本 " + runVersion + L" 无法正常启动，"
                                         L"但没有找到可回退的旧版本。\n\n")
                            + L"请到设置里检查更新，或手动重新下载启动器。\n\n"
                            + L"详细日志：\n" + paths.appData + L"\\bootstrap.log";
                    goto finish;
                }

                Launcher::DeleteFailedJar(paths, runVersion);
                Launcher::MarkVersionUnusable(paths, runVersion);
                Launcher::UpdateManifestCurrent(paths, nextVersion);
                Log::WriteF(L"回退: %s -> %s，准备重试",
                            runVersion.c_str(), nextVersion.c_str());

                runVersion = nextVersion;
                jarPath = nextJar;
            }
        }
    }

finish:
    Log::Write(L"===== 引导流程结束 =====");

    CloseProgressWindowIfAny();

    return 0;
}

} 

int WINAPI wWinMain(HINSTANCE ,
                    HINSTANCE ,
                    LPWSTR ,
                    int ) {

    HANDLE hMutex = CreateMutexW(nullptr, TRUE,
                                 L"Global\\MCLauncherBootstrap_SingleInstance");
    if (hMutex && GetLastError() == ERROR_ALREADY_EXISTS) {
        Log::Write(L"已有引导器实例在运行，本次退出");
        HWND existing = FindWindowW(L"MCLauncherProgressWnd", nullptr);
        if (existing) {
            SetForegroundWindow(existing);
        }
        CloseHandle(hMutex);
        return 0;
    }

    Ui::InitCommonControls();

    {
        AppPaths paths;
        paths.Init();
        Log::Init(paths);
    }

    Log::WriteF(L"引导器启动，版本 %s", BOOTSTRAPPER_VERSION);

    

    InitArch();

    

    

    

    
    g_progressWnd = Ui::CreateProgressWindow(L"Novus Minecraft Launcher — 正在准备运行环境");
    if (!g_progressWnd) {
        Log::Write(L"进度窗口创建失败，将静默运行");
    }

    g_workerThread = CreateThread(nullptr, 0, BootstrapThread, nullptr, 0, nullptr);
    if (!g_workerThread) {
        DWORD err = GetLastError();
        Log::WriteF(L"创建引导线程失败 (err=%lu)", err);

        if (hMutex) {
            ReleaseMutex(hMutex);
            CloseHandle(hMutex);
        }

        MessageBoxW(nullptr,
                    L"无法启动引导线程（系统资源不足或安全软件拦截）。\n\n"
                    L"请重启电脑后重试，或将本程序加入杀毒软件白名单。",
                    L"Novus Minecraft Launcher",
                    MB_OK | MB_ICONERROR | MB_TOPMOST);
        return 1;
    }

    
    MSG msg;
    for (;;) {
        while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
            if (msg.message == WM_QUIT) {

                

                
                if (g_cancelAllowed) {
                    Log::Write(L"收到 WM_QUIT，请求取消");
                    g_cancelled = true;
                    if (g_progressWnd) {
                        Ui::UpdateProgress(g_progressWnd, L"正在取消...", 0, 0);
                    }
                } else {
                    Log::Write(L"等待启动阶段收到窗口关闭，忽略（不影响启动）");
                }
            } else if (msg.message == WM_APP_CANCEL_REQ) {

                

                

                if (g_cancelAllowed) {
                    Log::Write(L"收到取消请求，正在停止下载");
                    g_cancelled = true;
                } else {
                    Log::Write(L"等待启动阶段收到取消请求，忽略（不影响启动）");
                }
            } else {
                TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }

        if (g_workerThread) {
            DWORD wait = WaitForSingleObject(g_workerThread, 30);
            if (wait == WAIT_OBJECT_0) {
                break;
            }
        } else {
            break;
        }

        MsgWaitForMultipleObjects(0, nullptr, FALSE, 30, QS_ALLINPUT);
    }

    if (g_workerThread) {
        WaitForSingleObject(g_workerThread, 5000);
        CloseHandle(g_workerThread);
        g_workerThread = nullptr;
    }

    if (g_progressWnd && IsWindow(g_progressWnd)) {
        DestroyWindow(g_progressWnd);
    }
    g_progressWnd = nullptr;

    if (g_outcome.result == StepResult::Ok) {

        Log::Write(L"引导成功，主程序已启动");
    } else if (g_outcome.result == StepResult::Cancelled) {

        Log::Write(L"用户取消了引导流程");
    } else {

        Log::WriteF(L"引导失败: %s", StepResultText(g_outcome.result));

        std::wstring text = g_outcome.message;
        if (text.empty()) {
            text = std::wstring(L"启动器准备失败。\n\n原因：")
                   + StepResultText(g_outcome.result)
                   + L"\n\n请查看日志获取详细信息。";
        }
        Ui::Error(text, L"Novus Minecraft Launcher — 启动失败");
    }

    if (hMutex) {
        ReleaseMutex(hMutex);
        CloseHandle(hMutex);
    }

    Log::Write(L"引导器退出");
    return 0;
}
