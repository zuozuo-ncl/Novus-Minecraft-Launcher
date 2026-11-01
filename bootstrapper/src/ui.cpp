// ============================================================================
//  ui.cpp —— 原生进度窗口与消息框
//
//  设计要点：
//    - 下载在工作线程执行，UI 在主线程跑消息循环 —— 绝不卡死窗口
//    - 工作线程通过 PostMessage 投递进度到窗口线程更新控件
//    - 窗口带「取消」按钮，点击后置位取消标志，工作线程感知后中止
//
//  全部使用宽字符 API（CreateWindowExW / MessageBoxW / SetWindowTextW），
//  中文提示与中文路径都不会乱码。
// ============================================================================
#include "common.h"

#include <commctrl.h>
#include <atomic>
#include <cstdio>


namespace {

// ---- 控件 ID ----
constexpr int kIdProgressBar = 1001;
constexpr int kIdStageLabel  = 1002;
constexpr int kIdPercentLabel= 1003;
constexpr int kIdCancelBtn   = 1004;

// ---- 自定义消息：工作线程 -> UI 线程 ----
constexpr UINT WM_APP_PROGRESS = WM_APP + 1;    // wParam=done%, lParam=stage 字符串指针
constexpr UINT WM_APP_FINISHED = WM_APP + 2;    // 工作完成，关闭窗口
constexpr UINT WM_APP_SHOW     = WM_APP + 3;    // 请求显示窗口（创建工作线程不能直接 ShowWindow）
constexpr UINT WM_APP_CANCEL   = WM_APP + 4;    // 用户点了取消 -> 通知主线程置位取消标志

// 进度窗口的句柄集合
struct WindowState {
    HWND hwnd        = nullptr;
    HWND progress    = nullptr;
    HWND stageLabel  = nullptr;
    HWND percentLabel= nullptr;
    HWND cancelBtn   = nullptr;
    volatile bool cancelled = false;
    volatile bool finished  = false;
};

// 目前只支持单个进度窗口，用静态实例保存
WindowState g_state;

// 窗口字体（比系统默认好看些）
HFONT g_font = nullptr;

HFONT GetUiFont() {
    if (!g_font) {
        NONCLIENTMETRICSW ncm;
        ZeroMemory(&ncm, sizeof(ncm));
        ncm.cbSize = sizeof(ncm);
        if (SystemParametersInfoW(SPI_GETNONCLIENTMETRICS, sizeof(ncm), &ncm, 0)) {
            g_font = CreateFontIndirectW(&ncm.lfMessageFont);
        }
        if (!g_font) {
            g_font = static_cast<HFONT>(GetStockObject(DEFAULT_GUI_FONT));
        }
    }
    return g_font;
}

// ---------------------------------------------------------------------------
// 窗口过程
// ---------------------------------------------------------------------------
LRESULT CALLBACK ProgressWndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
        case WM_CREATE: {
            HINSTANCE hInst = reinterpret_cast<LPCREATESTRUCT>(lParam)->hInstance;

            // 进度条
            g_state.progress = CreateWindowExW(
                    0, PROGRESS_CLASSW, L"",
                    WS_CHILD | WS_VISIBLE | PBS_SMOOTH,
                    20, 60, 400, 22,
                    hwnd, reinterpret_cast<HMENU>(kIdProgressBar), hInst, nullptr);

            // 阶段文字
            g_state.stageLabel = CreateWindowExW(
                    0, L"STATIC", L"准备中...",
                    WS_CHILD | WS_VISIBLE | SS_LEFT | SS_ENDELLIPSIS,
                    20, 22, 400, 28,
                    hwnd, reinterpret_cast<HMENU>(kIdStageLabel), hInst, nullptr);

            // 百分比
            g_state.percentLabel = CreateWindowExW(
                    0, L"STATIC", L"",
                    WS_CHILD | WS_VISIBLE | SS_RIGHT,
                    20, 90, 400, 20,
                    hwnd, reinterpret_cast<HMENU>(kIdPercentLabel), hInst, nullptr);

            // 取消按钮
            g_state.cancelBtn = CreateWindowExW(
                    0, L"BUTTON", L"取消",
                    WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_PUSHBUTTON,
                    340, 120, 80, 28,
                    hwnd, reinterpret_cast<HMENU>(kIdCancelBtn), hInst, nullptr);

            // 统一字体
            HFONT font = GetUiFont();
            SendMessageW(g_state.stageLabel, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
            SendMessageW(g_state.percentLabel, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
            SendMessageW(g_state.cancelBtn, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);

            // 进度条范围 0-100
            SendMessageW(g_state.progress, PBM_SETRANGE, 0, MAKELPARAM(0, 100));
            SendMessageW(g_state.progress, PBM_SETPOS, 0, 0);

            return 0;
        }

        case WM_COMMAND: {
            if (LOWORD(wParam) == kIdCancelBtn) {
                g_state.cancelled = true;
                SetWindowTextW(g_state.stageLabel, L"正在取消...");
                EnableWindow(g_state.cancelBtn, FALSE);

                // 关键：通知主线程把全局取消标志置位。
                //
                // 工作线程检查的是 main.cpp 里的 g_cancelled，而按钮只能
                // 改到本模块的 g_state.cancelled —— 两者早期互不相通，
                // 导致「点取消没反应」。这里通过消息把请求转给主线程，
                // 由主线程统一置位。
                if (g_state.hwnd) {
                    PostMessageW(g_state.hwnd, WM_APP_CANCEL, 0, 0);
                }
            }
            return 0;
        }

        // 工作线程请求显示窗口（窗口是在主线程创建的，只能由主线程显示）
        case WM_APP_SHOW: {
            Ui::ShowProgressWindow(g_state.hwnd);
            return 0;
        }

        // 工作线程投递的进度更新
        case WM_APP_PROGRESS: {
            int percent = static_cast<int>(wParam);
            const wchar_t* stage = reinterpret_cast<const wchar_t*>(lParam);

            if (stage && g_state.stageLabel) {
                SetWindowTextW(g_state.stageLabel, stage);
            }
            if (g_state.progress) {
                if (percent < 0) {
                    // -1 表示进度未知：用跑马灯效果
                    SetWindowLongPtrW(g_state.progress, GWL_STYLE,
                                      GetWindowLongPtrW(g_state.progress, GWL_STYLE)
                                      | PBS_MARQUEE);
                    SendMessageW(g_state.progress, PBM_SETMARQUEE, TRUE, 30);
                    SetWindowTextW(g_state.percentLabel, L"");
                } else {
                    // 恢复正常进度显示
                    LONG_PTR style = GetWindowLongPtrW(g_state.progress, GWL_STYLE);
                    if (style & PBS_MARQUEE) {
                        SendMessageW(g_state.progress, PBM_SETMARQUEE, FALSE, 0);
                        SetWindowLongPtrW(g_state.progress, GWL_STYLE,
                                          style & ~PBS_MARQUEE);
                    }
                    SendMessageW(g_state.progress, PBM_SETPOS, percent, 0);

                    wchar_t buf[32];
                    _snwprintf_s(buf, _countof(buf), _TRUNCATE, L"%d%%", percent);
                    SetWindowTextW(g_state.percentLabel, buf);
                }
            }

            // 释放工作线程分配的内存
            if (stage) {
                delete[] stage;
            }
            return 0;
        }

        // 工作线程结束
        case WM_APP_FINISHED: {
            g_state.finished = true;
            DestroyWindow(hwnd);
            return 0;
        }

        case WM_CLOSE: {
            // 点右上角 ✕ = 取消
            g_state.cancelled = true;
            SetWindowTextW(g_state.stageLabel, L"正在取消...");
            EnableWindow(g_state.cancelBtn, FALSE);

            // 通知主线程置位取消标志（否则工作线程不知道）
            if (g_state.hwnd) {
                PostMessageW(g_state.hwnd, WM_APP_CANCEL, 0, 0);
            }

            // 注意：这里**不**直接 DestroyWindow。
            // 工作线程可能正在写文件，立刻销毁窗口会让后续进度更新
            // 发到一个不存在的句柄。等它真正结束后再销毁（WM_APP_FINISHED）。
            return 0;
        }

        case WM_DESTROY: {
            g_state.hwnd = nullptr;
            return 0;
        }
    }

    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

// 注册窗口类（只注册一次）
void EnsureWindowClass() {
    static bool registered = false;
    if (registered) {
        return;
    }

    WNDCLASSEXW wc;
    ZeroMemory(&wc, sizeof(wc));
    wc.cbSize        = sizeof(wc);
    wc.lpfnWndProc   = ProgressWndProc;
    wc.hInstance     = GetModuleHandleW(nullptr);
    wc.hCursor       = LoadCursor(nullptr, IDC_ARROW);
    wc.hbrBackground = reinterpret_cast<HBRUSH>(COLOR_BTNFACE + 1);
    wc.lpszClassName = L"MCLauncherProgressWnd";
    // 窗口图标：不设置的话标题栏和任务栏显示的是默认的空白图标。
    // 尺寸传 0：由系统从 ICO 里挑最合适的一档。别用 GetSystemMetrics(SM_CXICON)
    // 去精确匹配 —— 150% 缩放时它是 40px，而 ICO 里没有 40 这一档，会加载失败。
    wc.hIcon   = reinterpret_cast<HICON>(LoadImageW(
            GetModuleHandleW(nullptr), MAKEINTRESOURCEW(IDI_APPICON),
            IMAGE_ICON, 0, 0, LR_DEFAULTCOLOR));
    wc.hIconSm = reinterpret_cast<HICON>(LoadImageW(
            GetModuleHandleW(nullptr), MAKEINTRESOURCEW(IDI_APPICON),
            IMAGE_ICON, 0, 0, LR_DEFAULTCOLOR));

    RegisterClassExW(&wc);
    registered = true;
}

} // namespace

// ============================================================================
//  Ui 实现
// ============================================================================
namespace Ui {

void InitCommonControls() {
    INITCOMMONCONTROLSEX icc;
    icc.dwSize = sizeof(icc);
    icc.dwICC  = ICC_PROGRESS_CLASS | ICC_STANDARD_CLASSES;
    InitCommonControlsEx(&icc);
    EnsureWindowClass();
}

// ---------------------------------------------------------------------------
// 创建进度窗口（居中显示）
// ---------------------------------------------------------------------------
// ---------------------------------------------------------------------------
// 显示进度窗口
//
//   **必须在创建窗口的线程（主线程）上调用**。
//   工作线程需要展示时用 PostMessage(WM_APP_SHOW) 请求。
// ---------------------------------------------------------------------------
void ShowProgressWindow(HWND hwnd) {
    if (!hwnd || !IsWindow(hwnd)) {
        return;
    }
    ShowWindow(hwnd, SW_SHOWNORMAL);
    SetForegroundWindow(hwnd);
}

HWND CreateProgressWindow(const std::wstring& title) {
    EnsureWindowClass();

    g_state.cancelled = false;
    g_state.finished  = false;

    // 屏幕居中
    int w = 460;
    int h = 175;

    int screenW = GetSystemMetrics(SM_CXSCREEN);
    int screenH = GetSystemMetrics(SM_CYSCREEN);
    int x = (screenW - w) / 2;
    int y = (screenH - h) / 2;

    // 注意：这里**不带 WS_VISIBLE**，窗口创建后是隐藏的。
    //
    // 原因（重要）：本函数会被工作线程调用，而 Win32 要求窗口消息只能由
    // 创建窗口的线程处理。之前工作线程创建并直接 WS_VISIBLE 显示窗口，
    // 主线程跑消息循环 —— 消息被派发到错误的线程，界面直接卡死。
    //
    // 现在改为：窗口在**主线程**提前创建（见 main.cpp），
    // 工作线程需要展示时发 WM_APP_SHOW 消息，由主线程执行 ShowWindow。
    HWND hwnd = CreateWindowExW(
            WS_EX_APPWINDOW | WS_EX_TOPMOST,
            L"MCLauncherProgressWnd",
            title.c_str(),
            WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU,
            x, y, w, h,
            nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);

    if (!hwnd) {
        Log::WriteF(L"创建进度窗口失败 (err=%lu)", GetLastError());
        return nullptr;
    }

    g_state.hwnd = hwnd;

    UpdateWindow(hwnd);
    SetForegroundWindow(hwnd);

    return hwnd;
}

// ---------------------------------------------------------------------------
// 更新进度（可从任意线程调用）
// ---------------------------------------------------------------------------
void UpdateProgress(HWND hwnd, const std::wstring& stage,
                    uint64_t done, uint64_t total) {
    if (!hwnd || g_state.finished) {
        return;
    }

    int percent = -1;
    if (total > 0) {
        // 留 1% 给最后的收尾，避免进度条早早到 100% 却还在跑
        double p = static_cast<double>(done) / static_cast<double>(total) * 100.0;
        percent = static_cast<int>(p);
        if (percent > 100) percent = 100;
        if (percent < 0)   percent = 0;
    }

    // ---- 节流 ----
    //
    // 下载 45MB 的 jar 会产生约 720 次回调（每 64KB 一次）。
    // 早期版本每次都 PostMessage，UI 线程被消息淹没，窗口明显卡顿。
    //
    // 现在只在下列情况才真正投递：
    //   - 百分比发生变化（整数位）
    //   - 距离上次投递超过 200ms（保证长时间停在某个百分比时界面仍在刷新）
    //
    // **关键**：这里只能使用「无状态的原子量」，不能缓存 std::wstring。
    // UpdateProgress 会被多个线程调用（下载线程、Java 下载线程、
    // 主线程的「正在取消」提示）。早先版本用局部 static std::wstring
    // 缓存上一次的文本，多线程并发读写会让 wstring 内部指针错乱，
    // 表现为界面直接卡死。原子整数没有这个问题。
    static std::atomic<int>  lastPercent{-2};
    static std::atomic<DWORD> lastTick{0};

    DWORD now = GetTickCount();

    int  prevPercent = lastPercent.load(std::memory_order_relaxed);
    DWORD prevTick   = lastTick.load(std::memory_order_relaxed);

    bool percentChanged = (percent != prevPercent);
    bool timeElapsed = (prevTick == 0) || (now - prevTick >= 200);

    if (!percentChanged && !timeElapsed) {
        return;     // 节流：这次更新可以丢弃
    }

    lastPercent.store(percent, std::memory_order_relaxed);
    lastTick.store(now, std::memory_order_relaxed);

    wchar_t* stageCopy = nullptr;
    if (!stage.empty()) {
        // 拷贝一份传给 UI 线程，由它负责释放
        size_t len = stage.size() + 1;
        stageCopy = new wchar_t[len];
        wcsncpy_s(stageCopy, len, stage.c_str(), _TRUNCATE);
    }

    PostMessageW(hwnd, WM_APP_PROGRESS,
                 static_cast<WPARAM>(percent),
                 reinterpret_cast<LPARAM>(stageCopy));
}

// ---------------------------------------------------------------------------
// 关闭进度窗口
// ---------------------------------------------------------------------------
void CloseProgressWindow(HWND hwnd) {
    if (!hwnd) {
        return;
    }
    g_state.finished = true;
    PostMessageW(hwnd, WM_APP_FINISHED, 0, 0);
}

// ---------------------------------------------------------------------------
// 处理消息队列（供工作线程等待时调用，保持窗口响应）
// ---------------------------------------------------------------------------
void PumpMessages() {
    MSG msg;
    while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
        if (msg.message == WM_QUIT) {
            // 收到退出消息：标记取消，让工作线程尽快退出
            g_state.cancelled = true;
            break;
        }
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
}

bool IsCancelled(HWND /*hwnd*/) {
    return g_state.cancelled;
}

// ---------------------------------------------------------------------------
// 消息框（宽字符版本，中文安全）
// ---------------------------------------------------------------------------
void Info(const std::wstring& text, const std::wstring& title) {
    MessageBoxW(nullptr, text.c_str(), title.c_str(),
                MB_OK | MB_ICONINFORMATION | MB_TOPMOST);
}

void Error(const std::wstring& text, const std::wstring& title) {
    MessageBoxW(nullptr, text.c_str(), title.c_str(),
                MB_OK | MB_ICONERROR | MB_TOPMOST);
}

bool Confirm(const std::wstring& text, const std::wstring& title) {
    int r = MessageBoxW(nullptr, text.c_str(), title.c_str(),
                        MB_YESNO | MB_ICONQUESTION | MB_TOPMOST);
    return r == IDYES;
}

BOOL AskStartupOk(const std::wstring& version) {
    // 返回：
    //   TRUE  -> 进程正常（用户选了「已正常打开」或「再等等」）
    //   FALSE -> 进程有问题，需要回退（用户选了「出了问题」）

    std::wstring title =
            L"启动器是否正常启动？ — " + version +
            L"\n\n按钮说明：【是】= 已正常打开 ｜ 【否】= 出了问题 ｜ 【取消】= 再等等";

    std::wstring text  =
            L"请观察窗口状态，选对应按钮：\n\n"
            L"【是】  窗口正常显示、能正常操作\n"
            L"      → 引导器放行，不再干预\n\n"
            L"【否】  窗口没出现 / 白屏 / 卡死 / 闪一下就关了\n"
            L"      → 自动回退到上一个能用的版本\n\n"
            L"【取消】 还在加载、还没拿准\n"
            L"      → 关掉弹窗继续观察，引导器再等一会儿";

    int r = MessageBoxW(nullptr, text.c_str(),
                        title.c_str(),
                        MB_YESNOCANCEL | MB_ICONQUESTION | MB_SETFOREGROUND
                        | MB_SYSTEMMODAL);

    if (r == IDYES) { return TRUE; }
    if (r == IDNO)  { return FALSE; }
    // IDCANCEL -> 「再等等」，返回 TRUE
    return TRUE;
}
} // namespace Ui