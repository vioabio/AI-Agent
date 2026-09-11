<#
.SYNOPSIS
  Aggregate explicit observability probe references into a redacted Markdown index.

.DESCRIPTION
  The producer never copies metric values or raw response bodies. It validates
  the four canonical probe files, records only relative paths, hashes, sizes and
  source status, and writes an immutable report. Missing or malformed raw
  evidence is a blocking result. It does not contact Prometheus, Alertmanager,
  Grafana or the application.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)]
    [string]$InputDir,

    [Parameter(Mandatory = $true)]
    [string]$Output,

    [string]$ReleaseId = '',

    [ValidateSet('prod', 'staging')]
    [string]$Environment = 'staging',

    [switch]$NoClobber
)

$ErrorActionPreference = 'Stop'
$script:DryRunRequested = [bool]$WhatIfPreference
$WhatIfPreference = $false
$script:RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

function Resolve-RepoPath {
    param([string]$Value, [string]$Name, [switch]$Directory, [switch]$MustExist)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.IndexOf([char]0) -ge 0) { throw "$Name 不能为空或包含无效字符。" }
    $candidate = if ([IO.Path]::IsPathRooted($Value)) { [IO.Path]::GetFullPath($Value) } else { [IO.Path]::GetFullPath((Join-Path $script:RepoRoot $Value)) }
    $prefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "$Name 必须位于仓库内。" }
    if ($Directory) {
        if ($MustExist -and -not (Test-Path -LiteralPath $candidate -PathType Container)) { throw "$Name 目录不存在。" }
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { throw "$Name 不能是文件。" }
        if ($MustExist) {
            $item = Get-Item -LiteralPath $candidate -Force
            if ($item.LinkType) { throw "$Name 不允许通过符号链接读取。" }
        } else {
            [IO.Directory]::CreateDirectory($candidate) | Out-Null
        }
    } else {
        if (Test-Path -LiteralPath $candidate -PathType Container) { throw "$Name 不能是目录。" }
        if ($MustExist -and -not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw "$Name 文件不存在。" }
        if ($MustExist) {
            $item = Get-Item -LiteralPath $candidate -Force
            if ($item.LinkType) { throw "$Name 不允许通过符号链接读取。" }
        }
    }
    return $candidate
}

function Relative-RepoPath([string]$Path) {
    $prefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    return $Path.Substring($prefix.Length).Replace('\', '/')
}

function Assert-Id([string]$Value, [string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    if ($Value -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{1,127}$' -or $Value -match '(?i)(pending|unknown|tbd|<)') { throw "$Name 无效。" }
    return $Value.Trim()
}

function Assert-NoSecretText([string]$Text, [string]$Name) {
    foreach ($line in ($Text -split '\r?\n')) {
        if ($line -match '(?i)^\s*["'']?(password|passwd|api[_-]?key|access[_-]?key|client[_-]?secret|private[_-]?key|token)["'']?\s*[:=]\s*["'']?([^"'',;\s}]+)') {
            $value = $Matches[2]
            if ($value -notmatch '(?i)^(redacted|\*{3,}|\$\{[^}]+\})$') { throw "$Name 含未脱敏 secret。" }
        }
        if ($line -match '(?:sk-[A-Za-z0-9]{12,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9_]{20,})') { throw "$Name 含 token-shaped value。" }
    }
}

function Read-Artifact([string]$Path, [string]$Name, [string]$ExpectedRelease, [string]$ExpectedEnvironment) {
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.Length -le 0) { throw "$Name 为空。" }
    $raw = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
    Assert-NoSecretText $raw $Name
    try { $json = $raw | ConvertFrom-Json } catch { throw "$Name JSON 解析失败。" }
    if ([string]$json.schemaVersion -ne 'v1.1') { throw "$Name schemaVersion 必须为 v1.1。" }
    if ($ExpectedRelease -and $json.releaseId -and [string]$json.releaseId -ne $ExpectedRelease) { throw "$Name releaseId 不一致。" }
    if ($ExpectedEnvironment -and $json.environment -and [string]$json.environment -ne $ExpectedEnvironment) { throw "$Name environment 不一致。" }
    $sourcePassed = $null
    if ($null -ne $json.passed) { $sourcePassed = [bool]$json.passed }
    elseif ($json.probes) {
        $probeRows = @($json.probes)
        $sourcePassed = ($probeRows.Count -gt 0 -and @($probeRows | Where-Object { $_.passed -ne $true }).Count -eq 0)
    }
    return [ordered]@{
        name = $Name
        path = Relative-RepoPath $Path
        sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
        bytes = $item.Length
        sourcePassed = $sourcePassed
        schemaVersion = [string]$json.schemaVersion
    }
}

function Write-ImmutableText([string]$Path, [string]$Content) {
    if (Test-Path -LiteralPath $Path) { throw "输出已存在（证据不可覆盖）: $Path" }
    $parent = Split-Path -Parent $Path
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    $temporary = Join-Path $parent ('.' + [IO.Path]::GetFileName($Path) + "." + $PID + "." + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($temporary, $Content, $utf8)
        [IO.File]::Move($temporary, $Path)
    } finally {
        if (Test-Path -LiteralPath $temporary) { try { [IO.File]::Delete($temporary) } catch { } }
    }
}

$release = Assert-Id $ReleaseId 'ReleaseId'
$inputPath = Resolve-RepoPath $InputDir 'InputDir' -Directory -MustExist
$outputPath = Resolve-RepoPath $Output 'Output'
$requiredNames = @('prometheus-targets.json', 'prometheus-rules.json', 'alertmanager-events.json', 'grafana-health.json')
$optionalNames = @('observability-probes.json', 'fault-injection-timeline.md', 'dashboard-config-hash.json', 'fault-plan.sha256.json')
$rows = @()
$missing = @()
$errors = @()
foreach ($name in $requiredNames) {
    $path = Join-Path $inputPath $name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { $missing += $name; continue }
    try { $rows += Read-Artifact $path $name $release $Environment } catch { $errors += $_.Exception.Message }
}
foreach ($name in $optionalNames) {
    $path = Join-Path $inputPath $name
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        if ($name -like '*.json') {
            try { $rows += Read-Artifact $path $name $release $Environment } catch { $errors += $_.Exception.Message }
        } else {
            $item = Get-Item -LiteralPath $path -Force
            $raw = Get-Content -LiteralPath $path -Raw -Encoding UTF8
            Assert-NoSecretText $raw $name
            $rows += [ordered]@{ name = $name; path = Relative-RepoPath $path; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant(); bytes = $item.Length; sourcePassed = $null; schemaVersion = $null }
        }
    }
}
if ($missing.Count -gt 0) { $errors += ('缺少原始观测证据: ' + ($missing -join ', ')) }
$requiredRows = @($rows | Where-Object { $_.name -in $requiredNames })
$allSourcesPassed = ($requiredRows.Count -eq $requiredNames.Count -and @($requiredRows | Where-Object { $_.sourcePassed -ne $true }).Count -eq 0)
$passed = ($missing.Count -eq 0 -and $errors.Count -eq 0 -and $allSourcesPassed)
$status = if ($passed) { 'passed' } else { 'environment-pending' }
$table = @('| artifact | repo-relative path | sha256 | bytes | source status |', '|---|---|---|---:|---|')
foreach ($row in $rows) {
    $sourceStatus = if ($null -eq $row.sourcePassed) { 'not-applicable' } elseif ($row.sourcePassed) { 'passed' } else { 'blocked' }
    $table += "| $($row.name) | $($row.path) | $($row.sha256) | $($row.bytes) | $sourceStatus |"
}
$reason = if ($errors.Count -gt 0) { ($errors -join '; ') } elseif (-not $passed) { 'raw probe files exist but source probes are not all passed' } else { 'all required raw probe references validated' }
$releaseLabel = if ($release) { $release } else { 'not-bound' }
$markdown = @(
    '# v1.1 Observability Evidence Index',
    '',
    "> status: $status; releaseId: $releaseLabel; environment: $Environment",
    '',
    'This file is an immutable index. It references raw evidence by path and SHA-256; it does not copy or rewrite metric values, query responses, credentials or headers.',
    '',
    ($table -join [Environment]::NewLine),
    '',
    "generatedAt: $([DateTime]::UtcNow.ToString('o'))",
    'producer: aggregate-observability-evidence',
    "passed: $($passed.ToString().ToLowerInvariant())",
    "reason: $reason",
    'redacted: true'
) -join [Environment]::NewLine
Write-ImmutableText $outputPath ($markdown + [Environment]::NewLine)
Write-Output $markdown
if (-not $passed) { exit 1 }
