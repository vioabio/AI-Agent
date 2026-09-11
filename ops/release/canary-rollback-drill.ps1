<#
.SYNOPSIS
  Produce a fail-closed canary promotion/rollback drill record.

.DESCRIPTION
  This wrapper validates candidate/previous release lineage and writes the
  configuration snapshot plus JSONL drill event required by N7.  It does not
  execute Docker, a registry operation, a traffic switch, a database restore
  or a production request.  -Execute is retained as an explicit hand-off
  marker and returns non-zero until an audited platform adapter is installed.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string]$Manifest,
    [Parameter(Mandatory = $true)] [string]$PreviousManifest,
    [Parameter(Mandatory = $true)] [string]$ProjectName,
    [Parameter(Mandatory = $true)] [string]$ApprovalId,
    [Parameter(Mandatory = $true)] [string]$ThresholdFile,
    [Parameter(Mandatory = $true)] [string]$ConfigSnapshot,
    [Parameter(Mandatory = $true)] [string]$Evidence,
    [ValidateSet('prod', 'staging')] [string]$Environment = 'staging',
    [ValidateRange(1, 100)] [int]$CanaryPercent = 5,
    [switch]$FreshIsolatedProject,
    [switch]$Execute,
    [switch]$WhatIf,
    [switch]$NoClobber
)

$ErrorActionPreference = 'Stop'
$script:RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

function Resolve-RepoPath {
    param([Parameter(Mandatory = $true)] [string]$Value, [Parameter(Mandatory = $true)] [string]$Name, [switch]$MustExist)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.IndexOf([char]0) -ge 0) { throw "$Name 不能为空。" }
    $candidate = if ([IO.Path]::IsPathRooted($Value)) { [IO.Path]::GetFullPath($Value) } else { [IO.Path]::GetFullPath((Join-Path $script:RepoRoot $Value)) }
    $prefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "$Name 必须位于仓库内。" }
    if (Test-Path -LiteralPath $candidate -PathType Container) { throw "$Name 不能是目录。" }
    if ($MustExist -and -not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw "$Name 不存在。" }
    if ($MustExist -and (Get-Item -LiteralPath $candidate -Force).LinkType) { throw "$Name 不允许符号链接。" }
    if (-not $MustExist) {
        $parent = Split-Path -Parent $candidate
        while ($parent -and -not (Test-Path -LiteralPath $parent -PathType Container)) {
            $next = Split-Path -Parent $parent
            if ($next -eq $parent) { break }
            $parent = $next
        }
        if ($parent -and (Get-Item -LiteralPath $parent -Force).LinkType) { throw "$Name 父目录不允许符号链接。" }
    }
    return $candidate
}
function Relative-RepoPath([string]$Path) { return $Path.Substring($script:RepoRoot.TrimEnd('\').Length + 1).Replace('\', '/') }
function Assert-Id([object]$Value, [string]$Name) {
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text) -or $text -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{1,127}$' -or $text -match '(?i)(pending|unknown|tbd|<)') { throw "$Name 缺失或无效。" }
    return $text.Trim()
}
function Read-Json([string]$Path, [string]$Name) {
    try { return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json } catch { throw "$Name JSON 解析失败。" }
}
function Get-Digest([object]$Image, [string]$Name) {
    $value = if ($Image -is [string]) { [string]$Image } else { [string]$Image.repoDigest }
    if ($value -notmatch '^.+@sha256:[a-fA-F0-9]{64}$' -or $value -match '(?i)(pending|unknown|tbd|<|example\.invalid)') { throw "$Name 必须是不可变 repo@sha256 digest。" }
    return $value.Trim()
}
function Write-Atomic([string]$Path, [string]$Content) {
    if (Test-Path -LiteralPath $Path) { throw "输出已存在（证据不可覆盖）：$(Relative-RepoPath $Path)" }
    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    $temporary = Join-Path (Split-Path -Parent $Path) ('.' + [IO.Path]::GetFileName($Path) + '.' + $PID + '.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($temporary, $Content, $utf8)
        [IO.File]::Move($temporary, $Path)
    } finally { if (Test-Path -LiteralPath $temporary) { try { [IO.File]::Delete($temporary) } catch { } } }
}

if ($WhatIf -and $Execute) { throw '-WhatIf 与 -Execute 不能同时使用。' }
if ($Environment -ne 'staging') { throw 'canary-rollback-drill 只允许 staging 隔离演练。' }
if (-not $FreshIsolatedProject) { throw '必须显式提供 -FreshIsolatedProject。' }
if ($ProjectName -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{2,95}$' -or $ProjectName -match '(?i)(^|[-_.])(prod|production|default)([-_.]|$)' -or $ProjectName -notmatch '(?i)(canary|staging|candidate|drill|restore)') { throw 'ProjectName 不是可识别的隔离演练项目名。' }
$approval = Assert-Id $ApprovalId 'ApprovalId'
$candidatePath = Resolve-RepoPath $Manifest 'Manifest' -MustExist
$previousPath = Resolve-RepoPath $PreviousManifest 'PreviousManifest' -MustExist
$thresholdPath = Resolve-RepoPath $ThresholdFile 'ThresholdFile' -MustExist
$snapshotPath = Resolve-RepoPath $ConfigSnapshot 'ConfigSnapshot'
$evidencePath = Resolve-RepoPath $Evidence 'Evidence'
$candidate = Read-Json $candidatePath 'Manifest'
$previous = Read-Json $previousPath 'PreviousManifest'
$threshold = Read-Json $thresholdPath 'ThresholdFile'
if ([string]$candidate.schemaVersion -ne 'v1.1' -or [string]$previous.schemaVersion -ne 'v1.1') { throw 'candidate/previous manifest schemaVersion 必须为 v1.1。' }
if ([string]$previous.releaseClass -ne 'GA' -or [string]$previous.status -ne 'GA' -or [string]$previous.environment -ne 'prod') { throw 'PreviousManifest 必须是 GA/prod。' }
if ([string]$candidate.status -notin @('BLOCKED', 'RC', 'DEMO_ONLY')) { throw 'Manifest 必须是 BLOCKED/RC/DEMO_ONLY candidate。' }
$candidateId = Assert-Id $candidate.releaseId 'candidate releaseId'
$previousId = Assert-Id $previous.releaseId 'previous releaseId'
if ($candidateId -eq $previousId) { throw 'candidate 与 previous releaseId 必须不同。' }
$candidateBackend = Get-Digest $candidate.images.backend 'candidate backend'
$candidateFrontend = Get-Digest $candidate.images.frontend 'candidate frontend'
$previousBackend = Get-Digest $previous.images.backend 'previous backend'
$previousFrontend = Get-Digest $previous.images.frontend
if ($candidateBackend -eq $candidateFrontend -or $previousBackend -eq $previousFrontend) { throw 'backend/frontend digest 不能相同。' }
$thresholdVersion = [string]$threshold.thresholdVersion
if ([string]::IsNullOrWhiteSpace($thresholdVersion)) { $thresholdVersion = [string]$threshold.dictionaryVersion }
if ([string]::IsNullOrWhiteSpace($thresholdVersion) -or $thresholdVersion -match '(?i)(pending|draft|unknown|tbd|<)' -or @($threshold.metrics).Count -eq 0) { throw 'ThresholdFile 必须是已审核且包含 metrics 的快照。' }

foreach ($path in @($snapshotPath, $evidencePath)) { if (Test-Path -LiteralPath $path) { throw "输出已存在（no-clobber）：$(Relative-RepoPath $path)" } }
$now = [DateTime]::UtcNow.ToString('o')
$status = if ($WhatIf) { 'dry-run' } else { 'blocked' }
$stateReason = if ($WhatIf) { 'dry-run：只验证双 manifest/threshold/隔离项目，未调用 promote/rollback adapter' } else { '未配置经审计的平台 adapter；执行请求被拒绝，未修改任何状态' }
$snapshot = [ordered]@{
    schemaVersion = 'v1.1'; producer = 'canary-rollback-drill'; generatedAt = $now
    environment = 'staging'; projectName = $ProjectName; approvalIdPresent = $true
    canaryPercent = $CanaryPercent; candidateReleaseId = $candidateId; previousReleaseId = $previousId
    candidate = [ordered]@{ backend = $candidateBackend; frontend = $candidateFrontend; manifest = Relative-RepoPath $candidatePath }
    previous = [ordered]@{ backend = $previousBackend; frontend = $previousFrontend; manifest = Relative-RepoPath $previousPath }
    thresholdVersion = $thresholdVersion; network = 'external-adapter-required'; volume = 'external-adapter-required'
    mutationPerformed = $false; status = $status; passed = $false; reason = $stateReason; redacted = $true
}
$drill = [ordered]@{
    schemaVersion = 'v1.1'; producer = 'canary-rollback-drill'; drillId = 'drill-' + [Guid]::NewGuid().ToString('N')
    generatedAt = $now; environment = 'staging'; projectName = $ProjectName; approvalIdPresent = $true
    candidateReleaseId = $candidateId; previousReleaseId = $previousId; canaryPercent = $CanaryPercent
    thresholdVersion = $thresholdVersion; trigger = [ordered]@{ metric = 'manual-threshold-breach'; value = 'redacted'; threshold = 'loaded-from-snapshot' }
    oldDigest = [ordered]@{ backend = $previousBackend; frontend = $previousFrontend }
    newDigest = [ordered]@{ backend = $candidateBackend; frontend = $candidateFrontend }
    readiness = [ordered]@{ status = 'not-executed'; passed = $false }
    businessProbes = [ordered]@{ status = 'not-executed'; passed = $false }
    exitCode = 1; mutationPerformed = $false; status = $status; passed = $false; reason = $stateReason; redacted = $true
}
Write-Atomic $snapshot (($snapshot | ConvertTo-Json -Depth 20) + [Environment]::NewLine)
Write-Atomic $evidencePath (($drill | ConvertTo-Json -Depth 20 -Compress) + [Environment]::NewLine)
$drill | ConvertTo-Json -Depth 20
if (-not $WhatIf) { exit 1 }
exit 0
