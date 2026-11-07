#!/usr/bin/env bash
# ----------------------------------------------------------------------------
#  运行离线测试（不需要 Maven、不联网、不需要 JavaFX 运行时）
#
#  用法：bash tools/run-tests.sh
#
#  依赖：
#    - JDK 17+（javac / java）。默认用 JAVA_HOME/bin，找不到时依次尝试
#      /c/Program Files/Java/jdk-21.0.10 与 java 命令。
#    - target/cp.txt：依赖 jar 的 classpath（由 mvn dependency:build-classpath
#      生成，仓库里已经有一份）。缺失时脚本会提示如何生成。
# ----------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."

find_jdk() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    echo "$JAVA_HOME/bin"; return
  fi
  for d in "/c/Program Files/Java/jdk-21.0.10" "/c/Program Files/Java/jdk-17"; do
    [ -x "$d/bin/javac" ] && { echo "$d/bin"; return; }
  done
  command -v javac >/dev/null 2>&1 && { dirname "$(command -v javac)"; return; }
  echo "找不到 javac：请设置 JAVA_HOME" >&2; exit 2
}
BIN="$(find_jdk)"
JAVAC="$BIN/javac"; JAVA="$BIN/java"

if [ ! -f target/cp.txt ]; then
  echo "缺少 target/cp.txt（依赖 classpath）。生成方式：" >&2
  echo "  mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt" >&2
  exit 2
fi
CP="$(tr -d '\r' < target/cp.txt)"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
# Git Bash 下 mktemp 给的是 MSYS 路径（/tmp/...），Java 不认，转成 Windows 路径
if command -v cygpath >/dev/null 2>&1; then
  OUT_WIN="$(cygpath -w "$OUT")"
else
  OUT_WIN="$OUT"
fi

echo "编译 main + test ..."
"$JAVAC" -encoding UTF-8 --release 17 -nowarn -d "$OUT_WIN" -cp "$CP" \
    $(find src/main/java src/test/java -name '*.java')

echo "运行测试 ..."
# 由 AllTests 统一汇总；各测试类只注册用例、不自行退出
"$JAVA" -Dstdout.encoding=UTF-8 \
    -cp "$OUT_WIN;$CP" com.mclauncher.test.AllTests
