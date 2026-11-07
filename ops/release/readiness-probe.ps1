[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string]$BaseUrl,
    [Parameter(Mandatory = $true)] [string]$Environment,
    [Parameter(Mandatory = $true)] [string]$ReleaseId,
    [Parameter(Mandatory = $true)] [string]$Evidence,
    [string]$ExpectedImageDigest = '',
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
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{1,127}$' -or $Value -match '(?i)(pending|unknown|tbd|<)') { throw "$Name 缺失或无效" }
    return $Value.Trim()
}
function Resolve-RepoPath([string]$Value, [string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Value)) { throw "$Name 不能为空" }
    $root = (Get-Location).Path
    $candidate = if ([IO.Path]::IsPathRooted($Value)) { [IO.Path]::GetFullPath($Value) } else { [IO.Path]::GetFullPath((Join-Path $root $Value)) }
    if (-not $candidate.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw "$Name 必须位于仓库内" }
    if ((Test-Path -LiteralPath $candidate) -and (Get-Item -LiteralPath $candidate).PSIsContainer) { throw "$Name 不能是目录" }
    return $candidate
}
function Write-AtomicJson([string]$Path, [object]$Body) {
    if ($NoClobber -and (Test-Path -LiteralPath $Path)) { throw "输出已存在（-NoClobber）" }
    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $tmp = "$Path.$PID.$([Guid]::NewGuid().ToString('N')).tmp"
    try {
        $Body | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $tmp -Encoding UTF8 -NoNewline
        Move-Item -LiteralPath $tmp -Destination $Path -Force:(!$NoClobber)
    } finally { if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue } }
}

$release = Require-Id $ReleaseId 'ReleaseId'
if ($Environment -notin @('prod', 'staging')) { throw 'Environment 只允许 prod 或 staging' }
$baseUri = $null
if (-not [Uri]::TryCreate($BaseUrl.TrimEnd('/'), [UriKind]::Absolute, [ref]$baseUri)) { throw 'BaseUrl 必须是完整 http/https URL' }
if ($baseUri.Scheme -notin @('http', 'https')) { throw 'BaseUrl 只允许 http/https' }
if ($Environment -eq 'prod' -and ($baseUri.Host -match '^(localhost|127\.0\.0\.1|::1)$' -or $baseUri.Host -match '(?i)staging|test')) { throw 'prod readiness 禁止 localhost/staging/test 地址' }
$out = Resolve-RepoPath $Evidence 'Evidence'
$digest = if ([string]::IsNullOrWhiteSpace($ExpectedImageDigest)) { $null } else { $ExpectedImageDigest.Trim() }
if ($digest -and $digest -notmatch '^.+@sha256:[a-f0-9]{64}$') { throw 'ExpectedImageDigest 必须是完整 @sha256 digest' }

$result = [ordered]@{
    schemaVersion = 'v1.1'; producer = 'readiness-probe'; checkedAt = [DateTime]::UtcNow.ToString('o')
    releaseId = $release; environment = $Environment; baseUrl = "$($baseUri.Scheme)://$($baseUri.Host)$(if($baseUri.IsDefaultPort){''}else{":$($baseUri.Port)"})"
    status = 'environment-pending'; passed = $false; httpStatus = $null; latencyMs = $null; readiness = $null; imageDigest = $digest
    errors = @(); redacted = $true
}

if ($WhatIf) {
    $result.status = 'environment-pending'; $result.errors = @('WhatIf：未访问 readiness endpoint')
    Write-AtomicJson $out $result
    $result | ConvertTo-Json -Depth 8
    exit 1
}

$token = [Environment]::GetEnvironmentVariable('VIO_READINESS_TOKEN')
if ([string]::IsNullOrWhiteSpace($token)) { $token = [Environment]::GetEnvironmentVariable('VIO_API_KEY') }
$headers = @{}
if (-not [string]::IsNullOrWhiteSpace($token)) { $headers['X-API-Key'] = $token }
$uri = "$($BaseUrl.TrimEnd('/'))/api/ready/details"
$watch = [Diagnostics.Stopwatch]::StartNew()
try {
    $response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri $uri -Headers $headers
    $watch.Stop(); $result.latencyMs = $watch.ElapsedMilliseconds; $result.httpStatus = [int]$response.StatusCode
    $payload = $response.Content | ConvertFrom-Json
    $data = if ($payload.data) { $payload.data } else { $payload }
    $result.readiness = [ordered]@{ status = [string]$data.status; lifecycleReady = [bool]$data.lifecycleReady; dependenciesHealthy = [bool]$data.dependenciesHealthy; releasePointerReady = [bool]$data.releasePointerReady; blockingReasons = @($data.blockingReasons) }
    $result.passed = ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300 -and $data.status -eq 'READY' -and [string]$data.schemaVersion -eq 'v1.1')
    if (-not $result.passed) { $result.errors += 'readiness 返回非 READY 或 schemaVersion 不匹配' }
} catch {
    $watch.Stop(); $result.latencyMs = $watch.ElapsedMilliseconds
    try { $result.httpStatus = [int]$_.Exception.Response.StatusCode.value__ } catch { }
    $result.errors += 'readiness endpoint 不可达或返回错误（详情已脱敏）'
} finally {
    $token = $null; $headers = $null
}
$result.status = if ($result.passed) { 'passed' } else { 'blocked' }
Write-AtomicJson $out $result
$result | ConvertTo-Json -Depth 8
if (-not $result.passed) { exit 1 }
