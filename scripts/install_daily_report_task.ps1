param(
    [string]$TaskName = "LiepinRpaDailyReport",
    [string]$ProjectRoot = "",
    [string]$At = "10:00",
    [int]$RetryMinutes = 15,
    [int]$RetryCount = 48
)

$ErrorActionPreference = "Stop"

if (-not $ProjectRoot) {
    $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

$RunnerPath = Join-Path $ProjectRoot "scripts\run_daily_report.ps1"

if (-not (Test-Path $RunnerPath)) {
    throw "Daily report runner not found: $RunnerPath"
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
    -Description "Send the latest completed and unreported Liepin RPA search batch." `
    -Force | Out-Null

Write-Host "Installed scheduled task: $TaskName at $At; retry every $RetryMinutes minutes up to $RetryCount times"
