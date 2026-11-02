cmake_minimum_required(VERSION 3.15)

project(MCLauncherBootstrap
        VERSION 1.0.0
        DESCRIPTION "Minecraft Launcher Native Bootstrapper"
        LANGUAGES CXX)

# ---------------------------------------------------------------
# C++ 标准
# ---------------------------------------------------------------
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# ---------------------------------------------------------------
# 只在 Windows 上构建（本引导器大量使用 Win32 API）
# ---------------------------------------------------------------
if(NOT WIN32)
    message(FATAL_ERROR "本项目仅支持 Windows（依赖 WinHTTP / Win32 API）")
endif()

# ---------------------------------------------------------------
# 源码
# ---------------------------------------------------------------
set(BOOTSTRAP_SOURCES
    src/main.cpp
    src/util.cpp
    src/http.cpp
    src/zip.cpp
    src/java.cpp
    src/launcher.cpp
    src/ui.cpp
)

# UNICODE / _UNICODE：全程使用宽字符 API，正确处理中文路径
add_executable(MCLauncherBootstrap WIN32 ${BOOTSTRAP_SOURCES})

target_include_directories(MCLauncherBootstrap PRIVATE src)

target_compile_definitions(MCLauncherBootstrap PRIVATE
    UNICODE
    _UNICODE
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    _WIN32_WINNT=0x0601     # Windows 7 及以上
)

# 链接所需的系统库
#   winhttp   —— HTTP/HTTPS 下载
#   shell32   —— ShellExecuteW / SHGetFolderPathW
#   shlwapi   —— 路径辅助
#   ole32     —— COM 初始化（Shell 解压）
#   oleaut32  —— Variant/SysAllocString 等 OLE 自动化函数（Shell 解压必需）
#   uuid      —— GUID_NULL / IID_IDispatch 等符号
#   user32/gdi32/comctl32 —— 原生 UI 与进度条
#   advapi32  —— 注册表读取（检测已安装的 Java）
target_link_libraries(MCLauncherBootstrap PRIVATE
    winhttp
    shell32
    shlwapi
    ole32
    oleaut32
    uuid
    user32
    gdi32
    comctl32
    advapi32
    bcrypt       # 计算 jar 的 SHA-256（完整性校验）
)

# ---------------------------------------------------------------
# 编译器选项
# ---------------------------------------------------------------
if(MSVC)
    target_compile_options(MCLauncherBootstrap PRIVATE /W4 /utf-8 /permissive-)
    # 静态链接 CRT，避免用户机器缺 VC++ 运行库
    set_property(TARGET MCLauncherBootstrap PROPERTY
                 MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
else()
    # MinGW / GCC
    target_compile_options(MCLauncherBootstrap PRIVATE
        -Wall -Wextra -Wno-unused-parameter -finput-charset=UTF-8)
    # -municode   让入口点解析为 wWinMain（而不是 ANSI 的 WinMain）
    #             缺了它会报 undefined reference to `WinMain'
    # -mwindows   标记为 GUI 子系统，运行时不弹控制台黑框
    # -static*    静态链接运行时，exe 可独立分发
    target_link_options(MCLauncherBootstrap PRIVATE
        -municode -mwindows
        -static-libgcc -static-libstdc++ -static)
endif()

# ---------------------------------------------------------------
# 输出：exe 直接生成在项目根，方便取用
# ---------------------------------------------------------------
set_target_properties(MCLauncherBootstrap PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/bin"
    OUTPUT_NAME "MCLauncherBootstrap"
)

# 用于嵌入 exe 图标（可选）：把 icon.ico 放在 src/ 下即可
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/src/app.rc")
    # app.rc 里用相对路径引用 app.ico：把 src 加进资源搜索路径
    enable_language(RC)
    set(CMAKE_RC_FLAGS "${CMAKE_RC_FLAGS} -I${CMAKE_CURRENT_SOURCE_DIR}/src")
    target_sources(MCLauncherBootstrap PRIVATE src/app.rc src/app.ico)
endif()