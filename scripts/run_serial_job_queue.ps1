param(
    [string]$ProjectRoot = "",
    [ValidatePattern('^\d{8}$')]
    [string]$RunDate = (Get-Date -Format "yyyyMMdd"),
    [int]$DarenExpectedKeywords = 96,
    [int]$FinanceExpectedKeywords = 22,
    [int]$MaxAbnormalResumeAttempts = 3
)

$ErrorActionPreference = "Stop"

if (-not $ProjectRoot) {
    $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

$PythonExe = Join-Path $ProjectRoot ".venv\Scripts\python.exe"
$ArtifactsDir = Join-Path $ProjectRoot "artifacts"
$LogDir = Join-Path $ProjectRoot "logs"
$QueueLog = Join-Path $LogDir "serial_job_queue_${RunDate}.log"
$SearchLock = Join-Path $ArtifactsDir "search_workflow.lock"
$RunState = Join-Path $ArtifactsDir "search_workflow_run_state.json"

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
Set-Location $ProjectRoot

function Write-QueueLog {
    param([string]$Message)
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    "[$timestamp] $Message" | Out-File -LiteralPath $QueueLog -Append -Encoding utf8
}

function Get-CompletedKeywordCount {
    param([string]$CheckpointPath)
    if (-not (Test-Path -LiteralPath $CheckpointPath)) { return 0 }
    try {
        $checkpoint = Get-Content -LiteralPath $CheckpointPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($null -ne $checkpoint.completed_keyword_keys) {
            return @($checkpoint.completed_keyword_keys).Count
        }
        return 0
    }
    catch {
        Write-QueueLog "invalid checkpoint path=$CheckpointPath error=$($_.Exception.Message)"
        return -1
    }
}

function Get-ExpectedKeywordCount {
    param([string]$ProfileRelativePath)
    $script = @'
import sys
from pathlib import Path
from liepin_rpa.task_generation.generator import generate_jd_smart_search_task_seeds
print(len(generate_jd_smart_search_task_seeds(Path(sys.argv[1]))))
'@
    $output = & $PythonExe -c $script $ProfileRelativePath
    if ($LASTEXITCODE -ne 0 -or -not $output) {
        throw "Failed to calculate keyword count for $ProfileRelativePath"
    }
    return [int]($output | Select-Object -Last 1)
}

function Get-RunStatus {
    if (-not (Test-Path -LiteralPath $RunState)) { return "missing" }
    try {
        return [string](Get-Content -LiteralPath $RunState -Raw -Encoding UTF8 | ConvertFrom-Json).status
    }
    catch { return "invalid" }
}

function Get-RunCheckpoint {
    if (-not (Test-Path -LiteralPath $RunState)) { return "" }
    try {
        return [string](Get-Content -LiteralPath $RunState -Raw -Encoding UTF8 | ConvertFrom-Json).context.checkpoint_file
    }
    catch { return "" }
}

function Test-CheckpointInProgress {
    param([string]$CheckpointPath)
    if (-not (Test-Path -LiteralPath $CheckpointPath)) { return $false }
    try {
        $checkpoint = Get-Content -LiteralPath $CheckpointPath -Raw -Encoding UTF8 | ConvertFrom-Json
        return $null -ne $checkpoint.in_progress_keyword
    }
    catch { return $true }
}

function Get-SearchRiskBlocker {
    $appLog = Join-Path $LogDir "app.log"
    if (-not (Test-Path -LiteralPath $RunState) -or -not (Test-Path -LiteralPath $appLog)) {
        return ""
    }
    try {
        $state = Get-Content -LiteralPath $RunState -Raw -Encoding UTF8 | ConvertFrom-Json
        $startedAt = [DateTimeOffset]::FromUnixTimeSeconds([long]$state.started_at)
        foreach ($line in Get-Content -LiteralPath $appLog -Tail 4000 -Encoding UTF8) {
            try { $entry = $line | ConvertFrom-Json } catch { continue }
            if (-not $entry.timestamp -or -not $entry.event) { continue }
            $timestamp = [DateTimeOffset]::Parse([string]$entry.timestamp)
            if ($timestamp -lt $startedAt) { continue }
            $eventName = [string]$entry.event
            if ($eventName -match 'captcha|slider|account.*risk|risk.*control|login.*(error|fail|abnormal)|keyword_device_error_circuit_breaker|keyword_error_circuit_breaker') {
                return $eventName
            }
        }
    }
    catch {
        return "risk_check_failed"
    }
    return ""
}

function Wait-ForSearchExit {
    while ($true) {
        $active = @(Get-CimInstance Win32_Process | Where-Object {
            $_.Name -match '^python(.exe)?$' -and $_.CommandLine -match 'run_search_workflow.py'
        })
        if ($active.Count -eq 0 -and -not (Test-Path -LiteralPath $SearchLock)) { return }
        Start-Sleep -Seconds 20
    }
}

function Invoke-SearchResume {
    param(
        [string]$CheckpointRelativePath,
        [string]$ProfileRelativePath,
        [string]$JobName,
        [int]$ExpectedKeywords
    )
    $env:WORKFLOW_SELECTION_MODE = "jd_smart"
    $env:WORKFLOW_JOB_PROFILE_PATH = $ProfileRelativePath
    Write-QueueLog "start job=$JobName checkpoint=$CheckpointRelativePath profile=$ProfileRelativePath"
    $stdoutLog = Join-Path $LogDir "serial_job_queue_${RunDate}_${JobName}.stdout.log"
    $stderrLog = Join-Path $LogDir "serial_job_queue_${RunDate}_${JobName}.stderr.log"
    $process = Start-Process -FilePath $PythonExe `
        -ArgumentList @(
            "scripts\run_search_workflow.py",
            "--checkpoint-file", $CheckpointRelativePath,
            "--daily-budget", [string]$ExpectedKeywords
        ) `
        -WorkingDirectory $ProjectRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog `
        -PassThru -Wait
    $exitCode = $process.ExitCode
    Write-QueueLog "finish job=$JobName exit_code=$exitCode status=$(Get-RunStatus)"
    return $exitCode
}

function Complete-Job {
    param(
        [string]$JobName,
        [string]$CheckpointRelativePath,
        [string]$ProfileRelativePath,
        [int]$ExpectedKeywords
    )
    $checkpointPath = Join-Path $ProjectRoot $CheckpointRelativePath
    $abnormalResumeAttempts = 0
    while ($true) {
        Wait-ForSearchExit
        $completed = Get-CompletedKeywordCount $checkpointPath
        $status = Get-RunStatus
        $runCheckpoint = Get-RunCheckpoint
        $checkpointInProgress = Test-CheckpointInProgress $checkpointPath
        $riskBlocker = Get-SearchRiskBlocker
        Write-QueueLog "check job=$JobName completed=$completed expected=$ExpectedKeywords status=$status run_checkpoint=$runCheckpoint checkpoint_in_progress=$checkpointInProgress"
        if ($riskBlocker) {
            Write-QueueLog "stop queue after search risk job=$JobName event=$riskBlocker"
            return $false
        }
        if ($completed -eq $ExpectedKeywords -and -not $checkpointInProgress) {
            if ($runCheckpoint -eq $CheckpointRelativePath -and $status -eq "completed") {
                return $true
            }
        }
        if ($completed -lt 0 -or $status -eq "invalid") {
            Write-QueueLog "stop queue job=$JobName completed=$completed status=$status"
            return $false
        }
        Write-QueueLog "resume incomplete job=$JobName completed=$completed status=$status"
        $exitCode = Invoke-SearchResume `
            $CheckpointRelativePath `
            $ProfileRelativePath `
            $JobName `
            $ExpectedKeywords
        if ($exitCode -ne 0) {
            $abnormalResumeAttempts += 1
            $riskBlocker = Get-SearchRiskBlocker
            if ($riskBlocker) {
                Write-QueueLog "stop queue after search risk job=$JobName event=$riskBlocker"
                return $false
            }
            if ($abnormalResumeAttempts -ge $MaxAbnormalResumeAttempts) {
                Write-QueueLog "stop queue after repeated non-zero exit job=$JobName exit_code=$exitCode attempts=$abnormalResumeAttempts"
                return $false
            }
            Write-QueueLog "resume after non-zero exit job=$JobName exit_code=$exitCode attempt=$abnormalResumeAttempts"
        }
        else {
            $abnormalResumeAttempts = 0
        }
        Start-Sleep -Seconds 10
    }
}

$darenProfile = "config\job_profiles\daren_live_bd.yaml"
$financeProfile = "config\job_profiles\finance_shared_management.yaml"
$darenCheckpoint = "artifacts\jd_smart_${RunDate}_checkpoint.json"
$financeCheckpoint = "artifacts\finance_shared_${RunDate}_checkpoint.json"
$actualDarenKeywordCount = Get-ExpectedKeywordCount $darenProfile
$actualFinanceKeywordCount = Get-ExpectedKeywordCount $financeProfile
if ($actualDarenKeywordCount -ne $DarenExpectedKeywords) {
    throw "Daren catalog mismatch: expected=$DarenExpectedKeywords actual=$actualDarenKeywordCount"
}
if ($actualFinanceKeywordCount -ne $FinanceExpectedKeywords) {
    throw "Finance catalog mismatch: expected=$FinanceExpectedKeywords actual=$actualFinanceKeywordCount"
}

Write-QueueLog "queue watcher started run_date=$RunDate daren_expected=$DarenExpectedKeywords finance_expected=$FinanceExpectedKeywords"
if (-not (Complete-Job "daren_live_bd" $darenCheckpoint $darenProfile $DarenExpectedKeywords)) {
    exit 1
}
Write-QueueLog "first job completed; switching to finance"
if (-not (Complete-Job "finance_shared_management" $financeCheckpoint $financeProfile $FinanceExpectedKeywords)) {
    exit 2
}
Write-QueueLog "all queued jobs completed"
exit 0
