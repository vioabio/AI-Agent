<#
.SYNOPSIS
  Verify an explicitly selected v1.1 recovery backup without exposing its contents.

.DESCRIPTION
  The v1.1 recovery gate treats a backup checksum as an input, not as a value to
  be guessed.  The script only reads the selected file, computes SHA-256 and
  writes a small, redacted text record.  It never connects to PostgreSQL,
  object storage or a production service.  Evidence files are append-only:
  an existing output is rejected even when -NoClobber is omitted.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupFile,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedSha256,

    [Parameter(Mandatory = $true)]
    [string]$Output,

    # Kept for parity with the release runbook.  Evidence is immutable in all
    # modes; omission does not enable overwrite.
    [switch]$NoClobber
)

$ErrorActionPreference = 'Stop'
$script:DryRunRequested = [bool]$WhatIfPreference
$WhatIfPreference = $false
$script:RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

function Resolve-RepoPath {
    param([string]$Value, [string]$Name, [switch]$MustExist)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.IndexOf([char]0) -ge 0) {
        throw "$Name 不能为空或包含无效字符。"
    }
    $candidate = if ([IO.Path]::IsPathRooted($Value)) {
        [IO.Path]::GetFullPath($Value)
    } else {
        [IO.Path]::GetFullPath((Join-Path $script:RepoRoot $Value))
    }
    $prefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Name 必须位于仓库内。"
    }
    if (Test-Path -LiteralPath $candidate -PathType Container) {
        throw "$Name 不能是目录。"
    }
    if ($MustExist -and -not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "$Name 不存在或不是普通文件。"
    }
    if ($MustExist) {
        $item = Get-Item -LiteralPath $candidate -Force
        if ($item.LinkType) { throw "$Name 不允许通过符号链接读取。" }
        if ($item.Length -le 0) { throw "$Name 为空文件。" }
    }
    return $candidate
}

function Write-ImmutableText {
    param([string]$Path, [string]$Content)
    if (Test-Path -LiteralPath $Path) { throw "输出已存在（证据不可覆盖）: $Path" }
    $parent = Split-Path -Parent $Path
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    $temporary = Join-Path $parent ('.' + [IO.Path]::GetFileName($Path) + "." + $PID + "." + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($temporary, $Content, $utf8)
        [IO.File]::Move($temporary, $Path)
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            try { [IO.File]::Delete($temporary) } catch { }
        }
    }
}

function Normalize-Sha256([string]$Value) {
    $normalized = $Value.Trim() -replace '^sha256:', ''
    if ($normalized -notmatch '^[a-fA-F0-9]{64}$') {
        throw 'ExpectedSha256 必须是 64 位十六进制 SHA-256（可带 sha256: 前缀）。'
    }
    return $normalized.ToLowerInvariant()
}

function Relative-RepoPath([string]$Path) {
    $prefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    return $Path.Substring($prefix.Length).Replace('\', '/')
}

$dryRun = $script:DryRunRequested
$expected = Normalize-Sha256 $ExpectedSha256
$backupPath = Resolve-RepoPath $BackupFile 'BackupFile' -MustExist
$outputPath = Resolve-RepoPath $Output 'Output'
$relativeBackup = Relative-RepoPath $backupPath
$relativeOutput = Relative-RepoPath $outputPath

if ($dryRun) {
    $record = @(
        'schemaVersion=v1.1',
        'producer=verify-backup',
        'status=environment-pending',
        'passed=false',
        "backupFile=$relativeBackup",
        'sha256=not-computed',
        "expectedSha256=$expected",
        'reason=WhatIf; backup bytes were not read',
        "output=$relativeOutput",
        'redacted=true'
    ) -join [Environment]::NewLine
    Write-ImmutableText $outputPath ($record + [Environment]::NewLine)
    Write-Output $record
    exit 0
}

$item = Get-Item -LiteralPath $backupPath -Force
$actual = (Get-FileHash -LiteralPath $backupPath -Algorithm SHA256).Hash.ToLowerInvariant()
$passed = $actual -eq $expected
$status = if ($passed) { 'verified' } else { 'blocked' }
$record = @(
    'schemaVersion=v1.1',
    'producer=verify-backup',
    "verifiedAt=$([DateTime]::UtcNow.ToString('o'))",
    "status=$status",
    "passed=$($passed.ToString().ToLowerInvariant())",
    "backupFile=$relativeBackup",
    "bytes=$($item.Length)",
    "sha256=$actual",
    "expectedSha256=$expected",
    "output=$relativeOutput",
    'redacted=true'
) -join [Environment]::NewLine
Write-ImmutableText $outputPath ($record + [Environment]::NewLine)
Write-Output $record
if (-not $passed) { exit 1 }
