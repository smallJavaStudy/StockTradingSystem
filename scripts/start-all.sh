#!/bin/bash
# ============================================================
# StockTradingSystem 一键启动脚本
# 用法: bash scripts/start-all.sh
# ============================================================
set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log()  { echo -e "${GREEN}[INFO]${NC}  $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC}  $1"; }
err()  { echo -e "${RED}[ERROR]${NC} $1"; }
step() { echo -e "\n${CYAN}=== $1 ===${NC}"; }

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND_DIR="$PROJECT_DIR/backend"
FRONTEND_DIR="$PROJECT_DIR/frontend"
SCRIPTS_DIR="$PROJECT_DIR/scripts"
MYSQL_BIN="/c/Program Files/MySQL/MySQL Server 5.7/bin/mysql.exe"

# ============================================================
# Step 1: Kill old processes occupying target ports
# ============================================================
step "Killing old processes on port 8080 & 5173"

kill_port() {
    local port=$1
    local pids
    pids=$(netstat -ano 2>/dev/null | grep ":$port " | grep LISTENING | awk '{print $NF}' | sort -u)
    if [ -n "$pids" ]; then
        for pid in $pids; do
            log "Killing PID $pid on port $port (tree)"
            powershell -Command "Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue" 2>/dev/null || true
        done
    else
        log "Port $port is free"
    fi
}

kill_port 8080
kill_port 5173
sleep 1

# ============================================================
# Step 2: Verify MySQL is running
# ============================================================
step "Checking MySQL"

if "$MYSQL_BIN" -u root -proot -e "SELECT 1" 2>/dev/null >/dev/null; then
    log "MySQL is running and accessible"
else
    warn "MySQL not reachable, trying to start service..."
    net start MySQL57 2>/dev/null || true
    sleep 2
    if "$MYSQL_BIN" -u root -proot -e "SELECT 1" 2>/dev/null >/dev/null; then
        log "MySQL started successfully"
    else
        err "Cannot connect to MySQL. Please start it manually."
        exit 1
    fi
fi

# Ensure stock_db exists
"$MYSQL_BIN" -u root -proot -e "CREATE DATABASE IF NOT EXISTS stock_db DEFAULT CHARSET utf8mb4;" 2>/dev/null

# ============================================================
# Step 3: Start Backend (Spring Boot)
# ============================================================
step "Starting backend (Spring Boot on :8080)"

cd "$BACKEND_DIR"
mvn spring-boot:run -q > "$SCRIPTS_DIR/backend.log" 2>&1 &
BACKEND_PID=$!
log "Backend starting (PID: $BACKEND_PID, log: scripts/backend.log)"

# Wait for backend to be ready
log "Waiting for backend to start..."
for i in $(seq 1 60); do
    if curl -s http://localhost:8080/api/stock/list >/dev/null 2>&1; then
        log "Backend is ready! (http://localhost:8080)"
        break
    fi
    if [ $i -eq 60 ]; then
        err "Backend failed to start within 60s. Check scripts/backend.log"
        exit 1
    fi
    sleep 2
done

# ============================================================
# Step 4: Start Frontend (Vite)
# ============================================================
step "Starting frontend (Vite on :5173)"

cd "$FRONTEND_DIR"
npm run dev > "$SCRIPTS_DIR/frontend.log" 2>&1 &
FRONTEND_PID=$!
log "Frontend starting (PID: $FRONTEND_PID, log: scripts/frontend.log)"

# Wait for frontend to be ready
log "Waiting for frontend to start..."
for i in $(seq 1 30); do
    if curl -s -o /dev/null -w "%{http_code}" http://localhost:5173/ 2>/dev/null | grep -q 200; then
        log "Frontend is ready! (http://localhost:5173)"
        break
    fi
    if [ $i -eq 30 ]; then
        err "Frontend failed to start within 30s. Check scripts/frontend.log"
        exit 1
    fi
    sleep 1
done

# ============================================================
# Step 5: Run smoke test
# ============================================================
step "Running API smoke test"
python "$SCRIPTS_DIR/api_smoke_test.py" 2>/dev/null || warn "Smoke test had failures (may be normal if DB is empty)"

# ============================================================
# Done
# ============================================================
echo ""
echo -e "${GREEN}============================================================${NC}"
echo -e "${GREEN}  StockTradingSystem 启动成功!${NC}"
echo -e "${GREEN}============================================================${NC}"
echo ""
echo -e "  前端:  ${CYAN}http://localhost:5173${NC}"
echo -e "  后端:  ${CYAN}http://localhost:8080${NC}"
echo -e "  日志:  ${CYAN}$SCRIPTS_DIR/backend.log${NC}"
echo -e "        ${CYAN}$SCRIPTS_DIR/frontend.log${NC}"
echo ""
echo -e "  停止服务: ${YELLOW}bash scripts/stop-all.sh${NC}"
echo ""
