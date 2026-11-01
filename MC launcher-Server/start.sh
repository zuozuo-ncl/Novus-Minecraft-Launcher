#!/bin/bash
# MC Launcher Server 启动脚本
# 适用于 Ubuntu / Linux 系统
# 目标目录: /www/wwwroot/MC launcher-Server

set -e  # 遇到错误立即退出

# ==================== 配置区域 ====================
PROJECT_DIR="/www/wwwroot/MC launcher-Server"
SERVER_FILE="server.js"
PORT=5003
NODE_VERSION="18"  # 建议 Node.js 版本
LOG_DIR="$PROJECT_DIR/logs"
PID_FILE="$PROJECT_DIR/server.pid"
# ==================================================

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_debug() {
    echo -e "${BLUE}[DEBUG]${NC} $1"
}

# 检查是否为 root 用户（不建议用 root 运行）
check_root() {
    if [[ $EUID -eq 0 ]]; then
        log_warn "检测到 root 用户运行，建议使用普通用户运行服务"
        read -p "是否继续? (y/N): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
    fi
}

# 检查 Node.js 环境
check_node() {
    if ! command -v node &> /dev/null; then
        log_error "未找到 Node.js，请先安装 Node.js ${NODE_VERSION}+"
        echo "安装命令示例:"
        echo "  curl -fsSL https://deb.nodesource.com/setup_${NODE_VERSION}.x | sudo -E bash -"
        echo "  sudo apt-get install -y nodejs"
        exit 1
    fi
    
    local node_ver=$(node -v | sed 's/v//')
    local major_ver=$(echo $node_ver | cut -d. -f1)
    
    if [[ $major_ver -lt $NODE_VERSION ]]; then
        log_warn "Node.js 版本过低: v$node_ver (建议 v${NODE_VERSION}+)"
    else
        log_info "Node.js 版本: v$node_ver ✓"
    fi
    
    if ! command -v npm &> /dev/null; then
        log_error "未找到 npm"
        exit 1
    fi
}

# 检查项目目录
check_project_dir() {
    if [[ ! -d "$PROJECT_DIR" ]]; then
        log_error "项目目录不存在: $PROJECT_DIR"
        exit 1
    fi
    
    cd "$PROJECT_DIR"
    
    if [[ ! -f "$SERVER_FILE" ]]; then
        log_error "服务器文件不存在: $SERVER_FILE"
        exit 1
    fi
    
    if [[ ! -f "package.json" ]]; then
        log_error "package.json 不存在"
        exit 1
    fi
    
    log_info "项目目录检查通过: $PROJECT_DIR"
}

# 安装依赖
install_deps() {
    log_info "检查并安装依赖..."
    
    if [[ ! -d "node_modules" ]] || [[ ! -f "package-lock.json" ]]; then
        log_info "安装 npm 依赖..."
        npm install --production
    else
        log_info "依赖已存在，跳过安装"
    fi
}

# 创建日志目录
setup_logs() {
    mkdir -p "$LOG_DIR"
    log_info "日志目录: $LOG_DIR"
}

# 检查端口占用
check_port() {
    if lsof -ti:$PORT &> /dev/null; then
        local pids=$(lsof -ti:$PORT)
        log_warn "端口 $PORT 被占用，进程: $pids"
        return 1
    fi
    return 0
}

# 启动服务器
start_server() {
    log_info "启动服务器..."
    
    # 检查是否已经在运行
    if [[ -f "$PID_FILE" ]]; then
        local old_pid=$(cat "$PID_FILE" 2>/dev/null)
        if [[ -n "$old_pid" ]] && kill -0 "$old_pid" 2>/dev/null; then
            log_error "服务器已在运行 (PID: $old_pid)"
            return 1
        else
            log_warn "发现残留 PID 文件，清理中..."
            rm -f "$PID_FILE"
        fi
    fi
    
    # 检查端口
    if ! check_port; then
        log_error "端口 $PORT 被占用，无法启动"
        return 1
    fi
    
    # 启动服务器 (使用 nohup 后台运行)
    cd "$PROJECT_DIR"
    nohup node "$SERVER_FILE" >> "$LOG_DIR/server.log" 2>&1 &
    local pid=$!
    
    # 保存 PID
    echo $pid > "$PID_FILE"
    
    # 等待启动
    sleep 2
    
    # 验证启动成功
    if kill -0 $pid 2>/dev/null; then
        if lsof -ti:$PORT | grep -q $pid; then
            log_info "服务器启动成功! PID: $pid"
            log_info "监听端口: $PORT"
            log_info "日志文件: $LOG_DIR/server.log"
            return 0
        fi
    fi
    
    log_error "服务器启动失败"
    rm -f "$PID_FILE"
    return 1
}

# 停止服务器
stop_server() {
    log_info "停止服务器..."
    
    if [[ ! -f "$PID_FILE" ]]; then
        log_warn "PID 文件不存在，尝试查找进程..."
        local pids=$(lsof -ti:$PORT)
        if [[ -n "$pids" ]]; then
            for pid in $pids; do
                kill $pid 2>/dev/null && log_info "已停止进程: $pid"
            done
        else
            log_info "未发现运行中的服务器"
        fi
        return 0
    fi
    
    local pid=$(cat "$PID_FILE" 2>/dev/null)
    if [[ -z "$pid" ]]; then
        log_warn "PID 文件为空"
        rm -f "$PID_FILE"
        return 0
    fi
    
    if kill -0 $pid 2>/dev/null; then
        kill $pid
        sleep 1
        
        # 强制杀死
        if kill -0 $pid 2>/dev/null; then
            kill -9 $pid
            log_warn "强制终止进程: $pid"
        else
            log_info "服务器已停止 (PID: $pid)"
        fi
    else
        log_info "进程 $pid 已不存在"
    fi
    
    rm -f "$PID_FILE"
}

# 重启服务器
restart_server() {
    log_info "重启服务器..."
    stop_server
    sleep 1
    start_server
}

# 查看状态
status_server() {
    echo "===================="
    echo "  服务器状态检查"
    echo "===================="
    
    if [[ -f "$PID_FILE" ]]; then
        local pid=$(cat "$PID_FILE" 2>/dev/null)
        if [[ -n "$pid" ]] && kill -0 $pid 2>/dev/null; then
            echo -e "状态: ${GREEN}运行中${NC} (PID: $pid)"
            
            # 检查端口
            if lsof -ti:$PORT | grep -q $pid; then
                echo -e "端口 $PORT: ${GREEN}监听中${NC}"
            else
                echo -e "端口 $PORT: ${RED}未监听${NC}"
            fi
            
            # 显示进程信息
            echo "进程信息:"
            ps -p $pid -o pid,ppid,cmd,etime,pcpu,pmem 2>/dev/null || true
        else
            echo -e "状态: ${RED}未运行${NC} (PID 文件存在但进程不存在)"
        fi
    else
        # 尝试通过端口查找
        local pids=$(lsof -ti:$PORT)
        if [[ -n "$pids" ]]; then
            echo -e "状态: ${YELLOW}运行中 (无 PID 文件)${NC}"
            echo "端口 $PORT 被进程占用: $pids"
        else
            echo -e "状态: ${RED}未运行${NC}"
        fi
    fi
    
    echo "===================="
    
    # 显示最近日志
    if [[ -f "$LOG_DIR/server.log" ]]; then
        echo "最近日志 (最后 10 行):"
        tail -10 "$LOG_DIR/server.log"
    fi
}

# 查看日志
view_logs() {
    local lines=${1:-50}
    if [[ -f "$LOG_DIR/server.log" ]]; then
        tail -n $lines -f "$LOG_DIR/server.log"
    else
        log_warn "日志文件不存在: $LOG_DIR/server.log"
    fi
}

# 显示帮助
show_help() {
    cat << EOF
MC Launcher Server 管理脚本

用法: $0 {start|stop|restart|status|logs|install}

命令:
  start     启动服务器
  stop      停止服务器
  restart   重启服务器
  status    查看服务器状态
  logs      查看实时日志 (默认 50 行)
  install   安装依赖并初始化

示例:
  $0 start
  $0 restart
  $0 logs 100    # 查看最近 100 行日志

配置:
  项目目录: $PROJECT_DIR
  服务器文件: $SERVER_FILE
  端口: $PORT
  日志目录: $LOG_DIR
  PID 文件: $PID_FILE

EOF
}

# 主函数
main() {
    case "${1:-help}" in
        start)
            check_root
            check_node
            check_project_dir
            install_deps
            setup_logs
            start_server
            ;;
        stop)
            check_project_dir
            stop_server
            ;;
        restart)
            check_root
            check_node
            check_project_dir
            setup_logs
            restart_server
            ;;
        status)
            check_project_dir
            status_server
            ;;
        logs)
            check_project_dir
            view_logs $2
            ;;
        install)
            check_root
            check_node
            check_project_dir
            install_deps
            setup_logs
            log_info "初始化完成，可使用 '$0 start' 启动服务器"
            ;;
        help|--help|-h)
            show_help
            ;;
        *)
            log_error "未知命令: $1"
            show_help
            exit 1
            ;;
    esac
}

main "$@"