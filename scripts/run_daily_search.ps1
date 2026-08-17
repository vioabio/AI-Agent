param(
    [string]$ProjectRoot = "",
    [int]$WindowStartHour = 8,
    [int]$WindowEndHour = 9
)

$ErrorActionPreference = "Stop"

if (-not $ProjectRoot) {
    $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

$LogDir = Join-Path $ProjectRoot "logs"
$LogPath = Join-Path $LogDir "daily_search_task.log"
$SerialRunnerPath = Join-Path $ProjectRoot "scripts\run_serial_job_queue.ps1"

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
Set-Location $ProjectRoot

function Write-TaskLog {
    param([string]$Message)
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    "[$timestamp] $Message" | Out-File -FilePath $LogPath -Append -Encoding utf8
}

function Invoke-SerialQueueLogged {
    param([string]$RunDate)

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & powershell.exe `
            -NoProfile `
            -ExecutionPolicy Bypass `
            -File $SerialRunnerPath `
            -ProjectRoot $ProjectRoot `
            -RunDate $RunDate 2>&1 |
            Out-File -FilePath $LogPath -Append -Encoding utf8
        return $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

$now = Get-Date
$windowStart = Get-Date -Hour $WindowStartHour -Minute 0 -Second 0
$windowEnd = Get-Date -Hour $WindowEndHour -Minute 0 -Second 0

if ($windowEnd -le $windowStart) {
    throw "WindowEndHour must be later than WindowStartHour."
}

if ($now -lt $windowEnd) {
    $delayStart = if ($now -gt $windowStart) { $now } else { $windowStart }
    $minDelaySeconds = [Math]::Max(0, [int]($delayStart - $now).TotalSeconds)
    $maxDelaySeconds = [Math]::Max($minDelaySeconds, [int]($windowEnd - $now).TotalSeconds)
    $delaySeconds = Get-Random -Minimum $minDelaySeconds -Maximum ($maxDelaySeconds + 1)
    $plannedAt = $now.AddSeconds($delaySeconds)
    Write-TaskLog "daily search scheduled randomized start_at=$($plannedAt.ToString('yyyy-MM-dd HH:mm:ss')) delay_seconds=$delaySeconds"
    if ($delaySeconds -gt 0) {
        Start-Sleep -Seconds $delaySeconds
    }
} else {
    Write-TaskLog "daily search started after random window, running immediately"
}

$runDate = Get-Date -Format "yyyyMMdd"
Write-TaskLog "start daily dual-job search run_date=$runDate"
$exitCode = Invoke-SerialQueueLogged $runDate
Write-TaskLog "finish daily search exit_code=$exitCode"

exit $exitCode
