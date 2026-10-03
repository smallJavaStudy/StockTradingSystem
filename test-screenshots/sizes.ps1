# list top-level directory sizes (MB) for gitignore planning
Get-ChildItem d:\code\StockTradingSystem -Force -Directory | ForEach-Object {
    $size = (Get-ChildItem $_.FullName -Recurse -Force -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    '{0,-24} {1,10:N1} MB' -f $_.Name, ($size / 1MB)
}
Get-ChildItem d:\code\StockTradingSystem -Force -File | ForEach-Object {
    '{0,-24} {1,10:N1} MB (file)' -f $_.Name, ($_.Length / 1MB)
}
# check git identity
Write-Output ("git user.name  = " + (git config --global user.name))
Write-Output ("git user.email = " + (git config --global user.email))
