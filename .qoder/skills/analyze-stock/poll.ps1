param(
    [Parameter(Mandatory=$true)]
    [string]$InstanceId,
    [string]$BaseUrl = "http://localhost:8080",
    [int]$IntervalSeconds = 30,
    [int]$TimeoutMinutes = 45
)

# Poll workflow execution status until terminal state (COMPLETED/FAILED/CANCELLED) or timeout.
# Usage: powershell -ExecutionPolicy Bypass -File poll.ps1 -InstanceId "<processInstanceId>"
# NOTE: keep this file ASCII-only so Windows PowerShell 5.1 (ANSI codepage) can parse it safely.

$ErrorActionPreference = "Stop"
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
$terminalStates = @("COMPLETED", "FAILED", "CANCELLED")
$url = "$BaseUrl/api/v1/workflow-execution/$InstanceId"

Write-Output "[poll] start polling $url (interval ${IntervalSeconds}s, timeout ${TimeoutMinutes} min)"

while ($true) {
    try {
        $resp = Invoke-RestMethod -Uri $url -Method GET -TimeoutSec 20
    } catch {
        Write-Output "[poll] query failed (will retry): $($_.Exception.Message)"
        Start-Sleep -Seconds $IntervalSeconds
        continue
    }

    $status = $resp.status
    $nodes = $resp.nodes
    $done = 0; $running = 0; $failed = 0; $pending = 0
    if ($nodes) {
        foreach ($n in $nodes) {
            switch ($n.status) {
                "COMPLETED" { $done++ }
                "RUNNING"   { $running++ }
                "FAILED"    { $failed++ }
                default     { $pending++ }
            }
        }
    }
    $ts = Get-Date -Format "HH:mm:ss"
    Write-Output "[$ts] status=$status | nodes: done=$done running=$running failed=$failed pending=$pending"

    if ($terminalStates -contains $status) {
        Write-Output "[poll] TERMINAL: $status"
        if ($nodes) {
            Write-Output "[poll] node detail:"
            foreach ($n in $nodes) {
                Write-Output "  - $($n.nodeName) [$($n.nodeId)] = $($n.status)"
            }
        }
        exit 0
    }

    if ((Get-Date) -gt $deadline) {
        Write-Output "[poll] TIMEOUT: not terminal after ${TimeoutMinutes} min, last status=$status"
        exit 2
    }
    Start-Sleep -Seconds $IntervalSeconds
}
