#!/bin/bash
# ============================================================
# StockTradingSystem 一键停止脚本
# 用法: bash scripts/stop-all.sh
# ============================================================

GREEN='\033[0;32m'
NC='\033[0m'
log() { echo -e "${GREEN}[INFO]${NC}  $1"; }

echo ""
log "Stopping StockTradingSystem..."

# Kill by port - use PowerShell to kill process trees
powershell -Command "
    Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-Process -Id \$_.OwningProcess -Force -ErrorAction SilentlyContinue }
    Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object { Stop-Process -Id \$_.OwningProcess -Force -ErrorAction SilentlyContinue }
" 2>/dev/null

# Also kill any lingering java/node from this project
powershell -Command "
    Get-CimInstance Win32_Process -Filter \"name='java.exe'\" -ErrorAction SilentlyContinue |
        Where-Object { \$_.CommandLine -like '*stock-analysis*' -or \$_.CommandLine -like '*StockTradingSystem*' } |
        ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }
    Get-CimInstance Win32_Process -Filter \"name='node.exe'\" -ErrorAction SilentlyContinue |
        Where-Object { \$_.CommandLine -like '*StockTradingSystem*fronend*' -or \$_.CommandLine -like '*vite*' } |
        ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }
" 2>/dev/null

sleep 1
log "All services stopped."
echo ""
