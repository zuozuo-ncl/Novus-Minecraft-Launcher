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
