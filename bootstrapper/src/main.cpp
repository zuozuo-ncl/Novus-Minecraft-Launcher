// ============================================================================
//  main.cpp —— 引导器入口
//
//  职责边界（重要）：
//
//    引导器**只做「让程序能跑起来」这一件事** —— 装 Java、拿到一个能用的 jar。
//    日常更新（查新版本、下载新版 jar）由 **Java 启动器自己**负责。
//
//    因此流程被刻意设计成「有 jar 就完全不联网」：
//      - 本地已有 jar  →  直接启动，不查接口、不比较版本
//      - 本地没有 jar  →  查接口拿版本号，下载一个
//
//    这样做的原因：如果引导器也参与版本比较，用户在启动器里点了更新，
//    下次启动引导器又会去查一遍接口、再下一遍，两边重复劳动；
//    且接口不可用时会无谓地报错，而本地明明有 jar 可用。
//
//  其余设计目标：
//    - **下载时**才显示进度窗口（JRE / jar 可能耗时几分钟）
//    - **启动阶段完全静默**：Java 与 jar 齐备就直接拉起，不弹窗、不等待
//    - 作为 GUI 程序运行（无控制台黑框），工作线程做事，主线程跑消息循环
//
//  流程：
//    1. 解析路径（%APPDATA%\MC_Launcher）
//    2. 确保 Java 17（没有则下载，**此时才显示进度窗口**）
//    3. 扫描本地 jar：
//         - 有  → 取版本最高的，直接进入第 4 步
//         - 没有 → 查接口拿版本号 → 下载（**同样按需显示进度窗口**）
//    4. 静默拉起 javaw -jar，立即退出
// ============================================================================
#include "common.h"

namespace {

// 引导结果，供主线程决定是否弹错误框
struct BootstrapOutcome {
    StepResult result = StepResult::Ok;
    std::wstring message;       // 失败时的详细说明
};

BootstrapOutcome g_outcome;
HANDLE g_workerThread = nullptr;
HWND   g_progressWnd  = nullptr;

// 进度窗口是否已经创建（按需创建：只有真的要下载时才弹）
volatile bool g_progressWndCreated = false;

// 取消标志：进度窗口上的「取消」按钮或关闭窗口会置位
volatile bool g_cancelled = false;

// 当前是否处在「可以取消的下载/安装阶段」
//
//  以前只要进度窗口关掉就置位取消，哪怕下载早就结束、已经在等启动器
//  起来 —— 结果用户顺手关掉一个还在屏幕上的下载窗口，引导器就真的
//  放弃了这次启动。现在只有这段区间内的取消才算数。
volatile bool g_cancelAllowed = true;

// 请求显示进度窗口的消息（与 ui.cpp 里的 WM_APP_SHOW 保持一致）
//   WM_APP + 3
constexpr UINT WM_APP_SHOW_REQ = WM_APP + 3;

// ---------------------------------------------------------------------------
// 请求显示进度窗口（工作线程调用）
//
//   **不再在这里创建窗口** —— 窗口由主线程提前创建好（见 wWinMain），
//   这里只发一个消息请主线程把它显示出来。
//
//   为什么：Win32 规定窗口消息只能由创建窗口的线程处理。早期版本由
//   工作线程创建窗口、主线程跑消息循环，消息派发到错误线程，导致界面卡死。
// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// 构造磁盘空间不足的提示文案
//
//   带上具体数字（需要多少、实际剩多少），玩家才知道该清理多少。
//   早期只笼统说「文件读写失败」，用户无从下手。
// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// 把「具体网络原因」附加到错误文案后面
//
//  HTTP 层知道失败的确切原因（DNS 解析失败 / 连接超时 / 证书错误），
//  但调用方只拿到 StepResult 枚举。这里把详情取出来补在提示里 ——
//  玩家能据此判断该检查网线、改 DNS 还是等服务器恢复，而不是只看到
//  一句无从下手的"网络连接失败"。
// ---------------------------------------------------------------------------
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
    // 主线程已提前创建，句柄通过全局变量共享
    if (!g_progressWnd || !IsWindow(g_progressWnd)) {
        Log::Write(L"进度窗口尚未就绪，将继续静默下载");
        return nullptr;
    }

    if (!g_progressWndCreated) {
        // 请主线程执行 ShowWindow（工作线程不能直接操作窗口）
        PostMessageW(g_progressWnd, WM_APP_SHOW_REQ, 0, 0);
        g_progressWndCreated = true;
        Log::Write(L"已请求主线程显示进度窗口");
    }

    return g_progressWnd;
}

// ---------------------------------------------------------------------------
// 关闭进度窗口（如果它被创建过）
// ---------------------------------------------------------------------------
void CloseProgressWindowIfAny() {
    if (g_progressWndCreated && g_progressWnd && IsWindow(g_progressWnd)) {
        Ui::CloseProgressWindow(g_progressWnd);
        g_progressWnd = nullptr;
    }
}

// ---------------------------------------------------------------------------
// 工作线程：执行完整引导流程
// ---------------------------------------------------------------------------
// 已通过（或录入日志的）jar 路径：回退换 jar 时据此决定是否重新校验。
// 取「选择时」的路径而不是回退循环里的 jarPath —— 后者每轮都会被覆盖，
// 本机多个 jar 时不能凭空出错（多算一次哈希只是慢一点，无害）。
std::wstring chosenJar;

DWORD WINAPI BootstrapThread(LPVOID /*param*/) {
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

        // 各步骤共用的结果变量
        StepResult r = StepResult::Ok;

        // 注意：这个回调**不创建窗口**，只更新已有的窗口。
        // 窗口由「确实要下载」的分支显式创建，这样：
        //   - Java 与 jar 都已就绪时，全程无窗口，秒启动
        //   - 需要下载时才弹出进度条
        ProgressFn progress = [](const std::wstring& stage,
                                 uint64_t done, uint64_t total) {
            if (g_progressWndCreated && g_progressWnd) {
                Ui::UpdateProgress(g_progressWnd, stage, done, total);
            }
        };

        // ---------- 步骤 1：确保 Java 17 ----------
        //
        // Java 必须先于 jar 处理：即便本地有 jar，没有 Java 也跑不起来，
        // 而装 Java 是引导器存在的**首要理由**。
        //
        // 先静默探测；只有确认需要下载时才弹进度窗口。
        {
            std::wstring existingJava = Java::FindJava17(paths);
            if (!existingJava.empty()) {
                // 已有 Java：静默跳过，不弹窗
                Log::WriteF(L"已有可用 Java，跳过下载: %s", existingJava.c_str());
                // 复用 EnsureJava 走一遍（它会立刻返回已有的路径）
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
                // 需要下载 Java -> 此时才显示进度窗口
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

                    // 磁盘不足时给出确切数字，而不是笼统的下载失败
                    if (r == StepResult::FileError) {
                        uint64_t freeBytes2 = 0;
                        if (!Util::HasFreeSpace(paths.appData,
                                MIN_FREE_SPACE_BYTES, freeBytes2)) {
                            g_outcome.message = BuildNoSpaceMessage(paths.appData);
                            goto finish;
                        }
                    }

                    // 磁盘不足单独给提示：它能给出确切数字，
                    // 比笼统的「文件读写失败」有用得多
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

        // 统一取出 Java 路径（供后续启动用）
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

        // ---------- 步骤 2：确定要启动哪个 jar ----------
        //
        // 关键：**本地有 jar 就完全不联网**。
        //
        // 早先这里会无条件查一次版本接口、再把本地版本和服务器版本比较，
        // 落后就下载新版。问题是「更新」本该由 Java 启动器负责，
        // 引导器再插一手会造成：
        //   - 用户在启动器里点更新后，下次启动引导器又查一遍、下一遍
        //   - 接口不可用时报错，而本地其实有能用的 jar
        //
        // 现在只有「一个 jar 都没有」时才去查接口、下载，
        // 那属于「首次运行」场景，不是「更新」场景。
        std::wstring jarPath;

        {
            std::wstring localVersion, localJar;

            // 优先按版本清单选 —— 清单记录了哪个版本是当前应使用的，
            // 以及哪些版本被验证过可用。Java 启动器在新版崩溃时会改写它，
            // 引导器必须尊重这个决定，否则会把坏版本又拉起来。
            bool picked = (Launcher::LoadPreferredJar(paths, localVersion, localJar)
                           == StepResult::Ok);

            if (!picked) {
                // 清单不可用（不存在/损坏/无有效条目）：退回按版本号取最高
                picked = (Launcher::FindLatestLocalJar(paths, localVersion, localJar)
                          == StepResult::Ok);
                if (picked) {
                    Log::WriteF(L"按版本号取最高的本地 jar: %s", localVersion.c_str());
                }
            }

            if (picked) {
                // 本地已有 jar：直接使用，不查接口、不比较版本
                Log::WriteF(L"本地已有启动器 jar（%s），直接使用，跳过版本查询: %s",
                            localVersion.c_str(), localJar.c_str());
                jarPath = localJar;
                r = StepResult::Ok;
            } else {
                // 本地一个 jar 都没有（首次运行）：查接口拿版本号后下载
                Log::Write(L"本地没有任何启动器 jar，属于首次运行，将查询版本并下载");

                std::wstring version;
                StepResult vr = Launcher::QueryLatestVersion(version);

                if (vr != StepResult::Ok) {
                    // 接口失败不致命：退回内置默认版本继续尝试
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
                // 首次安装：已有文件也按官方哈希校验，不符则删掉重下
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
                        // 具体原因（期望/实际哈希、下载地址）由 EnsureJar 填好，
                        // 直接展示比笼统的「网络被劫持」更有用
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

        // ---------- 步骤 3.5：统一校验（首次启动 + 之后每次回退都要单独校验）----------
        // 为什么不拦死：宁可「无法判定时放行并记日志」，也不能因为服务器漏传 .sha256、
        // 用户换了目录、内置表还没收录新版本，就把人锁在启动器外面。
        // 但任何可信锚点与文件内容不一致，一定是篡改或损坏，直接拒绝。
        {
            std::wstring verifyDetail;
            StepResult vr = Launcher::VerifyJar(paths, jarPath, progress, cancelFlag, &verifyDetail);
            chosenJar = jarPath;
            if (vr != StepResult::Ok) {
                g_outcome.result = vr;
                if (vr == StepResult::UntrustedJar) {
                    // 锚点与文件不一致才是篡改信号；「没有锚点」只记日志不拦人
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

        // ---------- 步骤 4：启动 + 确认 + 必要时回退重试 ----------
        //
        // 流程（最多重试 MAX_ROLLBACK_ATTEMPTS 次）：
        //   1. 拉起 jar（不等待）
        //   2. 等待它「正常起来」：进程存活 + 收到了本次的 startup.ok
        //   3. 成功 -> 直接退出
        //   4. 失败 -> 弹窗问用户「是否正常」
        //        · 正常 -> 关闭提示，退出（用户自有判断）
        //        · 不正常 -> 标记该版本不可用、清单 current 退一格，
        //                    换 jar 重来
        //
        // 为什么要问用户而不是自动判定：判定逻辑只能看出「进程崩了」
        // 或「界面没起来」，但「起来了却功能异常」只有用户知道。
        // 询问能覆盖这类情况。
        {
            // 当前要启动的版本号（回退时会变）
            std::wstring runVersion;
            {
                // 从 jarPath 反推版本号：mc-launcher-<版本>.jar
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
            DWORD lastChildPid = 0;   // 上一次迭代的 PID，用于回退时关闭残留进程

            while (true) {
                ++attempt;
                Log::WriteF(L"[尝试 %d/%d] 启动版本 %s",
                            attempt, MAX_ROLLBACK_ATTEMPTS, runVersion.c_str());

                // 清掉上一次的启动标记，否则旧标记会让本次判定直接通过。
                // 必须用 DeleteFileQuiet：文件不存在也算成功。
                Util::DeleteFileQuiet(
                        Util::JoinPath(paths.appData, STARTUP_OK_NAME));

                // 回退候选同样要校验：与当前 jar 不同时才重新算。
                //
                // 先等校验完成再启动：VerifyJar 返回之前绝不调用 Launch。
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
                        --attempt;       // 换候选不算一次失败尝试
                        continue;
                    }
                    chosenJar = jarPath;
                }

                DWORD childPid = lastChildPid;  // 继承上一轮的 PID，防止回退后找不到
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

                // ---- 等待「正常起来」----
                //
                // 进入启动阶段：下载/安装都已经结束，先把进度窗口收掉，
                // 并停止接受「取消」—— 窗口关得晚一点不再影响本次启动。
                CloseProgressWindowIfAny();
                g_cancelAllowed = false;
                //
                // 先短检（3 秒），多数机器此时已就绪；没成功继续轮询到 15 秒。
                // 15 秒还没写标记但进程仍活着 -> 只是慢（首次启动 JVM + JavaFX
                // 首次加载实测 4~6 秒，冷启动更久），继续观察到进程退出为止，
                // **不弹窗**：以前一到 15 秒就弹「是否正常」，用户还没看清
                // 主界面就被问，选错就触发回退；引导器退出后弹窗还挡在桌面上。
                // 只有进程真的崩了 / 自己退了却没出界面，才需要询问用户。
                std::wstring why;
                Launcher::WaitResult wr = Launcher::WaitForStartupEx(
                        paths.appData, childProcess, childPid,
                        STARTUP_FIRST_CHECK_MS, STARTUP_POLL_MS,
                        &g_cancelled, &why);

                if (wr == Launcher::WaitResult::TimedOut) {
                    Log::Write(L"首次检查未通过，继续等待（进程仍存活，不视为失败）");
                    // 之后每次最多等 30 秒，超时再续 —— 不能无限阻塞，
                    // 否则进程卡死时引导器永远不退出、也看不到任何提示
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

                // 取消：直接结束，不弹窗、不回退
                if (wr == Launcher::WaitResult::Cancelled || g_cancelled) {
                    if (childProcess) {
                        CloseHandle(childProcess);
                        childProcess = nullptr;
                    }
                    Log::Write(L"等待期间被取消，保持当前版本");
                    g_outcome.result = StepResult::Cancelled;
                    goto finish;
                }

                // TimedOut 之后的兜底（理论上不会走到）：进程还活着，视为已在运行
                if (wr == Launcher::WaitResult::TimedOut) {
                    Log::WriteF(L"版本 %s 迟迟未写启动标记但进程仍在，按已启动处理",
                                runVersion.c_str());
                    if (childProcess) {
                        CloseHandle(childProcess);
                    }
                    g_outcome.result = StepResult::Ok;
                    break;
                }

                // ---- 真失败（崩溃 / 界面没出现）：询问用户是否回退 ----
                Log::WriteF(L"版本 %s 启动判定失败：%s", runVersion.c_str(), why.c_str());

                if (childProcess) {
                    CloseHandle(childProcess);
                    childProcess = nullptr;
                }

                // ---- 询问用户 ----
                //
                // 注意：这里要先把进度窗口收起来，否则消息框会被它盖住。
                CloseProgressWindowIfAny();

                BOOL userResult = Ui::AskStartupOk(runVersion);

                Log::WriteF(L"用户选择结果: %d (FALSE=出问题, TRUE=正常/再等等)", userResult);

                if (userResult == FALSE) {
                    // 用户明确选了「出了问题」→ 执行回退
                    Log::Write(L"用户确认出了问题，准备回退");
                } else {
                    // 用户选了「已正常打开」或「再等等」
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

                // 关闭坏版本残留的进程，避免干扰下次启动
                // （之前我们已经关掉句柄，现在用 PID 重新打开再终止）
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

                // 先找候选再动文件：
                // 以前是「先删 jar + 标记不可用」再 FindRollbackCandidate，
                // 找不到候选时用户就只剩一个被删掉的 jar 和一份标记不可用的清单
                // （原本能用的版本被永久废掉）。现在只有确认有候选才落盘。
                std::wstring nextVersion, nextJar;
                if (!Launcher::FindRollbackCandidate(paths, runVersion,
                                                     nextVersion, nextJar)) {
                    // 没有候选：只标记失败次数，保留 jar，下次还会再试
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

                // 确认有候选：这时才删除坏 jar、标记不可用、清单退一格
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

} // namespace

// ============================================================================
//  WinMain
// ============================================================================
int WINAPI wWinMain(HINSTANCE /*hInstance*/,
                    HINSTANCE /*hPrevInstance*/,
                    LPWSTR /*lpCmdLine*/,
                    int /*nCmdShow*/) {
    // 单实例检查：避免用户狂点图标拉起多个引导器
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

    // 初始化通用控件（进度条需要）
    Ui::InitCommonControls();

    // 初始化日志
    {
        AppPaths paths;
        paths.Init();
        Log::Init(paths);
    }

    Log::WriteF(L"引导器启动，版本 %s", BOOTSTRAPPER_VERSION);

    // 架构检测：必须在任何 JRE 下载逻辑之前。
    // 它决定下载 32 位还是 64 位 JRE —— 判据是**系统原生位数**，
    // 因为 64 位 JVM 跑不了 32 位系统，反之则只是损失性能。
    InitArch();

    // 在**主线程**提前创建进度窗口（隐藏状态）。
    //
    // 关键：Win32 要求窗口消息只能由创建窗口的线程处理。所以窗口必须在
    // 主线程创建，工作线程需要展示时发 WM_APP_SHOW_REQ 请主线程 ShowWindow。
    // 早期版本让工作线程创建窗口，主线程跑消息循环 -> 消息派发到错误线程
    // -> 界面卡死。
    //
    // 窗口先隐藏，若 Java 与 jar 都就绪则全程不显示，做到秒启动。
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

    // ---- 主线程：消息循环 ----
    // 工作线程跑下载，主线程只处理消息 -> 进度窗口始终可响应
    MSG msg;
    for (;;) {
        while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
            if (msg.message == WM_QUIT) {
                // 用户关闭了进度窗口 -> 请求取消
                // 只有下载/安装阶段关窗口才算取消。
                // 已经在等启动器起来时，窗口可能只是留在屏幕上没关，
                // 用户顺手关掉不应终止本次启动。
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
                // 窗口上的「取消」按钮被点击。
                //
                // 按钮在 ui.cpp 里，只能改到那边的局部状态；工作线程检查的是
                // 这里的 g_cancelled。早期两者互不相通，表现为「点取消没反应」。
                // 现在由窗口发消息过来，在这里统一置位。
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

        // 没有消息时稍等，避免空转吃满 CPU
        MsgWaitForMultipleObjects(0, nullptr, FALSE, 30, QS_ALLINPUT);
    }

    // ---- 收尾 ----
    if (g_workerThread) {
        WaitForSingleObject(g_workerThread, 5000);
        CloseHandle(g_workerThread);
        g_workerThread = nullptr;
    }

    if (g_progressWnd && IsWindow(g_progressWnd)) {
        DestroyWindow(g_progressWnd);
    }
    g_progressWnd = nullptr;

    // ---- 反馈 ----
    if (g_outcome.result == StepResult::Ok) {
        // 成功：静默退出，不打扰用户
        Log::Write(L"引导成功，主程序已启动");
    } else if (g_outcome.result == StepResult::Cancelled) {
        // 用户主动取消：也静默退出
        Log::Write(L"用户取消了引导流程");
    } else {
        // 失败：必须让用户知道原因
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