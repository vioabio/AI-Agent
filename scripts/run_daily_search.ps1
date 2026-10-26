param(
    [string]$ProjectRoot = "",
    [int]$WindowStartHour = 8,
    [int]$WindowEndHour = 9,
    [switch]$ResolveOnly
)

$ErrorActionPreference = "Stop"

if (-not $ProjectRoot) {
    $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

$LogDir = Join-Path $ProjectRoot "logs"
$LogPath = Join-Path $LogDir "daily_search_task.log"
$RunStatePath = Join-Path $ProjectRoot "artifacts\search_workflow_run_state.json"
$SingleJobRunnerPath = Join-Path $ProjectRoot "scripts\run_single_ai_job_search.ps1"
$Profiles = @{
    fde = "fde_ai_deployment_engineer_v1"
    ai_development = "ai_development_engineer_v1"
}

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
Set-Location $ProjectRoot

function Write-TaskLog {
    param([string]$Message)
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    "[$timestamp] $Message" | Out-File -LiteralPath $LogPath -Append -Encoding utf8
}

function Get-RunDateFromCheckpoint {
    param([string]$CheckpointFile, [string]$FallbackDate)
    if ($CheckpointFile -match '_(\d{8})_checkpoint\.json$') {
        return $Matches[1]
    }
    return $FallbackDate
}

function Resolve-SingleJobDispatch {
    param([string]$Today)

    if (-not (Test-Path -LiteralPath $RunStatePath)) {
        return @{ JobKey = "fde"; RunDate = $Today; Reason = "bootstrap" }
    }
    try {
        $state = Get-Content -LiteralPath $RunStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $profileId = [string]$state.context.job_profile_id
        $checkpointFile = [string]$state.context.checkpoint_file
        $lastJobKey = if ($profileId -eq $Profiles.fde) {
            "fde"
        }
        elseif ($profileId -eq $Profiles.ai_development) {
            "ai_development"
        }
        else {
            ""
        }

        if ($lastJobKey -and [string]$state.status -ne "completed") {
            return @{
                JobKey = $lastJobKey
                RunDate = Get-RunDateFromCheckpoint `
                    -CheckpointFile $checkpointFile `
                    -FallbackDate $Today
                Reason = "resume_incomplete"
            }
        }
        if ($lastJobKey -eq "fde") {
            return @{
                JobKey = "ai_development"
                RunDate = $Today
                Reason = "alternate_after_fde"
            }
        }
        if ($lastJobKey -eq "ai_development") {
            return @{
                JobKey = "fde"
                RunDate = $Today
                Reason = "alternate_after_ai_development"
            }
        }
    }
    catch {
        Write-TaskLog "run state unavailable, using bootstrap job error=$($_.Exception.Message)"
    }
    return @{ JobKey = "fde"; RunDate = $Today; Reason = "bootstrap" }
}

function Invoke-SingleJobSearchLogged {
    param([string]$JobKey, [string]$RunDate)

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & powershell.exe `
            -NoProfile `
            -ExecutionPolicy Bypass `
            -File $SingleJobRunnerPath `
            -ProjectRoot $ProjectRoot `
            -JobKey $JobKey `
            -RunDate $RunDate 2>&1 |
            Out-File -LiteralPath $LogPath -Append -Encoding utf8
        return $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

$today = Get-Date -Format "yyyyMMdd"
if ($ResolveOnly) {
    $dispatch = Resolve-SingleJobDispatch -Today $today
    [PSCustomObject]$dispatch | ConvertTo-Json -Compress
    exit 0
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
    Write-TaskLog "daily single-job batch scheduled start_at=$($plannedAt.ToString('yyyy-MM-dd HH:mm:ss')) delay_seconds=$delaySeconds"
    if ($delaySeconds -gt 0) {
        Start-Sleep -Seconds $delaySeconds
    }
}
else {
    Write-TaskLog "daily search started after random window, running immediately"
}

$dispatch = Resolve-SingleJobDispatch -Today $today
Write-TaskLog "start single-job batch job=$($dispatch.JobKey) run_date=$($dispatch.RunDate) reason=$($dispatch.Reason)"
$exitCode = Invoke-SingleJobSearchLogged `
    -JobKey $dispatch.JobKey `
    -RunDate $dispatch.RunDate
Write-TaskLog "finish single-job batch job=$($dispatch.JobKey) run_date=$($dispatch.RunDate) exit_code=$exitCode"
exit $exitCode
