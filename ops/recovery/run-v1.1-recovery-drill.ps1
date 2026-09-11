<#
.SYNOPSIS
  Produce an auditable, fail-closed v1.1 recovery-drill record.

.DESCRIPTION
  The wrapper validates an isolated project, backup and previous release
  manifest, then emits one JSONL record.  It performs no network, Docker,
  PostgreSQL or pointer mutation in -WhatIf/default mode.  The destructive
  switch is deliberately blocked because this repository has no reviewed
  restore adapter; it cannot silently fall back to COMPOSE_PROJECT_NAME or a
  production endpoint.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)]
    [string]$BaseUrl,

    [Parameter(Mandatory = $true)]
    [string]$ProjectName,

    [Parameter(Mandatory = $true)]
    [string]$BackupFile,

    [Parameter(Mandatory = $true)]
    [ValidateSet('container', 'database', 'index-or-dependency')]
    [string]$FailureType,

    [Parameter(Mandatory = $true)]
    [string]$DrillId,

    [Parameter(Mandatory = $true)]
    [string]$PreviousManifest,

    [Parameter(Mandatory = $true)]
    [string]$ApprovalId,

    [Parameter(Mandatory = $true)]
    [string]$Output,

    [ValidateSet('prod', 'staging')]
    [string]$Environment = 'staging',

    [string]$ExpectedSha256 = '',
    [string]$RestoreOutput = '',
    [string]$ReleasePointerOutput = '',
    [int]$TargetRpoSeconds = 900,
    [int]$TargetRtoSeconds = 1800,
    [switch]$FreshIsolatedProject,
    [Alias('Execute')]
    [switch]$ExecuteDestructive,
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
    if (Test-Path -LiteralPath $candidate -PathType Container) { throw "$Name 不能是目录。" }
    if ($MustExist -and -not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "$Name 不存在或不是普通文件。"
    }
    if ($MustExist) {
        $item = Get-Item -LiteralPath $candidate -Force
        if ($item.LinkType) { throw "$Name 不允许通过符号链接读取。" }
    } else {
        $parent = Split-Path -Parent $candidate
        while ($parent -and -not (Test-Path -LiteralPath $parent -PathType Container)) {
            $next = Split-Path -Parent $parent
            if ($next -eq $parent) { break }
            $parent = $next
        }
        if ($parent) {
            $parentItem = Get-Item -LiteralPath $parent -Force
            if ($parentItem.LinkType) { throw "$Name 的父目录不允许通过符号链接写入。" }
        }
    }
    return $candidate
}

function Assert-Id([object]$Value, [string]$Name) {
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text) -or $text -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{1,127}$' -or $text -match '(?i)(pending|unknown|tbd|not[-_]?published|<)') {
        throw "$Name 缺失或仍是占位符。"
    }
    return $text.Trim()
}

function Assert-NoSecretSurface([object]$Value, [string]$Path = 'manifest') {
    if ($null -eq $Value) { return }
    if ($Value -is [System.Collections.IDictionary]) {
        foreach ($key in $Value.Keys) {
            $keyText = [string]$key
            $child = $Value[$key]
            if ($keyText -match '(?i)(password|passwd|api[_-]?key|access[_-]?key|client[_-]?secret|private[_-]?key|^token$)') {
                $childText = [string]$child
                if (-not [string]::IsNullOrWhiteSpace($childText) -and $childText -notmatch '(?i)^(redacted|\*{3,}|\$\{[^}]+\})$') {
                    throw "$Path.$keyText 含未脱敏 secret。"
                }
            }
            Assert-NoSecretSurface $child "$Path.$keyText"
        }
        return
    }
    if ($Value -is [System.Collections.IEnumerable] -and -not ($Value -is [string])) {
        $index = 0
        foreach ($item in $Value) { Assert-NoSecretSurface $item "$Path[$index]"; $index++ }
        return
    }
    if ($Value.PSObject -and $Value.PSObject.Properties.Count -gt 0) {
        foreach ($property in $Value.PSObject.Properties) {
            $keyText = [string]$property.Name
            $child = $property.Value
            if ($keyText -match '(?i)(password|passwd|api[_-]?key|access[_-]?key|client[_-]?secret|private[_-]?key|^token$)') {
                $childText = [string]$child
                if (-not [string]::IsNullOrWhiteSpace($childText) -and $childText -notmatch '(?i)^(redacted|\*{3,}|\$\{[^}]+\})$') {
                    throw "$Path.$keyText 含未脱敏 secret。"
                }
            }
            Assert-NoSecretSurface $child "$Path.$keyText"
        }
    }
}

function Normalize-Sha256([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    $normalized = $Value.Trim() -replace '^sha256:', ''
    if ($normalized -notmatch '^[a-fA-F0-9]{64}$') { throw 'ExpectedSha256/BACKUP_SHA256 必须是 64 位十六进制 SHA-256。' }
    return $normalized.ToLowerInvariant()
}

function Write-AtomicJsonl {
    param([string]$Path, [object]$Record)
    if (Test-Path -LiteralPath $Path) { throw "输出已存在（证据不可覆盖）: $Path" }
    $parent = Split-Path -Parent $Path
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    $temporary = Join-Path $parent ('.' + [IO.Path]::GetFileName($Path) + "." + $PID + "." + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($temporary, ($Record | ConvertTo-Json -Depth 16 -Compress) + [Environment]::NewLine, $utf8)
        [IO.File]::Move($temporary, $Path)
    } finally {
        if (Test-Path -LiteralPath $temporary) { try { [IO.File]::Delete($temporary) } catch { } }
    }
}

function Relative-RepoPath([string]$Path) {
    $prefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    return $Path.Substring($prefix.Length).Replace('\', '/')
}

if ($script:DryRunRequested -and $ExecuteDestructive) { throw '-WhatIf 与 -ExecuteDestructive 不能同时使用。' }
$drill = Assert-Id $DrillId 'DrillId'
if ($TargetRpoSeconds -le 0 -or $TargetRtoSeconds -le 0) { throw 'RPO/RTO 目标必须是正整数秒。' }
if (-not $FreshIsolatedProject) { throw '必须显式提供 -FreshIsolatedProject；不会对默认 Compose project 做恢复演练。' }
if ($ProjectName -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$' -or $ProjectName -match '(?i)(^|[-_.])(prod|production|default)([-_.]|$)' -or $ProjectName -notmatch '(?i)(container|database|db|index|dependency|restore|drill|canary)') {
    throw 'ProjectName 不是可识别的隔离演练项目名。'
}
if ($ApprovalId -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$' -or $ApprovalId -match '(?i)(pending|unknown|tbd|<)') { throw 'ApprovalId 缺失或无效。' }
$baseUri = $null
if (-not [Uri]::TryCreate($BaseUrl.TrimEnd('/'), [UriKind]::Absolute, [ref]$baseUri) -or $baseUri.Scheme -notin @('http', 'https')) { throw 'BaseUrl 必须是 http/https URL。' }
if ($Environment -eq 'prod' -and ($baseUri.Host -match '^(localhost|127\.0\.0\.1|::1)$' -or $baseUri.Host -match '(?i)(staging|test)')) { throw 'prod 演练禁止 localhost/staging/test 地址。' }

$backupPath = Resolve-RepoPath $BackupFile 'BackupFile' -MustExist
$manifestPath = Resolve-RepoPath $PreviousManifest 'PreviousManifest' -MustExist
$outputPath = Resolve-RepoPath $Output 'Output'
$backupItem = Get-Item -LiteralPath $backupPath -Force
if ($backupItem.Length -le 0) { throw 'BackupFile 为空。' }
$actualSha256 = (Get-FileHash -LiteralPath $backupPath -Algorithm SHA256).Hash.ToLowerInvariant()
$expected = Normalize-Sha256 $ExpectedSha256
if ([string]::IsNullOrWhiteSpace($expected)) { $expected = Normalize-Sha256 ([Environment]::GetEnvironmentVariable('BACKUP_SHA256')) }
$checksumStatus = if ($expected) { if ($actualSha256 -eq $expected) { 'verified' } else { throw 'backup checksum 不匹配；演练保持阻断。' } } else { 'environment-pending' }

try { $manifestObject = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { throw 'PreviousManifest 不是可解析的 JSON。' }
Assert-NoSecretSurface $manifestObject
if ([string]$manifestObject.schemaVersion -ne 'v1.1' -or [string]$manifestObject.releaseClass -ne 'GA' -or [string]$manifestObject.status -ne 'GA' -or [string]$manifestObject.environment -ne 'prod') {
    throw 'PreviousManifest 必须是 schemaVersion=v1.1 且 releaseClass/status=GA、environment=prod。'
}
$previousReleaseId = Assert-Id $manifestObject.releaseId 'previous releaseId'
$dataReleaseId = Assert-Id $manifestObject.dataReleaseId 'dataReleaseId'
$knowledgeReleaseId = Assert-Id $manifestObject.knowledgeReleaseId 'knowledgeReleaseId'
$indexReleaseId = Assert-Id $manifestObject.indexReleaseId 'indexReleaseId'

$restorePath = $null
$pointerPath = $null
if (-not [string]::IsNullOrWhiteSpace($RestoreOutput)) { $restorePath = Resolve-RepoPath $RestoreOutput 'RestoreOutput' }
if (-not [string]::IsNullOrWhiteSpace($ReleasePointerOutput)) { $pointerPath = Resolve-RepoPath $ReleasePointerOutput 'ReleasePointerOutput' }
if ($ExecuteDestructive) {
    if ($Environment -ne 'staging') { throw '-ExecuteDestructive 只允许 staging。' }
    foreach ($name in @('VIO_RELEASE_COMPOSE_PROJECT', 'VIO_BASE_URL', 'VIO_API_KEY', 'BACKUP_SHA256')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) { throw \"执行模式缺少环境变量 $name；不会猜测凭据。\" }
    }
    if ($checksumStatus -ne 'verified') { throw '执行模式必须先通过 backup checksum。' }
    if ($null -eq $restorePath -or $null -eq $pointerPath) { throw '执行模式必须提供 RestoreOutput 和 ReleasePointerOutput。' }
    # There is intentionally no destructive adapter in this repository yet.
    # Reaching this branch is a reviewable failure, not permission to run
    # docker/pg_restore or mutate an active pointer.
}

$manifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
$backupRelative = Relative-RepoPath $backupPath
$manifestRelative = Relative-RepoPath $manifestPath
$status = if ($ExecuteDestructive) { 'blocked' } else { 'environment-pending' }
$reason = if ($ExecuteDestructive) {
    '未配置经审计的 staging restore adapter；未执行 Docker、pg_restore、索引切换或网络探针'
} else {
    'WhatIf/plan：未执行 Docker、pg_restore、索引切换或网络探针'
}
$record = [ordered]@{
    schemaVersion = 'v1.1'
    producer = 'run-v1.1-recovery-drill'
    drillId = $drill
    failureType = $FailureType
    generatedAt = [DateTime]::UtcNow.ToString('o')
    startedAt = [DateTime]::UtcNow.ToString('o')
    finishedAt = $null
    environment = $Environment
    baseUrl = "$($baseUri.Scheme)://$($baseUri.Host)$(if($baseUri.IsDefaultPort){''}else{':'+$baseUri.Port})"
    projectName = $ProjectName
    approvalIdPresent = $true
    freshIsolatedProject = $true
    backupFile = $backupRelative
    backupSha256 = $actualSha256
    expectedSha256Present = $null -ne $expected
    checksumStatus = $checksumStatus
    backupTimestamp = $backupItem.LastWriteTimeUtc.ToString('o')
    previousManifest = $manifestRelative
    previousManifestSha256 = $manifestHash
    previousReleaseId = $previousReleaseId
    dataReleaseId = $dataReleaseId
    knowledgeReleaseId = $knowledgeReleaseId
    indexReleaseId = $indexReleaseId
    lastCommittedAt = $null
    restorePointAt = $null
    readinessAt = $null
    actualRpoSeconds = $null
    actualRtoSeconds = $null
    targetRpoSeconds = $TargetRpoSeconds
    targetRtoSeconds = $TargetRtoSeconds
    clockSource = 'not-executed'
    status = $status
    passed = $false
    restoreOutput = if ($restorePath) { Relative-RepoPath $restorePath } else { $null }
    releasePointerOutput = if ($pointerPath) { Relative-RepoPath $pointerPath } else { $null }
    reason = $reason
    redacted = $true
}
Write-AtomicJsonl $outputPath $record
$record | ConvertTo-Json -Depth 16
if ($ExecuteDestructive) { exit 1 }
