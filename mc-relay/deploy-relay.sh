set -euo pipefail

SERVER="${1:-Your public ip}"
REMOTE_DIR="/opt/mc-relay"
HTTP_PORT="${HTTP_PORT:-Your port}"
RANGE="${RANGE:Your port range}"

echo "=== 部署到 $SERVER ==="

echo "[1/5] 上传文件"
ssh "admin@$SERVER" "sudo mkdir -p $REMOTE_DIR && sudo chown \$USER:\$USER $REMOTE_DIR"
scp relay-server.js "admin@$SERVER:$REMOTE_DIR/"

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
Environment=HTTP_PORT=$HTTP_PORT
Environment=RANGE=$RANGE
Environment=HOST=0.0.0.0
ExecStart=/usr/bin/node $REMOTE_DIR/relay-server.js
Restart=always
RestartSec=5
LimitNOFILE=65535
StandardOutput=journal
StandardError=journal
SyslogIdentifier=mc-relay

[Install]
WantedBy=multi-user.target
UNIT

echo "[3/5] 启动服务"
ssh "admin@$SERVER" "sudo systemctl daemon-reload && sudo systemctl enable --now mc-relay && sleep 2 && sudo systemctl --no-pager status mc-relay | head -12"

echo "[4/5] 防火墙"
ssh "admin@$SERVER" "if command -v ufw >/dev/null && sudo ufw status 2>/dev/null | grep -q active; then
  sudo ufw allow $HTTP_PORT/tcp comment 'mc-relay api' >/dev/null
  sudo ufw allow $RANGE/tcp comment 'mc-relay tunnels' >/dev/null
  echo '  ufw 已放行'
else
  echo '  ufw 未启用 —— 需要去云控制台安全组放行:'
  echo '    TCP $HTTP_PORT      （申请房间 API）'
  echo '    TCP $RANGE （端口池，玩家连这里）'
fi"

echo "[5/5] 自检"
sleep 1
curl -s "http://$SERVER:$HTTP_PORT/health" || echo "  API 不可达（检查安全组）"
echo
