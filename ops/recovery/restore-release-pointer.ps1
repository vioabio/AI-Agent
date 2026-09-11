<#
.SYNOPSIS
  Validate a previous v1.1 release manifest and emit a pointer-restore plan.

.DESCRIPTION
  This is a fail-closed evidence producer.  It does not call a registry,
  database, Docker or a production API.  A dry-run validates that the selected
  manifest is an immutable GA/prod release and records the data, knowledge and
  index pointers that would be restored.  The optional -Execute switch is
  intentionally refused until a reviewed staging pointer adapter is supplied;
  there is no implicit fallback to an environment variable or a live service.
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)]
    [string]$Manifest,

    [Parameter(Mandatory = $true)]
    [string]$Output,

    [ValidateSet('prod', 'staging')]
    [string]$Environment = 'staging',

    [string]$ProjectName = '',
    [string]$ApprovalId = '',
    [string]$TargetPointerFile = '',
    [switch]$FreshIsolatedProject,
    [switch]$Execute,
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

function Get-Digest([object]$Image, [string]$Name) {
    $digest = if ($Image -is [string]) { [string]$Image } else { [string]$Image.repoDigest }
    if ($digest -notmatch '^.+@sha256:[a-fA-F0-9]{64}$') { throw "$Name 必须是完整 repo@sha256 digest。" }
    return $digest
}

function Assert-NoSecretSurface([object]$Value, [string]$Path = 'manifest') {
    if ($null -eq $Value) { return }
    if ($Value -is [System.Collections.IDictionary]) {
        foreach ($key in $Value.Keys) {
            $child = $Value[$key]
            $keyText = [string]$key
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

function Write-AtomicJson {
    param([string]$Path, [object]$Body)
    if (Test-Path -LiteralPath $Path) { throw "输出已存在（证据不可覆盖）: $Path" }
    $parent = Split-Path -Parent $Path
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    $temporary = Join-Path $parent ('.' + [IO.Path]::GetFileName($Path) + "." + $PID + "." + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($temporary, ($Body | ConvertTo-Json -Depth 16), $utf8)
        [IO.File]::Move($temporary, $Path)
    } finally {
        if (Test-Path -LiteralPath $temporary) { try { [IO.File]::Delete($temporary) } catch { } }
    }
}

function Relative-RepoPath([string]$Path) {
    $prefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    return $Path.Substring($prefix.Length).Replace('\', '/')
}

$manifestPath = Resolve-RepoPath $Manifest 'Manifest' -MustExist
$outputPath = Resolve-RepoPath $Output 'Output'
$manifestRelative = Relative-RepoPath $manifestPath
$manifestHash = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
try {
    $manifestObject = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
} catch {
    throw 'Manifest 不是可解析的 JSON。'
}
Assert-NoSecretSurface $manifestObject

if ([string]$manifestObject.schemaVersion -ne 'v1.1') { throw 'Manifest schemaVersion 必须为 v1.1。' }
if ([string]$manifestObject.releaseClass -ne 'GA' -or [string]$manifestObject.status -ne 'GA') { throw '上一版本必须同时是 releaseClass=GA、status=GA。' }
if ([string]$manifestObject.environment -ne 'prod') { throw '上一版本 manifest 必须绑定 prod。' }
$releaseId = Assert-Id $manifestObject.releaseId 'releaseId'
$dataReleaseId = Assert-Id $manifestObject.dataReleaseId 'dataReleaseId'
$knowledgeReleaseId = Assert-Id $manifestObject.knowledgeReleaseId 'knowledgeReleaseId'
$indexReleaseId = Assert-Id $manifestObject.indexReleaseId 'indexReleaseId'
$backendDigest = Get-Digest $manifestObject.images.backend 'images.backend'
$frontendDigest = Get-Digest $manifestObject.images.frontend 'images.frontend'
if ($backendDigest -eq $frontendDigest) { throw 'backend/frontend digest 不能相同。' }

$safeProject = $true
if (-not [string]::IsNullOrWhiteSpace($ProjectName)) {
    $safeProject = $ProjectName -match '^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$' -and $ProjectName -notmatch '(?i)(^|[-_.])(prod|production|default)([-_.]|$)'
}
if ($Execute) {
    if ($Environment -ne 'staging') { throw '-Execute 只允许 staging。' }
    if (-not $FreshIsolatedProject) { throw '-Execute 必须同时提供 -FreshIsolatedProject。' }
    if (-not $safeProject -or [string]::IsNullOrWhiteSpace($ProjectName)) { throw 'ProjectName 必须是隔离且非 prod 的项目名。' }
    if ([string]::IsNullOrWhiteSpace($ApprovalId) -or $ApprovalId -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$' -or $ApprovalId -match '(?i)(pending|unknown|tbd|<)') { throw 'ApprovalId 缺失或无效。' }
    if ([string]::IsNullOrWhiteSpace($TargetPointerFile)) { throw '-Execute 必须显式提供 TargetPointerFile；不会读取默认 active 指针。' }
    [void](Resolve-RepoPath $TargetPointerFile 'TargetPointerFile')
}

$pointer = [ordered]@{
    dataReleaseId = $dataReleaseId
    knowledgeReleaseId = $knowledgeReleaseId
    indexReleaseId = $indexReleaseId
    sourceReleaseId = $releaseId
}
$status = if ($Execute) { 'blocked' } else { 'environment-pending' }
$passed = $false
$reason = if ($Execute) {
    '执行适配器未在当前仓库配置；未修改任何 pointer、数据库或生产服务'
} else {
    'WhatIf/plan：仅验证 previous manifest，未修改任何 pointer'
}
$result = [ordered]@{
    schemaVersion = 'v1.1'
    producer = 'restore-release-pointer'
    generatedAt = [DateTime]::UtcNow.ToString('o')
    status = $status
    passed = $passed
    environment = $Environment
    projectName = if ([string]::IsNullOrWhiteSpace($ProjectName)) { $null } else { $ProjectName }
    approvalIdPresent = -not [string]::IsNullOrWhiteSpace($ApprovalId)
    freshIsolatedProject = [bool]$FreshIsolatedProject
    manifest = $manifestRelative
    manifestSha256 = $manifestHash
    previousReleaseId = $releaseId
    pointer = $pointer
    targetPointerFile = if ([string]::IsNullOrWhiteSpace($TargetPointerFile)) { $null } else { Relative-RepoPath (Resolve-RepoPath $TargetPointerFile 'TargetPointerFile') }
    reason = $reason
    redacted = $true
}
Write-AtomicJson $outputPath $result
$result | ConvertTo-Json -Depth 12
if ($Execute) { exit 1 }
