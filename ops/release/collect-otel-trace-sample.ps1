[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string]$BaseUrl,
    [Parameter(Mandatory = $true)] [string]$RunId,
    [Parameter(Mandatory = $true)] [string]$TraceId,
    [Parameter(Mandatory = $true)] [string]$Output,
    [Parameter(Mandatory = $true)] [string]$RawOutput,
    [ValidateSet('local','staging','prod')] [string]$Environment = 'staging',
    [string]$TraceEndpoint = '',
    [switch]$WhatIf,
    [switch]$NoClobber
)

$ErrorActionPreference = 'Stop'

function Safe([object]$Value) {
    if ($null -eq $Value) { return $null }
    $text = ([string]$Value).Replace("`r", ' ').Replace("`n", ' ')
    return $text.Substring(0, [Math]::Min(240, $text.Length))
}
function Require-Id([string]$Value, [string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{1,127}$' -or $Value -match '(?i)(unknown|pending|tbd|<)') { throw "$Name 无效" }
    return $Value.Trim()
}
function Repo-Path([string]$Value, [string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Value)) { throw "$Name 不能为空" }
    $root = (Get-Location).Path
    $candidate = if ([IO.Path]::IsPathRooted($Value)) { [IO.Path]::GetFullPath($Value) } else { [IO.Path]::GetFullPath((Join-Path $root $Value)) }
    if (-not $candidate.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw "$Name 必须位于仓库内" }
    return $candidate
}
function Temp-Path([string]$Value, [string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Value)) { throw "$Name 不能为空" }
    $candidate = if ([IO.Path]::IsPathRooted($Value)) { [IO.Path]::GetFullPath($Value) } else { [IO.Path]::GetFullPath((Join-Path (Get-Location).Path $Value)) }
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $candidate.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) { throw "$Name 必须位于系统临时目录" }
    if (Test-Path -LiteralPath $candidate -PathType Container) { throw "$Name 不能是目录" }
    return $candidate
}
function Write-Atomic([string]$Path, [object]$Body) {
    if ($NoClobber -and (Test-Path -LiteralPath $Path)) { throw "输出已存在（-NoClobber）" }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $tmp = "$Path.$PID.$([Guid]::NewGuid().ToString('N')).tmp"
    try {
        ($Body | ConvertTo-Json -Depth 20) | Set-Content -LiteralPath $tmp -Encoding UTF8 -NoNewline
        Move-Item -LiteralPath $tmp -Destination $Path -Force:(!$NoClobber)
    } finally { if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue } }
}
function Redact-Object([object]$Value) {
    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Collections.IEnumerable] -and -not ($Value -is [string])) {
        $items = @()
        foreach ($item in $Value) { $items += ,(Redact-Object $item) }
        return $items
    }
    if ($Value -is [pscustomobject]) {
        $out = [ordered]@{}
        foreach ($property in $Value.PSObject.Properties) {
            $name = [string]$property.Name
            if ($name -match '(?i)(token|secret|password|api[_-]?key|authorization|prompt_raw|tool_input_raw)') { $out[$name] = '[REDACTED]' }
            else { $out[$name] = Redact-Object $property.Value }
        }
        return [pscustomobject]$out
    }
    if ($Value -is [string]) {
        return ([string]$Value -replace '(?i)Bearer\s+[A-Za-z0-9._~+\/-]+', 'Bearer [REDACTED]' -replace '(?i)(api[_-]?key|token|secret|password)=([^&\s]+)', '$1=[REDACTED]')
    }
    return $Value
}

$run = Require-Id $RunId 'RunId'
$trace = Require-Id $TraceId 'TraceId'
if ($Environment -notin @('local','staging','prod')) { throw 'Environment 无效' }
$outputPath = Repo-Path $Output 'Output'
$rawPath = Temp-Path $RawOutput 'RawOutput'
$uri = $null
if (-not [Uri]::TryCreate($BaseUrl.TrimEnd('/'), [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -notin @('http','https')) { throw 'BaseUrl 必须是 http/https URL' }
if ($Environment -eq 'prod' -and ($uri.Host -match '^(localhost|127\.0\.0\.1|::1)$' -or $uri.Host -match '(?i)staging|test')) { throw 'prod 禁止 localhost/staging/test 地址' }
$endpointPath = if ([string]::IsNullOrWhiteSpace($TraceEndpoint)) { '/api/actuator/otel/traces' } else { $TraceEndpoint.Trim() }
if (-not $endpointPath.StartsWith('/')) { $endpointPath = "/$endpointPath" }
$query = "runId=$([Uri]::EscapeDataString($run))&traceId=$([Uri]::EscapeDataString($trace))"
$requestUri = "$($BaseUrl.TrimEnd('/'))$endpointPath`?$query"

$result = [ordered]@{
    schemaVersion = 'v1.1'; producer = 'collect-otel-trace-sample'; generatedAt = [DateTime]::UtcNow.ToString('o')
    runId = $run; traceId = $trace; environment = $Environment; endpoint = $requestUri
    status = 'environment-pending'; passed = $false; spanCount = 0; errors = @(); redacted = $true
}

if ($WhatIf) {
    $result.errors += 'WhatIf：未访问 OTel endpoint'
    Write-Atomic $outputPath $result
    $result | ConvertTo-Json -Depth 12
    exit 1
}

$token = [Environment]::GetEnvironmentVariable('VIO_API_KEY')
$headers = @{ Accept = 'application/json'; 'X-Run-Id' = $run; 'X-Trace-Id' = $trace }
if (-not [string]::IsNullOrWhiteSpace($token)) { $headers['X-API-Key'] = $token }
$rawContent = $null
try {
    $response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri $requestUri -Headers $headers -ErrorAction Stop
    $rawContent = [string]$response.Content
    if ($NoClobber -and (Test-Path -LiteralPath $rawPath)) { throw 'RawOutput 已存在（-NoClobber）' }
    $rawContent | Set-Content -LiteralPath $rawPath -Encoding UTF8 -NoNewline
    if ([string]::IsNullOrWhiteSpace($rawContent)) { throw 'OTel 响应为空' }
    $parsed = $rawContent | ConvertFrom-Json
    $spans = if ($parsed -is [System.Array]) { @($parsed) } elseif ($parsed.spans) { @($parsed.spans) } elseif ($parsed.data -and $parsed.data.spans) { @($parsed.data.spans) } else { @($parsed) }
    $safeSpans = @()
    foreach ($span in $spans) {
        if ($null -eq $span) { continue }
        $safeSpan = Redact-Object $span
        if ([string]$safeSpan.schemaVersion -ne 'v1.1') { $result.errors += 'span schemaVersion 必须为 v1.1' }
        if ([string]$safeSpan.traceId -ne $trace) { $result.errors += 'span traceId 与请求不一致' }
        if ($safeSpan.runId -and [string]$safeSpan.runId -ne $run) { $result.errors += 'span runId 与请求不一致' }
        $safeSpans += ,$safeSpan
    }
    if ($safeSpans.Count -eq 0) { $result.errors += 'OTel 响应没有 span' }
    $result.spanCount = $safeSpans.Count
    $result.sample = $safeSpans
    if ($result.errors.Count -eq 0 -and [int]$response.StatusCode -ge 200 -and [int]$response.StatusCode -lt 300) { $result.passed = $true; $result.status = 'passed' }
    else { $result.status = 'blocked' }
} catch {
    $result.errors += 'OTel endpoint 不可达或响应不符合契约（详情已脱敏）'
    $result.status = 'blocked'; $result.passed = $false
} finally {
    $token = $null; $headers = $null; $rawContent = $null
}
Write-Atomic $outputPath $result
$result | ConvertTo-Json -Depth 20
if (-not $result.passed) { exit 1 }
