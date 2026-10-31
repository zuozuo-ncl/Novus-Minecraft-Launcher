#include "common.h"

#include <commctrl.h>
#include <atomic>
#include <cstdio>

namespace {

constexpr int kIdProgressBar = 1001;
constexpr int kIdStageLabel  = 1002;
constexpr int kIdPercentLabel= 1003;
constexpr int kIdCancelBtn   = 1004;

constexpr UINT WM_APP_PROGRESS = WM_APP + 1;    
constexpr UINT WM_APP_FINISHED = WM_APP + 2;    
constexpr UINT WM_APP_SHOW     = WM_APP + 3;    
constexpr UINT WM_APP_CANCEL   = WM_APP + 4;    

struct WindowState {
    HWND hwnd        = nullptr;
    HWND progress    = nullptr;
    HWND stageLabel  = nullptr;
    HWND percentLabel= nullptr;
    HWND cancelBtn   = nullptr;
    volatile bool cancelled = false;
    volatile bool finished  = false;
};

WindowState g_state;

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

LRESULT CALLBACK ProgressWndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
        case WM_CREATE: {
            HINSTANCE hInst = reinterpret_cast<LPCREATESTRUCT>(lParam)->hInstance;

            g_state.progress = CreateWindowExW(
                    0, PROGRESS_CLASSW, L"",
                    WS_CHILD | WS_VISIBLE | PBS_SMOOTH,
                    20, 60, 400, 22,
                    hwnd, reinterpret_cast<HMENU>(kIdProgressBar), hInst, nullptr);

            g_state.stageLabel = CreateWindowExW(
                    0, L"STATIC", L"准备中...",
                    WS_CHILD | WS_VISIBLE | SS_LEFT | SS_ENDELLIPSIS,
                    20, 22, 400, 28,
                    hwnd, reinterpret_cast<HMENU>(kIdStageLabel), hInst, nullptr);

            g_state.percentLabel = CreateWindowExW(
                    0, L"STATIC", L"",
                    WS_CHILD | WS_VISIBLE | SS_RIGHT,
                    20, 90, 400, 20,
                    hwnd, reinterpret_cast<HMENU>(kIdPercentLabel), hInst, nullptr);

            g_state.cancelBtn = CreateWindowExW(
                    0, L"BUTTON", L"取消",
                    WS_CHILD | WS_VISIBLE | WS_TABSTOP | BS_PUSHBUTTON,
                    340, 120, 80, 28,
                    hwnd, reinterpret_cast<HMENU>(kIdCancelBtn), hInst, nullptr);

            HFONT font = GetUiFont();
            SendMessageW(g_state.stageLabel, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
            SendMessageW(g_state.percentLabel, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
            SendMessageW(g_state.cancelBtn, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);

            SendMessageW(g_state.progress, PBM_SETRANGE, 0, MAKELPARAM(0, 100));
            SendMessageW(g_state.progress, PBM_SETPOS, 0, 0);

            return 0;
        }

        case WM_COMMAND: {
            if (LOWORD(wParam) == kIdCancelBtn) {
                g_state.cancelled = true;
                SetWindowTextW(g_state.stageLabel, L"正在取消...");
                EnableWindow(g_state.cancelBtn, FALSE);

                

                

                
                if (g_state.hwnd) {
                    PostMessageW(g_state.hwnd, WM_APP_CANCEL, 0, 0);
                }
            }
            return 0;
        }

        case WM_APP_SHOW: {
            Ui::ShowProgressWindow(g_state.hwnd);
            return 0;
        }

        case WM_APP_PROGRESS: {
            int percent = static_cast<int>(wParam);
            const wchar_t* stage = reinterpret_cast<const wchar_t*>(lParam);

            if (stage && g_state.stageLabel) {
                SetWindowTextW(g_state.stageLabel, stage);
            }
            if (g_state.progress) {
                if (percent < 0) {

                    SetWindowLongPtrW(g_state.progress, GWL_STYLE,
                                      GetWindowLongPtrW(g_state.progress, GWL_STYLE)
                                      | PBS_MARQUEE);
                    SendMessageW(g_state.progress, PBM_SETMARQUEE, TRUE, 30);
                    SetWindowTextW(g_state.percentLabel, L"");
                } else {

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

            if (stage) {
                delete[] stage;
            }
            return 0;
        }

        case WM_APP_FINISHED: {
            g_state.finished = true;
            DestroyWindow(hwnd);
            return 0;
        }

        case WM_CLOSE: {

            g_state.cancelled = true;
            SetWindowTextW(g_state.stageLabel, L"正在取消...");
            EnableWindow(g_state.cancelBtn, FALSE);

            if (g_state.hwnd) {
                PostMessageW(g_state.hwnd, WM_APP_CANCEL, 0, 0);
            }

            

            return 0;
        }

        case WM_DESTROY: {
            g_state.hwnd = nullptr;
            return 0;
        }
    }

    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

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

    

    wc.hIcon   = reinterpret_cast<HICON>(LoadImageW(
            GetModuleHandleW(nullptr), MAKEINTRESOURCEW(IDI_APPICON),
            IMAGE_ICON, 0, 0, LR_DEFAULTCOLOR));
    wc.hIconSm = reinterpret_cast<HICON>(LoadImageW(
            GetModuleHandleW(nullptr), MAKEINTRESOURCEW(IDI_APPICON),
            IMAGE_ICON, 0, 0, LR_DEFAULTCOLOR));

    RegisterClassExW(&wc);
    registered = true;
}

} 

namespace Ui {

void InitCommonControls() {
    INITCOMMONCONTROLSEX icc;
    icc.dwSize = sizeof(icc);
    icc.dwICC  = ICC_PROGRESS_CLASS | ICC_STANDARD_CLASSES;
    InitCommonControlsEx(&icc);
    EnsureWindowClass();
}

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

    int w = 460;
    int h = 175;

    int screenW = GetSystemMetrics(SM_CXSCREEN);
    int screenH = GetSystemMetrics(SM_CYSCREEN);
    int x = (screenW - w) / 2;
    int y = (screenH - h) / 2;

    

    

    

    
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

void UpdateProgress(HWND hwnd, const std::wstring& stage,
                    uint64_t done, uint64_t total) {
    if (!hwnd || g_state.finished) {
        return;
    }

    int percent = -1;
    if (total > 0) {

        double p = static_cast<double>(done) / static_cast<double>(total) * 100.0;
        percent = static_cast<int>(p);
        if (percent > 100) percent = 100;
        if (percent < 0)   percent = 0;
    }

    

    

    

    

    

    

    
    static std::atomic<int>  lastPercent{-2};
    static std::atomic<DWORD> lastTick{0};

    DWORD now = GetTickCount();

    int  prevPercent = lastPercent.load(std::memory_order_relaxed);
    DWORD prevTick   = lastTick.load(std::memory_order_relaxed);

    bool percentChanged = (percent != prevPercent);
    bool timeElapsed = (prevTick == 0) || (now - prevTick >= 200);

    if (!percentChanged && !timeElapsed) {
        return;     
    }

    lastPercent.store(percent, std::memory_order_relaxed);
    lastTick.store(now, std::memory_order_relaxed);

    wchar_t* stageCopy = nullptr;
    if (!stage.empty()) {

        size_t len = stage.size() + 1;
        stageCopy = new wchar_t[len];
        wcsncpy_s(stageCopy, len, stage.c_str(), _TRUNCATE);
    }

    PostMessageW(hwnd, WM_APP_PROGRESS,
                 static_cast<WPARAM>(percent),
                 reinterpret_cast<LPARAM>(stageCopy));
}

void CloseProgressWindow(HWND hwnd) {
    if (!hwnd) {
        return;
    }
    g_state.finished = true;
    PostMessageW(hwnd, WM_APP_FINISHED, 0, 0);
}

void PumpMessages() {
    MSG msg;
    while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
        if (msg.message == WM_QUIT) {

            g_state.cancelled = true;
            break;
        }
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
}

bool IsCancelled(HWND ) {
    return g_state.cancelled;
}

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

    return TRUE;
}
} 
