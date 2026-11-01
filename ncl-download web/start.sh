#!/bin/bash

set -e

PROJECT_DIR="Your project directory"
SERVER_FILE="server.js"
PORT=5005
NODE_VERSION="18"
LOG_DIR="$PROJECT_DIR/logs"
PID_FILE="$PROJECT_DIR/server.pid"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

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

install_deps() {
    log_info "检查并安装依赖..."
    
    if [[ ! -d "node_modules" ]] || [[ ! -f "package-lock.json" ]]; then
        log_info "安装 npm 依赖..."
        npm install --production
    else
        log_info "依赖已存在，跳过安装"
    fi
}

setup_logs() {
    mkdir -p "$LOG_DIR"
    log_info "日志目录: $LOG_DIR"
}

check_port() {
    if lsof -ti:$PORT &> /dev/null; then
        local pids=$(lsof -ti:$PORT)
        log_warn "端口 $PORT 被占用，进程: $pids"
        return 1
    fi
    return 0
}

start_server() {
    log_info "启动服务器..."
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
    if ! check_port; then
        log_error "端口 $PORT 被占用，无法启动"
        return 1
    fi
    cd "$PROJECT_DIR"
    nohup node "$SERVER_FILE" >> "$LOG_DIR/server.log" 2>&1 &
    local pid=$!
    echo $pid > "$PID_FILE"
    sleep 2
    
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

restart_server() {
    log_info "重启服务器..."
    stop_server
    sleep 1
    start_server
}

status_server() {
    echo "===================="
    echo "  服务器状态检查"
    echo "===================="
    
    if [[ -f "$PID_FILE" ]]; then
        local pid=$(cat "$PID_FILE" 2>/dev/null)
        if [[ -n "$pid" ]] && kill -0 $pid 2>/dev/null; then
            echo -e "状态: ${GREEN}运行中${NC} (PID: $pid)"
            
            if lsof -ti:$PORT | grep -q $pid; then
                echo -e "端口 $PORT: ${GREEN}监听中${NC}"
            else
                echo -e "端口 $PORT: ${RED}未监听${NC}"
            fi
            
            echo "进程信息:"
            ps -p $pid -o pid,ppid,cmd,etime,pcpu,pmem 2>/dev/null || true
        else
            echo -e "状态: ${RED}未运行${NC} (PID 文件存在但进程不存在)"
        fi
    else
        local pids=$(lsof -ti:$PORT)
        if [[ -n "$pids" ]]; then
            echo -e "状态: ${YELLOW}运行中 (无 PID 文件)${NC}"
            echo "端口 $PORT 被进程占用: $pids"
        else
            echo -e "状态: ${RED}未运行${NC}"
        fi
    fi
    echo "===================="
    if [[ -f "$LOG_DIR/server.log" ]]; then
        echo "最近日志 (最后 10 行):"
        tail -10 "$LOG_DIR/server.log"
    fi
}

view_logs() {
    local lines=${1:-50}
    if [[ -f "$LOG_DIR/server.log" ]]; then
        tail -n $lines -f "$LOG_DIR/server.log"
    else
        log_warn "日志文件不存在: $LOG_DIR/server.log"
    fi
}

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

main() {
    case "${1:-help}" in
        start)
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
