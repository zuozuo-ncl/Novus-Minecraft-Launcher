#!/usr/bin/env bash
# MC 联机中转 —— 部署到 Ubuntu 服务器
# 用法：bash deploy-relay.sh [server_ip]
#
# 环境变量：
#   SERVER_IP   目标服务器（也可用第一个参数；都没有时直接报错，不再硬编码公网 IP）
#   API_KEY     开房/列表接口的密钥。未设置时自动生成一个并写入远端 .env，
#               启动器需要在请求里带 X-Api-Key（relay-host.js 支持 --api-key）
set -euo pipefail

SERVER="${1:-${SERVER_IP:-}}"
if [ -z "$SERVER" ]; then
  echo "用法：SERVER_IP=1.2.3.4 bash deploy-relay.sh   （或 bash deploy-relay.sh 1.2.3.4）" >&2
  exit 2
fi
REMOTE_DIR="/opt/mc-relay"
HTTP_PORT="${HTTP_PORT:-8080}"
# VPN 帧端口 = HTTP_PORT + 1（房主/玩家的长连接都连这里）
VPN_PORT="${VPN_PORT:-$((HTTP_PORT + 1))}"

echo "=== 部署到 $SERVER ==="

# 1. 上传
#    必须把 relay-server.js 的依赖一起传上去：只传 relay-server.js 时，
#    require('./vpn-room') 会 MODULE_NOT_FOUND，服务无限重启，自检还会误报成安全组问题。
echo "[1/5] 上传文件"
ssh "admin@$SERVER" "sudo mkdir -p $REMOTE_DIR && sudo chown \$USER:\$USER $REMOTE_DIR"
scp relay-server.js vpn-room.js vpn-tunnel.js "admin@$SERVER:$REMOTE_DIR/"

# 1.5 生成/保留 API_KEY
echo "[1.5/5] 配置 API_KEY"
KEY="${API_KEY:-}"
if [ -z "$KEY" ]; then
  KEY=$(ssh "admin@$SERVER" "if [ -f $REMOTE_DIR/.env ]; then grep -E '^API_KEY=' $REMOTE_DIR/.env | cut -d= -f2-; fi")
  if [ -z "$KEY" ]; then
    KEY=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
    echo "  已生成新的 API_KEY，请保存：" >&2
    echo "  $KEY" >&2
  else
    echo "  沿用远端已有的 API_KEY"
  fi
fi
ssh "admin@$SERVER" "umask 077 && printf 'API_KEY=%s\n' '$KEY' > $REMOTE_DIR/.env"

# 2. systemd 服务
echo "[2/5] 配置 systemd"
ssh "admin@$SERVER" "sudo tee /etc/systemd/system/mc-relay.service > /dev/null" <<UNIT
[Unit]
Description=MC Relay Server (LAN tunneling for Minecraft)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$USER
WorkingDirectory=$REMOTE_DIR
EnvironmentFile=$REMOTE_DIR/.env
Environment=HTTP_PORT=$HTTP_PORT
Environment=VPN_PORT=$VPN_PORT
Environment=HOST=0.0.0.0
ExecStart=/usr/bin/env node $REMOTE_DIR/relay-server.js
Restart=always
RestartSec=5
LimitNOFILE=65535
StandardOutput=journal
StandardError=journal
SyslogIdentifier=mc-relay

[Install]
WantedBy=multi-user.target
UNIT

# 3. 启动
echo "[3/5] 启动服务"
ssh "admin@$SERVER" "sudo systemctl daemon-reload && sudo systemctl enable --now mc-relay && sleep 2 && sudo systemctl --no-pager status mc-relay | head -12"

# 4. 防火墙（有 ufw 才动）
echo "[4/5] 防火墙"
ssh "admin@$SERVER" "if command -v ufw >/dev/null && sudo ufw status 2>/dev/null | grep -q active; then
  sudo ufw allow $HTTP_PORT/tcp comment 'mc-relay api' >/dev/null
  sudo ufw allow $VPN_PORT/tcp comment 'mc-relay vpn frames' >/dev/null
  echo '  ufw 已放行'
else
  echo '  ufw 未启用 —— 需要去云控制台安全组放行:'
  echo '    TCP $HTTP_PORT      （申请房间 API + 房主控制通道）'
  echo '    TCP $VPN_PORT      （VPN 帧端口，房主/玩家长连接）'
fi"

# 5. 自检
echo "[5/5] 自检"
sleep 1
for i in 1 2 3; do
  if curl -fsS "http://$SERVER:$HTTP_PORT/health" >/dev/null; then
    echo "  /health 正常"
    break
  fi
  if [ "$i" = 3 ]; then
    echo "  /health 连续 $i 次失败，下面是服务端日志尾部：" >&2
    ssh "admin@$SERVER" "sudo journalctl -u mc-relay -n 30 --no-pager" >&2 || true
    exit 1
  fi
  sleep 2
done
curl -s "http://$SERVER:$HTTP_PORT/health"; echo
echo "  启动器/relay-host 请求需要带上：X-Api-Key: $KEY"