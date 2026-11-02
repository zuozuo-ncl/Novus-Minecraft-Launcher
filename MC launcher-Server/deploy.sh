#!/bin/bash
# MC Launcher Server 部署脚本
# 用于在 Ubuntu 服务器上快速部署

set -e

# ==================== 配置 ====================
REMOTE_USER="www"           # 服务器用户
REMOTE_HOST="your-server-ip" # 服务器 IP 或域名
REMOTE_DIR="/www/wwwroot/MC launcher-Server"  # 远程目录
LOCAL_DIR="$(cd "$(dirname "$0")" && pwd)"    # 本地项目目录
# ==============================================

# 颜色
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

show_help() {
    cat << EOF
部署脚本用法:
  $0 [选项]

选项:
  -h, --host HOST      服务器地址 (默认: $REMOTE_HOST)
  -u, --user USER      SSH 用户 (默认: $REMOTE_USER)
  -d, --dir DIR        远程目录 (默认: $REMOTE_DIR)
  --setup              首次部署: 安装依赖、配置 systemd
  --update             更新代码并重启
  --logs               查看远程日志
  --status             查看远程状态

示例:
  # 首次部署
  $0 --setup -h 192.168.1.100 -u www
  
  # 更新代码
  $0 --update -h 192.168.1.100
  
  # 查看日志
  $0 --logs -h 192.168.1.100

EOF
}

# 检查 SSH 连接
check_ssh() {
    log_info "检查 SSH 连接: $REMOTE_USER@$REMOTE_HOST"
    if ! ssh -o ConnectTimeout=10 -o BatchMode=yes "$REMOTE_USER@$REMOTE_HOST" "echo 'SSH OK'" 2>/dev/null; then
        log_error "SSH 连接失败，请检查:"
        echo "  1. 服务器地址: $REMOTE_HOST"
        echo "  2. 用户名: $REMOTE_USER"
        echo "  3. SSH 密钥是否已配置 (ssh-copy-id)"
        echo "  4. 防火墙/安全组是否开放 22 端口"
        exit 1
    fi
    log_info "SSH 连接正常"
}

# 同步文件
sync_files() {
    log_info "同步文件到服务器..."
    
    # 排除不需要的文件
    rsync -avz --delete \
        --exclude 'node_modules' \
        --exclude '.git' \
        --exclude 'logs' \
        --exclude '*.log' \
        --exclude 'test*' \
        --exclude '*.sh' \
        --exclude '*.service' \
        "$LOCAL_DIR/" "$REMOTE_USER@$REMOTE_HOST:$REMOTE_DIR/"
    
    log_info "文件同步完成"
}

# 远程安装依赖
remote_install() {
    log_info "远程安装依赖..."
    ssh "$REMOTE_USER@$REMOTE_HOST" "cd '$REMOTE_DIR' && npm install --production"
    log_info "依赖安装完成"
}

# 远程配置 ADMIN_TOKEN
#
# 以前 --setup 完全不处理 ADMIN_TOKEN：按文档部署后写接口一律 503，
# 管理员还以为服务坏了。这里自动生成一次随机令牌写入 .env（600），
# 已存在则沿用。
setup_admin_token() {
    local env_file="$REMOTE_DIR/.env"
    local existing
    existing=$(ssh "$REMOTE_USER@$REMOTE_HOST" "test -f '$env_file' && grep -E '^ADMIN_TOKEN=' '$env_file' | cut -d= -f2- || true" 2>/dev/null || true)
    if [ -n "$existing" ]; then
        log_info "沿用已有的 ADMIN_TOKEN"
        ADMIN_TOKEN_VALUE="$existing"
    else
        ADMIN_TOKEN_VALUE=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' 
')
        ssh "$REMOTE_USER@$REMOTE_HOST" "umask 077; mkdir -p '$REMOTE_DIR'; printf 'ADMIN_TOKEN=%s
' '$ADMIN_TOKEN_VALUE' > '$env_file'; chmod 600 '$env_file'"
        log_info "已生成管理令牌并写入 $env_file（600）"
    fi
    log_info "Web 管理页需要填写的令牌：$ADMIN_TOKEN_VALUE"
}

# 远程配置 systemd
remote_setup_systemd() {
    log_info "配置 systemd 服务..."
    setup_admin_token
    
    # 上传 service 文件
    scp "$LOCAL_DIR/mc-launcher-server.service" "$REMOTE_USER@$REMOTE_HOST:/tmp/"
    
    # 修改 service 文件中的用户和路径
    ssh "$REMOTE_USER@$REMOTE_HOST" "
        sed -i 's/User=www/User=$REMOTE_USER/' /tmp/mc-launcher-server.service
        sed -i 's/Group=www/Group=$(id -gn)/' /tmp/mc-launcher-server.service
        sed -i \"s|WorkingDirectory=.*|WorkingDirectory=$REMOTE_DIR|\" /tmp/mc-launcher-server.service
        sed -i \"s|ReadWritePaths=.*|ReadWritePaths=$REMOTE_DIR\\nReadWritePaths=$REMOTE_DIR/logs|\" /tmp/mc-launcher-server.service
        
        sudo mv /tmp/mc-launcher-server.service /etc/systemd/system/
        sudo systemctl daemon-reload
        sudo systemctl enable mc-launcher-server
        sudo systemctl start mc-launcher-server
        sudo systemctl status mc-launcher-server --no-pager
    "
    
    log_info "systemd 服务配置完成"
}

# 远程重启服务
remote_restart() {
    log_info "重启远程服务..."
    ssh "$REMOTE_USER@$REMOTE_HOST" "sudo systemctl restart mc-launcher-server && sleep 2 && sudo systemctl status mc-launcher-server --no-pager"
    log_info "服务重启完成"
}

# 远程查看日志
remote_logs() {
    log_info "查看远程日志 (Ctrl+C 退出)..."
    ssh -t "$REMOTE_USER@$REMOTE_HOST" "sudo journalctl -u mc-launcher-server -f"
}

# 远程查看状态
remote_status() {
    log_info "查看远程服务状态..."
    ssh "$REMOTE_USER@$REMOTE_HOST" "sudo systemctl status mc-launcher-server --no-pager"
}

# 主函数
main() {
    local action=""
    
    while [[ $# -gt 0 ]]; do
        case $1 in
            -h|--host)
                REMOTE_HOST="$2"
                shift 2
                ;;
            -u|--user)
                REMOTE_USER="$2"
                shift 2
                ;;
            -d|--dir)
                REMOTE_DIR="$2"
                shift 2
                ;;
            --setup)
                action="setup"
                shift
                ;;
            --update)
                action="update"
                shift
                ;;
            --logs)
                action="logs"
                shift
                ;;
            --status)
                action="status"
                shift
                ;;
            -h|--help)
                show_help
                exit 0
                ;;
            *)
                log_error "未知参数: $1"
                show_help
                exit 1
                ;;
        esac
    done
    
    if [[ -z "$action" ]]; then
        log_error "请指定操作: --setup, --update, --logs, 或 --status"
        show_help
        exit 1
    fi
    
    if [[ "$REMOTE_HOST" == "your-server-ip" ]]; then
        log_error "请指定服务器地址: -h 或 --host"
        exit 1
    fi
    
    check_ssh
    
    case $action in
        setup)
            sync_files
            remote_install
            remote_setup_systemd
            log_info "首次部署完成!"
            echo "访问地址: http://$REMOTE_HOST:5003/"
            ;;
        update)
            sync_files
            remote_install
            remote_restart
            log_info "更新完成!"
            ;;
        logs)
            remote_logs
            ;;
        status)
            remote_status
            ;;
    esac
}

main "$@"