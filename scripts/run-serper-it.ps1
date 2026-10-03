Set-Location d:\code\StockTradingSystem\backend
mvn -q test "-Dtest=SerperSearchServiceIT" "-Dserper.it=true" "-Dserper.api-key=$env:SERPER_API_KEY" "-DfailIfNoTests=false" 2>&1 | Select-Object -Last 60
