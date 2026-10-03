# Verify staleness fix: poll backend health, re-acquire 300058, report exact kline count & latest date
$ok = $false
for ($i = 1; $i -le 45; $i++) {
    try {
        $r = Invoke-WebRequest -Uri 'http://localhost:8080/api/v1/workflow/list' -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200) { $ok = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if (-not $ok) { Write-Output "BACKEND_NOT_READY"; exit 1 }
Write-Output "BACKEND_READY"

Write-Output "===== POST /api/stock/300058/acquire (expect stale re-pull) ====="
try {
    $res = Invoke-RestMethod -Uri 'http://localhost:8080/api/stock/300058/acquire' -Method POST -TimeoutSec 120
    Write-Output ("tradeable=" + $res.tradeable + " klineCount=" + $res.klineCount)
    Write-Output ("detail=" + $res.detail)
} catch {
    Write-Output ("acquire failed: " + $_.Exception.Message)
}

Write-Output "===== exact kline count & latest date from GET /kline ====="
try {
    $body = (Invoke-WebRequest -Uri 'http://localhost:8080/api/stock/300058/kline' -UseBasicParsing -TimeoutSec 10).Content
    $rows = @($body | ConvertFrom-Json)
    $dates = @($rows | ForEach-Object { [string]($_.tradeDate) })
    Write-Output ("count=" + $rows.Count + " first=" + $dates[0] + " latest=" + $dates[$dates.Count - 1])
} catch {
    Write-Output ("kline query failed: " + $_.Exception.Message)
}
