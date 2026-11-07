[CmdletBinding()]
param(
    [ValidateSet('local','prod')][string]$Profile = 'prod',
    [Parameter(Mandatory=$true)][string[]]$Missing,
    [string[]]$Probe = @(),
    [string]$Application = 'src/main/resources/application-prod.yml',
    [string]$Evidence = 'artifacts/v1.1/baseline/n0-secret-required-failure.jsonl',
    [string]$Log = 'artifacts/v1.1/baseline/n0-secret-required-wrapper.log',
    [string]$Executable,
    [string[]]$ArgumentList = @(),
    [int]$TimeoutSeconds = 45,
    [switch]$ExecuteProcess,
    [switch]$AllowPending,
    [switch]$NoClobber
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Get-Location).Path
$applicationPath = Join-Path $repoRoot $Application
$evidencePath = Join-Path $repoRoot $Evidence
$logPath = Join-Path $repoRoot $Log
if ($NoClobber -and (Test-Path -LiteralPath $evidencePath)) { throw "证据已存在（-NoClobber）: $Evidence" }
if ($NoClobber -and (Test-Path -LiteralPath $logPath)) { throw "日志已存在（-NoClobber）: $Log" }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $evidencePath), (Split-Path -Parent $logPath) | Out-Null

$applicationText = if (Test-Path -LiteralPath $applicationPath) { Get-Content -LiteralPath $applicationPath -Raw -Encoding UTF8 } else { '' }
$probeMap = @{
    model = @('AI_DASHSCOPE_API_KEY')
    search = @('SEARCH_API_KEY')
}
$records = New-Object System.Collections.Generic.List[object]
$logLines = New-Object System.Collections.Generic.List[string]
$overallPassed = $true
$pending = $false

foreach ($secretName in $Missing) {
    if ([string]::IsNullOrWhiteSpace($secretName) -or $secretName -notmatch '^[A-Z][A-Z0-9_]+$') {
        $records.Add([pscustomobject]@{secretName='[invalid-name]'; checkType='input'; exitCode=2; redacted=$true; passed=$false})
        $overallPassed = $false
        continue
    }
    $placeholderPresent = $applicationText -match ('\$\{' + [regex]::Escape($secretName) + '(?::[^}]*)?\}')
    # Placeholder presence is only a static precondition. It is not proof that
    # a lazy model/search binding rejects a missing secret.
    $checkType = 'process-probe-pending'
    $exitCode = $null
    $passed = $false
    $itemPending = $true
    if (-not $placeholderPresent) {
        $checkType = 'missing-placeholder'
        $exitCode = 2
        $itemPending = $false
        $passed = $false
    }

    $relevantProbe = @($Probe | Where-Object { $probeMap.ContainsKey($_) -and $probeMap[$_] -contains $secretName })
    if ($ExecuteProcess) {
        if ([string]::IsNullOrWhiteSpace($Executable)) {
            $checkType = 'capability-probe-command-missing'
            $exitCode = 2
            $passed = $false
            $itemPending = $false
        } else {
            $result = Invoke-IsolatedProbe -SecretName $secretName -FilePath $Executable -Arguments $ArgumentList -TimeoutSeconds $TimeoutSeconds
            $checkType = 'process-probe'
            $exitCode = $result.ExitCode
            $passed = ($result.ExitCode -ne 0 -and $result.Redacted)
            $itemPending = $false
            $logLines.Add("$secretName capability-probe exit=$($result.ExitCode) redacted=$($result.Redacted)")
        }
    }
    if ($itemPending) { $pending = $true }
    if (-not $passed -and -not ($itemPending -and $AllowPending)) { $overallPassed = $false }
    $records.Add([pscustomobject]@{secretName=$secretName; checkType=$checkType; exitCode=$exitCode; redacted=$true; passed=$passed})
    $logLines.Add("$secretName $checkType expectedFailure=$passed")
}

$jsonl = (($records | ForEach-Object { $_ | ConvertTo-Json -Compress -Depth 4 }) -join [Environment]::NewLine) + [Environment]::NewLine
[IO.File]::WriteAllText($evidencePath, $jsonl, (New-Object Text.UTF8Encoding($false)))
$summary = "profile=$Profile records=$($records.Count) passed=$overallPassed pending=$pending generatedAt=$([DateTime]::UtcNow.ToString('o'))"
[IO.File]::WriteAllText($logPath, (($summary + [Environment]::NewLine + ($logLines -join [Environment]::NewLine)) + [Environment]::NewLine), (New-Object Text.UTF8Encoding($false)))
$records | ForEach-Object { $_ | ConvertTo-Json -Compress -Depth 4 }
if (-not $overallPassed) { exit 1 }
if ($pending -and -not $AllowPending) { exit 1 }
exit 0

function Invoke-IsolatedProbe {
    param([string]$SecretName, [string]$FilePath, [string[]]$Arguments, [int]$TimeoutSeconds)
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $FilePath
    foreach ($argument in $Arguments) { [void]$psi.ArgumentList.Add($argument) }
    $psi.WorkingDirectory = $repoRoot
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    # Copy only the current environment minus the variable under test. Values
    # never enter the evidence; child output is inspected and discarded.
    foreach ($entry in [Environment]::GetEnvironmentVariables().GetEnumerator()) {
        if ($entry.Key -ne $SecretName) { $psi.Environment[$entry.Key] = [string]$entry.Value }
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $psi
    try {
        [void]$process.Start()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            try { $process.Kill($true) } catch { }
            return [pscustomobject]@{ExitCode=124; Redacted=$true}
        }
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $combined = "$stdout`n$stderr"
        # A probe is considered unsafe if it echoes a likely secret assignment
        # or a full bearer/token value. Do not persist the offending text.
        $redacted = $combined -notmatch '(?i)(api[_-]?key|password|secret|authorization\s*:)\s*[=:]\s*[^\s,;]+'
        return [pscustomobject]@{ExitCode=$process.ExitCode; Redacted=$redacted}
    } finally { $process.Dispose() }
}
