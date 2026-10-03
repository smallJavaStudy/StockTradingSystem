# Pull ~6 months daily K-line for 4 stocks via Tencent/Sina cascade, 6s interval between symbols
$codes = @('300170', '603859', '300058', '688102')
$names = @{ '300170' = 'Hand'; '603859' = 'Nengke'; '300058' = 'BlueFocus'; '688102' = 'Sirui' }

# 0. backend health check
try {
    $r = Invoke-WebRequest -Uri 'http://localhost:8080/api/v1/workflow/list' -UseBasicParsing -TimeoutSec 5
    Write-Output ("BACKEND_UP status=" + $r.StatusCode)
} catch {
    Write-Output ("BACKEND_DOWN: " + $_.Exception.Message)
    exit 1
}

foreach ($code in $codes) {
    $name = $names[$code]
    Write-Output ""
    Write-Output ("===== [$code $name] kline count BEFORE =====")
    try {
        $before = @(Invoke-RestMethod -Uri "http://localhost:8080/api/stock/$code/kline" -TimeoutSec 10)
        Write-Output ("before: " + $before.Count + " rows")
    } catch {
        Write-Output ("before: query failed - " + $_.Exception.Message)
    }

    Write-Output ("===== [$code $name] POST /acquire =====")
    try {
        $res = Invoke-RestMethod -Uri "http://localhost:8080/api/stock/$code/acquire" -Method POST -TimeoutSec 120
        Write-Output ($res | ConvertTo-Json -Depth 5 -Compress)
    } catch {
        Write-Output ("acquire failed: " + $_.Exception.Message)
    }

    Write-Output ("===== [$code $name] kline count AFTER =====")
    try {
        $after = @(Invoke-RestMethod -Uri "http://localhost:8080/api/stock/$code/kline" -TimeoutSec 10)
        if ($after.Count -gt 0) {
            $first = $after | Select-Object -First 1
            $last = $after | Select-Object -Last 1
            Write-Output ("after: " + $after.Count + " rows (" + $first.tradeDate + " to " + $last.tradeDate + "), lastClose=" + $last.close)
        } else {
            Write-Output "after: 0 rows"
        }
    } catch {
        Write-Output ("after: query failed - " + $_.Exception.Message)
    }

    # rate-limit friendly interval between symbols
    Start-Sleep -Seconds 6
}

Write-Output ""
Write-Output "===== ALL DONE ====="
