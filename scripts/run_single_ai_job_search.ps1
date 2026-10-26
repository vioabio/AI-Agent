param(
    [string]$ProjectRoot = "",
    [Parameter(Mandatory = $true)]
    [ValidateSet("fde", "ai_development")]
    [string]$JobKey,
    [ValidatePattern('^\d{8}$')]
    [string]$RunDate = (Get-Date -Format "yyyyMMdd"),
    [int]$ExpectedKeywords = 0,
    [int]$MaxResumeAttempts = 3,
    [switch]$ValidateOnly
)

$ErrorActionPreference = "Stop"

if (-not $ProjectRoot) {
    $ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
}

$JobDefinitions = @{
    fde = @{
        Label = "FDE"
        Profile = "config\job_profiles\fde_ai_deployment_engineer_draft.yaml"
        CheckpointStem = "fde_ai_deployment_engineer"
        ExpectedKeywords = 40
        LogStem = "fde_daily"
    }
    ai_development = @{
        Label = "AI development"
        Profile = "config\job_profiles\ai_development_engineer.yaml"
        CheckpointStem = "ai_development_engineer"
        ExpectedKeywords = 32
        LogStem = "ai_development_daily"
    }
}
$Job = $JobDefinitions[$JobKey]
if ($ExpectedKeywords -le 0) {
    $ExpectedKeywords = [int]$Job.ExpectedKeywords
}

$PythonExe = Join-Path $ProjectRoot ".venv\Scripts\python.exe"
$ProfileRelativePath = [string]$Job.Profile
$CheckpointRelativePath = "artifacts\$($Job.CheckpointStem)_${RunDate}_checkpoint.json"
$CheckpointPath = Join-Path $ProjectRoot $CheckpointRelativePath
$ArtifactsDir = Join-Path $ProjectRoot "artifacts"
$LogDir = Join-Path $ProjectRoot "logs"
$RunnerLog = Join-Path $LogDir "$($Job.LogStem)_${RunDate}.log"
$SearchLock = Join-Path $ArtifactsDir "search_workflow.lock"
$RunState = Join-Path $ArtifactsDir "search_workflow_run_state.json"

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
Set-Location $ProjectRoot

function Write-JobLog {
    param([string]$Message)
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    "[$timestamp] job=$JobKey $Message" |
        Out-File -LiteralPath $RunnerLog -Append -Encoding utf8
}

function Get-CompletedKeywordCount {
    if (-not (Test-Path -LiteralPath $CheckpointPath)) { return 0 }
    try {
        $checkpoint = Get-Content -LiteralPath $CheckpointPath -Raw -Encoding UTF8 |
            ConvertFrom-Json
        if ($null -eq $checkpoint.completed_keyword_keys) { return 0 }
        return @($checkpoint.completed_keyword_keys).Count
    }
    catch {
        Write-JobLog "invalid checkpoint error=$($_.Exception.Message)"
        return -1
    }
}

function Test-CheckpointInProgress {
    if (-not (Test-Path -LiteralPath $CheckpointPath)) { return $false }
    try {
        $checkpoint = Get-Content -LiteralPath $CheckpointPath -Raw -Encoding UTF8 |
            ConvertFrom-Json
        return $null -ne $checkpoint.in_progress_keyword
    }
    catch { return $true }
}

function Get-RunStateFacts {
    if (-not (Test-Path -LiteralPath $RunState)) {
        return @{ Status = "missing"; Checkpoint = "" }
    }
    try {
        $state = Get-Content -LiteralPath $RunState -Raw -Encoding UTF8 | ConvertFrom-Json
        return @{
            Status = [string]$state.status
            Checkpoint = [string]$state.context.checkpoint_file
        }
    }
    catch { return @{ Status = "invalid"; Checkpoint = "" } }
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
            if ($eventName -match 'captcha|slider|account.*risk|risk.*control|login.*(error|fail|abnormal)|real.*name|device.*(offline|disconnect)|communication.*(quota|rights|insufficient)|keyword_device_error_circuit_breaker|keyword_error_circuit_breaker') {
                return $eventName
            }
        }
    }
    catch { return "risk_check_failed" }
    return ""
}

function Test-SearchAlreadyRunning {
    $active = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^python(.exe)?$' -and $_.CommandLine -match 'run_search_workflow.py'
    })
    return $active.Count -gt 0 -or (Test-Path -LiteralPath $SearchLock)
}

function Get-ActualKeywordCount {
    $script = @'
import sys
from pathlib import Path
from liepin_rpa.task_generation.generator import generate_jd_smart_search_task_seeds
print(len(generate_jd_smart_search_task_seeds(Path(sys.argv[1]))))
'@
    $output = & $PythonExe -c $script $ProfileRelativePath
    if ($LASTEXITCODE -ne 0 -or -not $output) {
        throw "Failed to calculate keyword count for $JobKey"
    }
    return [int]($output | Select-Object -Last 1)
}

function Invoke-JobWorkflow {
    param([int]$Attempt)

    $env:WORKFLOW_SELECTION_MODE = "jd_smart"
    $env:WORKFLOW_JOB_PROFILE_PATH = $ProfileRelativePath
    $stdoutLog = Join-Path $LogDir "$($Job.LogStem)_${RunDate}_attempt_${Attempt}.stdout.log"
    $stderrLog = Join-Path $LogDir "$($Job.LogStem)_${RunDate}_attempt_${Attempt}.stderr.log"
    $process = Start-Process -FilePath $PythonExe `
        -ArgumentList @(
            "scripts\run_search_workflow.py",
            "--checkpoint-file", $CheckpointRelativePath,
            "--daily-budget", [string]$ExpectedKeywords,
            "--standalone-job"
        ) `
        -WorkingDirectory $ProjectRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog `
        -PassThru -Wait
    return $process.ExitCode
}

if (-not (Test-Path -LiteralPath $PythonExe)) {
    throw "Python environment is missing: $PythonExe"
}

$actualKeywordCount = Get-ActualKeywordCount
if ($actualKeywordCount -ne $ExpectedKeywords) {
    throw "$($Job.Label) catalog mismatch: expected=$ExpectedKeywords actual=$actualKeywordCount"
}
if ($ValidateOnly) {
    Write-Output "$($Job.Label) schedule validation passed: expected=$ExpectedKeywords actual=$actualKeywordCount"
    exit 0
}

Write-JobLog "start single-job batch run_date=$RunDate expected=$ExpectedKeywords"
for ($attempt = 1; $attempt -le $MaxResumeAttempts; $attempt++) {
    $completed = Get-CompletedKeywordCount
    if ($completed -eq $ExpectedKeywords -and -not (Test-CheckpointInProgress)) {
        Write-JobLog "checkpoint already complete completed=$completed"
        exit 0
    }
    if ($completed -lt 0) { exit 1 }
    if (Test-SearchAlreadyRunning) {
        Write-JobLog "stop without starting second instance attempt=$attempt completed=$completed"
        exit 2
    }

    Write-JobLog "run attempt=$attempt completed=$completed checkpoint=$CheckpointRelativePath"
    $exitCode = Invoke-JobWorkflow -Attempt $attempt
    $riskBlocker = Get-SearchRiskBlocker
    if ($riskBlocker) {
        Write-JobLog "stop after risk event=$riskBlocker attempt=$attempt"
        exit 3
    }

    $completed = Get-CompletedKeywordCount
    $runFacts = Get-RunStateFacts
    $checkpointInProgress = Test-CheckpointInProgress
    Write-JobLog "finish attempt=$attempt exit_code=$exitCode completed=$completed status=$($runFacts.Status) checkpoint_in_progress=$checkpointInProgress"
    if (
        $exitCode -eq 0 -and
        $completed -eq $ExpectedKeywords -and
        -not $checkpointInProgress -and
        $runFacts.Status -eq "completed" -and
        $runFacts.Checkpoint -eq $CheckpointRelativePath
    ) {
        Write-JobLog "single-job batch completed completed=$completed"
        exit 0
    }
}

Write-JobLog "single-job batch incomplete after attempts=$MaxResumeAttempts completed=$(Get-CompletedKeywordCount)"
exit 1
