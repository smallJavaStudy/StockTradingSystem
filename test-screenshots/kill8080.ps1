# kill whatever listens on 8080 (whole process tree)
$conns = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if (-not $conns) {
    Write-Output "NO_LISTENER_ON_8080"
    exit 0
}
foreach ($c in $conns) {
    Write-Output ("killing PID " + $c.OwningProcess)
    taskkill /F /T /PID $c.OwningProcess
}
