param(
    [string]$TaskName = "LiepinRpaDailySearch",
    [string]$ProjectRoot = "",
    [string]$At = "08:00",
    [int]$RetryMinutes = 15,
    [int]$RetryCount = 48
)

$ErrorActionPreference = "Stop"

if (-not $ProjectRoot) {
    $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

$RunnerPath = Join-Path $ProjectRoot "scripts\run_daily_search.ps1"

if (-not (Test-Path $RunnerPath)) {
    throw "Daily search runner not found: $RunnerPath"
}

$action = New-ScheduledTaskAction `
    -Execute "powershell.exe" `
    -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$RunnerPath`" -ProjectRoot `"$ProjectRoot`""

$trigger = New-ScheduledTaskTrigger -Daily -At $At
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -MultipleInstances IgnoreNew `
    -RestartInterval (New-TimeSpan -Minutes $RetryMinutes) `
    -RestartCount $RetryCount

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Description "Run the two-job Liepin RPA search queue every day, including weekends, with a randomized start between 08:00 and 09:00." `
    -Force | Out-Null

Write-Host "Installed scheduled task: $TaskName every day (including weekends) at $At; runner randomizes execution between 08:00 and 09:00"
