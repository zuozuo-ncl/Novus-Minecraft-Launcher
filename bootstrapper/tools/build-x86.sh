#!/usr/bin/env bash
# x86 (32-bit) 构建
#
# 用法：bash tools/build-x86.sh
#
# 本机 32 位 MinGW 工具链直接调用会失败（cc1 加载时报 0xc000007b），
# 必须在 cmd 里用「只含 mingw32 的 PATH」执行 —— 见 tools/build-x86.cmd。
# 资源文件用 64 位 gcc 做预处理（-xc-header），再交给 32 位 windres 汇编，
# 因为 32 位 gcc 的预处理同样受这个问题影响。
set -e
BOOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$BOOT"

X86_WR='C:\msys64\mingw32\bin\windres.exe'
X86_CPP='C:\msys64\ucrt64\bin\gcc.exe'
TMP="$(cygpath -w /tmp)"

"$X86_WR" --preprocessor="$X86_CPP -E -xc-header -DRC_INVOKED" \
    -I src src/app.rc -O coff -o "$TMP/x86_res.o"

"$BOOT/tools/build-x86.cmd" "$TMP\\x86_res.o"
ls -la bin/MCLauncherBootstrap_x86.exe