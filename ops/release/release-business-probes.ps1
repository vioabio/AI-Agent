[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string]$BaseUrl,
    [Parameter(Mandatory = $true)] [string]$Fixture,
    [Parameter(Mandatory = $true)] [string]$Evidence,
    [Parameter(Mandatory = $true)] [string]$ReleaseId,
    [ValidateSet('prod','staging')] [string]$Environment = 'staging',
    [switch]$WhatIf,
    [switch]$NoClobber
)

$ErrorActionPreference = 'Stop'
function Safe([object]$Value) { if ($null -eq $Value) { return $null }; $text = ([string]$Value).Replace("`r", ' ').Replace("`n", ' '); return $text.Substring(0, [Math]::Min(240, $text.Length)) }
function Require-Id([string]$Value, [string]$Name) { if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z0-9][A-Za-z0-9._:-]{1,127}$' -or $Value -match '(?i)(unknown|pending|tbd|<)') { throw "$Name 缺失或无效" }; return $Value.Trim() }
function RepoFile([string]$Value, [string]$Name) { $root = (Get-Location).Path; $p = if ([IO.Path]::IsPathRooted($Value)) { [IO.Path]::GetFullPath($Value) } else { [IO.Path]::GetFullPath((Join-Path $root $Value)) }; if (-not $p.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw "$Name 必须位于仓库内" }; if (-not (Test-Path -LiteralPath $p -PathType Leaf)) { throw "$Name 不存在" }; return $p }
function Write-AtomicLines([string]$Path, [object[]]$Rows) {
    if ($NoClobber -and (Test-Path -LiteralPath $Path)) { throw 'Evidence 已存在（-NoClobber）' }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $tmp = "$Path.$PID.$([Guid]::NewGuid().ToString('N')).tmp"
    try { ($Rows | ForEach-Object { $_ | ConvertTo-Json -Depth 10 -Compress }) -join "`n" | Set-Content -LiteralPath $tmp -Encoding UTF8; Move-Item -LiteralPath $tmp -Destination $Path -Force:(!$NoClobber) }
    finally { if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue } }
}
function Invoke-Probe([string]$Name, [string]$Method, [string]$Uri, [object]$Body, [hashtable]$Headers) {
    $started = [DateTime]::UtcNow; $watch = [Diagnostics.Stopwatch]::StartNew(); $status = $null; $passed = $false; $reason = $null
    try {
        $params = @{ UseBasicParsing = $true; Method = $Method; Uri = $Uri; Headers = $Headers; ErrorAction = 'Stop' }
        if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Depth 10 -Compress); $params.ContentType = 'application/json' }
        $response = Invoke-WebRequest @params; $status = [int]$response.StatusCode; $passed = ($status -ge 200 -and $status -lt 300)
        if (-not $passed) { $reason = 'HTTP status outside expected 2xx' }
    } catch { try { $status = [int]$_.Exception.Response.StatusCode.value__ } catch { }; $reason = 'probe failed (response/error redacted)' }
    $watch.Stop()
    return [ordered]@{ schemaVersion = 'v1.1'; probeId = $Name; method = $Method; status = $status; latencyMs = $watch.ElapsedMilliseconds; startedAt = $started.ToString('o'); releaseId = $ReleaseId; environment = $Environment; passed = $passed; expected = 'endpoint reachable with expected status'; reason = $reason; redacted = $true }
}

$release = Require-Id $ReleaseId 'ReleaseId'
$fixturePath = RepoFile $Fixture 'Fixture'
$root = (Get-Location).Path
$evidencePath = if ([IO.Path]::IsPathRooted($Evidence)) { [IO.Path]::GetFullPath($Evidence) } else { [IO.Path]::GetFullPath((Join-Path $root $Evidence)) }
if (-not $evidencePath.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence 必须位于仓库内' }
$baseUri = $null
if (-not [Uri]::TryCreate($BaseUrl.TrimEnd('/'), [UriKind]::Absolute, [ref]$baseUri) -or $baseUri.Scheme -notin @('http','https')) { throw 'BaseUrl 必须是 http/https URL' }
if ($Environment -eq 'prod' -and ($baseUri.Host -match '^(localhost|127\.0\.0\.1|::1)$' -or $baseUri.Host -match '(?i)staging|test')) { throw 'prod probe 禁止 localhost/staging/test 地址' }
$token = [Environment]::GetEnvironmentVariable('VIO_API_KEY')
$headers = @{ Accept = 'application/json' }
if (-not [string]::IsNullOrWhiteSpace($token)) { $headers['X-API-Key'] = $token }

$rows = @()
if ($WhatIf) {
    $rows += [ordered]@{ schemaVersion = 'v1.1'; probeId = 'what-if'; status = $null; latencyMs = $null; releaseId = $release; environment = $Environment; passed = $false; expected = '未执行网络探针'; reason = 'WhatIf'; redacted = $true }
} else {
    $base = $BaseUrl.TrimEnd('/')
    $rows += Invoke-Probe 'spa-root' 'GET' "$base/" $null $headers
    $rows += Invoke-Probe 'readiness-details' 'GET' "$base/api/ready/details" $null $headers
    $rows += Invoke-Probe 'versioned-facts' 'GET' "$base/api/pokemon/facts?dataReleaseId=$([Uri]::EscapeDataString($release))&limit=1" $null $headers
    $rows += Invoke-Probe 'expired-cursor-negative' 'GET' "$base/api/chat/runs/invalid/events?afterEventId=-1" $null $headers
}
$passed = ($rows.Count -gt 0 -and @($rows | Where-Object { -not $_.passed }).Count -eq 0)
$summary = [ordered]@{ schemaVersion = 'v1.1'; producer = 'release-business-probes'; generatedAt = [DateTime]::UtcNow.ToString('o'); releaseId = $release; environment = $Environment; fixture = (Resolve-Path -LiteralPath $fixturePath).Path.Replace($root + [IO.Path]::DirectorySeparatorChar, '').Replace('\','/'); probeCount = $rows.Count; passed = $passed; status = if ($passed) { 'passed' } else { 'blocked' }; redacted = $true }
Write-AtomicLines $evidencePath $rows
$summary | ConvertTo-Json -Depth 8
if (-not $passed) { exit 1 }
