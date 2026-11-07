#!/usr/bin/env bash
# ----------------------------------------------------------------------------
#  意见反馈端到端测试：起一个带本地假 SMTP 的服务端，再用客户端同款请求提交
#
#  用法：bash tools/run-feedback-e2e.sh [服务端目录]
#        默认服务端目录是 ../MC launcher-Server
#
#  需要：node（含 nodemailer，服务端目录里 npm i）、JDK 17+、target/cp.txt
# ----------------------------------------------------------------------------
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER_DIR="${1:-../MC launcher-Server}"
[ -f "$SERVER_DIR/feedback.js" ] || { echo "找不到服务端：$SERVER_DIR" >&2; exit 2; }

find_jdk() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then echo "$JAVA_HOME/bin"; return; fi
  for d in "/c/Program Files/Java/jdk-21.0.10" "/c/Program Files/Java/jdk-17"; do
    [ -x "$d/bin/javac" ] && { echo "$d/bin"; return; }
  done
  command -v javac >/dev/null 2>&1 && { dirname "$(command -v javac)"; return; }
  echo "找不到 javac：请设置 JAVA_HOME" >&2; exit 2
}
BIN="$(find_jdk)"
CP="$(tr -d '\r' < target/cp.txt)"

WORK="$(mktemp -d)"
PORT=$(( 21000 + RANDOM % 5000 ))
if command -v cygpath >/dev/null 2>&1; then WORK_WIN="$(cygpath -w "$WORK")"; else WORK_WIN="$WORK"; fi

cleanup() {
  [ -n "${JAVA_PID:-}" ] && kill "$JAVA_PID" 2>/dev/null || true
  [ -n "${SRV_PID:-}" ] && kill "$SRV_PID" 2>/dev/null || true
  [ -n "${SMTP_PID:-}" ] && kill "$SMTP_PID" 2>/dev/null || true
  if [ -f "$WORK/created" ]; then while IFS= read -r f; do rm -f "$f"; done < "$WORK/created"; fi
  rm -rf "$WORK"
}
trap cleanup EXIT

# 1) 假 SMTP（复用服务端目录里的 test-feedback.js）
#    require 按「脚本所在目录」解析，所以脚本必须放进服务端目录；用完删除。
SMTP_PORT=$(( PORT + 1000 ))
SMTP_SCRIPT="$SERVER_DIR/.run-feedback-e2e-smtp.js"
cat > "$SMTP_SCRIPT" <<'EOF'
const { startSmtp } = require('./test-feedback');
startSmtp({ port: Number(process.env.SMTP_LISTEN_PORT) }).then((s) => {
  console.log('SMTP listening on ' + s.port);
});
EOF
echo "$SMTP_SCRIPT" >> "$WORK/created"
( cd "$SERVER_DIR" && SMTP_LISTEN_PORT="$SMTP_PORT" node .run-feedback-e2e-smtp.js > "$WORK/smtp.log" 2>&1 ) &
SMTP_PID=$!
for _ in $(seq 1 50); do
  grep -q "SMTP listening" "$WORK/smtp.log" 2>/dev/null && break
  sleep 0.1
done
if ! grep -q "SMTP listening" "$WORK/smtp.log" 2>/dev/null; then
  echo "假 SMTP 启动失败："; cat "$WORK/smtp.log"; exit 1
fi
echo "假 SMTP 端口 $SMTP_PORT"

# 2) 服务端
( cd "$SERVER_DIR" && PORT="$PORT" HOST=127.0.0.1 \
    SMTP_HOST=127.0.0.1 SMTP_PORT="$SMTP_PORT" SMTP_SECURE=false \
    SMTP_USER=bot@example.com SMTP_PASS=dummy TARGET_EMAIL=owner@example.com \
    FEEDBACK_LOG="$WORK/feedback.log" node server.js > "$WORK/server.log" 2>&1 ) &
SRV_PID=$!
for _ in $(seq 1 100); do
  curl -fs "http://127.0.0.1:$PORT/health" >/dev/null 2>&1 && break
  sleep 0.1
done
curl -fs "http://127.0.0.1:$PORT/health" >/dev/null || { echo "服务端未启动"; cat "$WORK/server.log"; exit 1; }

# 3) 客户端契约测试
echo "编译并运行 FeedbackE2E ..."
"$BIN/javac" -encoding UTF-8 --release 17 -nowarn -d "$WORK_WIN/classes" -cp "$CP" \
    $(find src/main/java src/test/e2e -name '*.java')
set +e
"$BIN/java" -Dstdout.encoding=UTF-8 -cp "$WORK_WIN/classes;$CP" \
    com.mclauncher.test.e2e.FeedbackE2E "http://127.0.0.1:$PORT"
CODE=$?
set -e

# 4) 检查本地日志里确实有中文与标记
if grep -q "E2E-MARKER-中文" "$WORK/feedback.log" 2>/dev/null; then
  echo "  ok   服务端 data/feedback.log 记录了反馈（含中文）"
else
  echo "  FAIL 本地日志里找不到测试反馈"; CODE=1
fi

exit "$CODE"