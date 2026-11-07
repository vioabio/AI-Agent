<#
.SYNOPSIS
  Validate and plan a v1.1 release rollback.

.DESCRIPTION
  The adapter accepts only an immutable GA/prod previous manifest and an
  explicit approval/isolated staging project. It emits a redacted rollback
  plan and never mutates a registry, active release pointer, Compose project,
  database or traffic route. -Execute is intentionally fail-closed until a
  reviewed platform adapter is configured.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Manifest,

    [Parameter(Mandatory = $true)]
    [string]$ProjectName,

    [Parameter(Mandatory = $true)]
    [string]$ApprovalId,

    [Parameter(Mandatory = $true)]
    [string]$ThresholdFile,

    [Parameter(Mandatory = $true)]
    [string]$Reason,

    [ValidateSet('prod', 'staging')]
    [string]$Environment = 'staging',

    [switch]$FreshIsolatedProject,

    [switch]$Execute,

    [switch]$AllowProduction,

    [string]$ConfigSnapshot = '',

    [string]$Evidence = '',

    [switch]$WhatIf,

    [switch]$NoClobber
)

$ErrorActionPreference = 'Stop'
$script:RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

function Resolve-RepoPath {
    param(
        [Parameter(Mandatory = $true)] [string]$Value,
        [Parameter(Mandatory = $true)] [string]$Name,
        [switch]$MustExist
    )
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
    if ($MustExist -and -not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw "$Name 不存在或不是普通文件。" }
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

function Relative-RepoPath([string]$Path) {
    $prefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    return $Path.Substring($prefix.Length).Replace('\', '/')
}

function Assert-Id([object]$Value, [string]$Name) {
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text) -or
        $text -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{1,127}$' -or
        $text -match '(?i)(pending|unknown|tbd|not[-_]?published|<)') {
        throw "$Name 缺失或仍是占位符。"
    }
    return $text.Trim()
}

function Assert-Project([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value) -or
        $Value -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$' -or
        $Value -match '(?i)(^|[-_.])(prod|production|default)([-_.]|$)') {
        throw 'ProjectName 必须是非 prod 的显式 Compose/部署项目名。'
    }
    return $Value.Trim()
}

function Read-JsonFile([string]$Path, [string]$Name) {
    try { return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json }
    catch { throw "$Name 不是可解析的 JSON。" }
}

function Assert-NoSecretSurface([object]$Value, [string]$Path = 'manifest') {
    if ($null -eq $Value) { return }
    if ($Value -is [string] -or $Value.GetType().IsPrimitive -or $Value -is [decimal] -or $Value -is [datetime]) { return }
    if ($Value -is [System.Collections.IDictionary]) {
        foreach ($key in $Value.Keys) {
            $keyText = [string]$key
            $child = $Value[$key]
            if ($keyText -match '(?i)(password|passwd|api[_-]?key|access[_-]?key|client[_-]?secret|private[_-]?key|^token$)') {
                $childText = [string]$child
                if (-not [string]::IsNullOrWhiteSpace($childText) -and
                    $childText -notmatch '(?i)^(redacted|masked|removed|\*{3,}|\$\{[^}]+\})$') {
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
                if (-not [string]::IsNullOrWhiteSpace($childText) -and
                    $childText -notmatch '(?i)^(redacted|masked|removed|\*{3,}|\$\{[^}]+\})$') {
                    throw "$Path.$keyText 含未脱敏 secret。"
                }
            }
            Assert-NoSecretSurface $child "$Path.$keyText"
        }
    }
}

function Get-Digest([object]$Image, [string]$Name) {
    $digest = if ($Image -is [string]) { [string]$Image } else { [string]$Image.repoDigest }
    if ($digest -notmatch '^.+@sha256:[a-fA-F0-9]{64}$' -or
        $digest -match '(?i)(pending|unknown|tbd|example\.invalid|<)') {
        throw "$Name 必须是不可变 repo@sha256 digest。"
    }
    return $digest.Trim()
}

function Get-EntryPath([object]$Entry, [string]$Name, [object]$ManifestObject) {
    if ($null -eq $Entry) { throw "$Name 证据条目缺失。" }
    $raw = if ($Entry -is [string]) { [string]$Entry } else { [string]$Entry.path }
    if ([string]::IsNullOrWhiteSpace($raw)) { throw "$Name 证据 path 缺失。" }
    $normalized = $raw.Replace('/', '\')
    $artifactRoot = [string]$ManifestObject.artifactRoot
    $candidates = @()
    if ($normalized -match '^(?i)artifacts\\') { $candidates += (Join-Path $script:RepoRoot $normalized) }
    elseif (-not [string]::IsNullOrWhiteSpace($artifactRoot)) { $candidates += (Join-Path (Join-Path $script:RepoRoot $artifactRoot) $normalized) }
    $candidates += (Join-Path $script:RepoRoot $normalized)
    foreach ($candidate in $candidates) {
        try { return (Resolve-RepoPath $candidate "$Name path" -MustExist) } catch { }
    }
    throw "$Name 证据文件不存在或路径越界。"
}

function Assert-EvidenceHash([object]$Entry, [string]$Name, [string]$Path) {
    if ($Entry -is [string]) { throw "$Name 缺少 sha256。" }
    $expected = [string]$Entry.sha256
    if ([string]::IsNullOrWhiteSpace($expected)) { throw "$Name 缺少 sha256。" }
    $expected = $expected -replace '^sha256:', ''
    if ($expected -notmatch '^[a-fA-F0-9]{64}$') { throw "$Name sha256 格式无效。" }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    if ($actual -ine $expected) { throw "$Name sha256 不匹配。" }
}

function Assert-Threshold([string]$Path) {
    $threshold = Read-JsonFile $Path 'ThresholdFile'
    Assert-NoSecretSurface $threshold 'threshold'
    $version = [string]$threshold.thresholdVersion
    if ([string]::IsNullOrWhiteSpace($version)) { $version = [string]$threshold.dictionaryVersion }
    if ([string]::IsNullOrWhiteSpace($version) -or $version -match '(?i)(draft|pending|unknown|tbd|<)') {
        throw 'ThresholdFile 必须包含已审核的 thresholdVersion/dictionaryVersion，不能是 draft/pending。'
    }
    $metrics = @($threshold.metrics)
    if ($metrics.Count -eq 0) { throw 'ThresholdFile.metrics 不能为空。' }
    foreach ($metric in $metrics) {
        if ([string]::IsNullOrWhiteSpace([string]$metric.metricId) -or [string]::IsNullOrWhiteSpace([string]$metric.thresholdVersion)) {
            throw 'ThresholdFile 每个 metric 必须包含 metricId 和 thresholdVersion。'
        }
    }
    return [ordered]@{ version = $version; sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant(); path = Relative-RepoPath $Path; metricCount = $metrics.Count }
}

function Write-AtomicJson([string]$Path, [object]$Body) {
    if (Test-Path -LiteralPath $Path) { throw "输出已存在（证据不可覆盖）: $Path" }
    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    $temporary = Join-Path (Split-Path -Parent $Path) ('.' + [IO.Path]::GetFileName($Path) + '.' + $PID + '.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($temporary, ($Body | ConvertTo-Json -Depth 20) + [Environment]::NewLine, $utf8)
        [IO.File]::Move($temporary, $Path)
    } finally { if (Test-Path -LiteralPath $temporary) { try { [IO.File]::Delete($temporary) } catch { } } }
}

function Write-AtomicJsonl([string]$Path, [object]$Body) {
    if (Test-Path -LiteralPath $Path) { throw "输出已存在（证据不可覆盖）: $Path" }
    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    $temporary = Join-Path (Split-Path -Parent $Path) ('.' + [IO.Path]::GetFileName($Path) + '.' + $PID + '.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($temporary, (($Body | ConvertTo-Json -Depth 20 -Compress) + [Environment]::NewLine), $utf8)
        [IO.File]::Move($temporary, $Path)
    } finally { if (Test-Path -LiteralPath $temporary) { try { [IO.File]::Delete($temporary) } catch { } } }
}

if ($WhatIf -and $Execute) { throw '-WhatIf 与 -Execute 不能同时使用。' }
$project = Assert-Project $ProjectName
$approval = Assert-Id $ApprovalId 'ApprovalId'
$reasonText = $Reason.Trim()
if ([string]::IsNullOrWhiteSpace($reasonText) -or $reasonText.Length -gt 240 -or $reasonText -match '(?i)(password|api[_-]?key|token|secret)') { throw 'Reason 不能为空、过长或包含敏感字段。' }
if (-not $FreshIsolatedProject) { throw '必须显式提供 -FreshIsolatedProject；不会操作默认项目。' }
if ($Environment -eq 'staging' -and $project -notmatch '(?i)(canary|staging|candidate|release|drill|restore)') { throw 'staging ProjectName 必须包含隔离标识。' }
if ($Environment -eq 'prod') {
    if (-not $AllowProduction) { throw 'prod rollback 必须显式提供 -AllowProduction；默认拒绝。' }
    if (-not $Execute) { throw 'prod rollback 仅允许显式执行审批流程；WhatIf 不能伪造生产回滚。' }
    $prodApproval = [Environment]::GetEnvironmentVariable('VIO_PRODUCTION_RELEASE_APPROVAL_ID')
    if ([string]::IsNullOrWhiteSpace($prodApproval) -or $prodApproval -ne $approval) { throw '生产审批环境变量未匹配；拒绝回滚。' }
}

$manifestPath = Resolve-RepoPath $Manifest 'Manifest' -MustExist
$thresholdPath = Resolve-RepoPath $ThresholdFile 'ThresholdFile' -MustExist
$manifestObject = Read-JsonFile $manifestPath 'Manifest'
Assert-NoSecretSurface $manifestObject
if ([string]$manifestObject.schemaVersion -ne 'v1.1') { throw 'Manifest schemaVersion 必须为 v1.1。' }
if ([string]$manifestObject.releaseClass -ne 'GA' -or [string]$manifestObject.status -ne 'GA' -or [string]$manifestObject.environment -ne 'prod') {
    throw 'rollback Manifest 必须是 schemaVersion=v1.1 且 releaseClass/status=GA、environment=prod。'
}
$releaseId = Assert-Id $manifestObject.releaseId 'previous releaseId'
$dataReleaseId = Assert-Id $manifestObject.dataReleaseId 'dataReleaseId'
$knowledgeReleaseId = Assert-Id $manifestObject.knowledgeReleaseId 'knowledgeReleaseId'
$indexReleaseId = Assert-Id $manifestObject.indexReleaseId 'indexReleaseId'
$backendDigest = Get-Digest $manifestObject.images.backend 'images.backend'
$frontendDigest = Get-Digest $manifestObject.images.frontend 'images.frontend'
if ($backendDigest -eq $frontendDigest) { throw 'backend/frontend digest 不能相同。' }

$signatureEvidence = [ordered]@{}
foreach ($name in @('backend', 'frontend')) {
    $imageEvidence = $manifestObject.evidence.images.$name
    if ($null -eq $imageEvidence) { throw "evidence.images.$name 缺失。" }
    $verifyEntry = $imageEvidence.signatureVerify
    $verifyPath = Get-EntryPath $verifyEntry "$($name) signatureVerify" $manifestObject
    Assert-EvidenceHash $verifyEntry "$($name) signatureVerify" $verifyPath
    $signatureEvidence[$name] = [ordered]@{ path = Relative-RepoPath $verifyPath; sha256 = (Get-FileHash -LiteralPath $verifyPath -Algorithm SHA256).Hash.ToLowerInvariant(); verifiedEvidencePresent = $true }
}
$thresholdInfo = Assert-Threshold $thresholdPath

$snapshotPath = $null
$evidencePath = $null
if (-not [string]::IsNullOrWhiteSpace($ConfigSnapshot)) { $snapshotPath = Resolve-RepoPath $ConfigSnapshot 'ConfigSnapshot' }
if (-not [string]::IsNullOrWhiteSpace($Evidence)) { $evidencePath = Resolve-RepoPath $Evidence 'Evidence' }
$status = if ($WhatIf) { 'dry-run' } elseif ($Execute) { 'blocked' } else { 'environment-pending' }
$reasonState = if ($WhatIf) {
    'dry-run：已验证 previous GA manifest/digest/签名证据/threshold/隔离项目；未调用部署平台或修改任何状态'
} elseif ($Execute) {
    '未配置经审计的部署平台 adapter；执行请求被 fail-closed 拒绝，未修改 registry、Compose、active pointer 或流量路由'
} else {
    '未指定 -WhatIf 或 -Execute；仅生成待审批回滚计划，未调用部署平台'
}
$plan = [ordered]@{
    schemaVersion = 'v1.1'; producer = 'rollback-adapter'; operation = 'rollback'
    generatedAt = [DateTime]::UtcNow.ToString('o'); environment = $Environment
    releaseId = $releaseId; manifest = Relative-RepoPath $manifestPath
    manifestSha256 = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    projectName = $project; approvalIdPresent = $true; freshIsolatedProject = $true
    reason = $reasonText; threshold = $thresholdInfo
    rollbackTargets = [ordered]@{
        backendImage = $backendDigest; frontendImage = $frontendDigest
        dataReleaseId = $dataReleaseId; knowledgeReleaseId = $knowledgeReleaseId; indexReleaseId = $indexReleaseId
        signatureEvidence = $signatureEvidence
    }
    mutationPerformed = $false; executed = $false; adapter = 'not-configured'
    status = $status; passed = $false; stateReason = $reasonState
    configSnapshot = if ($snapshotPath) { Relative-RepoPath $snapshotPath } else { $null }
    evidence = if ($evidencePath) { Relative-RepoPath $evidencePath } else { $null }
    redacted = $true
}
if ($snapshotPath) { Write-AtomicJson $snapshotPath $plan }
if ($evidencePath) {
    $event = [ordered]@{
        schemaVersion = 'v1.1'; producer = 'rollback-adapter'; operation = 'rollback'
        generatedAt = $plan.generatedAt; releaseId = $releaseId; environment = $Environment
        projectName = $project; reason = $reasonText; status = $status; passed = $false
        mutationPerformed = $false; rollbackTargets = $plan.rollbackTargets
        stateReason = $reasonState; redacted = $true
    }
    Write-AtomicJsonl $evidencePath $event
}
$plan | ConvertTo-Json -Depth 20
if ($Execute) { exit 1 }
exit 0
