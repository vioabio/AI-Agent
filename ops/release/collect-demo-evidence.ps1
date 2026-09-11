<#
.SYNOPSIS
  Collect redacted, release-scoped DEMO_ONLY evidence from staging.

.DESCRIPTION
  The demo collector is a read-only producer.  It never promotes a release,
  mutates traffic, copies prod/release evidence, or persists response bodies.
  -WhatIf creates an explicit pending bundle so the later demo gate can report
  the missing live evidence instead of treating a dry-run as a pass.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [ValidateSet('staging')] [string]$Environment,
    [Parameter(Mandatory = $true)] [string]$BaseUrl,
    [string]$ProxyBaseUrl = '',
    [Parameter(Mandatory = $true)] [string]$ReleaseId,
    [Parameter(Mandatory = $true)] [string]$EvidenceRoot,
    [Parameter(Mandatory = $true)] [string]$ActiveReleaseOutput,
    [string]$ObservabilityDir = '',
    [string]$PrometheusBaseUrl = '',
    [string]$AlertmanagerBaseUrl = '',
    [string]$GrafanaBaseUrl = '',
    [string]$EnvironmentContract = 'artifacts/v1.1/baseline/environment-contract.json',
    [string]$ComposeConfigInput = '',
    [switch]$NoCopyFromProd,
    [switch]$NoCopyFromRelease,
    [switch]$WhatIf,
    [switch]$NoClobber
)

$ErrorActionPreference = 'Stop'
$script:RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$script:Release = $null

function Resolve-RepoPath {
    param([Parameter(Mandatory = $true)][string]$Value,
          [Parameter(Mandatory = $true)][string]$Name,
          [switch]$MustExist,
          [switch]$Directory)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.IndexOf([char]0) -ge 0) { throw "$Name 不能为空或包含无效字符。" }
    $candidate = if ([IO.Path]::IsPathRooted($Value)) { [IO.Path]::GetFullPath($Value) } else { [IO.Path]::GetFullPath((Join-Path $script:RepoRoot $Value)) }
    $prefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "$Name 必须位于仓库内。" }
    if ($Directory) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { throw "$Name 不能是文件。" }
        if ($MustExist -and -not (Test-Path -LiteralPath $candidate -PathType Container)) { throw "$Name 目录不存在。" }
        if ((Test-Path -LiteralPath $candidate -PathType Container) -and (Get-Item -LiteralPath $candidate -Force).LinkType) { throw "$Name 不允许通过符号链接。" }
        if (-not (Test-Path -LiteralPath $candidate)) { [IO.Directory]::CreateDirectory($candidate) | Out-Null }
    } else {
        if (Test-Path -LiteralPath $candidate -PathType Container) { throw "$Name 不能是目录。" }
        if ($MustExist -and -not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw "$Name 文件不存在。" }
        if ($MustExist -and (Get-Item -LiteralPath $candidate -Force).LinkType) { throw "$Name 不允许通过符号链接。" }
        if (-not $MustExist) {
            $parent = Split-Path -Parent $candidate
            while ($parent -and -not (Test-Path -LiteralPath $parent -PathType Container)) {
                $next = Split-Path -Parent $parent
                if ($next -eq $parent) { break }
                $parent = $next
            }
            if ($parent -and (Get-Item -LiteralPath $parent -Force).LinkType) { throw "$Name 的父目录不允许通过符号链接写入。" }
        }
    }
    return $candidate
}

function Relative-RepoPath([string]$Value) {
    $prefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    return $Value.Substring($prefix.Length).Replace('\', '/')
}

function Assert-Id([string]$Value, [string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{1,127}$' -or $Value -match '(?i)(pending|unknown|tbd|<)') { throw "$Name 缺失或仍是占位符。" }
    return $Value.Trim()
}

function Safe([object]$Value) {
    if ($null -eq $Value) { return $null }
    $text = ([string]$Value).Replace([char]13, ' ').Replace([char]10, ' ')
    return $text.Substring(0, [Math]::Min(240, $text.Length))
}

function Parse-Url([string]$Value, [string]$Name, [switch]$Required) {
    if ([string]::IsNullOrWhiteSpace($Value)) {
        if ($Required) { throw "$Name 不能为空。" }
        return $null
    }
    $uri = $null
    if (-not [Uri]::TryCreate($Value.TrimEnd('/'), [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -notin @('http', 'https')) { throw "$Name 必须是 http/https URL。" }
    if (-not [string]::IsNullOrWhiteSpace($uri.UserInfo) -or $uri.Query -or $uri.Fragment) { throw "$Name 不得包含凭据、query 或 fragment。" }
    return $uri
}

function Write-Atomic([string]$Path, [string]$Content) {
    if (Test-Path -LiteralPath $Path) { throw "输出已存在（证据不可覆盖）: $(Relative-RepoPath $Path)" }
    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    $temporary = Join-Path (Split-Path -Parent $Path) ('.' + [IO.Path]::GetFileName($Path) + '.' + $PID + '.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::WriteAllText($temporary, $Content, (New-Object System.Text.UTF8Encoding($false)))
        [IO.File]::Move($temporary, $Path)
    } finally {
        if (Test-Path -LiteralPath $temporary) { try { [IO.File]::Delete($temporary) } catch {} }
    }
}

function Write-Json([string]$Path, [object]$Value) { Write-Atomic $Path (($Value | ConvertTo-Json -Depth 20) + [Environment]::NewLine) }
function Write-Jsonl([string]$Path, [object[]]$Rows) { Write-Atomic $Path (((@($Rows) | ForEach-Object { $_ | ConvertTo-Json -Depth 20 -Compress }) -join [Environment]::NewLine) + [Environment]::NewLine) }

function Get-Commit {
    try {
        $value = & git -C $script:RepoRoot rev-parse HEAD 2>$null
        if ($LASTEXITCODE -eq 0 -and $value) { return ([string]$value).Trim() }
    } catch {}
    return 'unknown'
}

function Pending-Row([string]$ProbeId, [string]$Reason) {
    return [ordered]@{
        schemaVersion = 'v1.1'; probeId = $ProbeId; method = $null; endpoint = $null; status = $null
        expectedStatus = @(); latencyMs = $null; releaseId = $script:Release; environment = 'staging'
        passed = $false; reason = $Reason; redacted = $true
    }
}

function Invoke-GetProbe([string]$ProbeId, [Uri]$Uri, [hashtable]$Headers, [int[]]$Expected = @(200, 201, 202, 204)) {
    $watch = [Diagnostics.Stopwatch]::StartNew(); $status = $null; $passed = $false; $reason = $null
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri $Uri.AbsoluteUri -Headers $Headers -ErrorAction Stop
        $status = [int]$response.StatusCode; $passed = $Expected -contains $status
        if (-not $passed) { $reason = 'HTTP status outside expected set' }
    } catch {
        try { $status = [int]$_.Exception.Response.StatusCode.value__ } catch {}
        $reason = 'endpoint unavailable or unauthorized (response redacted)'
    }
    $watch.Stop()
    return [ordered]@{
        schemaVersion = 'v1.1'; probeId = $ProbeId; method = 'GET'; endpoint = "$($Uri.Scheme)://$($Uri.Host)$(if($Uri.IsDefaultPort){''}else{':'+$Uri.Port})$($Uri.AbsolutePath)"
        status = $status; expectedStatus = @($Expected); latencyMs = $watch.ElapsedMilliseconds
        releaseId = $script:Release; environment = 'staging'; passed = $passed; reason = $reason; redacted = $true
    }
}

$script:Release = Assert-Id $ReleaseId 'ReleaseId'
if (-not $NoCopyFromProd -or -not $NoCopyFromRelease) { throw '必须显式提供 -NoCopyFromProd 与 -NoCopyFromRelease。' }
$baseUri = Parse-Url $BaseUrl 'BaseUrl' -Required
$proxyUri = Parse-Url $ProxyBaseUrl 'ProxyBaseUrl'
$promUri = Parse-Url $PrometheusBaseUrl 'PrometheusBaseUrl'
$alertUri = Parse-Url $AlertmanagerBaseUrl 'AlertmanagerBaseUrl'
$grafanaUri = Parse-Url $GrafanaBaseUrl 'GrafanaBaseUrl'

$rootPath = Resolve-RepoPath $EvidenceRoot 'EvidenceRoot' -Directory
$rootRelative = Relative-RepoPath $rootPath
if ($rootRelative -notmatch '^artifacts/v1\.1/staging/[A-Za-z0-9][A-Za-z0-9._:-]{1,127}$' -or (Split-Path -Leaf $rootPath) -ne $script:Release) { throw 'EvidenceRoot 必须是 artifacts/v1.1/staging/<ReleaseId>。' }
$activePath = Resolve-RepoPath $ActiveReleaseOutput 'ActiveReleaseOutput'
$rootPrefix = $rootPath.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
if (-not $activePath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'ActiveReleaseOutput 必须位于 EvidenceRoot 内。' }
if ([string]::IsNullOrWhiteSpace($ObservabilityDir)) { $obsPath = Join-Path $rootPath 'observability' }
else { $obsPath = Resolve-RepoPath $ObservabilityDir 'ObservabilityDir' -Directory }
if (-not $obsPath.StartsWith($rootPath.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'ObservabilityDir 必须位于 EvidenceRoot 内。' }
[IO.Directory]::CreateDirectory($obsPath) | Out-Null

$contractPath = Resolve-RepoPath $EnvironmentContract 'EnvironmentContract' -MustExist
try { $contract = Get-Content -LiteralPath $contractPath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { throw 'EnvironmentContract JSON 解析失败。' }
if ([string]$contract.schemaVersion -ne 'v1.1') { throw 'EnvironmentContract 必须是 v1.1。' }
if ($ComposeConfigInput) {
    $composePath = Resolve-RepoPath $ComposeConfigInput 'ComposeConfigInput' -MustExist
    $composeRelative = Relative-RepoPath $composePath
    if ($composeRelative -match '^(?i)artifacts/v1\.1/(prod|release|staging)/') { throw 'ComposeConfigInput 不得来自 evidence 目录，避免复制证据。' }
}

$outputs = @(
    $activePath, (Join-Path $rootPath 'readiness.json'), (Join-Path $rootPath 'business-probes.jsonl'),
    (Join-Path $rootPath 'promote-config-snapshot.json'), (Join-Path $rootPath 'rollback-drill.jsonl'),
    (Join-Path $rootPath 'release-compose-config-effective.txt'), (Join-Path $rootPath 'environment-contract-validation.json'),
    (Join-Path $rootPath 'collector-report.json'), (Join-Path $obsPath 'prometheus-targets.json'),
    (Join-Path $obsPath 'prometheus-rules.json'), (Join-Path $obsPath 'alertmanager-events.json'),
    (Join-Path $obsPath 'grafana-health.json'), (Join-Path $obsPath 'observability-probes.json'),
    (Join-Path $obsPath 'targets-alerts-trace.md')
)
foreach ($output in $outputs) { if (Test-Path -LiteralPath $output) { throw "输出已存在（no-clobber）：$(Relative-RepoPath $output)" } }

$contractNames = @($contract.variables | ForEach-Object { [string]$_.name })
$requiredContractNames = @('VIO_BASE_URL', 'VIO_PROXY_BASE_URL', 'VIO_PROMETHEUS_URL', 'VIO_ALERTMANAGER_URL', 'VIO_GRAFANA_URL', 'VIO_DEV_COMPOSE_PROJECT')
$missingContract = @($requiredContractNames | Where-Object { $_ -notin $contractNames })
$contractReport = [ordered]@{
    schemaVersion = 'v1.1'; producer = 'collect-demo-evidence'; generatedAt = [DateTime]::UtcNow.ToString('o')
    environment = 'staging'; releaseId = $script:Release; contract = Relative-RepoPath $contractPath
    contractSha256 = (Get-FileHash -LiteralPath $contractPath -Algorithm SHA256).Hash.ToLowerInvariant()
    requiredVariables = $requiredContractNames; missingVariables = $missingContract
    passed = ($missingContract.Count -eq 0); status = if ($missingContract.Count -eq 0) { 'ready-for-review' } else { 'blocked' }; redacted = $true
}

$headers = @{ Accept = 'application/json' }
$token = [Environment]::GetEnvironmentVariable('VIO_READINESS_TOKEN')
if ([string]::IsNullOrWhiteSpace($token)) { $token = [Environment]::GetEnvironmentVariable('VIO_API_KEY') }
if (-not [string]::IsNullOrWhiteSpace($token)) { $headers['X-API-Key'] = $token }
$readiness = $null; $businessRows = @(); $obsRows = @()
if ($WhatIf) {
    $readiness = Pending-Row 'readiness-details' 'WhatIf：未访问 staging readiness endpoint'
    foreach ($name in @('spa-root', 'versioned-facts', 'expired-cursor-410')) { $businessRows += Pending-Row $name 'WhatIf：未访问 staging 业务 endpoint' }
    foreach ($name in @('prometheus-targets', 'prometheus-rules', 'alertmanager-events', 'grafana-health')) { $obsRows += Pending-Row $name 'WhatIf：未访问监控 endpoint' }
} else {
    $base = $baseUri.AbsoluteUri.TrimEnd('/')
    $readiness = Invoke-GetProbe 'readiness-details' ([Uri]($base + '/api/ready/details')) $headers
    $businessRows += Invoke-GetProbe 'spa-root' ([Uri]($base + '/')) $headers
    $businessRows += Invoke-GetProbe 'versioned-facts' ([Uri]($base + '/api/pokemon/facts?limit=1')) $headers
    $businessRows += Invoke-GetProbe 'expired-cursor-410' ([Uri]($base + '/api/chat/runs/invalid/events?afterEventId=-1')) $headers @(410)
    if ($promUri) {
        $obsRows += Invoke-GetProbe 'prometheus-targets' ([Uri]($promUri.AbsoluteUri.TrimEnd('/') + '/api/v1/targets')) $headers
        $obsRows += Invoke-GetProbe 'prometheus-rules' ([Uri]($promUri.AbsoluteUri.TrimEnd('/') + '/api/v1/rules')) $headers
    } else { $obsRows += Pending-Row 'prometheus-targets' '未提供 PrometheusBaseUrl'; $obsRows += Pending-Row 'prometheus-rules' '未提供 PrometheusBaseUrl' }
    if ($alertUri) { $obsRows += Invoke-GetProbe 'alertmanager-events' ([Uri]($alertUri.AbsoluteUri.TrimEnd('/') + '/api/v2/alerts')) $headers } else { $obsRows += Pending-Row 'alertmanager-events' '未提供 AlertmanagerBaseUrl' }
    if ($grafanaUri) { $obsRows += Invoke-GetProbe 'grafana-health' ([Uri]($grafanaUri.AbsoluteUri.TrimEnd('/') + '/api/health')) $headers } else { $obsRows += Pending-Row 'grafana-health' '未提供 GrafanaBaseUrl' }
}

$active = [ordered]@{
    schemaVersion = 'v1.1'; producer = 'collect-demo-evidence'; generatedAt = [DateTime]::UtcNow.ToString('o')
    environment = 'staging'; releaseId = $script:Release; releaseClass = 'DEMO_ONLY'; status = 'environment-pending'
    imageDigest = $null; source = 'explicit-demo-release-id'; mutationPerformed = $false; redacted = $true
}
$promote = [ordered]@{ schemaVersion = 'v1.1'; producer = 'collect-demo-evidence'; operation = 'promote-config-snapshot'; environment = 'staging'; releaseId = $script:Release; status = 'environment-pending'; passed = $false; mutationPerformed = $false; reason = '需要独立 staging 发布适配器；collector 不执行 promote'; redacted = $true }
$rollback = [ordered]@{ schemaVersion = 'v1.1'; producer = 'collect-demo-evidence'; operation = 'rollback-drill'; environment = 'staging'; releaseId = $script:Release; status = 'environment-pending'; passed = $false; mutationPerformed = $false; reason = '需要批准的隔离 rollback drill；collector 不执行回滚'; redacted = $true }
$compose = if ($ComposeConfigInput) { @('schemaVersion=v1.1', 'producer=collect-demo-evidence', 'environment=staging', "releaseId=$($script:Release)", "source=$(Relative-RepoPath (Resolve-RepoPath $ComposeConfigInput 'ComposeConfigInput' -MustExist))", 'status=validated-redacted-input', 'bodyPersisted=false', 'redacted=true') } else { @('schemaVersion=v1.1', 'producer=collect-demo-evidence', 'environment=staging', "releaseId=$($script:Release)", 'status=environment-pending', 'source=external-staging-compose-adapter-required', 'reason=未提供独立 staging Compose/config 脱敏快照', 'redacted=true') }

Write-Json $activePath $active
Write-Json (Join-Path $rootPath 'environment-contract-validation.json') $contractReport
Write-Json (Join-Path $rootPath 'readiness.json') $readiness
Write-Jsonl (Join-Path $rootPath 'business-probes.jsonl') $businessRows
Write-Json (Join-Path $rootPath 'promote-config-snapshot.json') $promote
Write-Jsonl (Join-Path $rootPath 'rollback-drill.jsonl') @($rollback)
Write-Atomic (Join-Path $rootPath 'release-compose-config-effective.txt') (($compose -join [Environment]::NewLine) + [Environment]::NewLine)
foreach ($name in @('prometheus-targets.json', 'prometheus-rules.json', 'alertmanager-events.json', 'grafana-health.json')) {
    $probeName = switch ($name) {
        'prometheus-targets.json' { 'prometheus-targets'; break }
        'prometheus-rules.json' { 'prometheus-rules'; break }
        'alertmanager-events.json' { 'alertmanager-events'; break }
        default { 'grafana-health' }
    }
    $subset = @($obsRows | Where-Object { $_.probeId -eq $probeName })
    Write-Json (Join-Path $obsPath $name) ([ordered]@{schemaVersion = 'v1.1'; producer = 'collect-demo-evidence'; environment = 'staging'; releaseId = $script:Release; probes = $subset; redacted = $true})
}
Write-Json (Join-Path $obsPath 'observability-probes.json') ([ordered]@{schemaVersion = 'v1.1'; producer = 'collect-demo-evidence'; environment = 'staging'; releaseId = $script:Release; probes = $obsRows; passed = (@($obsRows | Where-Object { $_.passed -ne $true }).Count -eq 0 -and $obsRows.Count -gt 0); redacted = $true})
Write-Atomic (Join-Path $obsPath 'targets-alerts-trace.md') ("# DEMO_ONLY observability`r`n`r`n- schemaVersion: v1.1`r`n- environment: staging`r`n- releaseId: $($script:Release)`r`n- status: environment-pending`r`n- reason: 仅保存脱敏 probe 引用；未复制生产或 release evidence`r`n")

$probeRows = @($readiness) + @($businessRows) + @($obsRows)
$allPassed = ($probeRows.Count -gt 0 -and @($probeRows | Where-Object { $_.passed -ne $true }).Count -eq 0)
$passed = (-not $WhatIf -and $contractReport.passed -and $allPassed -and $promote.passed -and $rollback.passed)
$report = [ordered]@{
    schemaVersion = 'v1.1'; producer = 'collect-demo-evidence'; generatedAt = [DateTime]::UtcNow.ToString('o'); gitCommit = Get-Commit
    environment = 'staging'; releaseId = $script:Release; releaseClass = 'DEMO_ONLY'; evidenceRoot = $rootRelative
    noCopyFromProd = [bool]$NoCopyFromProd; noCopyFromRelease = [bool]$NoCopyFromRelease
    readinessPassed = [bool]$readiness.passed; businessProbesPassed = ($businessRows.Count -gt 0 -and @($businessRows | Where-Object { $_.passed -ne $true }).Count -eq 0)
    observabilityPassed = ($obsRows.Count -gt 0 -and @($obsRows | Where-Object { $_.passed -ne $true }).Count -eq 0)
    configSnapshotPassed = [bool]$promote.passed; rollbackDrillPassed = [bool]$rollback.passed
    status = if ($passed) { 'passed' } elseif ($WhatIf) { 'environment-pending' } else { 'blocked' }
    passed = $passed; exitCode = if ($passed -or $WhatIf) { 0 } else { 1 }
    reason = if ($WhatIf) { 'WhatIf：只生成 pending 证据，未访问 staging endpoint' } elseif (-not $passed) { '现场证据不完整或依赖未通过；保持 blocked' } else { 'all demo evidence passed' }
    outputFiles = @($outputs | ForEach-Object { Relative-RepoPath $_ }); redacted = $true
}
Write-Json (Join-Path $rootPath 'collector-report.json') $report
$report | ConvertTo-Json -Depth 20
if (-not ($passed -or $WhatIf)) { exit 1 }
exit 0
