#!/usr/bin/env bash
# ----------------------------------------------------------------------------
#  联机中继端到端测试：本机起一个 Go 中继，跑 src/test/e2e 下的用例
#
#  用法：bash tools/run-e2e-tests.sh [中继源码目录]
#        默认中继目录是 ../mc-relay-go
#
#  需要：go、JDK 17+、target/cp.txt（同 run-tests.sh）
# ----------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."

RELAY_SRC="${1:-../mc-relay-go}"
[ -d "$RELAY_SRC" ] || { echo "找不到中继源码目录：$RELAY_SRC" >&2; exit 2; }

# 随机挑一组空闲端口（HTTP / VPN 两端口），冲突概率可忽略
PORT=$(( 20000 + RANDOM % 20000 ))

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
CP="$(tr -d '\r' < target/cp.txt)"

WORK="$(mktemp -d)"
trap 'kill "${RELAY_PID:-}" 2>/dev/null || true; rm -rf "$WORK"' EXIT
if command -v cygpath >/dev/null 2>&1; then WORK_WIN="$(cygpath -w "$WORK")"; else WORK_WIN="$WORK"; fi

echo "构建中继 ..."
( cd "$RELAY_SRC" && go build -o "$WORK/relay" . )

echo "启动中继（HTTP 127.0.0.1:$PORT）..."
"$WORK/relay" -http-port "$PORT" -host 127.0.0.1 > "$WORK/relay.log" 2>&1 &
RELAY_PID=$!
for _ in $(seq 1 50); do
  if curl -s "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then break; fi
  sleep 0.2
done

echo "编译 e2e 用例 ..."
"$BIN/javac" -encoding UTF-8 --release 17 -nowarn -d "$WORK_WIN/classes" -cp "$CP" \
    $(find src/main/java src/test/e2e -name '*.java')

echo "运行 e2e ..."
set +e
"$BIN/java" -Dstdout.encoding=UTF-8 -cp "$WORK_WIN/classes;$CP" \
    com.mclauncher.test.e2e.RelayE2E "$PORT"
CODE=$?
set -e

if [ "$CODE" -ne 0 ]; then
  echo "--- 中继日志（末尾） ---"
  tail -30 "$WORK/relay.log"
fi
exit "$CODE"