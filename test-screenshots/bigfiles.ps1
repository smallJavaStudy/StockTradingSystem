# find files > 40MB and top-20 largest files overall
$files = Get-ChildItem d:\code\StockTradingSystem -Recurse -Force -File -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '\\node_modules\\|\\target\\|\\dist\\|\\\.git\\' }
Write-Output "=== files > 40MB (excluding node_modules/target/dist) ==="
$big = $files | Where-Object { $_.Length -gt 40MB } | Sort-Object Length -Descending
if (-not $big) { Write-Output "(none)" }
foreach ($f in $big) { '{0,8:N1} MB  {1}' -f ($f.Length / 1MB), $f.FullName.Replace('d:\code\StockTradingSystem\', '') }
Write-Output "=== top 20 largest files ==="
foreach ($f in ($files | Sort-Object Length -Descending | Select-Object -First 20)) {
    '{0,8:N1} MB  {1}' -f ($f.Length / 1MB), $f.FullName.Replace('d:\code\StockTradingSystem\', '')
}
