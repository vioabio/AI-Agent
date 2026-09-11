<#
.SYNOPSIS
  Collect redacted, release-scoped production evidence.

.DESCRIPTION
  This producer only performs read-only HTTP probes and local validation. It
  never copies staging/release artifacts, mutates production, executes Docker
  or writes an active pointer. Missing live evidence is written as an explicit
  pending record and keeps the report blocked. Every output is written once
  through a sibling temporary file; existing evidence is never overwritten.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BaseUrl,
    [string]$ProxyBaseUrl = '',
    [Parameter(Mandatory = $true)]
    [string]$ReleaseId,
    [Parameter(Mandatory = $true)]
    [string]$ExpectedImageDigest,
    [Parameter(Mandatory = $true)]
    [string]$EvidenceRoot,
    [string]$ObservabilityDir = '',
    [string]$PrometheusBaseUrl = '',
    [string]$AlertmanagerBaseUrl = '',
    [string]$GrafanaBaseUrl = '',
    [string]$Environment = 'prod',
    [string]$EnvironmentContract = 'artifacts/v1.1/baseline/environment-contract.json',
    [string]$ActiveRelease = '',
    [string]$ComposeConfigInput = '',
    [string]$Fixture = 'artifacts/v1.1/baseline/fixture-manifest.json',
    [switch]$NoCopyFromStaging,
    [switch]$NoCopyFromRelease,
    [switch]$WhatIf,
    [switch]$NoClobber
)

$ErrorActionPreference = 'Stop'
$script:RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

function Resolve-RepoPath {
    param([Parameter(Mandatory = $true)] [string]$Value, [Parameter(Mandatory = $true)] [string]$Name, [switch]$MustExist)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.IndexOf([char]0) -ge 0) { throw "$Name 不能为空或包含无效字符。" }
    $candidate = if ([IO.Path]::IsPathRooted($Value)) { [IO.Path]::GetFullPath($Value) } else { [IO.Path]::GetFullPath((Join-Path $script:RepoRoot $Value)) }
    $prefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "$Name 必须位于仓库内。" }
    if (Test-Path -LiteralPath $candidate -PathType Container) { throw "$Name 不能是目录。" }
    if ($MustExist -and -not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw "$Name 不存在或不是普通文件。" }
    if ($MustExist -and (Get-Item -LiteralPath $candidate -Force).LinkType) { throw "$Name 不允许通过符号链接读取。" }
    if (-not $MustExist) {
        $parent = Split-Path -Parent $candidate
        while ($parent -and -not (Test-Path -LiteralPath $parent -PathType Container)) {
            $next = Split-Path -Parent $parent
            if ($next -eq $parent) { break }
            $parent = $next
        }
        if ($parent -and (Get-Item -LiteralPath $parent -Force).LinkType) { throw "$Name 的父目录不允许通过符号链接写入。" }
    }
    return $candidate
}

function Ensure-RepoDirectory {
    param([Parameter(Mandatory = $true)] [string]$Value, [Parameter(Mandatory = $true)] [string]$Name)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.IndexOf([char]0) -ge 0) { throw "$Name 不能为空或包含无效字符。" }
    $candidate = if ([IO.Path]::IsPathRooted($Value)) { [IO.Path]::GetFullPath($Value) } else { [IO.Path]::GetFullPath((Join-Path $script:RepoRoot $Value)) }
    $prefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "$Name 必须位于仓库内。" }
    $relative = $candidate.Substring($prefix.Length).Replace('\', '/')
    if ($relative -notmatch '^artifacts/v1\.1/prod/[A-Za-z0-9][A-Za-z0-9._:-]{1,127}$') { throw "$Name 必须是 artifacts/v1.1/prod/<releaseId> 目录。" }
    if ($relative.Split('/')[-1] -ne $script:ReleaseId) { throw "$Name 末级目录必须与 ReleaseId 一致。" }
    if ((Test-Path -LiteralPath $candidate -PathType Container) -and (Get-Item -LiteralPath $candidate -Force).LinkType) { throw "$Name 不允许通过符号链接。" }
    [IO.Directory]::CreateDirectory($candidate) | Out-Null
    return $candidate
}

function Relative-RepoPath([string]$Path) {
    $prefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    return $Path.Substring($prefix.Length).Replace('\', '/')
}

function Safe([object]$Value) {
    if ($null -eq $Value) { return $null }
    $text = ([string]$Value).Replace([char]13, ' ').Replace([char]10, ' ')
    return $text.Substring(0, [Math]::Min(240, $text.Length))
}

function Assert-Id([object]$Value, [string]$Name) {
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text) -or $text -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{1,127}$' -or $text -match '(?i)(pending|unknown|tbd|not[-_]?published|<)') { throw "$Name 缺失或无效。" }
    return $text.Trim()
}

function Assert-Digest([string]$Value) {
    $text = $Value.Trim()
    if ($text -notmatch '^.+@sha256:[a-fA-F0-9]{64}$' -or $text -match '(?i)(pending|unknown|tbd|example\.invalid|<)') { throw 'ExpectedImageDigest 必须是完整 repo@sha256 digest。' }
    return $text
}

function Parse-Url([string]$Value, [string]$Name, [switch]$Required) {
    if ([string]::IsNullOrWhiteSpace($Value)) { if ($Required) { throw "$Name 不能为空。" }; return $null }
    $uri = $null
    if (-not [Uri]::TryCreate($Value.TrimEnd('/'), [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -notin @('http', 'https')) { throw "$Name 必须是 http/https URL。" }
    if (-not [string]::IsNullOrWhiteSpace($uri.UserInfo) -or $uri.Query -or $uri.Fragment) { throw "$Name 不得包含凭据、query 或 fragment。" }
    if ($uri.Host -match '^(localhost|127\.0\.0\.1|::1)$' -or $uri.Host -match '(?i)(staging|test)') { throw "$Name 指向 localhost/staging/test，生产证据拒绝。" }
    return $uri
}

function Read-Json([string]$Path, [string]$Name) {
    try { return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json } catch { throw "$Name JSON 解析失败。" }
}

function Assert-NoSecretText([string]$Text, [string]$Name) {
    foreach ($line in ($Text -split '\r?\n')) {
        if ($line -match '(?i)^\s*["'']?(password|passwd|api[_-]?key|access[_-]?key|client[_-]?secret|private[_-]?key|token)["'']?\s*[:=]\s*["'']?([^"'',;\s}]+)') {
            $value = $Matches[2]
            if ($value -notmatch '(?i)^(redacted|masked|removed|\*{3,}|\$\{[^}]+\})$') { throw "$Name 含未脱敏 secret。" }
        }
        if ($line -match '(?:sk-[A-Za-z0-9]{12,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9_]{20,})') { throw "$Name 含 token-shaped value。" }
    }
}

function Write-Atomic([string]$Path, [string]$Content) {
    if (Test-Path -LiteralPath $Path) { throw "输出已存在（证据不可覆盖）: $(Relative-RepoPath $Path)" }
    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    $temporary = Join-Path (Split-Path -Parent $Path) ('.' + [IO.Path]::GetFileName($Path) + '.' + $PID + '.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($temporary, $Content, $utf8)
        [IO.File]::Move($temporary, $Path)
    } finally { if (Test-Path -LiteralPath $temporary) { try { [IO.File]::Delete($temporary) } catch { } } }
}

function Write-Json([string]$Path, [object]$Body) { Write-Atomic $Path (($Body | ConvertTo-Json -Depth 20) + [Environment]::NewLine) }
function Write-Jsonl([string]$Path, [object[]]$Rows) { Write-Atomic $Path (((@($Rows) | ForEach-Object { $_ | ConvertTo-Json -Depth 20 -Compress }) -join [Environment]::NewLine) + [Environment]::NewLine) }

function Get-Commit {
    try {
        $value = & git -C $script:RepoRoot rev-parse HEAD 2>$null
        if ($LASTEXITCODE -eq 0 -and $value) { return ([string]$value).Trim() }
    } catch { }
    return 'unknown'
}

function Invoke-ReadProbe([string]$ProbeId, [Uri]$Uri, [hashtable]$Headers, [int[]]$ExpectedStatus = @(200, 201, 202, 204)) {
    $started = [DateTime]::UtcNow
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $status = $null; $passed = $false; $reason = $null; $schema = $null
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri $Uri.AbsoluteUri -Headers $Headers -ErrorAction Stop
        $status = [int]$response.StatusCode
        $passed = $ExpectedStatus -contains $status
        if ($ProbeId -eq 'readiness-details') {
            try {
                $payload = $response.Content | ConvertFrom-Json
                $data = if ($payload.data) { $payload.data } else { $payload }
                $schema = [ordered]@{ schemaVersion = [string]$data.schemaVersion; status = [string]$data.status; lifecycleReady = [bool]$data.lifecycleReady; dependenciesHealthy = [bool]$data.dependenciesHealthy; releasePointerReady = [bool]$data.releasePointerReady }
                $passed = $passed -and [string]$data.status -eq 'READY' -and [string]$data.schemaVersion -eq 'v1.1'
            } catch { $passed = $false; $reason = 'readiness response schema invalid (body not persisted)' }
        }
        if (-not $passed -and -not $reason) { $reason = 'HTTP status outside expected set' }
    } catch {
        try { $status = [int]$_.Exception.Response.StatusCode.value__ } catch { }
        $reason = 'endpoint unavailable or unauthorized (response redacted)'
    } finally { $watch.Stop() }
    return [ordered]@{
        schemaVersion = 'v1.1'; probeId = $ProbeId; method = 'GET'; endpoint = $Uri.AbsoluteUri
        status = $status; expectedStatus = @($ExpectedStatus); latencyMs = $watch.ElapsedMilliseconds
        startedAt = $started.ToString('o'); finishedAt = [DateTime]::UtcNow.ToString('o')
        releaseId = $script:ReleaseId; environment = 'prod'; imageDigest = $script:ExpectedDigest
        passed = $passed; reason = $reason; readiness = $schema; redacted = $true
    }
}

function Pending-Row([string]$ProbeId, [string]$Reason) {
    return [ordered]@{
        schemaVersion = 'v1.1'; probeId = $ProbeId; method = $null; endpoint = $null; status = $null
        expectedStatus = @(); latencyMs = $null; startedAt = [DateTime]::UtcNow.ToString('o')
        finishedAt = $null; releaseId = $script:ReleaseId; environment = 'prod'
        imageDigest = $script:ExpectedDigest; passed = $false; reason = $Reason; redacted = $true
    }
}

$script:ReleaseId = Assert-Id $ReleaseId 'ReleaseId'
$script:ExpectedDigest = Assert-Digest $ExpectedImageDigest
if ($Environment -ne 'prod') { throw 'collect-prod-evidence 只允许 -Environment prod。' }
if (-not $NoCopyFromStaging -or -not $NoCopyFromRelease) { throw '必须显式提供 -NoCopyFromStaging 与 -NoCopyFromRelease。' }
$baseUri = Parse-Url $BaseUrl 'BaseUrl' -Required
$proxyUri = Parse-Url $ProxyBaseUrl 'ProxyBaseUrl'
$promUri = Parse-Url $PrometheusBaseUrl 'PrometheusBaseUrl'
$alertUri = Parse-Url $AlertmanagerBaseUrl 'AlertmanagerBaseUrl'
$grafanaUri = Parse-Url $GrafanaBaseUrl 'GrafanaBaseUrl'

$rootPath = Ensure-RepoDirectory $EvidenceRoot 'EvidenceRoot'
if ([string]::IsNullOrWhiteSpace($ObservabilityDir)) {
    $obsPath = Join-Path $rootPath 'observability'
} else {
    $obsPath = if ([IO.Path]::IsPathRooted($ObservabilityDir)) { [IO.Path]::GetFullPath($ObservabilityDir) } else { [IO.Path]::GetFullPath((Join-Path $script:RepoRoot $ObservabilityDir)) }
    $repoPrefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    if (-not $obsPath.StartsWith($repoPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'ObservabilityDir 必须位于仓库内。' }
    if (Test-Path -LiteralPath $obsPath -PathType Leaf) { throw 'ObservabilityDir 不能是文件。' }
    if ((Test-Path -LiteralPath $obsPath -PathType Container) -and (Get-Item -LiteralPath $obsPath -Force).LinkType) { throw 'ObservabilityDir 不允许通过符号链接。' }
}
if (-not $obsPath.StartsWith($rootPath.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'ObservabilityDir 必须位于 EvidenceRoot 内。' }
[IO.Directory]::CreateDirectory($obsPath) | Out-Null

$contractPath = Resolve-RepoPath $EnvironmentContract 'EnvironmentContract' -MustExist
$fixturePath = Resolve-RepoPath $Fixture 'Fixture' -MustExist
$contract = Read-Json $contractPath 'EnvironmentContract'
if ([string]$contract.schemaVersion -ne 'v1.1' -or $null -eq $contract.variables) { throw 'EnvironmentContract 必须是 v1.1 variables contract。' }
$requiredContractNames = @('VIO_BASE_URL', 'VIO_PROXY_BASE_URL', 'VIO_PROMETHEUS_URL', 'VIO_ALERTMANAGER_URL', 'VIO_GRAFANA_URL', 'VIO_RELEASE_COMPOSE_PROJECT', 'VIO_PUBLISHED_RELEASE_ID')
$contractNames = @($contract.variables | ForEach-Object { [string]$_.name })
$missingContract = @($requiredContractNames | Where-Object { $_ -notin $contractNames })

$activePath = $null; $active = $null
if (-not [string]::IsNullOrWhiteSpace($ActiveRelease)) {
    $activePath = Resolve-RepoPath $ActiveRelease 'ActiveRelease' -MustExist
} else {
    $candidateActive = Join-Path $rootPath 'active-prod-release-resolution.json'
    if (Test-Path -LiteralPath $candidateActive -PathType Leaf) { $activePath = Resolve-RepoPath $candidateActive 'ActiveRelease' -MustExist }
}
if ($activePath) {
    $active = Read-Json $activePath 'ActiveRelease'
    if ([string]$active.environment -ne 'prod' -or [string]$active.releaseId -ne $script:ReleaseId) { throw 'ActiveRelease 必须绑定 prod 与 ReleaseId。' }
    $activeDigest = [string]$active.imageDigest
    if (-not [string]::IsNullOrWhiteSpace($activeDigest) -and $activeDigest -ne $script:ExpectedDigest) { throw 'ActiveRelease imageDigest 与 ExpectedImageDigest 不一致。' }
} elseif (-not $WhatIf) {
    throw '实际采集必须提供 active production release resolution；不会猜测当前 release。'
}

$composeInputPath = $null
if (-not [string]::IsNullOrWhiteSpace($ComposeConfigInput)) {
    $composeInputPath = Resolve-RepoPath $ComposeConfigInput 'ComposeConfigInput' -MustExist
    $composeRelative = Relative-RepoPath $composeInputPath
    if ($composeRelative -match '^(?i)(artifacts/v1\.1/(staging|release|prod)/|artifacts/v1\.0/)') { throw 'ComposeConfigInput 不能来自 staging/release/prod evidence 或旧 release artifact。' }
}

$outputPaths = @(
    (Join-Path $rootPath 'readiness.json'), (Join-Path $rootPath 'business-probes.jsonl'),
    (Join-Path $rootPath 'promote-config-snapshot.json'), (Join-Path $rootPath 'rollback-drill.jsonl'),
    (Join-Path $rootPath 'release-compose-config-effective.txt'), (Join-Path $rootPath 'environment-contract-validation.json'),
    (Join-Path $rootPath 'collector-report.json'), (Join-Path $obsPath 'prometheus-targets.json'),
    (Join-Path $obsPath 'prometheus-rules.json'), (Join-Path $obsPath 'alertmanager-events.json'),
    (Join-Path $obsPath 'grafana-health.json'), (Join-Path $obsPath 'observability-probes.json'),
    (Join-Path $obsPath 'targets-alerts-trace.md')
)
foreach ($outputPath in $outputPaths) { if (Test-Path -LiteralPath $outputPath) { throw "输出已存在（no-clobber）：$(Relative-RepoPath $outputPath)" } }

$contractReport = [ordered]@{
    schemaVersion = 'v1.1'; producer = 'collect-prod-evidence'; generatedAt = [DateTime]::UtcNow.ToString('o')
    environment = 'prod'; releaseId = $script:ReleaseId; contract = Relative-RepoPath $contractPath
    contractSha256 = (Get-FileHash -LiteralPath $contractPath -Algorithm SHA256).Hash.ToLowerInvariant()
    requiredVariables = $requiredContractNames; missingVariables = $missingContract
    environmentPresence = [ordered]@{
        VIO_PUBLISHED_RELEASE_ID = -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('VIO_PUBLISHED_RELEASE_ID'))
        VIO_RELEASE_COMPOSE_PROJECT = -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('VIO_RELEASE_COMPOSE_PROJECT'))
        VIO_API_KEY = -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('VIO_API_KEY'))
        VIO_READINESS_TOKEN = -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('VIO_READINESS_TOKEN'))
    }
    passed = ($missingContract.Count -eq 0 -and $null -ne $active)
    status = if ($missingContract.Count -eq 0 -and $null -ne $active) { 'passed' } else { 'environment-pending' }
    redacted = $true
}

$headers = @{ Accept = 'application/json' }
$token = [Environment]::GetEnvironmentVariable('VIO_READINESS_TOKEN')
if ([string]::IsNullOrWhiteSpace($token)) { $token = [Environment]::GetEnvironmentVariable('VIO_API_KEY') }
if (-not [string]::IsNullOrWhiteSpace($token)) { $headers['X-API-Key'] = $token }
$readiness = if ($WhatIf) { Pending-Row 'readiness-details' 'WhatIf：未访问生产 readiness endpoint' } else { Invoke-ReadProbe 'readiness-details' ([Uri]("$($baseUri.AbsoluteUri.TrimEnd('/'))/api/ready/details")) $headers }

$businessRows = @()
if ($WhatIf) {
    $businessRows += Pending-Row 'spa-root' 'WhatIf：未访问生产 SPA'
    $businessRows += Pending-Row 'versioned-facts' 'WhatIf：未访问版本化 facts API'
    $businessRows += Pending-Row 'run-create-first-event-terminal' '需要隔离生产演练 fixture；本 producer 不创建用户 Run'
    $businessRows += Pending-Row 'sse-reconnect' '需要真实 SSE/Last-Event-ID 现场样本；本 producer 不伪造'
    $businessRows += Pending-Row 'cancel' '需要受控 Run cancel 现场样本；本 producer 不执行写操作'
    $businessRows += Pending-Row 'quota-429' '需要隔离配额 fixture；本 producer 不改变生产配额'
    $businessRows += Pending-Row 'expired-cursor-410' '需要已过期游标现场样本；本 producer 不伪造'
} else {
    $businessRows += Invoke-ReadProbe 'spa-root' ([Uri]("$($baseUri.AbsoluteUri.TrimEnd('/'))/")) $headers
    $businessRows += Invoke-ReadProbe 'versioned-facts' ([Uri]("$($baseUri.AbsoluteUri.TrimEnd('/'))/api/pokemon/facts?dataReleaseId=$([Uri]::EscapeDataString($script:ReleaseId))&limit=1")) $headers
    $businessRows += Pending-Row 'run-create-first-event-terminal' '需要隔离生产演练 fixture；只读 collector 不创建 Run'
    $businessRows += Pending-Row 'sse-reconnect' '需要真实 SSE/Last-Event-ID 现场样本；只读 collector 不伪造'
    $businessRows += Pending-Row 'cancel' '需要受控 Run cancel 现场样本；只读 collector 不执行写操作'
    $businessRows += Pending-Row 'quota-429' '需要隔离配额 fixture；只读 collector 不改变生产配额'
    $businessRows += Invoke-ReadProbe 'expired-cursor-410' ([Uri]("$($baseUri.AbsoluteUri.TrimEnd('/'))/api/chat/runs/invalid/events?afterEventId=-1")) $headers @(410)
}

$obsRows = @()
if ($WhatIf) {
    foreach ($name in @('prometheus-targets', 'prometheus-rules', 'alertmanager-events', 'grafana-health')) { $obsRows += Pending-Row $name 'WhatIf：未访问监控端点' }
} else {
    if ($promUri) {
        $obsRows += Invoke-ReadProbe 'prometheus-targets' ([Uri]("$($promUri.AbsoluteUri.TrimEnd('/'))/api/v1/targets")) $headers
        $obsRows += Invoke-ReadProbe 'prometheus-rules' ([Uri]("$($promUri.AbsoluteUri.TrimEnd('/'))/api/v1/rules")) $headers
    } else { $obsRows += Pending-Row 'prometheus-targets' '未提供 PrometheusBaseUrl'; $obsRows += Pending-Row 'prometheus-rules' '未提供 PrometheusBaseUrl' }
    if ($alertUri) { $obsRows += Invoke-ReadProbe 'alertmanager-events' ([Uri]("$($alertUri.AbsoluteUri.TrimEnd('/'))/api/v2/alerts")) $headers } else { $obsRows += Pending-Row 'alertmanager-events' '未提供 AlertmanagerBaseUrl' }
    if ($grafanaUri) { $obsRows += Invoke-ReadProbe 'grafana-health' ([Uri]("$($grafanaUri.AbsoluteUri.TrimEnd('/'))/api/health")) $headers } else { $obsRows += Pending-Row 'grafana-health' '未提供 GrafanaBaseUrl' }
}

$observabilityPassed = ($obsRows.Count -eq 4 -and @($obsRows | Where-Object { $_.passed -ne $true }).Count -eq 0)
$obsByName = @{}
foreach ($row in $obsRows) { $obsByName[$row.probeId] = $row }
$obsFileMap = [ordered]@{ 'prometheus-targets' = 'prometheus-targets.json'; 'prometheus-rules' = 'prometheus-rules.json'; 'alertmanager-events' = 'alertmanager-events.json'; 'grafana-health' = 'grafana-health.json' }
foreach ($name in $obsFileMap.Keys) {
    Write-Json (Join-Path $obsPath $obsFileMap[$name]) ([ordered]@{ schemaVersion = 'v1.1'; producer = 'collect-prod-evidence'; releaseId = $script:ReleaseId; environment = 'prod'; collectedAt = [DateTime]::UtcNow.ToString('o'); probe = $obsByName[$name]; bodyPersisted = $false; redacted = $true })
}
Write-Json (Join-Path $obsPath 'observability-probes.json') ([ordered]@{ schemaVersion = 'v1.1'; producer = 'collect-prod-evidence'; releaseId = $script:ReleaseId; environment = 'prod'; probes = $obsRows; passed = $observabilityPassed; redacted = $true })
$obsStatus = if ($observabilityPassed) { 'passed' } else { 'environment-pending' }
$obsTable = @('# Production observability evidence index', '', "releaseId: $($script:ReleaseId)", 'environment: prod', "status: $obsStatus", '', 'Raw response bodies are intentionally not persisted; see the per-probe redacted summaries and SHA-256 evidence hash producer.')
Write-Atomic (Join-Path $obsPath 'targets-alerts-trace.md') (($obsTable -join [Environment]::NewLine) + [Environment]::NewLine)

$composeOutput = Join-Path $rootPath 'release-compose-config-effective.txt'
if ($composeInputPath -and -not $WhatIf) {
    $composeText = Get-Content -LiteralPath $composeInputPath -Raw -Encoding UTF8
    Assert-NoSecretText $composeText 'ComposeConfigInput'
    $composeRecord = @('schemaVersion=v1.1', 'producer=collect-prod-evidence', 'environment=prod', "releaseId=$($script:ReleaseId)", "imageDigest=$($script:ExpectedDigest)", "source=$(Relative-RepoPath $composeInputPath)", 'status=validated-redacted-input', 'bodyPersisted=false', 'redacted=true') -join [Environment]::NewLine
} else {
    $composeRecord = @('schemaVersion=v1.1', 'producer=collect-prod-evidence', 'environment=prod', "releaseId=$($script:ReleaseId)", "imageDigest=$($script:ExpectedDigest)", 'status=environment-pending', 'source=external-compose-adapter-required', 'reason=未提供独立的生产 Compose/config 脱敏快照；本 producer 不复制 staging/release 文件', 'redacted=true') -join [Environment]::NewLine
}
Write-Atomic $composeOutput ($composeRecord + [Environment]::NewLine)

$promoteSnapshot = [ordered]@{ schemaVersion = 'v1.1'; producer = 'collect-prod-evidence'; operation = 'promote-config-snapshot'; generatedAt = [DateTime]::UtcNow.ToString('o'); environment = 'prod'; releaseId = $script:ReleaseId; imageDigest = $script:ExpectedDigest; status = 'environment-pending'; passed = $false; mutationPerformed = $false; source = 'external-release-adapter-required'; reason = '本 producer 不执行或复制发布适配器快照'; redacted = $true }
Write-Json (Join-Path $rootPath 'promote-config-snapshot.json') $promoteSnapshot
$rollbackRow = [ordered]@{ schemaVersion = 'v1.1'; producer = 'collect-prod-evidence'; operation = 'rollback-drill'; generatedAt = [DateTime]::UtcNow.ToString('o'); environment = 'prod'; releaseId = $script:ReleaseId; imageDigest = $script:ExpectedDigest; status = 'environment-pending'; passed = $false; mutationPerformed = $false; source = 'approved-rollback-drill-required'; reason = '本 producer 不触发生产回滚；需独立批准的 rollback drill 证据'; redacted = $true }
Write-Jsonl (Join-Path $rootPath 'rollback-drill.jsonl') @($rollbackRow)

Write-Json (Join-Path $rootPath 'environment-contract-validation.json') $contractReport
Write-Json (Join-Path $rootPath 'readiness.json') $readiness
Write-Jsonl (Join-Path $rootPath 'business-probes.jsonl') $businessRows

$allRows = @($readiness) + @($businessRows) + @($obsRows)
$allProbesPassed = ($allRows.Count -gt 0 -and @($allRows | Where-Object { $_.passed -ne $true }).Count -eq 0)
$activePassed = ($null -ne $active)
$passed = (-not $WhatIf -and $contractReport.passed -and $activePassed -and $allProbesPassed -and $observabilityPassed -and $composeInputPath -and $promoteSnapshot.passed -and $rollbackRow.passed)
$report = [ordered]@{
    schemaVersion = 'v1.1'; producer = 'collect-prod-evidence'; generatedAt = [DateTime]::UtcNow.ToString('o')
    gitCommit = Get-Commit; environment = 'prod'; releaseId = $script:ReleaseId; imageDigest = $script:ExpectedDigest
    evidenceRoot = Relative-RepoPath $rootPath; observabilityDir = Relative-RepoPath $obsPath
    activeRelease = if ($activePath) { Relative-RepoPath $activePath } else { $null }
    fixture = Relative-RepoPath $fixturePath; noCopyFromStaging = [bool]$NoCopyFromStaging; noCopyFromRelease = [bool]$NoCopyFromRelease
    readinessPassed = [bool]$readiness.passed; businessProbesPassed = $allProbesPassed; observabilityPassed = $observabilityPassed
    configSnapshotPassed = [bool]$composeInputPath; rollbackDrillPassed = [bool]$rollbackRow.passed
    status = if ($passed) { 'passed' } elseif ($WhatIf) { 'environment-pending' } else { 'blocked' }
    passed = $passed; exitCode = if ($passed -or $WhatIf) { 0 } else { 1 }
    reason = if ($WhatIf) { 'WhatIf：所有输出均为显式 pending/dry-run 记录，未访问生产或监控端点' } elseif (-not $passed) { '现场证据不完整或依赖未通过；保持 blocked' } else { 'all production evidence passed' }
    outputFiles = @($outputPaths | ForEach-Object { Relative-RepoPath $_ }); redacted = $true
}
Write-Json (Join-Path $rootPath 'collector-report.json') $report
$report | ConvertTo-Json -Depth 20
if (-not ($passed -or $WhatIf)) { exit 1 }
exit 0
