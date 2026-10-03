# simple health check for backend 8080
try {
    $r = Invoke-WebRequest -Uri 'http://localhost:8080/api/v1/workflow/list' -UseBasicParsing -TimeoutSec 5
    Write-Output ("BACKEND_UP status=" + $r.StatusCode)
} catch {
    Write-Output ("BACKEND_DOWN: " + $_.Exception.Message)
}
