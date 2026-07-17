param(
    [string]$TaskName = "LiepinRpaDailyReport",
    [string]$ProjectRoot = "D:\lx\jianlizhuanqu",
    [string]$At = "08:00"
)

$ErrorActionPreference = "Stop"
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
    -StartWhenAvailable

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Description "Send Liepin RPA daily report for candidates stored since the last successful report." `
    -Force | Out-Null

Write-Host "Installed scheduled task: $TaskName at $At"
