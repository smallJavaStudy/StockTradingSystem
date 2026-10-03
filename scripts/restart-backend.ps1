# 重启后端：kill 占用 8080 的进程树后启动 spring-boot:run（不清理数据库文件）
$conns = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
foreach ($c in $conns) {
    Write-Host "Killing PID tree $($c.OwningProcess) on port 8080"
    taskkill /F /T /PID $c.OwningProcess
}
Start-Sleep -Seconds 3
Set-Location "$PSScriptRoot\..\backend"
mvn spring-boot:run
