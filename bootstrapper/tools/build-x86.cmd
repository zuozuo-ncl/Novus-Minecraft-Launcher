@echo off
rem x86 (32-bit) build. Called by tools/build-x86.sh, which builds the resource first.
rem Works around a local toolchain issue: with the default PATH the 32-bit cc1
rem fails to load (0xc000007b). A minimal PATH fixes it.
setlocal
cd /d "%~dp0.."
set RES=%~1
set PATH=C:\msys64\mingw32\bin;C:\Windows\system32;C:\Windows
C:\msys64\mingw32\bin\g++.exe -std=c++17 -O2 -Wall -Wextra -Wno-unused-parameter ^
  -DUNICODE -D_UNICODE -DWIN32_LEAN_AND_MEAN -DNOMINMAX -D_WIN32_WINNT=0x0601 ^
  -Isrc -municode -mwindows -static -static-libgcc -static-libstdc++ ^
  -o bin\MCLauncherBootstrap_x86.exe ^
  %RES% src\main.cpp src\util.cpp src\http.cpp src\zip.cpp ^
  src\java.cpp src\launcher.cpp src\ui.cpp ^
  -lwinhttp -lshell32 -lshlwapi -lole32 -loleaut32 -luuid ^
  -luser32 -lgdi32 -lcomctl32 -ladvapi32 -lbcrypt
if errorlevel 1 (
  echo [FAILED] x86 build failed
  exit /b 1
)
echo [OK] bin\MCLauncherBootstrap_x86.exe
