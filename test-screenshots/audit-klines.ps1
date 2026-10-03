# Final audit v3: use Invoke-RestMethod + pipeline (avoids the @() collapse quirk)
foreach ($code in @('300170', '603859', '300058', '688102')) {
    try {
        $rows = Invoke-RestMethod -Uri "http://localhost:8080/api/stock/$code/kline" -TimeoutSec 10
        $count = 0
        $newest = $null
        $oldest = $null
        foreach ($r in $rows) {
            $count = $count + 1
            if ($null -eq $newest) { $newest = $r }
            $oldest = $r
        }
        Write-Output ("$code : " + $count + " rows, " + $oldest.tradeDate + " to " + $newest.tradeDate + ", lastClose=" + $newest.close)
    } catch {
        Write-Output ("$code : query failed - " + $_.Exception.Message)
    }
}
