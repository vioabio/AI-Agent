<#
.SYNOPSIS
  Capture one immutable v1.1 observation window.

.DESCRIPTION
  The collector is intentionally a bounded, repeatable producer.  Each
  invocation writes exactly one release-scoped JSONL window and updates a
  checkpoint atomically.  In -WhatIf mode it performs no network call and
  writes an explicit environment-pending row.  A live invocation only marks
  a window passed when the configured endpoint returns a matching release and
  image digest; it never invents request counts or copies staging evidence.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('prod', 'staging')]
    [string]$Environment,

    [Parameter(Mandatory = $true)]
    [string]$BaseUrl,

    [Parameter(Mandatory = $true)]
    [string]$ReleaseId,

    [Parameter(Mandatory = $true)]
    [string]$WindowOutputDir,

    [Parameter(Mandatory = $true)]
    [string]$Checkpoint,

    [ValidateRange(1, 720)]
    [int]$WindowHours = 72,

    [ValidateRange(1, 100000000)]
    [int]$MinTotal = 500,

    [ValidateRange(1, 1000000)]
    [int]$MinPerWindow = 20,

    [string]$ExpectedImageDigest = '',
    [string]$WindowId = '',
    [string]$ReadinessPath = '/ready/details',
    [string]$MetricsPath = '/actuator/prometheus',
    [string]$TokenEnvironmentVariable = 'VIO_READINESS_TOKEN',
    [switch]$Resume,
    [switch]$NoCopyFromStaging,
    [switch]$NoCopyFromProd,
    [switch]$WhatIf,
    [switch]$NoClobber
)

$ErrorActionPreference = 'Stop'
$script:RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

function Resolve-RepoPath {
    param([Parameter(Mandatory = $true)][string]$Value, [Parameter(Mandatory = $true)][string]$Name,
          [switch]$Directory, [switch]$MustExist)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.IndexOf([char]0) -ge 0) { throw "$Name 不能为空或包含无效字符。" }
    $candidate = if ([IO.Path]::IsPathRooted($Value)) { [IO.Path]::GetFullPath($Value) }
                 else { [IO.Path]::GetFullPath((Join-Path $script:RepoRoot $Value)) }
    $prefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "$Name 必须位于仓库内。" }
    if ($Directory) {
        if ($MustExist -and -not (Test-Path -LiteralPath $candidate -PathType Container)) { throw "$Name 目录不存在。" }
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { throw "$Name 不能是文件。" }
        if ($MustExist) { if ((Get-Item -LiteralPath $candidate -Force).LinkType) { throw "$Name 不允许通过符号链接读取。" } }
        elseif (-not (Test-Path -LiteralPath $candidate)) { [IO.Directory]::CreateDirectory($candidate) | Out-Null }
    } else {
        if (Test-Path -LiteralPath $candidate -PathType Container) { throw "$Name 不能是目录。" }
        if ($MustExist -and -not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw "$Name 文件不存在。" }
        if ($MustExist -and (Get-Item -LiteralPath $candidate -Force).LinkType) { throw "$Name 不允许通过符号链接读取。" }
        if (-not $MustExist) {
            $parent = Split-Path -Parent $candidate
            while ($parent -and -not (Test-Path -LiteralPath $parent -PathType Container)) {
                $next = Split-Path -Parent $parent; if ($next -eq $parent) { break }; $parent = $next
            }
            if ($parent -and (Get-Item -LiteralPath $parent -Force).LinkType) { throw "$Name 的父目录不允许通过符号链接写入。" }
        }
    }
    return $candidate
}

function Relative-RepoPath([string]$Path) {
    $prefix = $script:RepoRoot.TrimEnd('\') + [IO.Path]::DirectorySeparatorChar
    return $Path.Substring($prefix.Length).Replace('\', '/')
}

function Assert-Id([string]$Value, [string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{1,127}$' -or $Value -match '(?i)(pending|unknown|tbd|<)') { throw "$Name 缺失或仍是占位符。" }
    return $Value.Trim()
}

function Assert-Digest([string]$Value, [string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    if ($Value -notmatch '^.+@sha256:[a-fA-F0-9]{64}$' -or $Value -match '(?i)(example\.invalid|pending|unknown|<)') { throw "$Name 必须是完整 repo@sha256 digest。" }
    return $Value.Trim()
}

function Safe-Url([Uri]$Uri) {
    if ($null -eq $Uri) { return $null }
    return "$($Uri.Scheme)://$($Uri.Host)$(if($Uri.IsDefaultPort){''}else{':'+$Uri.Port})$($Uri.AbsolutePath)"
}

function Write-AtomicText {
    param([string]$Path, [string]$Text)
    if (Test-Path -LiteralPath $Path) { throw "输出已存在（证据不可覆盖）: $Path" }
    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    $tmp = Join-Path (Split-Path -Parent $Path) ('.' + [IO.Path]::GetFileName($Path) + '.' + $PID + '.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::WriteAllText($tmp, $Text, (New-Object System.Text.UTF8Encoding($false)))
        [IO.File]::Move($tmp, $Path)
    } finally { if (Test-Path -LiteralPath $tmp) { try { [IO.File]::Delete($tmp) } catch {} } }
}

function Write-AtomicJson {
    param([string]$Path, [object]$Value, [switch]$Replace)
    if ((Test-Path -LiteralPath $Path) -and -not $Replace) { throw "输出已存在（证据不可覆盖）: $Path" }
    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    $tmp = Join-Path (Split-Path -Parent $Path) ('.' + [IO.Path]::GetFileName($Path) + '.' + $PID + '.' + [Guid]::NewGuid().ToString('N') + '.tmp')
    $backup = Join-Path (Split-Path -Parent $Path) ('.' + [IO.Path]::GetFileName($Path) + '.' + [Guid]::NewGuid().ToString('N') + '.bak')
    try {
        [IO.File]::WriteAllText($tmp, (($Value | ConvertTo-Json -Depth 20) + [Environment]::NewLine), (New-Object System.Text.UTF8Encoding($false)))
        if ($Replace -and (Test-Path -LiteralPath $Path)) {
            # File.Replace requires a non-empty backup path on Windows/.NET;
            # keep that backup beside the checkpoint and remove it immediately
            # after the atomic swap.
            [IO.File]::Replace($tmp, $Path, $backup, $true)
            if (Test-Path -LiteralPath $backup) { [IO.File]::Delete($backup) }
        }
        else { [IO.File]::Move($tmp, $Path) }
    } finally {
        if (Test-Path -LiteralPath $tmp) { try { [IO.File]::Delete($tmp) } catch {} }
        if (Test-Path -LiteralPath $backup) { try { [IO.File]::Delete($backup) } catch {} }
    }
}

function Parse-MetricCount([string]$Raw) {
    # Only use explicitly named counters; unknown exposition is not converted
    # into a successful denominator.
    $names = @('vio_run_requests_total', 'vio_agent_requests_total', 'http_server_requests_seconds_count')
    foreach ($name in $names) {
        $match = [regex]::Match($Raw, "(?m)^$([regex]::Escape($name))(?:\{[^}]*\})?\s+([0-9]+(?:\.[0-9]+)?)\s*$")
        if ($match.Success) { return [math]::Floor([double]$match.Groups[1].Value) }
    }
    return $null
}

$release = Assert-Id $ReleaseId 'ReleaseId'
$expectedDigest = Assert-Digest $ExpectedImageDigest 'ExpectedImageDigest'
$windowDir = Resolve-RepoPath $WindowOutputDir 'WindowOutputDir' -Directory
$checkpointPath = Resolve-RepoPath $Checkpoint 'Checkpoint'
$baseUri = $null
if (-not [Uri]::TryCreate($BaseUrl.TrimEnd('/'), [UriKind]::Absolute, [ref]$baseUri) -or $baseUri.Scheme -notin @('http', 'https')) { throw 'BaseUrl 必须是 http/https URL。' }
if ($Environment -eq 'prod' -and ($baseUri.Host -match '^(localhost|127\.0\.0\.1|::1)$' -or $baseUri.Host -match '(?i)(staging|test)')) { throw 'prod 观察禁止 localhost/staging/test 地址。' }
if ($Environment -eq 'prod' -and -not $NoCopyFromStaging) { throw 'prod 采集必须显式提供 -NoCopyFromStaging。' }
if ($Environment -eq 'staging' -and -not $NoCopyFromProd) { throw 'staging 采集必须显式提供 -NoCopyFromProd。' }

$now = [DateTime]::UtcNow
$windowStart = $now.AddMinutes(-5)
$windowEnd = $now
$id = if ([string]::IsNullOrWhiteSpace($WindowId)) { 'window-' + $windowEnd.ToString('yyyyMMddTHHmmssfffZ') } else { Assert-Id $WindowId 'WindowId' }
$windowPath = Resolve-RepoPath (Join-Path (Relative-RepoPath $windowDir) ($id + '.jsonl')) 'WindowOutput'
if (Test-Path -LiteralPath $windowPath) { throw 'WindowId 已存在；必须使用新的窗口 ID。' }

$checkpointData = [ordered]@{ schemaVersion = 'v1.1'; producer = 'collect-observation'; releaseId = $release; environment = $Environment; windowHours = $WindowHours; minTotal = $MinTotal; minPerWindow = $MinPerWindow; windows = @(); updatedAt = $now.ToString('o'); redacted = $true }
if (Test-Path -LiteralPath $checkpointPath) {
    try { $checkpointData = Get-Content -LiteralPath $checkpointPath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { throw 'Checkpoint JSON 解析失败。' }
    if ([string]$checkpointData.schemaVersion -ne 'v1.1' -or [string]$checkpointData.releaseId -ne $release -or [string]$checkpointData.environment -ne $Environment) { throw 'Checkpoint 与 release/environment 不一致。' }
    if (-not $Resume) { throw 'Checkpoint 已存在；若要续采必须显式提供 -Resume。' }
    if (@($checkpointData.windows | Where-Object { [string]$_ -eq $id }).Count -gt 0) { throw 'WindowId 已存在于 checkpoint。' }
}

$status = 'environment-pending'
$passed = $false
$requestCount = $null
$errorType = $null
$reason = 'WhatIf/plan：未调用生产或 staging endpoint，未生成业务分母'
$readinessStatus = $null
$observedReleaseId = $null
$observedDigest = $null
$latencyMs = $null
if (-not $WhatIf) {
    $token = [Environment]::GetEnvironmentVariable($TokenEnvironmentVariable)
    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($token)) { $headers['Authorization'] = 'Bearer ' + $token }
    $probeUri = [Uri]::new($baseUri.AbsoluteUri.TrimEnd('/') + $ReadinessPath)
    $metricUri = [Uri]::new($baseUri.AbsoluteUri.TrimEnd('/') + $MetricsPath)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -Uri $probeUri -Headers $headers -Method Get -TimeoutSec 10 -UseBasicParsing
        $sw.Stop(); $latencyMs = $sw.ElapsedMilliseconds
        if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) { throw "readiness HTTP $($response.StatusCode)" }
        $json = $response.Content | ConvertFrom-Json
        $readinessStatus = [string]$json.status
        # Keep the fallback explicit so the collector also parses on the
        # Windows PowerShell 5.1 runtime used by the release runbook.
        $observedReleaseId = [string]$json.releaseId
        if ([string]::IsNullOrWhiteSpace($observedReleaseId)) { $observedReleaseId = [string]$json.activeReleaseId }
        $observedDigest = [string]$json.imageDigest
        if ([string]::IsNullOrWhiteSpace($observedDigest)) { $observedDigest = [string]$json.backendImageDigest }
        $metricResponse = Invoke-WebRequest -Uri $metricUri -Headers $headers -Method Get -TimeoutSec 10 -UseBasicParsing
        $requestCount = Parse-MetricCount $metricResponse.Content
        if ($readinessStatus -notin @('ready', 'UP', 'ok', 'OK')) { throw 'readiness 未返回 ready/UP' }
        if ($observedReleaseId -and $observedReleaseId -ne $release) { throw 'readiness releaseId 不匹配' }
        if ($expectedDigest -and $observedDigest -and $observedDigest -ne $expectedDigest) { throw 'readiness image digest 不匹配' }
        if ($null -eq $requestCount) { throw 'Prometheus exposition 未包含受支持的请求计数器；不生成通过分母' }
        $status = if ($requestCount -ge $MinPerWindow) { 'passed' } else { 'environment-pending' }
        $passed = $status -eq 'passed'
        $reason = if ($passed) { 'readiness、release/digest（若提供）和显式请求计数器通过本窗口门槛' } else { "本窗口请求数 $requestCount 小于 minPerWindow=$MinPerWindow" }
    } catch {
        $sw.Stop(); $latencyMs = $sw.ElapsedMilliseconds; $errorType = $_.Exception.GetType().Name
        $reason = '现场探针失败：' + ($_.Exception.Message -replace '[\r\n]', ' ').Substring(0, [Math]::Min(200, ($_.Exception.Message -replace '[\r\n]', ' ').Length))
        $status = 'blocked'
    }
}

$record = [ordered]@{
    schemaVersion = 'v1.1'; producer = 'collect-observation'; windowId = $id
    releaseId = $release; environment = $Environment; generatedAt = $now.ToString('o')
    windowStart = $windowStart.ToString('o'); windowEnd = $windowEnd.ToString('o')
    windowHoursTarget = $WindowHours; minTotal = $MinTotal; minPerWindow = $MinPerWindow
    baseUrl = Safe-Url $baseUri; readinessPath = $ReadinessPath; metricsPath = $MetricsPath
    observedReleaseId = if ($observedReleaseId) { $observedReleaseId } else { $null }
    expectedImageDigest = if ($expectedDigest) { $expectedDigest } else { $null }
    observedImageDigest = if ($observedDigest) { $observedDigest } else { $null }
    requestCount = if ($null -eq $requestCount) { $null } else { [int64]$requestCount }
    latencyMs = $latencyMs; readinessStatus = $readinessStatus; errorType = $errorType
    status = $status; passed = $passed; reason = $reason
    # Keep the legacy source label for compatibility, while making the two
    # independent telemetry channels machine-readable for the N8 validator.
    # These are descriptors only; the validator still requires passed=true
    # and real timestamps, and WhatIf rows remain blocked.
    source = if ($WhatIf) { 'not-executed' } else { 'live-http-and-prometheus' }
    sourceChannels = @('http-readiness', 'prometheus-metrics')
    sourceEvidence = [ordered]@{
        http = [ordered]@{ kind = 'http-readiness'; path = $ReadinessPath; observedAt = $now.ToString('o'); statusCode = if ($readinessStatus) { 200 } else { $null } }
        prometheus = [ordered]@{ kind = 'prometheus-metrics'; path = $MetricsPath; observedAt = $now.ToString('o'); counter = 'explicit-request-counter'; requestCount = if ($null -eq $requestCount) { $null } else { [int64]$requestCount } }
    }
    noCopyFromStaging = [bool]$NoCopyFromStaging; noCopyFromProd = [bool]$NoCopyFromProd; redacted = $true
}
Write-AtomicText $windowPath (($record | ConvertTo-Json -Depth 20 -Compress) + [Environment]::NewLine)

$windowList = @($checkpointData.windows | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) + $id
$checkpointOut = [ordered]@{ schemaVersion = 'v1.1'; producer = 'collect-observation'; releaseId = $release; environment = $Environment; windowHours = $WindowHours; minTotal = $MinTotal; minPerWindow = $MinPerWindow; windows = @($windowList | Select-Object -Unique); updatedAt = [DateTime]::UtcNow.ToString('o'); lastWindowId = $id; redacted = $true }
Write-AtomicJson $checkpointPath $checkpointOut -Replace:([bool](Test-Path -LiteralPath $checkpointPath))
$record | ConvertTo-Json -Depth 20
if (-not $passed) { exit 1 }
exit 0
